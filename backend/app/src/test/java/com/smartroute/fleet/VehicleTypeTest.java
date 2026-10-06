package com.smartroute.fleet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleTypeTest {

    @Test
    void biggerVehiclesSatisfySmallerRequirements() {
        assertThat(VehicleType.TRUCK.satisfies(VehicleType.VAN)).isTrue();
        assertThat(VehicleType.VAN.satisfies(VehicleType.VAN)).isTrue();
        assertThat(VehicleType.BIKE.satisfies(VehicleType.VAN)).isFalse();
        assertThat(VehicleType.BIKE.satisfies(null)).isTrue();
    }
}
