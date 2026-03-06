package com.gaurav.ecommerce.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaurav.ecommerce.model.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-Sent Events (SSE) Service — real-time dashboard updates.
 *
 * SSE vs WebSocket:
 * - SSE: server → client only, HTTP/1.1 compatible, auto-reconnect built-in
 * - WebSocket: bidirectional, requires upgrade handshake
 * - For dashboards (read-only), SSE is simpler and sufficient.
 *
 * Thread safety:
 * - ConcurrentHashMap for emitter registry (concurrent connects/disconnects)
 * - CopyOnWriteArrayList snapshot for broadcast (safe iteration while removing dead emitters)
 *
 * How it works:
 * 1. Dashboard client connects → GET /api/v1/orders/stream → SSE connection opened
 * 2. Each new order processed by Kafka consumer → broadcastOrder() called
 * 3. Order JSON pushed to ALL connected clients instantly
 * 4. Browser JavaScript updates dashboard in real-time (no polling)
 * 5. Dead emitters (closed browsers) detected on send failure → cleaned up
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SseEmitterService {

    private final ObjectMapper objectMapper;

    // Thread-safe registry of all active SSE connections
    // Key: unique client ID, Value: SseEmitter
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L; // 30 minutes

    /**
     * Register a new SSE client connection.
     * Called when a browser opens GET /api/v1/orders/stream
     */
    public SseEmitter createEmitter(String clientId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // Clean up on completion, timeout, or error
        emitter.onCompletion(() -> {
            log.info("SSE client disconnected: clientId={}", clientId);
            emitters.remove(clientId);
        });
        emitter.onTimeout(() -> {
            log.info("SSE client timed out: clientId={}", clientId);
            emitters.remove(clientId);
            emitter.complete();
        });
        emitter.onError(ex -> {
            log.warn("SSE error for clientId={}: {}", clientId, ex.getMessage());
            emitters.remove(clientId);
        });

        emitters.put(clientId, emitter);
        log.info("SSE client connected: clientId={}, totalClients={}", clientId, emitters.size());

        // Send a welcome event so client knows connection is established
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("clientId", clientId, "message", "Connected to order stream")));
        } catch (IOException e) {
            log.warn("Could not send welcome event to clientId={}", clientId);
        }

        return emitter;
    }

    /**
     * Broadcast a processed order event to ALL connected SSE clients.
     * Called asynchronously from Kafka consumer — does not block consumer processing.
     */
    @Async
    public void broadcastOrder(OrderEvent order) {
        if (emitters.isEmpty()) {
            return; // No clients connected — skip serialization overhead
        }

        try {
            // Build a lightweight dashboard event (not full order to save bandwidth)
            Map<String, Object> dashboardEvent = Map.of(
                    "orderId",      order.getOrderId(),
                    "customerId",   order.getCustomerId(),
                    "customerName", order.getCustomerName() != null ? order.getCustomerName() : "",
                    "status",       order.getStatus().name(),
                    "priority",     order.getPriority().name(),
                    "region",       order.getRegion(),
                    "category",     order.getCategory(),
                    "totalAmount",  order.getTotalAmount(),
                    "currency",     order.getCurrency(),
                    "partition",    order.getKafkaPartition(),
                    "timestamp",    order.getCreatedAt() != null ? order.getCreatedAt().toString() : ""
            );

            String payload = objectMapper.writeValueAsString(dashboardEvent);

            // Use CopyOnWriteArrayList snapshot for safe concurrent iteration
            new CopyOnWriteArrayList<>(emitters.entrySet()).forEach(entry -> {
                try {
                    entry.getValue().send(SseEmitter.event()
                            .name("order")
                            .data(payload));
                } catch (IOException e) {
                    log.warn("Failed to send to SSE client: clientId={} — removing", entry.getKey());
                    emitters.remove(entry.getKey());
                }
            });

            log.debug("Broadcasted order event to {} SSE clients: orderId={}",
                    emitters.size(), order.getOrderId());

        } catch (Exception e) {
            log.error("Failed to broadcast SSE event: {}", e.getMessage());
        }
    }

    /**
     * Broadcast a summary/stats update to all clients.
     * Called periodically to refresh dashboard metrics.
     */
    public void broadcastStats(Map<String, Object> stats) {
        if (emitters.isEmpty()) return;

        try {
            String payload = objectMapper.writeValueAsString(stats);
            new CopyOnWriteArrayList<>(emitters.entrySet()).forEach(entry -> {
                try {
                    entry.getValue().send(SseEmitter.event().name("stats").data(payload));
                } catch (IOException e) {
                    emitters.remove(entry.getKey());
                }
            });
        } catch (Exception e) {
            log.error("Failed to broadcast stats: {}", e.getMessage());
        }
    }

    public int getConnectedClientCount() {
        return emitters.size();
    }
}
