package com.gaurav.ecommerce.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaurav.ecommerce.dto.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-Sent Events (SSE) Broadcaster.
 *
 * Maintains a registry of active browser connections and pushes
 * real-time order events to all connected dashboard clients.
 *
 * SSE vs WebSocket for this use case:
 * - SSE is unidirectional (server → client) — perfect for live dashboards
 * - SSE uses plain HTTP — no upgrade handshake, works through proxies/load balancers
 * - Auto-reconnect built into browser EventSource API
 * - Lower overhead than WebSocket for read-only streaming
 *
 * Browser connects to: GET /api/orders/stream
 * Every new order event is pushed within ~100ms of Kafka consumption.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SseEventBroadcaster {

    private final ObjectMapper objectMapper;

    // Thread-safe registry: clientId → SseEmitter
    private final Map<Long, SseEmitter> clients    = new ConcurrentHashMap<>();
    private final AtomicLong            clientIdGen = new AtomicLong(0);

    /**
     * Register a new SSE client (called when browser connects to /api/orders/stream).
     * Timeout: 5 minutes — browser auto-reconnects via EventSource.
     */
    public SseEmitter register() {
        long       clientId = clientIdGen.incrementAndGet();
        SseEmitter emitter  = new SseEmitter(5 * 60 * 1000L); // 5 min timeout

        // Clean up on completion / timeout / error
        emitter.onCompletion(() -> {
            clients.remove(clientId);
            log.debug("SSE client disconnected: clientId={}, remaining={}", clientId, clients.size());
        });
        emitter.onTimeout(() -> {
            clients.remove(clientId);
            emitter.complete();
            log.debug("SSE client timed out: clientId={}", clientId);
        });
        emitter.onError(ex -> {
            clients.remove(clientId);
            log.debug("SSE client error: clientId={}, error={}", clientId, ex.getMessage());
        });

        clients.put(clientId, emitter);
        log.info("SSE client registered: clientId={}, total clients={}", clientId, clients.size());

        // Send initial connection confirmation
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("message", "Connected to order event stream",
                                 "clientId", clientId)));
        } catch (IOException e) {
            clients.remove(clientId);
        }

        return emitter;
    }

    /**
     * Broadcast an order event to all connected SSE clients.
     * Called by OrderEventConsumer after every successful Kafka message processing.
     */
    public void broadcast(OrderEvent event) {
        if (clients.isEmpty()) return;

        String eventData;
        try {
            // Send a lean dashboard payload — not the full event
            eventData = objectMapper.writeValueAsString(Map.of(
                    "orderId",       event.getOrderId(),
                    "customerId",    event.getCustomerId(),
                    "customerTier",  event.getCustomerTier(),
                    "status",        event.getStatus(),
                    "totalAmount",   event.getTotalAmount(),
                    "currency",      event.getCurrency(),
                    "region",        event.getRegion(),
                    "paymentMethod", event.getPaymentMethod() != null ? event.getPaymentMethod() : "",
                    "timestamp",     event.getEventTimestamp() != null
                                         ? event.getEventTimestamp().toString() : "",
                    "itemCount",     event.getItems() != null ? event.getItems().size() : 0
            ));
        } catch (Exception e) {
            log.warn("Failed to serialize SSE payload for orderId={}", event.getOrderId());
            return;
        }

        // Broadcast to all connected clients; remove dead ones
        clients.forEach((clientId, emitter) -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("order")
                        .data(eventData));
            } catch (IOException e) {
                log.debug("SSE client gone, removing: clientId={}", clientId);
                clients.remove(clientId);
                emitter.completeWithError(e);
            }
        });

        log.debug("SSE broadcast: orderId={}, recipients={}", event.getOrderId(), clients.size());
    }

    /** Returns count of currently connected SSE clients */
    public int getConnectedClientCount() {
        return clients.size();
    }
}
