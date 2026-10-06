package com.smartroute.fleet;

import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FleetApiTest extends ApiTestSupport {

    @Test
    void registersDriverWithGeneratedCodeAndVehicle() throws Exception {
        long warehouse = createWarehouse("WH-A");
        long vehicle = createVehicle("KA01-V-0001", "VAN");
        long driver = createDriver(warehouse, vehicle);
        getUrl("/api/drivers/" + driver)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.matchesPattern("DRV-\\d{6}")))
                .andExpect(jsonPath("$.status").value("OFFLINE"))
                .andExpect(jsonPath("$.vehicleId").value(vehicle))
                .andExpect(jsonPath("$.activeDeliveryCount").value(0));
    }

    @Test
    void vehicleCannotBeAssignedToTwoDrivers() throws Exception {
        long warehouse = createWarehouse("WH-A");
        long vehicle = createVehicle("KA01-V-0001", "VAN");
        createDriver(warehouse, vehicle);
        postJson("/api/drivers", """
                {"fullName":"Second","phone":"+91 90000 00002","homeWarehouseId":%d,"vehicleId":%d}
                """.formatted(warehouse, vehicle))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("Vehicle KA01-V-0001 is already assigned to another driver"));
    }

    @Test
    void driverWithoutVehicleCannotGoOnShift() throws Exception {
        long driver = createDriver(createWarehouse("WH-A"), null);
        putJson("/api/drivers/" + driver + "/status?status=AVAILABLE", "")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void onDeliveryCannotBeSetManually() throws Exception {
        long driver = createDriver(createWarehouse("WH-A"), createVehicle("KA01-B-0001", "BIKE"));
        putJson("/api/drivers/" + driver + "/status?status=ON_DELIVERY", "")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void listsDriversFilteredByStatusWithPagination() throws Exception {
        long warehouse = createWarehouse("WH-A");
        for (int i = 1; i <= 3; i++) {
            long driver = createDriver(warehouse, createVehicle("KA01-B-000" + i, "BIKE"));
            if (i < 3) {
                putJson("/api/drivers/" + driver + "/status?status=AVAILABLE", "").andExpect(status().isOk());
            }
        }
        getUrl("/api/drivers?status=AVAILABLE&size=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void pageSizeIsBounded() throws Exception {
        getUrl("/api/drivers?size=1000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unknownEnumValueIsBadRequest() throws Exception {
        getUrl("/api/drivers?status=FLYING")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void driverNeedsAnExistingWarehouse() throws Exception {
        postJson("/api/drivers", """
                {"fullName":"No Hub","phone":"+91 90000 00003","homeWarehouseId":12345}
                """).andExpect(status().isUnprocessableContent());
    }

    @Test
    void vehicleAssignedToDriverCannotBeRetired() throws Exception {
        long vehicle = createVehicle("KA01-T-0001", "TRUCK");
        createDriver(createWarehouse("WH-A"), vehicle);
        putJson("/api/vehicles/" + vehicle + "/status?status=RETIRED", "")
                .andExpect(status().isUnprocessableContent());
    }
}
