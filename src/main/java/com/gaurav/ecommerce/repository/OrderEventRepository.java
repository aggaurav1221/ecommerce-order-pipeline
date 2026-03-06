package com.gaurav.ecommerce.repository;

import com.gaurav.ecommerce.model.OrderEvent;
import com.gaurav.ecommerce.model.OrderEvent.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB repository for order events — time-series optimised queries.
 *
 * Compound indexes cover all query patterns:
 *  - status + createdAt → dashboard status filters
 *  - customerId + createdAt → customer order history
 *  - region + priority + createdAt → fulfilment routing analytics
 *  - category + totalAmount → category revenue analytics
 */
@Repository
public interface OrderEventRepository extends MongoRepository<OrderEvent, String> {

    Optional<OrderEvent> findByOrderId(String orderId);

    boolean existsByOrderId(String orderId);

    // ── Status queries ─────────────────────────────────────────────
    Page<OrderEvent> findByStatus(OrderStatus status, Pageable pageable);

    List<OrderEvent> findByStatusOrderByCreatedAtDesc(OrderStatus status);

    long countByStatus(OrderStatus status);

    // ── Customer queries ───────────────────────────────────────────
    Page<OrderEvent> findByCustomerId(String customerId, Pageable pageable);

    List<OrderEvent> findTop10ByCustomerIdOrderByCreatedAtDesc(String customerId);

    // ── Time-range queries (core time-series pattern) ──────────────
    @Query("{ 'createdAt': { $gte: ?0, $lte: ?1 } }")
    Page<OrderEvent> findByDateRange(Instant from, Instant to, Pageable pageable);

    @Query("{ 'status': ?0, 'createdAt': { $gte: ?1, $lte: ?2 } }")
    List<OrderEvent> findByStatusAndDateRange(OrderStatus status, Instant from, Instant to);

    // ── Priority + Region ──────────────────────────────────────────
    Page<OrderEvent> findByRegionAndPriority(String region,
                                              OrderEvent.OrderPriority priority,
                                              Pageable pageable);

    // ── Revenue analytics ──────────────────────────────────────────
    @Query("{ 'createdAt': { $gte: ?0, $lte: ?1 } }")
    List<OrderEvent> findAllByDateRange(Instant from, Instant to);

    // ── Failed / DLQ orders ────────────────────────────────────────
    List<OrderEvent> findByStatusAndRetryCountGreaterThan(OrderStatus status, int retryCount);

    // ── Aggregation: top categories by revenue ─────────────────────
    @Aggregation(pipeline = {
        "{ $match: { 'createdAt': { $gte: ?0 } } }",
        "{ $group: { _id: '$category', totalRevenue: { $sum: '$totalAmount' }, orderCount: { $sum: 1 } } }",
        "{ $sort: { totalRevenue: -1 } }",
        "{ $limit: 5 }"
    })
    List<CategoryRevenue> findTopCategoriesByRevenue(Instant since);

    // ── Aggregation: hourly order count for chart ──────────────────
    @Aggregation(pipeline = {
        "{ $match: { 'createdAt': { $gte: ?0 } } }",
        "{ $group: { _id: { $dateToString: { format: '%Y-%m-%dT%H:00:00Z', date: '$createdAt' } }, count: { $sum: 1 }, revenue: { $sum: '$totalAmount' } } }",
        "{ $sort: { _id: 1 } }"
    })
    List<HourlyStats> findHourlyStats(Instant since);

    // ── Projection interfaces for aggregation results ──────────────
    interface CategoryRevenue {
        String getId();
        BigDecimal getTotalRevenue();
        Long getOrderCount();
    }

    interface HourlyStats {
        String getId();       // hour bucket e.g. "2024-01-15T10:00:00Z"
        Long getCount();
        BigDecimal getRevenue();
    }
}
