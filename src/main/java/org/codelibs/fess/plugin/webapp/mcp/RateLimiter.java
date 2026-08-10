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

import java.util.concurrent.ConcurrentHashMap;

/**
 * A fixed-window, per-key request-rate limiter.
 *
 * <p>
 * Each key gets its own one-minute window: the first call for a key in a given minute opens
 * the window at count 1, subsequent calls within that same minute increment it, and a call
 * that would push the count past the configured limit is refused. The window resets the
 * instant {@link #currentMinute()} reports a different value -- there is no sliding or
 * leaky-bucket smoothing, which is a deliberate simplification (YAGNI): a caller can burst up
 * to the full limit again right at the boundary between two windows, but that is an accepted
 * trade-off for a counter this simple.
 * </p>
 *
 * <p>
 * <b>Unbounded growth:</b> {@link #buckets} would otherwise grow by one entry per distinct key
 * ever seen (e.g. every client IP on a public endpoint) for as long as the process runs. Once
 * the map holds more than {@link #MAX_TRACKED_KEYS} entries, {@link #tryAcquire(String)} sweeps
 * every entry whose window is not the current one before returning. This keeps steady-state
 * size bounded by however many distinct keys are active within a single one-minute window, at
 * the cost of an O(n) scan on the calls that cross the threshold. This is not an LRU cache and
 * does not need to be one: a simple stale-window sweep is enough for a moving one-minute
 * window, where "not current" and "not recently used" mean the same thing.
 * </p>
 */
public class RateLimiter {

    /**
     * The bucket-count threshold that triggers a stale-window sweep in {@link #tryAcquire(String)}.
     * Chosen as a round number comfortably above the distinct-client-IP count a single Fess
     * instance would plausibly see active within one minute; package-private so
     * {@code RateLimiterTest} can size its sweep test against the real value instead of a
     * duplicated literal.
     */
    static final int MAX_TRACKED_KEYS = 10_000;

    /** Width of the fixed window in seconds; also the value {@link #getRetryAfterSeconds()} reports. */
    private static final int WINDOW_SECONDS = 60;

    /** Requests allowed per key per window. {@code <= 0} disables the limiter entirely. */
    private final int perMinute;

    /**
     * Per-key state: {@code [windowStartEpochMinute, count]}. Package-private so
     * {@code RateLimiterTest} can assert on its size directly instead of needing a
     * test-only accessor on the production class.
     */
    final ConcurrentHashMap<String, long[]> buckets = new ConcurrentHashMap<>();

    /**
     * Creates a rate limiter.
     *
     * @param perMinute the number of calls a single key may make per one-minute window;
     *            {@code 0} or negative disables the limiter, and {@link #tryAcquire(String)}
     *            then always returns {@code true} without allocating or tracking anything
     */
    public RateLimiter(final int perMinute) {
        this.perMinute = perMinute;
    }

    /**
     * Consumes one call for {@code key} against its current window, opening a new window first
     * if the current one has moved on since {@code key} was last seen.
     *
     * @param key the caller identity to rate-limit; the authenticated subject when there is
     *            one, the client IP otherwise
     * @return {@code true} when the call is within the limit (or the limiter is disabled);
     *         {@code false} when {@code key} has already used up its window
     */
    public boolean tryAcquire(final String key) {
        if (perMinute <= 0) {
            return true;
        }
        final long minute = currentMinute();
        final long[] bucket = buckets.compute(key, (k, existing) -> {
            if (existing == null || existing[0] != minute) {
                return new long[] { minute, 1L };
            }
            existing[1]++;
            return existing;
        });
        if (buckets.size() > MAX_TRACKED_KEYS) {
            buckets.entrySet().removeIf(e -> e.getValue()[0] != minute);
        }
        return bucket[1] <= perMinute;
    }

    /**
     * Returns the current fixed window, as a count of whole minutes since the epoch.
     * <p>
     * A {@code protected} seam so tests can drive the window deterministically (advance it by
     * assignment) instead of sleeping past a real minute boundary.
     * </p>
     *
     * @return the current minute, i.e. {@code System.currentTimeMillis() / 60_000}
     */
    protected long currentMinute() {
        return System.currentTimeMillis() / 60_000L;
    }

    /**
     * Returns how long a caller who was just refused should wait before retrying.
     * <p>
     * This fixed-window limiter tracks whole minutes, not the sub-minute offset within the
     * current window, so it cannot report the caller's exact remaining wait -- that could be
     * anywhere from just under a second to just under a minute depending on when in the window
     * the refused call landed. Reporting the full window width is therefore the simplest value
     * that is always a safe (if sometimes conservative) upper bound.
     * </p>
     *
     * @return the retry delay in seconds, i.e. the window width
     */
    public int getRetryAfterSeconds() {
        return WINDOW_SECONDS;
    }
}
