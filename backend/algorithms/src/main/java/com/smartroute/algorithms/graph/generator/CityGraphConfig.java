package com.smartroute.algorithms.graph.generator;

/**
 * Parameters for a synthetic city road grid.
 *
 * @param rows                 grid rows (north–south), {@code >= 1}
 * @param columns              grid columns (east–west), {@code >= 1}
 * @param blockSizeMeters      distance between neighbouring intersections
 * @param originLatitude       latitude of the south-west corner
 * @param originLongitude      longitude of the south-west corner
 * @param arterialEvery        every n-th row/column is a faster arterial road
 * @param arterialSpeedKmh     free-flow speed on arterials
 * @param localSpeedKmh        free-flow speed on local streets
 * @param oneWayProbability    chance a local street segment is one-way
 * @param missingRoadProbability chance a local street segment does not exist (irregular blocks)
 * @param seed                 random seed; the same config always yields the same graph
 */
public record CityGraphConfig(
        int rows,
        int columns,
        double blockSizeMeters,
        double originLatitude,
        double originLongitude,
        int arterialEvery,
        double arterialSpeedKmh,
        double localSpeedKmh,
        double oneWayProbability,
        double missingRoadProbability,
        long seed) {

    /** Central Bengaluru, roughly 150 m blocks. */
    public static final double BENGALURU_LATITUDE = 12.9550;
    public static final double BENGALURU_LONGITUDE = 77.5750;

    public CityGraphConfig {
        if (rows < 1 || columns < 1) {
            throw new IllegalArgumentException("rows and columns must be >= 1");
        }
        if (blockSizeMeters <= 0 || arterialSpeedKmh <= 0 || localSpeedKmh <= 0) {
            throw new IllegalArgumentException("sizes and speeds must be positive");
        }
        if (arterialEvery < 1) {
            throw new IllegalArgumentException("arterialEvery must be >= 1");
        }
        if (oneWayProbability < 0 || oneWayProbability > 1 || missingRoadProbability < 0 || missingRoadProbability >= 1) {
            throw new IllegalArgumentException("probabilities must be in [0, 1)");
        }
    }

    /** A realistic default: mostly two-way streets, some one-ways and gaps, arterials every 5 blocks. */
    public static CityGraphConfig grid(int rows, int columns, long seed) {
        return new CityGraphConfig(rows, columns, 150, BENGALURU_LATITUDE, BENGALURU_LONGITUDE,
                5, 40, 20, 0.15, 0.05, seed);
    }
}
