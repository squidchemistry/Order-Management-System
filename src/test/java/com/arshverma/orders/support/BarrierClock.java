package com.arshverma.orders.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

/**
 * A UTC clock that, while armed, makes each caller of {@link #instant()} wait until N callers have arrived.
 * OrderService reads the clock after loading the order and before flushing the update, so arming it with
 * N=2 guarantees two concurrent confirms have both read version V before either writes — the exact
 * interleaving optimistic locking exists for, reproduced on every run instead of by luck.
 */
public class BarrierClock extends Clock {

    private volatile CyclicBarrier barrier;

    public void arm(int parties) {
        barrier = new CyclicBarrier(parties);
    }

    public void disarm() {
        barrier = null;
    }

    @Override
    public Instant instant() {
        CyclicBarrier current = barrier;
        if (current != null) {
            try {
                current.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("barrier not reached", e);
            }
        }
        return Instant.now();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        BarrierClock barrierClock() {
            return new BarrierClock();
        }
    }
}
