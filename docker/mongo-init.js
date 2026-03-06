db = db.getSiblingDB('orderdb');
db.createCollection('orders');
db.orders.createIndex({ orderId: 1 },                               { unique: true });
db.orders.createIndex({ status: 1, eventTimestamp: -1 });
db.orders.createIndex({ region: 1, eventTimestamp: -1 });
db.orders.createIndex({ customerId: 1, eventTimestamp: -1 });
db.orders.createIndex({ eventTimestamp: 1 },                        { expireAfterSeconds: 7776000 }); // 90-day TTL
db.orders.createIndex({ processingStatus: 1 });
print('✅ orderdb indexes created with 90-day TTL on eventTimestamp');
