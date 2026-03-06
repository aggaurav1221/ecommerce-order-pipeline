#!/bin/bash
# Create orders.placed topic with 4 partitions
# Partition 0 = PREMIUM lane, Partitions 1-3 = STANDARD/BASIC

kafka-topics --bootstrap-server localhost:9092 --create \
  --topic orders.placed \
  --partitions 4 \
  --replication-factor 1 \
  --if-not-exists

kafka-topics --bootstrap-server localhost:9092 --create \
  --topic orders.placed-dlt \
  --partitions 1 \
  --replication-factor 1 \
  --if-not-exists

echo "✅ Kafka topics created"
kafka-topics --bootstrap-server localhost:9092 --list
