package com.gaurav.ecommerce.scheduler;

import com.gaurav.ecommerce.dto.OrderEvent;
import com.gaurav.ecommerce.event.producer.OrderEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Order Event Simulator — generates realistic e-commerce order events.
 *
 * Publishes a configurable number of orders per second to Kafka.
 * Simulates a realistic distribution:
 *   - 15% PREMIUM customers (high-value, priority lane)
 *   - 55% STANDARD customers
 *   - 30% BASIC customers
 *
 * Run the simulator: set simulator.enabled=true in application.yml
 * or pass --simulator.enabled=true at startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSimulator {

    private final OrderEventProducer producer;

    @Value("${simulator.enabled:true}")
    private boolean enabled;

    @Value("${simulator.orders-per-batch:5}")
    private int ordersPerBatch;

    private static final Random RNG = new Random();

    private static final String[] TIERS    = {"PREMIUM", "STANDARD", "STANDARD",
                                               "STANDARD", "BASIC", "BASIC"};
    private static final String[] STATUSES = {"PLACED", "CONFIRMED", "SHIPPED",
                                               "DELIVERED", "CANCELLED"};
    private static final String[] REGIONS  = {"NORTH", "SOUTH", "EAST",
                                               "WEST", "INTERNATIONAL"};
    private static final String[] PAYMENTS = {"UPI", "CARD", "NETBANKING", "COD", "WALLET"};
    private static final String[] SOURCES  = {"WEB", "MOBILE_APP", "PARTNER_API"};
    private static final String[] WAREHOUSES = {"WH-DELHI-01", "WH-MUMBAI-01",
                                                 "WH-BANGALORE-01", "WH-HYDERABAD-01"};
    private static final String[] CATEGORIES = {"ELECTRONICS", "FASHION",
                                                 "GROCERY", "HOME", "BOOKS"};
    private static final String[] PRODUCTS   = {
        "Smartphone X12", "Wireless Earbuds", "Laptop Pro",
        "Running Shoes", "Formal Shirt", "Winter Jacket",
        "Rice 5kg", "Olive Oil", "Protein Powder",
        "Office Chair", "Study Lamp", "Air Purifier",
        "Java Programming Book", "Headphones", "Smart Watch"
    };

    /** Publish a batch of orders every 2 seconds */
    @Scheduled(fixedDelayString = "${simulator.interval-ms:2000}")
    public void simulate() {
        if (!enabled) return;

        for (int i = 0; i < ordersPerBatch; i++) {
            OrderEvent event = generateOrder();
            producer.publishOrder(event);
        }
    }

    /** Publish a burst of 20 orders on startup to populate the dashboard */
    @EventListener(ApplicationReadyEvent.class)
    public void publishStartupBurst() {
        if (!enabled) return;
        log.info("OrderSimulator: Publishing startup burst of 20 orders...");
        for (int i = 0; i < 20; i++) {
            producer.publishOrder(generateOrder());
        }
    }

    // ── Order Generator ───────────────────────────────────────────────────────

    private OrderEvent generateOrder() {
        String tier       = TIERS[RNG.nextInt(TIERS.length)];
        String customerId = "CUST-" + String.format("%04d", RNG.nextInt(1000));
        int    itemCount  = 1 + RNG.nextInt(4);

        List<OrderEvent.OrderItem> items = generateItems(itemCount);
        BigDecimal total = items.stream()
                .map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // PREMIUM customers tend to have higher order values
        if ("PREMIUM".equals(tier)) {
            total = total.multiply(BigDecimal.valueOf(1.5 + RNG.nextDouble()));
        }

        return OrderEvent.builder()
                .orderId("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .customerId(customerId)
                .customerTier(tier)
                .status(STATUSES[RNG.nextInt(STATUSES.length)])
                .totalAmount(total.setScale(2, RoundingMode.HALF_UP))
                .currency("INR")
                .region(REGIONS[RNG.nextInt(REGIONS.length)])
                .items(items)
                .paymentMethod(PAYMENTS[RNG.nextInt(PAYMENTS.length)])
                .warehouseId(WAREHOUSES[RNG.nextInt(WAREHOUSES.length)])
                .sourceSystem(SOURCES[RNG.nextInt(SOURCES.length)])
                .eventTimestamp(Instant.now())
                .build();
    }

    private List<OrderEvent.OrderItem> generateItems(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> {
            double price = 99 + ThreadLocalRandom.current().nextDouble(1, 50000);
            return OrderEvent.OrderItem.builder()
                    .productId("PROD-" + String.format("%04d", RNG.nextInt(500)))
                    .productName(PRODUCTS[RNG.nextInt(PRODUCTS.length)])
                    .category(CATEGORIES[RNG.nextInt(CATEGORIES.length)])
                    .quantity(1 + RNG.nextInt(3))
                    .unitPrice(BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP))
                    .build();
        }).collect(java.util.stream.Collectors.toList());
    }
}
