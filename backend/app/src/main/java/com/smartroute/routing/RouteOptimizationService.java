package com.smartroute.routing;

import com.smartroute.algorithms.graph.Dijkstra;
import com.smartroute.algorithms.graph.EdgeWeight;
import com.smartroute.algorithms.graph.GraphNode;
import com.smartroute.algorithms.graph.Path;
import com.smartroute.algorithms.graph.ShortestPathTree;
import com.smartroute.algorithms.sequencing.CostMatrix;
import com.smartroute.algorithms.sequencing.HeldKarp;
import com.smartroute.algorithms.sequencing.NearestNeighbourTour;
import com.smartroute.algorithms.sequencing.StopSequencer;
import com.smartroute.algorithms.sequencing.Tour;
import com.smartroute.algorithms.sequencing.TwoOpt;
import com.smartroute.common.error.ApiException;
import com.smartroute.common.error.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Orders the stops of a multi-stop trip and builds the route between them.
 *
 * <pre>
 * 1. snap the start and every stop to the road network                      k-d tree, O(log V) each
 * 2. cost matrix: one Dijkstra per point, stopping once all others are      (n+1) searches, not (n+1)²
 *    settled, on the current traffic snapshot
 * 3. visiting order: Held-Karp (exact, O(n²·2ⁿ)) or nearest neighbour
 *    + 2-opt (heuristic, O(n²) per pass)
 * 4. one A* per chosen leg for its real distance, time and geometry          n searches
 * 5. arrival times from departure + travel + service time; stops that
 *    miss their deadline are flagged
 * </pre>
 *
 * <p>One network snapshot is taken at the start and used throughout, so a traffic change mid-request cannot
 * mix two versions (same rule as {@link RouteEngine}).
 */
@Service
public class RouteOptimizationService {

    private final RoadNetworkProvider networks;
    private final RouteEngine engine;
    private final RoutingProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;

    RouteOptimizationService(RoadNetworkProvider networks, RouteEngine engine, RoutingProperties properties,
                             Clock clock, MeterRegistry meters) {
        this.networks = networks;
        this.engine = engine;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
    }

    public OptimizedRoute optimize(OptimizeRequest request) {
        RoadNetwork network = networks.current();
        RouteMode mode = request.modeOrDefault();
        checkCapacity(request);

        List<RouteEngine.SnappedPoint> points = new ArrayList<>();
        points.add(engine.snapOrFail(network, request.start(), "Start"));
        for (int i = 0; i < request.stops().size(); i++) {
            points.add(engine.snapOrFail(network, request.stops().get(i).location(), "Stop " + (i + 1)));
        }
        CostMatrix matrix = costMatrix(network, mode, points);
        if (!matrix.isComplete()) {
            throw new ApiException(ErrorCode.NO_ROUTE, "Some stops cannot be reached from the others by road");
        }

        long started = System.nanoTime();
        Tour tour = sequence(matrix, request);
        long sequencingMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        Timer.builder("smartroute.optimize.sequence").tag("algorithm", tour.optimal() ? "exact" : "heuristic")
                .tag("stops", String.valueOf(request.stops().size())).register(meters)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);

        return describe(network, request, mode, points, tour, sequencingMillis, null);
    }

    /** Runs both strategies on the same matrix and returns the heuristic result with the exact total attached. */
    public OptimizedRoute compareStrategies(OptimizeRequest request) {
        RoadNetwork network = networks.current();
        RouteMode mode = request.modeOrDefault();
        checkCapacity(request);
        List<RouteEngine.SnappedPoint> points = new ArrayList<>();
        points.add(engine.snapOrFail(network, request.start(), "Start"));
        for (int i = 0; i < request.stops().size(); i++) {
            points.add(engine.snapOrFail(network, request.stops().get(i).location(), "Stop " + (i + 1)));
        }
        CostMatrix matrix = costMatrix(network, mode, points);
        if (!matrix.isComplete()) {
            throw new ApiException(ErrorCode.NO_ROUTE, "Some stops cannot be reached from the others by road");
        }
        requireExactPossible(request.stops().size());
        Tour exact = HeldKarp.solve(matrix, 0, request.returnsToStart());
        long started = System.nanoTime();
        Tour heuristic = TwoOpt.improve(matrix, NearestNeighbourTour.of(matrix, 0, request.returnsToStart()),
                request.returnsToStart());
        long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        return describe(network, request, mode, points, heuristic, millis, exact.cost());
    }

    private Tour sequence(CostMatrix matrix, OptimizeRequest request) {
        int stops = request.stops().size();
        return switch (request.strategyOrDefault()) {
            case EXACT -> {
                requireExactPossible(stops);
                yield HeldKarp.solve(matrix, 0, request.returnsToStart());
            }
            case HEURISTIC -> TwoOpt.improve(matrix, NearestNeighbourTour.of(matrix, 0, request.returnsToStart()),
                    request.returnsToStart());
            case AUTO -> StopSequencer.sequence(matrix, 0, request.returnsToStart());
        };
    }

    private static void requireExactPossible(int stops) {
        if (stops > HeldKarp.MAX_STOPS) {
            throw ApiException.businessRule("The exact algorithm handles at most " + HeldKarp.MAX_STOPS
                    + " stops (its cost grows as n²·2ⁿ); ask for HEURISTIC instead");
        }
    }

    private void checkCapacity(OptimizeRequest request) {
        BigDecimal weight = request.stops().stream().map(OptimizeStop::weightOrZero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal volume = request.stops().stream().map(OptimizeStop::volumeOrZero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (request.capacityKg() != null && weight.compareTo(request.capacityKg()) > 0) {
            throw ApiException.businessRule("These stops weigh " + weight + " kg, which is more than the "
                    + request.capacityKg() + " kg capacity");
        }
        if (request.capacityM3() != null && volume.compareTo(request.capacityM3()) > 0) {
            throw ApiException.businessRule("These stops take " + volume + " m³, which is more than the "
                    + request.capacityM3() + " m³ capacity");
        }
    }

    /**
     * One Dijkstra per point to all the others, with the usual early stop. That is n+1 searches for an
     * (n+1)² matrix: running a point-to-point search per pair would be n²+n searches instead.
     */
    private CostMatrix costMatrix(RoadNetwork network, RouteMode mode, List<RouteEngine.SnappedPoint> points) {
        EdgeWeight weight = mode == RouteMode.SHORTEST ? EdgeWeight.DISTANCE : EdgeWeight.TRAVEL_TIME;
        List<Integer> nodes = points.stream().map(RouteEngine.SnappedPoint::nodeId).toList();
        double[][] cost = new double[points.size()][points.size()];
        for (int i = 0; i < points.size(); i++) {
            int from = nodes.get(i);
            ShortestPathTree tree = Dijkstra.shortestPathTree(network.graph(), from, weight, nodes);
            for (int j = 0; j < points.size(); j++) {
                cost[i][j] = i == j ? 0
                        : tree.isReachable(nodes.get(j)) ? tree.costTo(nodes.get(j)) : Double.POSITIVE_INFINITY;
            }
        }
        return CostMatrix.of(cost);
    }

    private OptimizedRoute describe(RoadNetwork network, OptimizeRequest request, RouteMode mode,
                                    List<RouteEngine.SnappedPoint> points, Tour tour, long sequencingMillis,
                                    Double comparedTo) {
        int[] order = tour.order();
        Instant departAt = request.departAt() == null ? clock.instant() : request.departAt();
        EdgeWeight weight = mode == RouteMode.SHORTEST ? EdgeWeight.DISTANCE : EdgeWeight.TRAVEL_TIME;

        List<OptimizedRoute.Visit> visits = new ArrayList<>();
        List<OptimizedRoute.Leg> legs = new ArrayList<>();
        List<OptimizedRoute.Late> late = new ArrayList<>();
        double totalDistance = 0;
        double totalDuration = 0;
        double serviceSeconds = 0;
        Instant cursor = departAt;
        visits.add(visit(0, 0, "Start", points.getFirst(), departAt, departAt));

        for (int step = 1; step < order.length; step++) {
            int fromPoint = order[step - 1];
            int toPoint = order[step];
            Path path = Dijkstra.shortestPath(network.graph(), points.get(fromPoint).nodeId(),
                    points.get(toPoint).nodeId(), weight);
            double distance = path.totalDistanceMeters();
            double duration = path.totalTravelTimeSeconds();
            totalDistance += distance;
            totalDuration += duration;
            Instant arriveAt = cursor.plusMillis(Math.round(duration * 1000));

            boolean backAtStart = toPoint == 0;
            String toLabel = backAtStart ? "Start" : labelOf(request, toPoint);
            legs.add(new OptimizedRoute.Leg(step - 1, step, fromPoint == 0 ? "Start" : labelOf(request, fromPoint),
                    toLabel, distance, duration, coordinates(network, path)));

            Instant leaveAt = arriveAt;
            if (!backAtStart) {
                OptimizeStop stop = request.stops().get(toPoint - 1);
                int service = stop.serviceOrZero();
                serviceSeconds += service * 60.0;
                leaveAt = arriveAt.plus(Duration.ofMinutes(service));
                if (stop.dueBy() != null && arriveAt.isAfter(stop.dueBy())) {
                    late.add(new OptimizedRoute.Late(toPoint - 1, labelOf(request, toPoint), stop.dueBy(), arriveAt,
                            Duration.between(stop.dueBy(), arriveAt).toSeconds()));
                }
            }
            visits.add(visit(step, backAtStart ? -1 : toPoint - 1, toLabel, points.get(toPoint), arriveAt, leaveAt));
            cursor = leaveAt;
        }

        return new OptimizedRoute(mode, request.returnsToStart(), departAt, totalDistance, totalDuration,
                serviceSeconds, cursor, tour.algorithm(), tour.optimal(), tour.steps(), sequencingMillis,
                request.stops().size(), visits, legs, late, comparedTo, network.version());
    }

    private static OptimizedRoute.Visit visit(int sequence, int stopIndex, String label,
                                              RouteEngine.SnappedPoint point, Instant arriveAt, Instant departAt) {
        return new OptimizedRoute.Visit(sequence, stopIndex, label, point.latitude(), point.longitude(), arriveAt,
                departAt, point.snapDistanceMeters());
    }

    private static String labelOf(OptimizeRequest request, int pointIndex) {
        OptimizeStop stop = request.stops().get(pointIndex - 1);
        return stop.label() == null || stop.label().isBlank() ? "Stop " + pointIndex : stop.label();
    }

    private static List<double[]> coordinates(RoadNetwork network, Path path) {
        List<double[]> coordinates = new ArrayList<>(path.nodeIds().size());
        for (int id : path.nodeIds()) {
            GraphNode node = network.graph().node(id);
            coordinates.add(new double[] {node.latitude(), node.longitude()});
        }
        return coordinates;
    }
}
