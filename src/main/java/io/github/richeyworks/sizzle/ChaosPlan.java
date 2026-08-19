package io.github.richeyworks.sizzle;

import java.util.Objects;

/**
 * A deterministic fault schedule for {@link Sizzle}: given a 1-based op index, decide whether
 * that op crashes, and how long to stall before it. Every rule here is a <b>pure function of the
 * op index</b> (probability included — it hashes {@code (seed, op)} through a fresh RNG rather
 * than carrying mutable state), so a plan is immutable, reusable, and reproducible: the same
 * seed crashes at exactly the same ops every run. Chaos you cannot replay is not a test, it is a
 * flake.
 */
public final class ChaosPlan {

    /** Decides, for a 1-based op index, whether that op should crash. Must be a pure function. */
    @FunctionalInterface
    interface CrashRule {
        boolean crashesAt(long op);
    }

    private final CrashRule rule;
    private final long latencyMillis;

    private ChaosPlan(CrashRule rule, long latencyMillis) {
        this.rule = rule;
        this.latencyMillis = latencyMillis;
    }

    /** A quiet plan: nothing crashes, nothing stalls. The transparent baseline. */
    public static ChaosPlan none() {
        return new ChaosPlan(op -> false, 0);
    }

    /** Crash exactly once, on the {@code n}-th op through the sink (1-based). */
    public static ChaosPlan crashOnceAtOp(long n) {
        if (n < 1) {
            throw new IllegalArgumentException("op index is 1-based: " + n);
        }
        return new ChaosPlan(op -> op == n, 0);
    }

    /** Crash on every {@code n}-th op — a steady drumbeat of faults for the recovery path. */
    public static ChaosPlan crashEveryNthOp(long n) {
        if (n < 1) {
            throw new IllegalArgumentException("period must be >= 1: " + n);
        }
        return new ChaosPlan(op -> op % n == 0, 0);
    }

    /**
     * Crash each op independently with probability {@code p}, deterministically seeded. The
     * decision for op {@code k} is a pure function of {@code (seed, k)}, so two runs with the
     * same seed crash at exactly the same ops regardless of timing.
     */
    public static ChaosPlan crashWithProbability(long seed, double p) {
        if (p < 0.0 || p > 1.0) {
            throw new IllegalArgumentException("probability must be in [0,1]: " + p);
        }
        return new ChaosPlan(op -> uniform(seed, op) < p, 0);
    }

    /**
     * A well-distributed pseudo-random double in {@code [0,1)} from {@code (seed, op)} via a
     * splitmix64 avalanche. {@link Random} seeded with nearby values correlates on its first
     * draw — fatal here, where ops are consecutive — so we mix explicitly instead.
     */
    private static double uniform(long seed, long op) {
        long z = seed + op * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z >>> 11) * 0x1.0p-53;
    }

    /**
     * A copy of this plan that also stalls {@code millis} before every op — injected latency,
     * for exercising lag-aware consumers (a replica's catch-up bound, an observer's await).
     */
    public ChaosPlan withLatencyMillis(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("latency must be >= 0: " + millis);
        }
        return new ChaosPlan(rule, millis);
    }

    boolean crashesAt(long op) {
        return rule.crashesAt(op);
    }

    long latencyMillis() {
        return latencyMillis;
    }

    /** Combine with another plan: crash if EITHER crashes; take the larger latency. */
    public ChaosPlan and(ChaosPlan other) {
        Objects.requireNonNull(other, "other");
        CrashRule a = this.rule;
        CrashRule b = other.rule;
        return new ChaosPlan(op -> a.crashesAt(op) || b.crashesAt(op),
                Math.max(this.latencyMillis, other.latencyMillis));
    }
}
