package com.gaurav.ecommerce.service;

import com.gaurav.ecommerce.dto.OrderEvent;
import com.gaurav.ecommerce.event.producer.OrderEventProducer;
import com.gaurav.ecommerce.scheduler.OrderSimulator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderSimulator Tests")
class OrderSimulatorTest {

    @Mock  private OrderEventProducer producer;
    @InjectMocks private OrderSimulator simulator;

    @Test
    @DisplayName("Simulator publishes correct batch size on each tick")
    void simulate_PublishesCorrectBatchSize() {
        ReflectionTestUtils.setField(simulator, "enabled",        true);
        ReflectionTestUtils.setField(simulator, "ordersPerBatch", 5);

        simulator.simulate();

        verify(producer, times(5)).publishOrder(any(OrderEvent.class));
    }

    @Test
    @DisplayName("Simulator does nothing when disabled")
    void simulate_DoesNothingWhenDisabled() {
        ReflectionTestUtils.setField(simulator, "enabled", false);

        simulator.simulate();

        verifyNoInteractions(producer);
    }

    @Test
    @DisplayName("Generated orders always have required fields non-null")
    void simulate_GeneratesValidOrders() {
        ReflectionTestUtils.setField(simulator, "enabled",        true);
        ReflectionTestUtils.setField(simulator, "ordersPerBatch", 10);

        ArgumentCaptor<OrderEvent> captor = ArgumentCaptor.forClass(OrderEvent.class);
        simulator.simulate();
        verify(producer, times(10)).publishOrder(captor.capture());

        List<OrderEvent> orders = captor.getAllValues();
        orders.forEach(order -> {
            assertThat(order.getOrderId()).isNotBlank();
            assertThat(order.getCustomerId()).isNotBlank();
            assertThat(order.getCustomerTier()).isIn("PREMIUM", "STANDARD", "BASIC");
            assertThat(order.getStatus()).isNotBlank();
            assertThat(order.getTotalAmount()).isPositive();
            assertThat(order.getCurrency()).isEqualTo("INR");
            assertThat(order.getRegion()).isNotBlank();
            assertThat(order.getItems()).isNotEmpty();
            assertThat(order.getEventTimestamp()).isNotNull();
        });
    }

    @Test
    @DisplayName("Startup burst publishes 20 orders")
    void startupBurst_Publishes20Orders() {
        ReflectionTestUtils.setField(simulator, "enabled", true);

        simulator.publishStartupBurst();

        verify(producer, times(20)).publishOrder(any(OrderEvent.class));
    }
}
