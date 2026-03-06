package com.gaurav.ecommerce.repository;

import com.gaurav.ecommerce.model.OrderDocument;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
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

@Repository
public interface OrderRepository extends MongoRepository<OrderDocument, String> {

    Optional<OrderDocument> findByOrderId(String orderId);

    Page<OrderDocument> findByStatus(String status, Pageable pageable);

    Page<OrderDocument> findByCustomerId(String customerId, Pageable pageable);

    Page<OrderDocument> findByRegion(String region, Pageable pageable);

    Page<OrderDocument> findByCustomerTier(String tier, Pageable pageable);

    Page<OrderDocument> findByEventTimestampBetween(Instant from, Instant to, Pageable pageable);

    List<OrderDocument> findByProcessingStatus(String processingStatus);

    // Total orders and revenue by status
    @Query("{ 'status': ?0, 'processingStatus': 'SUCCESS' }")
    long countByStatusAndSuccess(String status);

    // Regional sales aggregation
    @Aggregation(pipeline = {
        "{ $match: { 'processingStatus': 'SUCCESS' } }",
        "{ $group: { _id: '$region', totalOrders: { $sum: 1 }, totalRevenue: { $sum: '$totalAmount' } } }",
        "{ $sort: { totalRevenue: -1 } }"
    })
    List<RegionalSales> getRegionalSales();

    // Overall sales summary
    @Aggregation(pipeline = {
        "{ $match: { 'processingStatus': 'SUCCESS' } }",
        "{ $group: { _id: null, totalOrders: { $sum: 1 }, totalRevenue: { $sum: '$totalAmount' }, " +
        "avgOrderValue: { $avg: '$totalAmount' } } }"
    })
    OrderDocument.SalesSummary getSalesSummary();

    // Tier distribution
    @Aggregation(pipeline = {
        "{ $group: { _id: '$customerTier', count: { $sum: 1 }, revenue: { $sum: '$totalAmount' } } }",
        "{ $sort: { revenue: -1 } }"
    })
    List<TierStats> getTierDistribution();

    // ── Projection interfaces ─────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    class RegionalSales {
        private String     id;           // region name
        private long       totalOrders;
        private BigDecimal totalRevenue;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    class TierStats {
        private String     id;           // tier name
        private long       count;
        private BigDecimal revenue;
    }
}
