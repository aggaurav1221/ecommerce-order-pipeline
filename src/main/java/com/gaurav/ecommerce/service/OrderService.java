package com.gaurav.ecommerce.service;

import com.gaurav.ecommerce.dto.OrderEventRequest;
import com.gaurav.ecommerce.event.producer.OrderEventProducer;
import com.gaurav.ecommerce.exception.OrderNotFoundException;
import com.gaurav.ecommerce.model.OrderEvent;
import com.gaurav.ecommerce.repository.OrderEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Core order service — orchestrates order creation and querying.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderEventProducer   orderEventProducer;
    private final OrderEventRepository orderEventRepository;

    /**
     * Create and publish a new order event.
     * Flow: validate → build OrderEvent → publish to Kafka → return (async persist)
     */
    public OrderEvent placeOrder(OrderEventRequest request) {
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        OrderEvent order = OrderEvent.builder()
                .orderId(orderId)
                .customerId(request.getCustomerId())
                .customerName(request.getCustomerName())
                .customerEmail(request.getCustomerEmail())
                .status(OrderEvent.OrderStatus.PLACED)
                .priority(request.getPriority())
                .region(request.getRegion())
                .category(request.getCategory())
                .items(request.getItems())
                .totalAmount(request.getTotalAmount())
                .currency(request.getCurrency())
                .sourceSystem(request.getSourceSystem() != null ? request.getSourceSystem() : "API")
                .retryCount(0)
                .build();

        log.info("Placing order: orderId={}, priority={}, region={}, amount={}",
                orderId, order.getPriority(), order.getRegion(), order.getTotalAmount());

        orderEventProducer.publishOrder(order);
        return order;
    }

    /** GET /orders/{orderId} */
    public OrderEvent getOrder(String orderId) {
        return orderEventRepository.findByOrderId(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    /** GET /orders?status=PLACED&page=0&size=20 */
    public Page<OrderEvent> getOrdersByStatus(String status, int page, int size) {
        OrderEvent.OrderStatus orderStatus = OrderEvent.OrderStatus.valueOf(status.toUpperCase());
        return orderEventRepository.findByStatus(
                orderStatus, PageRequest.of(page, size, Sort.by("createdAt").descending()));
    }

    /** GET /orders?customerId=C001 */
    public Page<OrderEvent> getOrdersByCustomer(String customerId, int page, int size) {
        return orderEventRepository.findByCustomerId(
                customerId, PageRequest.of(page, size, Sort.by("createdAt").descending()));
    }

    /** GET /orders?region=NORTH&priority=EXPRESS */
    public Page<OrderEvent> getOrdersByRegionAndPriority(String region, String priority,
                                                          int page, int size) {
        OrderEvent.OrderPriority p = OrderEvent.OrderPriority.valueOf(priority.toUpperCase());
        return orderEventRepository.findByRegionAndPriority(
                region.toUpperCase(), p,
                PageRequest.of(page, size, Sort.by("createdAt").descending()));
    }

    /** GET /orders?from=2024-01-01T00:00:00Z&to=2024-01-02T00:00:00Z */
    public Page<OrderEvent> getOrdersByDateRange(Instant from, Instant to, int page, int size) {
        return orderEventRepository.findByDateRange(
                from, to, PageRequest.of(page, size, Sort.by("createdAt").descending()));
    }

    /** GET /orders/failed — DLQ monitoring */
    public List<OrderEvent> getFailedOrders() {
        return orderEventRepository.findByStatusAndRetryCountGreaterThan(
                OrderEvent.OrderStatus.FAILED, 0);
    }
}
