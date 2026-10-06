package com.smartroute.tracking;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The server-sent-events fan-out: one emitter per connected dashboard, written to from the Kafka consumer.
 *
 * <p>SSE rather than WebSockets because the traffic is one-way (server to browser), it is plain HTTP (so it
 * passes proxies and needs no second protocol), and the browser reconnects on its own.
 *
 * <p>Back-pressure is the real problem with a live feed: a browser on a slow connection must not be able to
 * hold up the Kafka consumer or fill the server's memory. Frames are therefore written directly and a client
 * whose write fails (a full socket buffer surfaces as an {@link IOException}) is dropped rather than
 * buffered — it will reconnect and get the current state from {@code GET /api/tracking/drivers}. Keeping a
 * queue per client would postpone that decision, not remove it, so the decision is made here, visibly.
 */
@Service
public class LiveStream {

    private static final Logger log = LoggerFactory.getLogger(LiveStream.class);

    private final Map<Long, SseEmitter> clients = new ConcurrentHashMap<>();
    private final AtomicLong nextClientId = new AtomicLong();
    private final Counter framesSent;
    private final Counter clientsDropped;

    LiveStream(MeterRegistry meters) {
        this.framesSent = Counter.builder("smartroute.live.frames").register(meters);
        this.clientsDropped = Counter.builder("smartroute.live.clients.dropped").register(meters);
        Gauge.builder("smartroute.live.clients", clients, Map::size).register(meters);
    }

    /** Registers a new client. The emitter has no timeout: the heartbeat keeps the connection in use. */
    public SseEmitter open() {
        long id = nextClientId.incrementAndGet();
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> clients.remove(id));
        emitter.onTimeout(() -> clients.remove(id));
        emitter.onError(error -> clients.remove(id));
        clients.put(id, emitter);
        try {
            emitter.send(SseEmitter.event().name("hello").data(Map.of("clients", clients.size())));
        } catch (IOException gone) {
            clients.remove(id);
        }
        return emitter;
    }

    /** Sends one frame to every connected client; clients that cannot keep up are dropped. */
    public void publish(String event, Object payload) {
        clients.forEach((id, emitter) -> {
            try {
                emitter.send(SseEmitter.event().name(event).data(payload));
                framesSent.increment();
            } catch (IOException | IllegalStateException cannotKeepUp) {
                // A browser that closed the tab, or one whose socket buffer is full. Either way it is gone.
                clients.remove(id);
                clientsDropped.increment();
                emitter.complete();
                log.debug("Dropped live client {}: {}", id, cannotKeepUp.toString());
            }
        });
    }

    /**
     * A comment frame on an idle stream. Without it, a proxy (or the browser) closes a connection that has
     * said nothing for a minute, and the dashboard reconnects for no reason.
     */
    @Scheduled(fixedDelayString = "${smartroute.tracking.heartbeat:20s}")
    void heartbeat() {
        if (!clients.isEmpty()) {
            publish("heartbeat", Map.of("at", java.time.Instant.now().toString()));
        }
    }

    public int connectedClients() {
        return clients.size();
    }

    /** Closes every stream; used when the application shuts down and by tests. */
    public void closeAll() {
        clients.values().forEach(SseEmitter::complete);
        clients.clear();
    }
}
