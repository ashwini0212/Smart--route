package com.smartroute.routing;

import com.smartroute.algorithms.graph.GeoMath;
import com.smartroute.common.error.ApiException;
import com.smartroute.common.security.CurrentUser;
import com.smartroute.common.security.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Computes routes for API callers and keeps a history of them. */
@Service
@Transactional(readOnly = true)
public class RouteHistoryService {

    private final RouteEngine engine;
    private final RouteRecordRepository records;

    RouteHistoryService(RouteEngine engine, RouteRecordRepository records) {
        this.engine = engine;
        this.records = records;
    }

    /** The search runs before the transaction's database work; only the INSERT happens inside it. */
    @Transactional
    public RouteResponse computeAndRecord(RouteMode mode, RouteRequest request, CurrentUser user) {
        RouteEngine.RouteOutcome outcome = engine.route(mode, request.from(), request.to());
        RouteRecord saved = records.save(new RouteRecord(user.id(), request.from(), request.to(), outcome.route(), outcome.cached()));
        return RouteResponse.of(saved.getId(), outcome.route(), outcome.from(), outcome.to(), outcome.cached(), saved.getCreatedAt());
    }

    /**
     * Staff and viewers can open any stored route; other users only their own. Someone else's route answers
     * 404, not 403, so ids can't be probed to learn which routes exist.
     */
    public RouteResponse get(long id, CurrentUser user) {
        RouteRecord record = records.findById(id)
                .filter(r -> canSeeAll(user) || r.getRequestedBy() == user.id())
                .orElseThrow(() -> ApiException.notFound("Route", id));
        return toResponse(record);
    }

    public Page<RouteResponse> mine(CurrentUser user, Pageable pageable) {
        return records.findByRequestedByOrderByCreatedAtDesc(user.id(), pageable).map(RouteHistoryService::toResponse);
    }

    private static boolean canSeeAll(CurrentUser user) {
        return user.role() == Role.ADMIN || user.role() == Role.DISPATCHER || user.role() == Role.VIEWER;
    }

    private static RouteResponse toResponse(RouteRecord r) {
        RoutePath path = new RoutePath(r.getMode(), r.getFromNode(), r.getToNode(), r.getDistanceMeters(),
                r.getDurationSeconds(), r.pathPoints(), r.getAlgorithm(), true, r.getNodesSettled(), r.getGraphVersion());
        double[] first = r.pathPoints().getFirst();
        double[] last = r.pathPoints().getLast();
        RouteEngine.SnappedPoint from = new RouteEngine.SnappedPoint(r.getFromNode(), first[0], first[1],
                GeoMath.haversineMeters(r.from().latitude(), r.from().longitude(), first[0], first[1]));
        RouteEngine.SnappedPoint to = new RouteEngine.SnappedPoint(r.getToNode(), last[0], last[1],
                GeoMath.haversineMeters(r.to().latitude(), r.to().longitude(), last[0], last[1]));
        return RouteResponse.of(r.getId(), path, from, to, r.isCacheHit(), r.getCreatedAt());
    }
}
