package com.smartroute.tracking;

import com.smartroute.common.security.Role;
import com.smartroute.fleet.DriverService;
import com.smartroute.fleet.LocationSource;
import com.smartroute.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The live stream and the tracking endpoints: who may read them, and what a client actually receives. */
class LiveStreamApiTest extends ApiTestSupport {

    @Autowired
    private LiveStream stream;

    @Autowired
    private LivePositions positions;

    @Autowired
    private DriverService drivers;

    private long warehouseId;

    @BeforeEach
    void setUpHub() throws Exception {
        warehouseId = createWarehouse("WH-STREAM");
        stream.closeAll();
    }

    @AfterEach
    void closeStreams() {
        stream.closeAll();
    }

    private MvcResult openStream(String token) throws Exception {
        return mockMvc.perform(auth(get("/api/tracking/stream").accept(MediaType.TEXT_EVENT_STREAM), token))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    @Test
    void aConnectedClientReceivesTheFramesThatArePublished() throws Exception {
        MvcResult result = openStream(adminToken);
        await().until(() -> stream.connectedClients() == 1);

        stream.publish("driver-moved", Map.of("driverId", 7, "latitude", 12.97, "longitude", 77.59));

        String body = result.getResponse().getContentAsString();
        // The first frame is the greeting, so the client knows the stream is open before anything happens.
        assertThat(body).contains("event:hello");
        assertThat(body).contains("event:driver-moved", "\"driverId\":7");
    }

    @Test
    void aClientThatIsGoneIsDroppedInsteadOfBeingBufferedForever() throws Exception {
        openStream(adminToken);
        await().until(() -> stream.connectedClients() == 1);
        stream.closeAll();

        stream.publish("driver-moved", Map.of("driverId", 1));

        assertThat(stream.connectedClients()).isZero();
    }

    @Test
    void oneUserCannotHoldUnlimitedConnections() throws Exception {
        // Each connection is a held HTTP connection and a copy of every frame, and the same token can open
        // them in a loop. The oldest is closed rather than the newest refused, so a reconnecting browser wins.
        for (int i = 0; i < LiveStream.MAX_PER_USER + 3; i++) {
            openStream(adminToken);
        }

        await().until(() -> stream.connectedClients() == LiveStream.MAX_PER_USER);
    }

    @Test
    void twoUsersEachGetTheirOwnAllowance() throws Exception {
        String dispatcher = users.token(Role.DISPATCHER);
        for (int i = 0; i < LiveStream.MAX_PER_USER; i++) {
            openStream(adminToken);
            openStream(dispatcher);
        }

        await().until(() -> stream.connectedClients() == LiveStream.MAX_PER_USER * 2);
    }

    @Test
    void theHeartbeatKeepsAnIdleStreamOpen() throws Exception {
        MvcResult result = openStream(adminToken);
        await().until(() -> stream.connectedClients() == 1);

        stream.heartbeat();

        assertThat(result.getResponse().getContentAsString()).contains("event:heartbeat");
    }

    @Test
    void theStreamIsForStaffAndViewersOnly() throws Exception {
        openStream(users.token(Role.VIEWER));
        mockMvc.perform(auth(get("/api/tracking/stream"), users.driverToken(createDriver(warehouseId, null))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tracking/stream")).andExpect(status().isUnauthorized());
    }

    @Test
    void positionsAreReadableAndSayWhereTheyCameFrom() throws Exception {
        long driverId = createDriver(warehouseId, createVehicle("KA01-S-0001", "VAN"));
        drivers.updateLocation(driverId, 12.95, 77.60, Instant.now());
        positions.record(new LivePosition(driverId, 12.98, 77.62, Instant.now(), LocationSource.SIMULATION));

        getUrl("/api/tracking/drivers")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].driverId").value(driverId))
                .andExpect(jsonPath("$[0].source").value("SIMULATION"));
        getUrl("/api/tracking/drivers/" + driverId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(12.98));
        getUrl("/api/tracking/drivers", users.token(Role.VIEWER)).andExpect(status().isOk());
        getUrl("/api/tracking/drivers", users.driverToken(createDriver(warehouseId, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownDriverPositionIs404() throws Exception {
        getUrl("/api/tracking/drivers/424242").andExpect(status().isNotFound());
    }

    @Test
    void theAdministrationEndpointsAreAdminOnly() throws Exception {
        String dispatcher = users.token(Role.DISPATCHER);

        postJson("/api/tracking/sweep", "", dispatcher).andExpect(status().isForbidden());
        postJson("/api/tracking/positions/rebuild", "", dispatcher).andExpect(status().isForbidden());
        postJson("/api/tracking/positions/rebuild", "").andExpect(status().isOk());
        getUrl("/api/tracking/stream/clients", users.token(Role.VIEWER)).andExpect(status().isForbidden());
    }

    @Test
    void theSimulationStatusSaysNothingIsSimulatedByDefault() throws Exception {
        getUrl("/api/simulation/status")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverMovement").value(false))
                .andExpect(jsonPath("$.traffic").value(false))
                .andExpect(jsonPath("$.note").value("Nothing is being simulated"));
    }
}
