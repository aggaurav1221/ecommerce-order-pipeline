package com.gaurav.ecommerce.controller;

import com.gaurav.ecommerce.dto.OrderEvent;
import com.gaurav.ecommerce.event.producer.OrderEventProducer;
import com.gaurav.ecommerce.model.OrderDocument;
import com.gaurav.ecommerce.repository.OrderRepository;
import com.gaurav.ecommerce.service.OrderPersistenceService;
import com.gaurav.ecommerce.service.SseEventBroadcaster;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST API for order events + SSE live dashboard stream.
 *
 * Endpoints:
 *   POST   /api/orders              → Publish order to Kafka
 *   GET    /api/orders              → Query orders (filter by status/customer/region/tier)
 *   GET    /api/orders/summary      → Sales summary (total orders, revenue, avg order value)
 *   GET    /api/orders/regional     → Regional sales breakdown
 *   GET    /api/orders/dlq          → Dead letter queue orders (failed processing)
 *   GET    /api/orders/stream       → SSE live event stream (open in browser!)
 */
@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Tag(name = "Order Pipeline", description = "E-Commerce order event publishing, querying, and live streaming")
public class OrderController {

    private final OrderEventProducer      producer;
    private final OrderPersistenceService persistenceService;
    private final SseEventBroadcaster     sseBroadcaster;
    private final OrderRepository         orderRepository;

    // ── PUBLISH ──────────────────────────────────────────────────────────────

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Publish an order event to Kafka",
               description = "PREMIUM customers routed to partition 0 via custom partitioner")
    public ResponseEntity<Map<String, String>> publishOrder(@Valid @RequestBody OrderEvent event) {
        log.info("API: publishing order: orderId={}, tier={}", event.getOrderId(), event.getCustomerTier());
        producer.publishOrder(event);
        return ResponseEntity.accepted().body(Map.of(
                "message",  "Order accepted and queued for processing",
                "orderId",  event.getOrderId(),
                "tier",     event.getCustomerTier(),
                "routing",  "PREMIUM".equals(event.getCustomerTier())
                             ? "partition-0 (priority lane)" : "standard partitions"
        ));
    }

    // ── QUERY ─────────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "Query orders with filters")
    public Page<OrderDocument> getOrders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String tier,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        if (status     != null) return persistenceService.getOrdersByStatus(status, page, size);
        if (customerId != null) return persistenceService.getOrdersByCustomer(customerId, page, size);
        if (region     != null) return persistenceService.getOrdersByRegion(region, page, size);
        if (tier       != null) return persistenceService.getOrdersByTier(tier, page, size);
        if (from != null && to != null) return persistenceService.getOrdersByDateRange(from, to, page, size);

        return orderRepository.findAll(
                org.springframework.data.domain.PageRequest.of(page, size,
                        org.springframework.data.domain.Sort.by("eventTimestamp").descending()));
    }

    @GetMapping("/summary")
    @Operation(summary = "Sales summary — total orders, revenue, average order value")
    public ResponseEntity<Map<String, Object>> getSummary() {
        var summary = persistenceService.getSalesSummary();
        long total  = orderRepository.count();
        long dlq    = orderRepository.findByProcessingStatus("DLQ").size();

        return ResponseEntity.ok(Map.of(
                "totalOrdersInDb",   total,
                "dlqOrders",         dlq,
                "connectedClients",  sseBroadcaster.getConnectedClientCount(),
                "salesSummary",      summary != null ? summary : Map.of(),
                "tierDistribution",  orderRepository.getTierDistribution()
        ));
    }

    @GetMapping("/regional")
    @Operation(summary = "Regional sales breakdown")
    public List<OrderRepository.RegionalSales> getRegionalSales() {
        return persistenceService.getRegionalSales();
    }

    @GetMapping("/dlq")
    @Operation(summary = "Orders in Dead Letter Queue — failed after all retries")
    public List<OrderDocument> getDlqOrders() {
        return persistenceService.getDlqOrders();
    }

    // ── SSE STREAM ────────────────────────────────────────────────────────────

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Server-Sent Events live order stream",
               description = "Open in browser — real-time order events pushed as they are processed from Kafka")
    public SseEmitter streamOrders() {
        log.info("New SSE client connected to order stream");
        return sseBroadcaster.register();
    }
}
