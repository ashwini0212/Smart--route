package com.smartroute.tracking;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
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

    /** Connections one user may hold at once. A dashboard needs one; a few tabs are normal; a loop is not. */
    static final int MAX_PER_USER = 4;

    private final Map<Long, SseEmitter> clients = new ConcurrentHashMap<>();
    /** Client ids per user, oldest first, so the oldest can be closed when a user opens one too many. */
    private final Map<Long, Deque<Long>> byUser = new ConcurrentHashMap<>();
    private final AtomicLong nextClientId = new AtomicLong();
    private final Counter framesSent;
    private final Counter clientsDropped;
    private final Clock clock;

    LiveStream(MeterRegistry meters, Clock clock) {
        this.clock = clock;
        this.framesSent = Counter.builder("smartroute.live.frames").register(meters);
        this.clientsDropped = Counter.builder("smartroute.live.clients.dropped").register(meters);
        Gauge.builder("smartroute.live.clients", clients, Map::size).register(meters);
    }

    /**
     * Registers a new client for one user. The emitter has no timeout: the heartbeat keeps the connection in
     * use.
     *
     * <p>Each connection is a held HTTP connection plus a copy of every frame, and nothing else here limits
     * how many a token may open, so a user's oldest connection is closed when they open their
     * {@value #MAX_PER_USER}+1-th. Closing the oldest rather than refusing the newest keeps a reconnecting
     * browser working: the stale connection is usually the one that was already abandoned.
     */
    public SseEmitter open(long userId) {
        long id = nextClientId.incrementAndGet();
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> forget(userId, id));
        emitter.onTimeout(() -> forget(userId, id));
        emitter.onError(error -> forget(userId, id));
        clients.put(id, emitter);
        enforceLimit(userId, id);
        try {
            emitter.send(SseEmitter.event().name("hello").data(Map.of("clients", clients.size())));
        } catch (IOException gone) {
            forget(userId, id);
        }
        return emitter;
    }

    private void enforceLimit(long userId, long newClientId) {
        Deque<Long> held = byUser.computeIfAbsent(userId, key -> new ConcurrentLinkedDeque<>());
        held.addLast(newClientId);
        while (held.size() > MAX_PER_USER) {
            Long oldest = held.pollFirst();
            if (oldest == null) {
                return;
            }
            SseEmitter stale = clients.remove(oldest);
            if (stale != null) {
                clientsDropped.increment();
                try {
                    stale.complete();
                } catch (RuntimeException alreadyGone) {
                    log.debug("Closing a replaced stream failed: {}", alreadyGone.toString());
                }
            }
        }
    }

    private void forget(long userId, long clientId) {
        clients.remove(clientId);
        Deque<Long> held = byUser.get(userId);
        if (held != null) {
            held.remove(clientId);
            if (held.isEmpty()) {
                byUser.remove(userId);
            }
        }
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
            publish("heartbeat", Map.of("at", clock.instant().toString()));
        }
    }

    public int connectedClients() {
        return clients.size();
    }

    /**
     * Closes every stream, at shutdown and in tests.
     *
     * <p>The annotation is the point: without it this said "used when the application shuts down" and nothing
     * called it, so a restart dropped every open connection instead of ending it. A completed stream tells the
     * browser the stream is over; a dropped one looks like a network fault it should retry through.
     */
    @PreDestroy
    public void closeAll() {
        clients.values().forEach(SseEmitter::complete);
        clients.clear();
    }
}
