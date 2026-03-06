package com.gaurav.ecommerce.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Order event published to Kafka topic: orders.placed
 *
 * Custom partitioning strategy:
 *   - PREMIUM customers  → partition 0 (dedicated, low-latency processing)
 *   - STANDARD customers → partitions 1-N (hash of customerId)
 *
 * This mirrors a real pattern: VIP / high-value customers get priority lanes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEvent {

    @NotBlank
    private String orderId;

    @NotBlank
    private String customerId;

    @NotBlank
    private String customerTier;       // PREMIUM | STANDARD | BASIC

    @NotBlank
    private String status;             // PLACED | CONFIRMED | SHIPPED | DELIVERED | CANCELLED

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal totalAmount;

    @NotBlank
    private String currency;           // INR | USD | GBP

    @NotBlank
    private String region;             // NORTH | SOUTH | EAST | WEST | INTERNATIONAL

    private List<OrderItem> items;

    private String paymentMethod;      // UPI | CARD | NETBANKING | COD | WALLET

    private String warehouseId;        // Fulfilment centre

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant eventTimestamp;

    private String sourceSystem;       // WEB | MOBILE_APP | PARTNER_API

    // Retry metadata — populated by DLQ consumer
    private Integer retryCount;
    private String  lastError;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderItem {
        private String     productId;
        private String     productName;
        private String     category;   // ELECTRONICS | FASHION | GROCERY | HOME
        private Integer    quantity;
        private BigDecimal unitPrice;
    }
}
