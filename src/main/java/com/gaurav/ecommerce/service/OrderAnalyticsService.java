package com.gaurav.ecommerce.service;

import com.gaurav.ecommerce.model.OrderEvent;
import com.gaurav.ecommerce.model.OrderEvent.OrderStatus;
import com.gaurav.ecommerce.repository.OrderEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Real-time analytics aggregation service.
 *
 * Maintains in-memory counters updated on every order event — O(1) dashboard reads.
 * Periodically syncs with MongoDB for persistence and accuracy.
 *
 * Analytics provided:
 * - Live order counts by status
 * - Total revenue (last 24h, last 7d)
 * - Orders by priority distribution
 * - Orders by region heatmap
 * - Top categories by revenue
 * - Hourly order volume chart data
 * - DLQ / failed order count
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderAnalyticsService {

    private final OrderEventRepository  orderEventRepository;
    private final SseEmitterService     sseEmitterService;

    // In-memory counters — updated on every Kafka consume (O(1))
    private final Map<String, Long>       statusCounts   = new ConcurrentHashMap<>();
    private final Map<String, Long>       regionCounts   = new ConcurrentHashMap<>();
    private final Map<String, Long>       priorityCounts = new ConcurrentHashMap<>();
    private final Map<String, Long>       categoryCounts = new ConcurrentHashMap<>();
    private volatile BigDecimal           totalRevenue24h = BigDecimal.ZERO;
    private volatile long                 totalOrders24h  = 0;
    private volatile long                 failedOrders    = 0;

    /**
     * Update in-memory analytics aggregates on each new order.
     * Called synchronously by Kafka consumer after MongoDB persist.
     * O(1) — just map increments, no DB calls.
     */
    public void updateAggregates(OrderEvent order) {
        // Status count
        String status = order.getStatus() != null ? order.getStatus().name() : "UNKNOWN";
        statusCounts.merge(status, 1L, Long::sum);

        // Region count
        String region = order.getRegion() != null ? order.getRegion() : "UNKNOWN";
        regionCounts.merge(region, 1L, Long::sum);

        // Priority count
        String priority = order.getPriority() != null ? order.getPriority().name() : "STANDARD";
        priorityCounts.merge(priority, 1L, Long::sum);

        // Category count
        String category = order.getCategory() != null ? order.getCategory() : "OTHER";
        categoryCounts.merge(category, 1L, Long::sum);

        // Revenue (only count confirmed orders)
        if (order.getTotalAmount() != null &&
            order.getStatus() != OrderStatus.CANCELLED &&
            order.getStatus() != OrderStatus.FAILED) {
            synchronized (this) {
                totalRevenue24h = totalRevenue24h.add(order.getTotalAmount());
                totalOrders24h++;
            }
        }

        if (order.getStatus() == OrderStatus.FAILED) {
            failedOrders++;
        }

        log.debug("Analytics updated: status={}, region={}, priority={}, revenue={}",
                status, region, priority, order.getTotalAmount());
    }

    /**
     * Get current analytics snapshot for dashboard.
     * O(1) — reads from in-memory maps, no DB call.
     */
    public Map<String, Object> getDashboardSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("statusCounts",       new HashMap<>(statusCounts));
        snapshot.put("regionCounts",       new HashMap<>(regionCounts));
        snapshot.put("priorityCounts",     new HashMap<>(priorityCounts));
        snapshot.put("categoryCounts",     new HashMap<>(categoryCounts));
        snapshot.put("totalRevenue24h",    totalRevenue24h.setScale(2, RoundingMode.HALF_UP));
        snapshot.put("totalOrders24h",     totalOrders24h);
        snapshot.put("failedOrders",       failedOrders);
        snapshot.put("avgOrderValue",      totalOrders24h > 0
                ? totalRevenue24h.divide(BigDecimal.valueOf(totalOrders24h), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        snapshot.put("connectedClients",   sseEmitterService.getConnectedClientCount());
        snapshot.put("snapshotAt",         Instant.now().toString());
        return snapshot;
    }

    /**
     * Periodic sync with MongoDB — recalculates aggregates from DB every 5 minutes.
     * Ensures accuracy after restarts or missed events.
     */
    @Scheduled(fixedDelay = 300_000) // every 5 minutes
    public void syncWithDatabase() {
        log.info("🔄 Syncing analytics from MongoDB...");
        try {
            Instant since = Instant.now().minus(24, ChronoUnit.HOURS);

            // Sync status counts from DB
            for (OrderStatus status : OrderStatus.values()) {
                long count = orderEventRepository.countByStatus(status);
                statusCounts.put(status.name(), count);
            }

            // Sync revenue from DB (authoritative source)
            List<OrderEvent> recentOrders = orderEventRepository.findAllByDateRange(
                    since, Instant.now());

            BigDecimal dbRevenue = recentOrders.stream()
                    .filter(o -> o.getTotalAmount() != null)
                    .filter(o -> o.getStatus() != OrderStatus.CANCELLED
                              && o.getStatus() != OrderStatus.FAILED)
                    .map(OrderEvent::getTotalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            synchronized (this) {
                totalRevenue24h = dbRevenue;
                totalOrders24h  = recentOrders.size();
            }

            // Broadcast updated stats to dashboard
            sseEmitterService.broadcastStats(getDashboardSnapshot());

            log.info("✅ Analytics synced: {} orders, revenue={}", totalOrders24h, totalRevenue24h);
        } catch (Exception e) {
            log.error("Analytics sync failed: {}", e.getMessage());
        }
    }

    /**
     * Get hourly volume data for chart (from MongoDB aggregation).
     */
    public List<Map<String, Object>> getHourlyChart(int hours) {
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);
        return orderEventRepository.findHourlyStats(since).stream()
                .map(s -> Map.<String, Object>of(
                        "hour",    s.getId(),
                        "orders",  s.getCount(),
                        "revenue", s.getRevenue() != null ? s.getRevenue() : BigDecimal.ZERO
                ))
                .collect(Collectors.toList());
    }
}
