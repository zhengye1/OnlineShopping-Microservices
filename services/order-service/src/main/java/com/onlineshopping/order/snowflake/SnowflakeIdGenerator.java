package com.onlineshopping.order.snowflake;

/**
 * Snowflake ID generator — copied from product-service for consistency.
 * Globally unique, time-sortable, externally visible Long IDs for orders.
 */
public class SnowflakeIdGenerator {
    public static final long EPOCH = 1704067200000L;
    public static final int DATACENTER_BITS = 5;
    public static final int WORKER_BITS = 5;
    public static final int SEQUENCE_BITS = 12;

    public static final int MAX_DATACENTER = (1 << DATACENTER_BITS) - 1;
    public static final int MAX_WORKER = (1 << WORKER_BITS) - 1;
    public static final int MAX_SEQUENCE = (1 << SEQUENCE_BITS) - 1;

    public static final int WORKER_SHIFT = SEQUENCE_BITS;
    public static final int DATACENTER_SHIFT = SEQUENCE_BITS + WORKER_BITS;
    public static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS + DATACENTER_BITS;

    private final long datacenterId;
    private final long workerId;
    private long sequence = 0;
    private long lastTimestamp = -1;

    public SnowflakeIdGenerator(long dc, long worker) {
        if (!(0 <= dc && dc <= MAX_DATACENTER) || !(0 <= worker && worker <= MAX_WORKER))
            throw new RuntimeException("Invalid Data center id or worker");
        this.datacenterId = dc;
        this.workerId = worker;
    }

    public synchronized long nextId() {
        long currentTs = System.currentTimeMillis();
        if (currentTs < lastTimestamp) {
            throw new ClockMovedBackwardsException("Clock moved backward");
        }
        if (currentTs == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) currentTs = waitNextMillis(lastTimestamp);
        } else {
            sequence = 0;
        }
        lastTimestamp = currentTs;
        return ((currentTs - EPOCH) << TIMESTAMP_SHIFT) |
                (datacenterId << DATACENTER_SHIFT) |
                (workerId << WORKER_SHIFT) | sequence;
    }

    private long waitNextMillis(long lastTs) {
        long ts = System.currentTimeMillis();
        while (ts <= lastTs) ts = System.currentTimeMillis();
        return ts;
    }
}
