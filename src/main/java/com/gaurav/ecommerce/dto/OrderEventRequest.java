package com.gaurav.ecommerce.dto;

import com.gaurav.ecommerce.model.OrderEvent;
import com.gaurav.ecommerce.model.OrderEvent.OrderItem;
import com.gaurav.ecommerce.model.OrderEvent.OrderPriority;
import com.gaurav.ecommerce.model.OrderEvent.OrderStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO for incoming order event requests.
 * Validated before publishing to Kafka.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEventRequest {

    @NotBlank(message = "customerId is required")
    private String customerId;

    @NotBlank(message = "customerName is required")
    private String customerName;

    private String customerEmail;

    @NotNull(message = "priority is required")
    private OrderPriority priority;

    @NotBlank(message = "region is required")
    private String region;

    @NotBlank(message = "category is required")
    private String category;

    @NotEmpty(message = "At least one item required")
    private List<OrderItem> items;

    @NotNull
    @DecimalMin(value = "0.01", message = "totalAmount must be positive")
    private BigDecimal totalAmount;

    @Builder.Default
    private String currency = "INR";

    private String sourceSystem;
}

