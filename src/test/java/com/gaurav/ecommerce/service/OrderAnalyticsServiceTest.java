package com.gaurav.ecommerce.service;

import com.gaurav.ecommerce.model.OrderEvent;
import com.gaurav.ecommerce.repository.OrderEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for OrderAnalyticsService in-memory aggregation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderAnalyticsService Tests")
class OrderAnalyticsServiceTest {

    @Mock private OrderEventRepository orderEventRepository;
    @Mock private SseEmitterService    sseEmitterService;

    @InjectMocks
    private OrderAnalyticsService analyticsService;

    private OrderEvent buildOrder(OrderEvent.OrderPriority priority,
                                  String region, String category,
                                  BigDecimal amount, OrderEvent.OrderStatus status) {
        return OrderEvent.builder()
                .orderId("ORD-" + Math.random())
                .customerId("CUST-001")
                .priority(priority)
                .region(region)
                .category(category)
                .totalAmount(amount)
                .status(status)
                .build();
    }

    @Test
    @DisplayName("Should correctly count orders by priority")
    void updateAggregates_TracksPriorityCounts() {
        analyticsService.updateAggregates(
                buildOrder(OrderEvent.OrderPriority.EXPRESS, "NORTH", "ELECTRONICS",
                        BigDecimal.valueOf(5000), OrderEvent.OrderStatus.PLACED));
        analyticsService.updateAggregates(
                buildOrder(OrderEvent.OrderPriority.FLASH_SALE, "SOUTH", "FASHION",
                        BigDecimal.valueOf(2000), OrderEvent.OrderStatus.PLACED));
        analyticsService.updateAggregates(
                buildOrder(OrderEvent.OrderPriority.STANDARD, "EAST", "GROCERY",
                        BigDecimal.valueOf(500), OrderEvent.OrderStatus.PLACED));

        Map<String, Object> snapshot = analyticsService.getDashboardSnapshot();

        @SuppressWarnings("unchecked")
        Map<String, Long> priorityCounts = (Map<String, Long>) snapshot.get("priorityCounts");
        assertThat(priorityCounts.get("EXPRESS")).isEqualTo(1L);
        assertThat(priorityCounts.get("FLASH_SALE")).isEqualTo(1L);
        assertThat(priorityCounts.get("STANDARD")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should accumulate revenue correctly, excluding CANCELLED and FAILED orders")
    void updateAggregates_RevenueExcludesCancelledAndFailed() {
        analyticsService.updateAggregates(
                buildOrder(OrderEvent.OrderPriority.STANDARD, "NORTH", "ELECTRONICS",
                        BigDecimal.valueOf(10000), OrderEvent.OrderStatus.PLACED));
        analyticsService.updateAggregates(
                buildOrder(OrderEvent.OrderPriority.STANDARD, "NORTH", "ELECTRONICS",
                        BigDecimal.valueOf(5000), OrderEvent.OrderStatus.CANCELLED)); // excluded
        analyticsService.updateAggregates(
                buildOrder(OrderEvent.OrderPriority.STANDARD, "NORTH", "ELECTRONICS",
                        BigDecimal.valueOf(3000), OrderEvent.OrderStatus.FAILED));   // excluded

        Map<String, Object> snapshot = analyticsService.getDashboardSnapshot();
        BigDecimal revenue = (BigDecimal) snapshot.get("totalRevenue24h");

        assertThat(revenue).isEqualByComparingTo(BigDecimal.valueOf(10000));
    }

    @Test
    @DisplayName("Should calculate average order value correctly")
    void getDashboardSnapshot_CalculatesCorrectAvgOrderValue() {
        analyticsService.updateAggregates(
                buildOrder(OrderEvent.OrderPriority.EXPRESS, "NORTH", "ELECTRONICS",
                        BigDecimal.valueOf(6000), OrderEvent.OrderStatus.PLACED));
        analyticsService.updateAggregates(
                buildOrder(OrderEvent.OrderPriority.STANDARD, "SOUTH", "FASHION",
                        BigDecimal.valueOf(4000), OrderEvent.OrderStatus.PLACED));

        Map<String, Object> snapshot = analyticsService.getDashboardSnapshot();
        BigDecimal avg = (BigDecimal) snapshot.get("avgOrderValue");

        assertThat(avg).isEqualByComparingTo(BigDecimal.valueOf(5000));
    }

    @Test
    @DisplayName("Should return zero avg order value when no orders processed")
    void getDashboardSnapshot_ZeroAvgWhenNoOrders() {
        Map<String, Object> snapshot = analyticsService.getDashboardSnapshot();
        BigDecimal avg = (BigDecimal) snapshot.get("avgOrderValue");
        assertThat(avg).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Should track failed orders count separately")
    void updateAggregates_TracksFailedOrderCount() {
        analyticsService.updateAggregates(
                buildOrder(OrderEvent.OrderPriority.STANDARD, "NORTH", "ELECTRONICS",
                        BigDecimal.valueOf(1000), OrderEvent.OrderStatus.FAILED));
        analyticsService.updateAggregates(
                buildOrder(OrderEvent.OrderPriority.STANDARD, "SOUTH", "FASHION",
                        BigDecimal.valueOf(2000), OrderEvent.OrderStatus.FAILED));

        Map<String, Object> snapshot = analyticsService.getDashboardSnapshot();
        Long failedOrders = (Long) snapshot.get("failedOrders");

        assertThat(failedOrders).isEqualTo(2L);
    }
}
