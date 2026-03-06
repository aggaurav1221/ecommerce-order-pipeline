package com.gaurav.ecommerce.service;

import com.gaurav.ecommerce.dto.OrderEvent;
import com.gaurav.ecommerce.model.OrderDocument;
import com.gaurav.ecommerce.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Persists order events to MongoDB and provides query methods for the REST API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPersistenceService {

    private final OrderRepository orderRepository;

    // ── Persist ──────────────────────────────────────────────────────────────

    public OrderDocument persistOrder(OrderEvent event, int partition,
                                      long offset, String topic,
                                      String processingStatus) {

        // Upsert — idempotent: same orderId can be retried safely
        OrderDocument existing = orderRepository.findByOrderId(event.getOrderId())
                .orElse(null);

        OrderDocument doc = buildDocument(event, partition, offset, topic, processingStatus);

        if (existing != null) {
            doc.setId(existing.getId());
            doc.setCreatedAt(existing.getCreatedAt());
        }

        OrderDocument saved = orderRepository.save(doc);
        log.debug("Order persisted: orderId={}, processingStatus={}", saved.getOrderId(), processingStatus);
        return saved;
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    public Page<OrderDocument> getOrdersByStatus(String status, int page, int size) {
        return orderRepository.findByStatus(status,
                PageRequest.of(page, size, Sort.by("eventTimestamp").descending()));
    }

    public Page<OrderDocument> getOrdersByCustomer(String customerId, int page, int size) {
        return orderRepository.findByCustomerId(customerId,
                PageRequest.of(page, size, Sort.by("eventTimestamp").descending()));
    }

    public Page<OrderDocument> getOrdersByRegion(String region, int page, int size) {
        return orderRepository.findByRegion(region,
                PageRequest.of(page, size, Sort.by("eventTimestamp").descending()));
    }

    public Page<OrderDocument> getOrdersByTier(String tier, int page, int size) {
        return orderRepository.findByCustomerTier(tier,
                PageRequest.of(page, size, Sort.by("eventTimestamp").descending()));
    }

    public Page<OrderDocument> getOrdersByDateRange(Instant from, Instant to, int page, int size) {
        return orderRepository.findByEventTimestampBetween(from, to,
                PageRequest.of(page, size, Sort.by("eventTimestamp").descending()));
    }

    public List<OrderDocument> getDlqOrders() {
        return orderRepository.findByProcessingStatus("DLQ");
    }

    public OrderDocument.SalesSummary getSalesSummary() {
        return orderRepository.getSalesSummary();
    }

    public List<OrderRepository.RegionalSales> getRegionalSales() {
        return orderRepository.getRegionalSales();
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private OrderDocument buildDocument(OrderEvent event, int partition,
                                        long offset, String topic, String status) {
        List<OrderDocument.OrderItemDoc> items = event.getItems() == null ? List.of() :
                event.getItems().stream().map(i -> OrderDocument.OrderItemDoc.builder()
                        .productId(i.getProductId())
                        .productName(i.getProductName())
                        .category(i.getCategory())
                        .quantity(i.getQuantity())
                        .unitPrice(i.getUnitPrice())
                        .build()
                ).collect(Collectors.toList());

        return OrderDocument.builder()
                .orderId(event.getOrderId())
                .customerId(event.getCustomerId())
                .customerTier(event.getCustomerTier())
                .status(event.getStatus())
                .totalAmount(event.getTotalAmount())
                .currency(event.getCurrency())
                .region(event.getRegion())
                .warehouseId(event.getWarehouseId())
                .paymentMethod(event.getPaymentMethod())
                .sourceSystem(event.getSourceSystem())
                .items(items)
                .kafkaPartition(partition)
                .kafkaOffset(offset)
                .kafkaTopic(topic)
                .retryCount(event.getRetryCount() != null ? event.getRetryCount() : 0)
                .processingStatus(status)
                .lastError(event.getLastError())
                .eventTimestamp(event.getEventTimestamp() != null
                        ? event.getEventTimestamp() : Instant.now())
                .build();
    }
}
