package com.smartroute.assignment;

import com.smartroute.algorithms.selection.TopK;
import com.smartroute.fleet.DriverCandidateView;
import com.smartroute.fleet.DriverService;
import com.smartroute.fleet.VehicleStatus;
import com.smartroute.order.OrderAssignmentView;
import com.smartroute.routing.EtaService;
import com.smartroute.routing.GeoPoint;
import com.smartroute.warehouse.WarehouseResponse;
import com.smartroute.warehouse.WarehouseService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks drivers for an order.
 *
 * <pre>
 *  pickup = the order's warehouse
 *  1. GEO radius search (Redis)            → drivers within R metres          O(log N + m)
 *  2. load their state from PostgreSQL     → hard rules, counted by reason     O(m)
 *  3. keep the nearest maxCandidates        (straight line)                    O(m log m)
 *  4. one Dijkstra on the reversed graph   → road ETA of every candidate       O((V + E) log V) worst case
 *  5. score each [HEURISTIC]                                                    O(c)
 *  6. top k with a bounded heap                                                 O(c log k)
 * </pre>
 *
 * <p>Nothing is reserved here: the ranking can be stale a moment later, so {@code AssignmentService}
 * re-checks every rule on the locked driver row before assigning.
 */
@Service
public class CandidateService {

    /** Best score first; then faster ETA; then lower driver id, so the order is total and repeatable. */
    static final Comparator<Candidate> BEST_FIRST = Comparator.comparingDouble(Candidate::score).reversed()
            .thenComparingDouble(Candidate::etaSeconds)
            .thenComparingLong(Candidate::driverId);

    private final DriverLocationIndex locations;
    private final DriverService drivers;
    private final WarehouseService warehouses;
    private final AssignmentConfigService config;
    private final EtaService etas;

    CandidateService(DriverLocationIndex locations, DriverService drivers, WarehouseService warehouses,
                     AssignmentConfigService config, EtaService etas) {
        this.locations = locations;
        this.drivers = drivers;
        this.warehouses = warehouses;
        this.config = config;
        this.etas = etas;
    }

    /** Ranking with a fresh, early-stopping ETA search. */
    public CandidateRanking rank(OrderAssignmentView order, int k) {
        return rank(order, k, config.current(), etas::secondsTo);
    }

    CandidateRanking rank(OrderAssignmentView order, int k, AssignmentSettings settings, EtaLookup etaLookup) {
        WarehouseResponse warehouse = warehouses.get(order.warehouseId());
        GeoPoint pickup = new GeoPoint(warehouse.latitude(), warehouse.longitude());
        List<DriverLocationIndex.Nearby> nearby =
                locations.within(pickup.latitude(), pickup.longitude(), settings.searchRadiusMeters());

        Map<Long, Double> straightLine = new HashMap<>();
        nearby.forEach(n -> straightLine.put(n.driverId(), n.distanceMeters()));
        Map<ExclusionReason, Integer> excluded = new EnumMap<>(ExclusionReason.class);
        List<DriverCandidateView> eligible = new ArrayList<>();
        for (DriverCandidateView driver : drivers.candidates(straightLine.keySet())) {
            ExclusionReason reason = hardRuleViolation(driver, order, settings);
            if (reason == null) {
                eligible.add(driver);
            } else {
                excluded.merge(reason, 1, Integer::sum);
            }
        }

        eligible.sort(Comparator.comparingDouble((DriverCandidateView d) -> straightLine.get(d.id()))
                .thenComparingLong(DriverCandidateView::id));
        if (eligible.size() > settings.maxCandidates()) {
            excluded.put(ExclusionReason.BEYOND_CANDIDATE_LIMIT, eligible.size() - settings.maxCandidates());
            eligible = eligible.subList(0, settings.maxCandidates());
        }

        Map<Long, GeoPoint> origins = new LinkedHashMap<>();
        eligible.forEach(d -> origins.put(d.id(), new GeoPoint(d.latitude(), d.longitude())));
        Map<Long, Double> eta = origins.isEmpty() ? Map.of() : etaLookup.secondsTo(pickup, origins);

        List<Candidate> scored = new ArrayList<>();
        for (DriverCandidateView d : eligible) {
            Double seconds = eta.get(d.id());
            if (seconds == null) {
                excluded.merge(ExclusionReason.UNREACHABLE, 1, Integer::sum);
                continue;
            }
            double fit = AssignmentScorer.capacityFit(order.weightKg(), order.volumeM3(), d.remainingKg(), d.remainingM3());
            AssignmentScorer.Score s = AssignmentScorer.score(seconds, d.activeDeliveries(), fit, order.priority(), settings);
            scored.add(new Candidate(0, d.id(), d.code(), d.vehicleType(), seconds, straightLine.get(d.id()),
                    d.activeDeliveries(), d.remainingKg(), d.remainingM3(), s.total(), s.eta(), s.workload(),
                    s.capacity()));
        }

        List<Candidate> top = TopK.of(scored, k, BEST_FIRST);
        List<Candidate> ranked = new ArrayList<>(top.size());
        for (int i = 0; i < top.size(); i++) {
            ranked.add(top.get(i).withRank(i + 1));
        }
        return new CandidateRanking(order.id(), order.code(), pickup.latitude(), pickup.longitude(), nearby.size(),
                scored.size(), excluded, ranked, CandidateRanking.ALGORITHM);
    }

    /** The first hard rule the driver breaks for this order, or null if they may take it. */
    static ExclusionReason hardRuleViolation(DriverCandidateView d, OrderAssignmentView order, AssignmentSettings settings) {
        if (!d.status().canReceiveOrders()) {
            return ExclusionReason.NOT_ON_SHIFT;
        }
        if (!d.hasVehicle() || d.vehicleStatus() != VehicleStatus.ACTIVE) {
            return ExclusionReason.NO_ACTIVE_VEHICLE;
        }
        if (!d.vehicleType().satisfies(order.requiredVehicleType())) {
            return ExclusionReason.VEHICLE_TOO_SMALL;
        }
        if (d.activeDeliveries() >= settings.maxActiveDeliveries()) {
            return ExclusionReason.TOO_MANY_DELIVERIES;
        }
        if (d.remainingKg().compareTo(order.weightKg()) < 0 || d.remainingM3().compareTo(order.volumeM3()) < 0) {
            return ExclusionReason.NOT_ENOUGH_CAPACITY;
        }
        return null;
    }
}
