package com.smartroute.assignment;

import com.smartroute.common.security.Role;
import com.smartroute.fleet.DriverService;
import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The visiting order over a driver's own active deliveries. */
class DeliveryRouteApiTest extends ApiTestSupport {

    @Autowired
    private DriverService driverService;

    private long warehouseId;
    private long driverId;

    @BeforeEach
    void setUpDriverWithDeliveries() throws Exception {
        warehouseId = createWarehouse("WH-ROUTE");
        long vehicleId = body(postJson("/api/vehicles", """
                {"plateNumber":"KA01-R-0001","type":"VAN","maxWeightKg":600,"maxVolumeM3":4}""")).get("id").asLong();
        driverId = createDriver(warehouseId, vehicleId);
        driverService.updateLocation(driverId, 12.9620, 77.5820, Instant.now());
        putJson("/api/drivers/" + driverId + "/status?status=AVAILABLE", "").andExpect(status().isOk());
    }

    private long order(double lat, double lon, String windowEnd) throws Exception {
        return body(postJson("/api/orders", """
                {"warehouseId":%d,"customerName":"Test Customer","dropAddress":"1 Test Street",
                 "dropLatitude":%s,"dropLongitude":%s,"priority":"NORMAL","weightKg":5,"volumeM3":0.05%s}
                """.formatted(warehouseId, lat, lon, windowEnd))).get("id").asLong();
    }

    private void assign(long orderId) throws Exception {
        postJson("/api/assignments", """
                {"orderId":%d,"driverId":%d}""".formatted(orderId, driverId)).andExpect(status().isCreated());
    }

    @Test
    void sequencesTheDriversDropsFromWhereTheyAre() throws Exception {
        // Three drops east of the driver, assigned in the wrong order on purpose.
        long far = order(12.9620, 77.6100, "");
        long near = order(12.9620, 77.5900, "");
        long middle = order(12.9620, 77.6000, "");
        assign(far);
        assign(near);
        assign(middle);

        JsonNode route = body(getUrl("/api/deliveries/route?driverId=" + driverId).andExpect(status().isOk()));
        List<String> labels = new ArrayList<>();
        route.get("visits").forEach(v -> labels.add(v.get("label").asString()));
        assertThat(labels).hasSize(4);
        assertThat(labels.getFirst()).isEqualTo("Start");
        JsonNode orders = body(getUrl("/api/orders?driverId=" + driverId));
        String nearCode = codeOf(orders, near);
        String middleCode = codeOf(orders, middle);
        String farCode = codeOf(orders, far);
        assertThat(labels.subList(1, 4)).containsExactly(nearCode, middleCode, farCode);
        assertThat(route.get("returnToStart").asBoolean()).isFalse();
        assertThat(route.get("optimal").asBoolean()).isTrue();
        // Each visit carries its arrival, and 4 minutes of service time per drop pushes the next one back.
        assertThat(route.get("serviceSeconds").asDouble()).isEqualTo(3 * 4 * 60.0);
        assertThat(route.get("visits").get(1).get("arriveAt").asString()).isNotBlank();
        assertThat(route.get("totalDurationSeconds").asDouble()).isPositive();
    }

    private static String codeOf(JsonNode orders, long id) {
        for (JsonNode order : orders.get("content")) {
            if (order.get("id").asLong() == id) {
                return order.get("code").asString();
            }
        }
        throw new AssertionError("order " + id + " not in the list");
    }

    @Test
    void deliveryWindowsThatCannotBeMetAreFlagged() throws Exception {
        assign(order(13.0500, 77.6800, ",\"windowStart\":\"2026-10-06T10:00:00Z\",\"windowEnd\":\"%s\""
                .formatted(Instant.now().plusSeconds(60))));
        JsonNode route = body(getUrl("/api/deliveries/route?driverId=" + driverId).andExpect(status().isOk()));
        assertThat(route.get("lateStops")).hasSize(1);
        assertThat(route.get("lateStops").get(0).get("lateBySeconds").asLong()).isPositive();
        assertThat(route.get("visits")).hasSize(2);
    }

    @Test
    void aDriverSeesTheirOwnRouteAndNobodyElsesData() throws Exception {
        assign(order(12.9700, 77.5900, ""));
        String token = users.driverToken(driverId);
        String viewer = users.token(Role.VIEWER);
        getUrl("/api/deliveries/mine/route", token).andExpect(status().isOk())
                .andExpect(jsonPath("$.visits.length()").value(2));
        getUrl("/api/deliveries/route?driverId=" + driverId, token).andExpect(status().isForbidden());
        getUrl("/api/deliveries/mine/route", viewer).andExpect(status().isForbidden());
        getUrl("/api/deliveries/route?driverId=" + driverId, viewer).andExpect(status().isOk());
    }

    @Test
    void clearMessagesWhenThereIsNothingToRoute() throws Exception {
        getUrl("/api/deliveries/route?driverId=" + driverId).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("no active deliveries")));
        getUrl("/api/deliveries/route?driverId=999999").andExpect(status().isNotFound());

        long other = createDriver(warehouseId, body(postJson("/api/vehicles", """
                {"plateNumber":"KA01-R-0002","type":"VAN","maxWeightKg":600,"maxVolumeM3":4}""")).get("id").asLong());
        getUrl("/api/deliveries/route?driverId=" + other).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("no known position")));
    }
}
