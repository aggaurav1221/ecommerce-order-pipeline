<div align="center">

<img src="https://img.shields.io/badge/Apache_Kafka-231F20?style=for-the-badge&logo=apache-kafka&logoColor=white" />
<img src="https://img.shields.io/badge/Java-17-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" />
<img src="https://img.shields.io/badge/Spring_Boot-3.2-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white" />
<img src="https://img.shields.io/badge/MongoDB-7.0-4EA94B?style=for-the-badge&logo=mongodb&logoColor=white" />
<img src="https://img.shields.io/badge/SSE-Live_Dashboard-FF6B35?style=for-the-badge" />
<img src="https://img.shields.io/badge/Docker-Ready-2CA5E0?style=for-the-badge&logo=docker&logoColor=white" />

<br/><br/>

# ⚡ E-Commerce Order Event Pipeline

### Real-Time Order Streaming with Apache Kafka
**Custom Partitioning · DLQ Retry · MongoDB Time-Series · SSE Live Dashboard**

<br/>

[![Build](https://img.shields.io/github/actions/workflow/status/aggaurav1221/ecommerce-order-pipeline/ci.yml?style=flat-square&label=CI)](https://github.com/aggaurav1221/ecommerce-order-pipeline/actions)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)

**[🔴 Live Dashboard Preview](#-live-dashboard) · [Quick Start](#-quick-start) · [Architecture](#-architecture)**

</div>

---

## 🌐 Overview

A production-grade **real-time event streaming pipeline** for e-commerce order processing — demonstrating the Kafka patterns used in enterprise systems handling millions of orders.

**What this project shows:**
- How major e-commerce platforms (Flipkart, Amazon, Meesho) process orders in real-time
- Kafka **custom partitioning** to give premium customers a dedicated fast lane
- **Non-blocking retry with DLQ** so one bad message never stalls the pipeline
- **Server-Sent Events** pushing live order data to a browser dashboard
- **MongoDB time-series** with TTL indexes for efficient event storage

> Built with the same patterns I applied at **Amdocs** (500M+ CDR records/day) and **Accenture** (Bell Canada real-time data streaming).

---

## 🏗 Architecture

```
                        ┌─────────────────────────────────┐
  REST API / Simulator  │     Order Event Producer         │
  POST /api/orders  ──► │  Key: "{TIER}:{customerId}"      │
                        │  OrderPartitioner (custom)       │
                        └──────────────┬──────────────────┘
                                       │
                    ┌──────────────────▼──────────────────────┐
                    │           orders.placed (4 partitions)   │
                    │                                          │
                    │  Partition 0  ◄── PREMIUM customers      │
                    │  Partition 1  ◄──┐                       │
                    │  Partition 2  ◄──┤ STANDARD / BASIC      │
                    │  Partition 3  ◄──┘ (hash of customerId)  │
                    └──────────────────┬──────────────────────┘
                                       │
                    ┌──────────────────▼──────────────────────┐
                    │         Order Event Consumer             │
                    │  @RetryableTopic: 4 attempts             │
                    │  Backoff: 2s → 4s → 8s (exponential)    │
                    │                          │               │
                    │  Success ──► MongoDB     │               │
                    │  Success ──► SSE Broadcast               │
                    │  Fail ──► orders.placed-dlt (DLQ)        │
                    └──────────────────────────────────────────┘
                              │                    │
              ┌───────────────▼────┐    ┌──────────▼───────────────┐
              │     MongoDB         │    │   Browser SSE Clients     │
              │  orders collection  │    │   GET /api/orders/stream  │
              │  Compound indexes   │    │   Live order dashboard    │
              │  90-day TTL         │    └──────────────────────────┘
              └────────────────────┘
```

### Custom Partitioner — The Priority Lane
```
PREMIUM:CUST-001  ──►  Partition 0  (dedicated, low latency)
STANDARD:CUST-042 ──►  hash("CUST-042") % 3 + 1  =  Partition 2
BASIC:CUST-999    ──►  hash("CUST-999") % 3 + 1  =  Partition 1
```
PREMIUM customers = ~15% of users but 60%+ of revenue → they never wait behind bulk standard orders.

### Retry Topology
```
orders.placed          (attempt 1 — original)
orders.placed-retry-0  (attempt 2 — after 2s)
orders.placed-retry-1  (attempt 3 — after 4s)
orders.placed-retry-2  (attempt 4 — after 8s)
orders.placed-dlt      (Dead Letter — manual review)
```

---

## 🖥 Live Dashboard

Open `http://localhost:8080` after startup:

- **Live order feed** — every Kafka event appears within ~100ms
- **Partition distribution** — watch PREMIUM vs STANDARD split in real-time
- **Regional breakdown** — NORTH / SOUTH / EAST / WEST / INTERNATIONAL
- **Payment methods** — UPI / CARD / NETBANKING / COD / WALLET
- **Revenue tracker** — running total and average order value

The dashboard uses **Server-Sent Events (SSE)** — a plain HTTP stream from Spring Boot to the browser. No WebSocket, no polling. Auto-reconnects on disconnect.

---

## ✨ Key Features

| Feature | Implementation | Why It Matters |
|---------|---------------|----------------|
| **Custom Partitioner** | `OrderPartitioner.java` | PREMIUM customers get dedicated Kafka partition — priority processing |
| **Non-Blocking Retry** | `@RetryableTopic` with exponential backoff | Failed messages don't stall the pipeline |
| **Dead Letter Queue** | `orders.placed-dlt` topic | Failed events preserved for ops review and manual replay |
| **SSE Live Stream** | `SseEventBroadcaster` + `EventSource` | Real-time browser updates without polling |
| **Idempotent Producer** | `enable.idempotence=true`, `acks=all` | Safe to retry — no duplicate messages |
| **Manual Ack** | `MANUAL_IMMEDIATE` ack mode | Offset only committed after successful processing |
| **Time-Series Storage** | MongoDB + compound indexes + 90-day TTL | Efficient time-range queries; auto-cleanup |
| **Order Simulator** | `OrderSimulator.java` | Generates realistic orders every 2s — see it working immediately |
| **Prometheus Metrics** | `kafka.orders.published/processed/dlq` | Production observability built in |

---

## 🚀 Quick Start

```bash
git clone https://github.com/aggaurav1221/ecommerce-order-pipeline.git
cd ecommerce-order-pipeline

docker-compose up -d
```

Wait ~45 seconds, then open:

| URL | What you'll see |
|-----|----------------|
| **http://localhost:8080** | 🔴 Live order dashboard (orders flowing in real-time!) |
| **http://localhost:8080/swagger-ui.html** | API documentation |
| **http://localhost:8081** | Kafka UI — watch messages land on partitions |
| **http://localhost:8082** | MongoDB Express — browse order documents |

The simulator starts automatically — you'll see orders flowing within seconds of startup.

---

## 📡 API Reference

### Publish an Order
```bash
# PREMIUM customer — will route to partition 0
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "orderId":       "ORD-TEST-001",
    "customerId":    "CUST-VIP-001",
    "customerTier":  "PREMIUM",
    "status":        "PLACED",
    "totalAmount":   15999.00,
    "currency":      "INR",
    "region":        "NORTH",
    "paymentMethod": "UPI",
    "sourceSystem":  "WEB",
    "eventTimestamp":"2024-01-15T10:30:00Z",
    "items": [
      { "productId": "PROD-001", "productName": "Laptop Pro",
        "category": "ELECTRONICS", "quantity": 1, "unitPrice": 15999.00 }
    ]
  }'
```

### Query Orders
```bash
# Latest orders
curl "http://localhost:8080/api/orders?size=10"

# By status
curl "http://localhost:8080/api/orders?status=PLACED"

# PREMIUM orders only
curl "http://localhost:8080/api/orders?tier=PREMIUM"

# By region
curl "http://localhost:8080/api/orders?region=NORTH"

# Sales summary
curl "http://localhost:8080/api/orders/summary"

# Regional breakdown
curl "http://localhost:8080/api/orders/regional"

# DLQ orders (failed processing)
curl "http://localhost:8080/api/orders/dlq"
```

### SSE Stream (open in browser)
```
http://localhost:8080/api/orders/stream
```
Or subscribe programmatically:
```javascript
const es = new EventSource('http://localhost:8080/api/orders/stream');
es.addEventListener('order', (e) => console.log(JSON.parse(e.data)));
```

---

## 🧪 Testing

```bash
# All tests
mvn clean verify

# Unit tests (fast, no Docker)
mvn test

# View coverage
open target/site/jacoco/index.html
```

| Test Class | What It Tests |
|-----------|---------------|
| `OrderPartitionerTest` | PREMIUM always → partition 0; STANDARD never → partition 0; consistent hashing |
| `OrderSimulatorTest` | Correct batch size; disabled mode; valid field generation |

---

## ⚙️ Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `simulator.enabled` | `true` | Auto-generate order events |
| `simulator.orders-per-batch` | `5` | Orders per scheduler tick |
| `simulator.interval-ms` | `2000` | Publish interval (ms) |
| `MONGODB_URI` | `mongodb://localhost:27017/orderdb` | MongoDB connection |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |

---

## 📁 Project Structure

```
ecommerce-order-pipeline/
├── src/main/java/com/gaurav/ecommerce/
│   ├── EcommerceOrderPipelineApplication.java
│   ├── config/
│   │   └── KafkaConfig.java          # Producer (custom partitioner) + Consumer factory
│   ├── controller/
│   │   └── OrderController.java      # REST API + SSE stream endpoint
│   ├── dto/
│   │   └── OrderEvent.java           # Kafka message payload
│   ├── event/
│   │   ├── producer/
│   │   │   ├── OrderEventProducer.java   # Async Kafka publish + Prometheus counters
│   │   │   └── OrderPartitioner.java     # PREMIUM → partition 0 routing logic
│   │   └── consumer/
│   │       └── OrderEventConsumer.java   # @RetryableTopic consumer + DLQ handler
│   ├── model/
│   │   └── OrderDocument.java        # MongoDB document + compound indexes
│   ├── repository/
│   │   └── OrderRepository.java      # Queries + MongoDB aggregation pipelines
│   ├── scheduler/
│   │   └── OrderSimulator.java       # Realistic order event generator
│   └── service/
│       ├── OrderPersistenceService.java  # MongoDB persistence + query logic
│       └── SseEventBroadcaster.java      # SSE client registry + broadcast
├── src/main/resources/
│   ├── application.yml
│   └── static/index.html             # Live dashboard (SSE + vanilla JS)
├── src/test/
│   ├── consumer/OrderPartitionerTest.java
│   └── service/OrderSimulatorTest.java
├── docker/
│   ├── Dockerfile                    # Multi-stage build
│   ├── kafka-init.sh                 # Creates 4-partition topic
│   └── mongo-init.js                 # Indexes + TTL setup
└── docker-compose.yml                # Full local stack
```

---

## 👨‍💻 Author

**Gaurav Aggarwal** — Senior Java Engineer | Kafka & Microservices Specialist

- 🏢 Consultant @ Deloitte | Previously Accenture, Amdocs
- 💼 [LinkedIn](https://www.linkedin.com/in/aggaurav1221)
- 🐙 [GitHub](https://github.com/aggaurav1221)
- 🩺 **Also see:** [FHIR Patient Management API](https://github.com/aggaurav1221/fhir-patient-api)

---

<div align="center">

**⭐ Star this repo if the Kafka patterns were useful!**

</div>
