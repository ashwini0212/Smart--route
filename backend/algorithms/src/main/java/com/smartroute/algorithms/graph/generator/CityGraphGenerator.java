package com.smartroute.algorithms.graph.generator;

import com.smartroute.algorithms.graph.GeoMath;
import com.smartroute.algorithms.graph.Graph;

import java.util.Random;

/**
 * Generates a deterministic synthetic city: a jittered grid of intersections with arterial roads,
 * local streets, some one-way segments and some missing segments.
 *
 * <p>This is test and benchmark data, not a real map. It lets us test and benchmark on graphs of any size
 * with a fixed seed. Real road data (OpenStreetMap) is added in Phase 3.
 *
 * <p>Every edge length is {@code straight-line distance × curvature}, with curvature ≥ 1, so the
 * haversine A* heuristic stays consistent.
 */
public final class CityGraphGenerator {

    private static final double METERS_PER_DEGREE_LATITUDE = 111_320.0;
    /** Intersections are shifted by up to this fraction of a block so the grid isn't perfectly regular. */
    private static final double POSITION_JITTER = 0.15;
    /** Real streets are not perfectly straight: length is 0–15 % longer than the straight line. */
    private static final double MAX_EXTRA_CURVATURE = 0.15;

    private CityGraphGenerator() {
    }

    public static Graph generate(CityGraphConfig config) {
        Random random = new Random(config.seed());
        Graph.Builder builder = Graph.builder();
        double metersPerDegreeLongitude =
                METERS_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(config.originLatitude()));

        for (int r = 0; r < config.rows(); r++) {
            for (int c = 0; c < config.columns(); c++) {
                double northMeters = (r + jitter(random)) * config.blockSizeMeters();
                double eastMeters = (c + jitter(random)) * config.blockSizeMeters();
                builder.addNode(
                        config.originLatitude() + northMeters / METERS_PER_DEGREE_LATITUDE,
                        config.originLongitude() + eastMeters / metersPerDegreeLongitude);
            }
        }

        Graph nodesOnly = builder.build(); // used only to read node coordinates
        for (int r = 0; r < config.rows(); r++) {
            for (int c = 0; c < config.columns(); c++) {
                int id = r * config.columns() + c;
                if (c + 1 < config.columns()) {
                    addRoad(builder, nodesOnly, config, random, id, id + 1, isArterial(r, config));
                }
                if (r + 1 < config.rows()) {
                    addRoad(builder, nodesOnly, config, random, id, id + config.columns(), isArterial(c, config));
                }
            }
        }
        return builder.build();
    }

    private static void addRoad(Graph.Builder builder, Graph coordinates, CityGraphConfig config, Random random,
                                int a, int b, boolean arterial) {
        // Arterials are always present and two-way; local streets can be missing or one-way.
        if (!arterial && random.nextDouble() < config.missingRoadProbability()) {
            return;
        }
        double straight = GeoMath.haversineMeters(coordinates.node(a), coordinates.node(b));
        double length = straight * (1 + random.nextDouble() * MAX_EXTRA_CURVATURE);
        double speedKmh = arterial ? config.arterialSpeedKmh() : config.localSpeedKmh();
        double seconds = length / (speedKmh / 3.6);

        if (!arterial && random.nextDouble() < config.oneWayProbability()) {
            if (random.nextBoolean()) {
                builder.addEdge(a, b, length, seconds);
            } else {
                builder.addEdge(b, a, length, seconds);
            }
        } else {
            builder.addBidirectionalEdge(a, b, length, seconds);
        }
    }

    private static boolean isArterial(int lineIndex, CityGraphConfig config) {
        return lineIndex % config.arterialEvery() == 0;
    }

    private static double jitter(Random random) {
        return (random.nextDouble() * 2 - 1) * POSITION_JITTER;
    }
}
