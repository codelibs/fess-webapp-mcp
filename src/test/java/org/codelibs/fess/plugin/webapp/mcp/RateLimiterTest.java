/*
 * Copyright 2012-2025 CodeLibs Project and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.codelibs.fess.plugin.webapp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RateLimiter}. Runs entirely in-process: {@link RateLimiter} never
 * touches {@code ComponentUtil}, so none of these tests need a DI container.
 */
public class RateLimiterTest {

    /** A {@link RateLimiter} whose {@code currentMinute()} is driven by a mutable field instead of the wall clock. */
    private static final class FakeClockRateLimiter extends RateLimiter {
        /** The minute {@link #currentMinute()} reports; advance it to simulate the passage of time. */
        long minute;

        FakeClockRateLimiter(final int perMinute) {
            super(perMinute);
        }

        @Override
        protected long currentMinute() {
            return minute;
        }
    }

    @Test
    public void testLimitIsEnforcedPerKey() {
        final RateLimiter limiter = new RateLimiter(2);
        assertTrue(limiter.tryAcquire("alice"));
        assertTrue(limiter.tryAcquire("alice"));
        assertFalse(limiter.tryAcquire("alice"), "third call within the window must be refused");
        assertTrue(limiter.tryAcquire("bob"), "buckets are per key");
    }

    @Test
    public void testZeroDisablesTheLimiter() {
        final RateLimiter limiter = new RateLimiter(0);
        for (int i = 0; i < 1000; i++) {
            assertTrue(limiter.tryAcquire("alice"));
        }
    }

    @Test
    public void testDisabledLimiterNeverTracksAnyKey() {
        // Resolution #4: "When disabled, the limiter must not allocate or track anything."
        // A per-key ConcurrentHashMap entry is the only thing there is to allocate here, so
        // this is checked directly rather than inferred from tryAcquire's return value alone.
        final RateLimiter limiter = new RateLimiter(0);
        for (int i = 0; i < 50; i++) {
            limiter.tryAcquire("key-" + i);
        }
        assertEquals(0, limiter.buckets.size(), "a disabled limiter must not populate its bucket map");
    }

    @Test
    public void testWindowResetsOnNextMinute() {
        final FakeClockRateLimiter limiter = new FakeClockRateLimiter(1);
        limiter.minute = 100L;
        assertTrue(limiter.tryAcquire("alice"));
        assertFalse(limiter.tryAcquire("alice"), "still inside the same window");
        limiter.minute = 101L;
        assertTrue(limiter.tryAcquire("alice"), "a new window must reset the count");
    }

    @Test
    public void testGetRetryAfterSecondsReturnsTheWindowWidth() {
        final RateLimiter limiter = new RateLimiter(1);
        assertEquals(60, limiter.getRetryAfterSeconds(), "the fixed window is one minute wide");
    }

    @Test
    public void testStaleEntriesAreSweptOnceTrackedKeysExceedTheBound() {
        final FakeClockRateLimiter limiter = new FakeClockRateLimiter(1);
        limiter.minute = 0L;
        for (int i = 0; i < RateLimiter.MAX_TRACKED_KEYS; i++) {
            limiter.tryAcquire("key-" + i);
        }
        assertEquals(RateLimiter.MAX_TRACKED_KEYS, limiter.buckets.size(), "every distinct key up to the bound must be tracked");

        limiter.minute = 1L; // every existing entry is now from a stale window
        assertTrue(limiter.tryAcquire("new-key"), "crossing the bound must not itself refuse the request");

        assertEquals(1, limiter.buckets.size(), "the sweep triggered by exceeding the bound must drop every stale-window entry");
        assertTrue(limiter.buckets.containsKey("new-key"));
    }
}
