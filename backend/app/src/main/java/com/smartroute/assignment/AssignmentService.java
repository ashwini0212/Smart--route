package com.smartroute.assignment;

import com.smartroute.common.error.ApiException;
import com.smartroute.common.error.ErrorCode;
import com.smartroute.fleet.DriverService;
import com.smartroute.order.OrderAssignmentView;
import com.smartroute.order.OrderService;
import com.smartroute.order.OrderStatus;
import com.smartroute.routing.EtaService;
import com.smartroute.routing.GeoPoint;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.PriorityQueue;

/** Assigns orders to drivers: by hand (a dispatcher's choice) or greedily (auto-dispatch). */
@Service
public class AssignmentService {

    private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);

    /** Orders read into the dispatch queue per run. Bounds memory; the rest wait for the next run. */
    static final int MAX_QUEUE = 5_000;
    /** How many top candidates an auto-dispatch tries before giving up on an order (others may have taken them). */
    static final int ATTEMPTS_PER_ORDER = 3;

    /**
     * Dispatch order: highest priority first, then the earliest deadline (orders without a time window
     * last), then the oldest order, then id so the order is total.
     */
    static final Comparator<OrderAssignmentView> DISPATCH_ORDER =
            Comparator.comparingInt((OrderAssignmentView o) -> o.priority().weight()).reversed()
                    .thenComparing(OrderAssignmentView::windowEnd, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(OrderAssignmentView::createdAt)
                    .thenComparingLong(OrderAssignmentView::id);

    private final OrderService orders;
    private final DriverService drivers;
    private final CandidateService candidates;
    private final AssignmentConfigService config;
    private final AssignmentRepository assignments;
    private final EtaService etas;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Counter autoAssigned;
    private final Counter autoUnassigned;

    AssignmentService(OrderService orders, DriverService drivers, CandidateService candidates,
                      AssignmentConfigService config, AssignmentRepository assignments, EtaService etas,
                      PlatformTransactionManager transactionManager, Clock clock, MeterRegistry meters) {
        this.orders = orders;
        this.drivers = drivers;
        this.candidates = candidates;
        this.config = config;
        this.assignments = assignments;
        this.etas = etas;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.autoAssigned = Counter.builder("smartroute.assignment.auto").tag("result", "assigned").register(meters);
        this.autoUnassigned = Counter.builder("smartroute.assignment.auto").tag("result", "unassigned").register(meters);
    }

    /**
     * A dispatcher's choice. The driver need not be the top candidate, but every hard rule is enforced on
     * the locked driver row. If the driver is in the ranking, its score and rank are stored for the record.
     */
    public AssignmentResponse assignManually(long orderId, long driverId, String reason, long userId) {
        OrderAssignmentView order = orders.assignmentView(orderId);
        requireWaiting(order);
        // Ranked outside the assigning transaction on purpose: see assign().
        Candidate ranked = candidates.rank(order, Integer.MAX_VALUE).candidates().stream()
                .filter(c -> c.driverId() == driverId).findFirst().orElse(null);
        String text = reason == null || reason.isBlank() ? "Assigned by dispatcher" : reason;
        AssignmentSettings settings = config.current();
        return transactions.execute(status ->
                assign(orderId, driverId, AssignmentMethod.MANUAL, userId, ranked, text, settings));
    }

    /**
     * Greedy auto-dispatch of up to {@code limit} waiting orders. <b>[HEURISTIC]</b>
     *
     * <p>Orders leave a priority queue most urgent first; each gets the best-scored driver available at that
     * moment, and that choice is never revisited. This is not a globally optimal matching (which would be an
     * assignment problem, e.g. Hungarian algorithm, O(n³)): an urgent order can take a driver that a later
     * order needed more. In exchange each order is decided in milliseconds and in its own transaction, so a
     * failure affects one order, never the batch.
     *
     * <p>All orders of one warehouse share a pickup point, so one full ETA tree per warehouse is computed per
     * run and reused (driver positions don't change during a run; the tree uses one traffic snapshot).
     */
    public AutoDispatchResult autoDispatch(int limit, long userId) {
        Instant started = clock.instant();
        long t0 = System.nanoTime();
        AssignmentSettings settings = config.current();
        PriorityQueue<OrderAssignmentView> queue = new PriorityQueue<>(DISPATCH_ORDER);
        queue.addAll(orders.waitingForDriver(MAX_QUEUE));
        int waiting = queue.size();

        Map<GeoPoint, EtaService.EtaTree> trees = new HashMap<>();
        EtaLookup sharedTrees = (pickup, origins) -> {
            EtaService.EtaTree tree = trees.computeIfAbsent(pickup, etas::treeTo);
            Map<Long, Double> result = new HashMap<>();
            origins.forEach((id, point) -> {
                OptionalDouble seconds = tree.secondsFrom(point);
                if (seconds.isPresent()) {
                    result.put(id, seconds.getAsDouble());
                }
            });
            return result;
        };

        List<AutoDispatchResult.Assigned> assigned = new ArrayList<>();
        List<AutoDispatchResult.Skipped> skipped = new ArrayList<>();
        while (!queue.isEmpty() && assigned.size() + skipped.size() < limit) {
            OrderAssignmentView order = queue.poll();
            CandidateRanking ranking = candidates.rank(order, ATTEMPTS_PER_ORDER, settings, sharedTrees);
            AutoDispatchResult.Assigned done = null;
            String lastFailure = null;
            for (Candidate candidate : ranking.candidates()) {
                try {
                    AssignmentResponse response = transactions.execute(status -> assign(order.id(), candidate.driverId(),
                            AssignmentMethod.AUTO, userId, candidate, "Auto-dispatch: rank " + candidate.rank()
                                    + ", score " + "%.3f".formatted(candidate.score()), settings));
                    done = new AutoDispatchResult.Assigned(order.id(), order.code(), order.priority(),
                            response.driverId(), candidate.driverCode(), candidate.etaSeconds(), candidate.score(),
                            candidate.rank());
                    break;
                } catch (ApiException | ConcurrencyFailureException e) {
                    // Someone else changed the order or the driver since the ranking was computed: try the next.
                    lastFailure = e.getMessage();
                }
            }
            if (done != null) {
                assigned.add(done);
                autoAssigned.increment();
            } else {
                String reason = ranking.candidates().isEmpty() ? ranking.summary()
                        : "Top candidates no longer available (" + lastFailure + ")";
                skipped.add(new AutoDispatchResult.Skipped(order.id(), order.code(), order.priority(), reason));
                autoUnassigned.increment();
            }
        }
        long millis = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        log.info("Auto-dispatch: {} assigned, {} not assigned, {} still waiting, {} ms",
                assigned.size(), skipped.size(), queue.size(), millis);
        return new AutoDispatchResult(started, waiting, assigned.size(), skipped.size(), queue.size(), millis,
                assigned, skipped, "greedy by priority queue [HEURISTIC]");
    }

    @Transactional(readOnly = true)
    public List<AssignmentResponse> forOrder(long orderId) {
        orders.assignmentView(orderId);
        return assignments.findByOrderIdOrderByCreatedAtAscIdAsc(orderId).stream().map(AssignmentResponse::from).toList();
    }

    /**
     * Lock order, re-check, lock driver and reserve capacity, mark assigned, record. Locks are always taken
     * in the order "order row, then driver row", so concurrent assignments can wait but never deadlock.
     *
     * <p>Must run in a transaction that has not read the driver before: a JPA query returns an entity that is
     * already in the persistence context as it was first read, even under {@code FOR UPDATE}, so the capacity
     * check would see a stale load. (The optimistic version check would still stop the double booking, but
     * as a confusing "concurrent modification" instead of a clear "does not fit".)
     */
    private AssignmentResponse assign(long orderId, long driverId, AssignmentMethod method, Long userId,
                                      Candidate candidate, String reason, AssignmentSettings settings) {
        OrderAssignmentView order = orders.lockForAssignment(orderId);
        requireWaiting(order);
        drivers.reserveCapacity(driverId, order.weightKg(), order.volumeM3(), order.requiredVehicleType(),
                settings.maxActiveDeliveries());
        orders.markAssigned(orderId, driverId, reason);
        Assignment saved = assignments.save(new Assignment(orderId, driverId, method, userId, candidate));
        return AssignmentResponse.from(saved);
    }

    private static void requireWaiting(OrderAssignmentView order) {
        if (order.status() != OrderStatus.CREATED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Order " + order.code() + " is " + order.status()
                    + (order.status() == OrderStatus.ASSIGNED ? "; unassign it first" : ""));
        }
    }
}
