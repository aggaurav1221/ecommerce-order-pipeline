package com.gaurav.ecommerce.service;

import com.gaurav.ecommerce.dto.OrderEventRequest;
import com.gaurav.ecommerce.event.producer.OrderEventProducer;
import com.gaurav.ecommerce.exception.OrderNotFoundException;
import com.gaurav.ecommerce.model.OrderEvent;
import com.gaurav.ecommerce.repository.OrderEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

/**
 * Unit tests for OrderService.
 * Uses Mockito — no Spring context, no Kafka, no MongoDB.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService Tests")
class OrderServiceTest {

    @Mock private OrderEventProducer   orderEventProducer;
    @Mock private OrderEventRepository orderEventRepository;

    @InjectMocks
    private OrderService orderService;

    private OrderEventRequest validRequest;
    private OrderEvent        sampleOrder;

    @BeforeEach
    void setUp() {
        OrderEvent.OrderItem item = OrderEvent.OrderItem.builder()
                .productId("PROD-001")
                .productName("iPhone 15")
                .quantity(1)
                .unitPrice(BigDecimal.valueOf(79999))
                .discount(BigDecimal.ZERO)
                .build();

        validRequest = OrderEventRequest.builder()
                .customerId("CUST-001")
                .customerName("Gaurav Aggarwal")
                .customerEmail("gaurav@example.com")
                .priority(OrderEvent.OrderPriority.EXPRESS)
                .region("NORTH")
                .category("ELECTRONICS")
                .items(List.of(item))
                .totalAmount(BigDecimal.valueOf(79999))
                .currency("INR")
                .sourceSystem("WEB")
                .build();

        sampleOrder = OrderEvent.builder()
                .orderId("ORD-ABC12345")
                .customerId("CUST-001")
                .customerName("Gaurav Aggarwal")
                .status(OrderEvent.OrderStatus.PLACED)
                .priority(OrderEvent.OrderPriority.EXPRESS)
                .region("NORTH")
                .category("ELECTRONICS")
                .totalAmount(BigDecimal.valueOf(79999))
                .currency("INR")
                .build();
    }

    // ── placeOrder() ─────────────────────────────────────────────────

    @Nested
    @DisplayName("placeOrder()")
    class PlaceOrderTests {

        @Test
        @DisplayName("Should place order, publish to Kafka, and return order with PLACED status")
        void placeOrder_ValidRequest_PublishesToKafkaAndReturnsOrder() {
            // Arrange
            given(orderEventProducer.publishOrder(any(OrderEvent.class)))
                    .willReturn(CompletableFuture.completedFuture(null));

            // Act
            OrderEvent result = orderService.placeOrder(validRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getOrderId()).startsWith("ORD-");
            assertThat(result.getStatus()).isEqualTo(OrderEvent.OrderStatus.PLACED);
            assertThat(result.getPriority()).isEqualTo(OrderEvent.OrderPriority.EXPRESS);
            assertThat(result.getCustomerId()).isEqualTo("CUST-001");

            // Verify Kafka publish was called exactly once
            then(orderEventProducer).should(times(1)).publishOrder(any(OrderEvent.class));
        }

        @Test
        @DisplayName("Should generate unique orderId for each order")
        void placeOrder_MultipleOrders_GeneratesUniqueIds() {
            given(orderEventProducer.publishOrder(any()))
                    .willReturn(CompletableFuture.completedFuture(null));

            OrderEvent order1 = orderService.placeOrder(validRequest);
            OrderEvent order2 = orderService.placeOrder(validRequest);

            assertThat(order1.getOrderId()).isNotEqualTo(order2.getOrderId());
        }

        @Test
        @DisplayName("Should set sourceSystem from request")
        void placeOrder_SetsSourceSystem() {
            given(orderEventProducer.publishOrder(any()))
                    .willReturn(CompletableFuture.completedFuture(null));

            OrderEvent result = orderService.placeOrder(validRequest);

            assertThat(result.getSourceSystem()).isEqualTo("WEB");
        }

        @Test
        @DisplayName("Should default sourceSystem to API when not provided")
        void placeOrder_NullSourceSystem_DefaultsToApi() {
            validRequest.setSourceSystem(null);
            given(orderEventProducer.publishOrder(any()))
                    .willReturn(CompletableFuture.completedFuture(null));

            OrderEvent result = orderService.placeOrder(validRequest);

            assertThat(result.getSourceSystem()).isEqualTo("API");
        }
    }

    // ── getOrder() ───────────────────────────────────────────────────

    @Nested
    @DisplayName("getOrder()")
    class GetOrderTests {

        @Test
        @DisplayName("Should return order when it exists in MongoDB")
        void getOrder_ExistingOrder_ReturnsOrder() {
            given(orderEventRepository.findByOrderId("ORD-ABC12345"))
                    .willReturn(Optional.of(sampleOrder));

            OrderEvent result = orderService.getOrder("ORD-ABC12345");

            assertThat(result).isNotNull();
            assertThat(result.getOrderId()).isEqualTo("ORD-ABC12345");
            assertThat(result.getStatus()).isEqualTo(OrderEvent.OrderStatus.PLACED);
        }

        @Test
        @DisplayName("Should throw OrderNotFoundException when order does not exist")
        void getOrder_NonExistentOrder_ThrowsNotFoundException() {
            given(orderEventRepository.findByOrderId("ORD-MISSING"))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.getOrder("ORD-MISSING"))
                    .isInstanceOf(OrderNotFoundException.class)
                    .hasMessageContaining("ORD-MISSING");

            // Verify no side effects
            then(orderEventProducer).shouldHaveNoInteractions();
        }
    }

    // ── getFailedOrders() ────────────────────────────────────────────

    @Nested
    @DisplayName("getFailedOrders()")
    class FailedOrderTests {

        @Test
        @DisplayName("Should return all FAILED orders from DLQ")
        void getFailedOrders_ReturnsDlqOrders() {
            OrderEvent failedOrder = OrderEvent.builder()
                    .orderId("ORD-FAILED001")
                    .status(OrderEvent.OrderStatus.FAILED)
                    .retryCount(4)
                    .processingError("Connection timeout after 3 retries")
                    .build();

            given(orderEventRepository.findByStatusAndRetryCountGreaterThan(
                    OrderEvent.OrderStatus.FAILED, 0))
                    .willReturn(List.of(failedOrder));

            List<OrderEvent> result = orderService.getFailedOrders();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo(OrderEvent.OrderStatus.FAILED);
            assertThat(result.get(0).getRetryCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("Should return empty list when no failed orders exist")
        void getFailedOrders_NoFailures_ReturnsEmptyList() {
            given(orderEventRepository.findByStatusAndRetryCountGreaterThan(any(), anyInt()))
                    .willReturn(List.of());

            List<OrderEvent> result = orderService.getFailedOrders();

            assertThat(result).isEmpty();
        }
    }
}
