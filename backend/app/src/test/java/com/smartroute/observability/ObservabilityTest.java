package com.smartroute.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.smartroute.common.security.Role;
import com.smartroute.common.web.CorrelationId;
import com.smartroute.events.EventConsumers;
import com.smartroute.events.EventEnvelope;
import com.smartroute.events.EventType;
import com.smartroute.support.ApiTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What makes this system debuggable: a correlation id that survives the request, the asynchronous hop and the
 * log line, and metrics that say what the system is doing.
 */
class ObservabilityTest extends ApiTestSupport {

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private DomainMetrics domainMetrics;

    @Autowired
    private EventConsumers consumers;

    private ListAppender<ILoggingEvent> accessLog;

    @BeforeEach
    void captureTheAccessLog() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = context.getLogger("com.smartroute.access");
        logger.setLevel(Level.INFO);
        accessLog = new ListAppender<>();
        accessLog.start();
        logger.addAppender(accessLog);
    }

    @AfterEach
    void releaseTheAccessLog() {
        ch.qos.logback.classic.Logger logger =
                ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("com.smartroute.access");
        logger.detachAppender(accessLog);
    }

    @Test
    void everyResponseCarriesACorrelationIdAndTheLogLineCarriesTheSameOne() throws Exception {
        getUrl("/api/warehouses").andExpect(status().isOk()).andExpect(header().exists(CorrelationId.HEADER));

        ILoggingEvent line = accessLog.list.getLast();
        assertThat(line.getFormattedMessage()).contains("GET /api/warehouses -> 200");
        assertThat(line.getMDCPropertyMap().get(CorrelationId.MDC_KEY)).isNotBlank();
        // And who made it, by id — never the email, which would put personal data in the log.
        assertThat(line.getMDCPropertyMap().get(RequestLogFilter.USER_MDC_KEY)).isNotBlank();
        assertThat(line.getFormattedMessage()).doesNotContain("@");
    }

    @Test
    void aClientSuppliedRequestIdIsReusedWhenItIsSafe() throws Exception {
        String mine = "11112222-3333-4444-5555-666677778888";
        mockMvc.perform(auth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/warehouses").header(CorrelationId.HEADER, mine), adminToken))
                .andExpect(header().string(CorrelationId.HEADER, mine));

        // A header that is not a plain id is replaced rather than written into the log as given.
        mockMvc.perform(auth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/warehouses").header(CorrelationId.HEADER, "nasty value\nINFO fake log line"), adminToken))
                .andExpect(header().string(CorrelationId.HEADER, org.hamcrest.Matchers.not("nasty value\nINFO fake log line")));
    }

    @Test
    void anEventCarriesItsRequestsTraceIdAcrossTheAsynchronousHop() {
        String traceId = UUID.randomUUID().toString();
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID().toString(), EventType.ORDER_CREATED, 1,
                "order", "1", Instant.now(), traceId, Map.of("orderId", 1));
        AtomicReference<String> seen = new AtomicReference<>();

        consumers.handleOnce("test-group", envelope, event -> seen.set(MDC.get(CorrelationId.MDC_KEY)));

        // This is the only thing that ties a log line written by a consumer back to the request that caused it.
        assertThat(seen.get()).isEqualTo(traceId);
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    void theDomainGaugesReportWhatIsInTheDatabase() throws Exception {
        long warehouseId = createWarehouse("WH-METRIC");
        postJson("/api/orders", """
                {"warehouseId":%d,"customerName":"Fictional Customer","dropAddress":"1 Test Street",
                 "dropLatitude":13.02,"dropLongitude":77.66,"priority":"NORMAL","weightKg":10,"volumeM3":0.5}
                """.formatted(warehouseId)).andExpect(status().isCreated());

        domainMetrics.sample();

        assertThat(meters.get("smartroute.orders.waiting").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("smartroute.deliveries.active").gauge().value()).isZero();
        assertThat(meters.get("smartroute.outbox.pending").gauge().value()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void metricsAreAdminOnlyAndIncludeTheApplicationsOwnMeters() throws Exception {
        getUrl("/actuator/prometheus")
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("smartroute_orders_waiting")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("application=\"smartroute\"")));

        getUrl("/actuator/prometheus", users.token(Role.DISPATCHER)).andExpect(status().isForbidden());
        getUrl("/actuator/metrics", users.token(Role.VIEWER)).andExpect(status().isForbidden());
        getUrl("/actuator/prometheus", null).andExpect(status().isUnauthorized());
    }

    @Test
    void healthStaysPublicSoProbesKeepWorking() throws Exception {
        getUrl("/actuator/health", null).andExpect(status().isOk());
        getUrl("/actuator/health/readiness", null).andExpect(status().isOk());
    }

    @Test
    void theHttpTimerRecordsRequestsWithTheirStatus() throws Exception {
        getUrl("/api/warehouses").andExpect(status().isOk());

        List<String> uris = meters.get("http.server.requests").timers().stream()
                .map(timer -> timer.getId().getTag("uri")).toList();
        assertThat(uris).contains("/api/warehouses");
    }

    @Test
    void theStreamAndTheProbesAreNotWrittenToTheAccessLog() throws Exception {
        getUrl("/actuator/health", null).andExpect(status().isOk());

        // Otherwise a health check every five seconds becomes most of the log, and the stream never ends.
        assertThat(accessLog.list.stream().map(ILoggingEvent::getFormattedMessage))
                .noneMatch(line -> line.contains("/actuator/health"));
    }
}
