package com.smartroute.algorithms.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GeoMathTest {

    @Test
    void zeroForSamePoint() {
        assertThat(GeoMath.haversineMeters(12.97, 77.59, 12.97, 77.59)).isZero();
    }

    @Test
    void oneDegreeOfLatitudeIsAbout111Km() {
        assertThat(GeoMath.haversineMeters(0, 0, 1, 0)).isCloseTo(111_195, within(10.0));
    }

    @Test
    void bengaluruToMysuruStraightLineIsAbout128Km() {
        // MG Road, Bengaluru (12.9756, 77.6066) to Mysuru Palace (12.3052, 76.6552): ~128 km great-circle.
        assertThat(GeoMath.haversineMeters(12.9756, 77.6066, 12.3052, 76.6552)).isCloseTo(127_900, within(1_000.0));
    }

    @Test
    void isSymmetric() {
        double ab = GeoMath.haversineMeters(12.9, 77.5, 13.1, 77.7);
        double ba = GeoMath.haversineMeters(13.1, 77.7, 12.9, 77.5);
        assertThat(ab).isCloseTo(ba, within(1e-9));
    }
}
