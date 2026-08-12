package com.sora.sora_agent.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RateLimiter} 纯单元测试。
 */
class RateLimiterTest {

    @Test
    void allowsWithinLimit() {
        RateLimiter rl = new RateLimiter();
        assertTrue(rl.tryAcquire("k", 3, 60_000));
        assertTrue(rl.tryAcquire("k", 3, 60_000));
        assertTrue(rl.tryAcquire("k", 3, 60_000));
    }

    @Test
    void rejectsBeyondLimit() {
        RateLimiter rl = new RateLimiter();
        assertTrue(rl.tryAcquire("k", 2, 60_000));
        assertTrue(rl.tryAcquire("k", 2, 60_000));
        assertFalse(rl.tryAcquire("k", 2, 60_000));
    }

    @Test
    void keysAreIndependent() {
        RateLimiter rl = new RateLimiter();
        rl.tryAcquire("a", 1, 60_000);
        assertTrue(rl.tryAcquire("b", 1, 60_000));
        assertFalse(rl.tryAcquire("a", 1, 60_000));
    }

    @Test
    void windowExpires() throws InterruptedException {
        RateLimiter rl = new RateLimiter();
        rl.tryAcquire("k", 1, 50);
        Thread.sleep(80);
        assertTrue(rl.tryAcquire("k", 1, 50));
    }

    @Test
    void clearResetsAll() {
        RateLimiter rl = new RateLimiter();
        rl.tryAcquire("k", 1, 60_000);
        rl.clear();
        assertTrue(rl.tryAcquire("k", 1, 60_000));
    }
}
