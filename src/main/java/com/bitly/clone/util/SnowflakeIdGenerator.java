package com.bitly.clone.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Distributed unique ID generator (Twitter Snowflake variant).
 * Structure: [41 bits timestamp][10 bits node id][12 bits sequence]
 * Thread-safe via synchronized block (low contention since generation is fast).
 */
@Slf4j
@Component
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1704067200000L; // 2024-01-01T00:00:00Z
    private static final long NODE_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_NODE_ID = (1L << NODE_ID_BITS) - 1;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    private static final long NODE_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + NODE_ID_BITS;

    private final long nodeId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(@Value("${app.snowflake.node-id}") long nodeId) {
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException("Node ID must be between 0 and " + MAX_NODE_ID);
        }
        this.nodeId = nodeId;
        log.info("SnowflakeIdGenerator initialized with nodeId={}", nodeId);
    }

    public synchronized long nextId() {
        long currentTimestamp = timestamp();

        if (currentTimestamp < lastTimestamp) {
            log.error("Clock moved backwards! lastTimestamp={}, currentTimestamp={}",
                    lastTimestamp, currentTimestamp);
            throw new IllegalStateException("Clock moved backwards. Refusing to generate id.");
        }

        if (currentTimestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                log.debug("Sequence overflow within same millisecond, waiting for next tick");
                currentTimestamp = waitForNextMillis(currentTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = currentTimestamp;

        long id = (currentTimestamp << TIMESTAMP_SHIFT)
                | (nodeId << NODE_ID_SHIFT)
                | sequence;

        log.debug("Generated Snowflake ID: {}", id);
        return id;
    }

    private long waitForNextMillis(long currentTimestamp) {
        long timestamp = timestamp();
        while (timestamp <= currentTimestamp) {
            timestamp = timestamp();
        }
        return timestamp;
    }

    private long timestamp() {
        return Instant.now().toEpochMilli() - EPOCH;
    }
}
