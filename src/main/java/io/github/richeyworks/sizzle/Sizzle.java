package io.github.richeyworks.sizzle;

import io.github.richeyworks.smokehouse.SmokeHouse;
import io.github.richeyworks.twine.Twine;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sizzle — engine fourteen of the ecosystem: the chaos engine. Where the other engines each
 * prove a contract holds, Sizzle proves the contract holds <b>while the floor is on fire</b>. It
 * wraps the write seam Twine named — its {@link Twine.PutSink}/{@link Twine.DeleteSink} — and
 * injects deterministic faults into the stream of ops flowing through it: a crash at op <i>k</i>,
 * a crash every <i>n</i>th op, a seeded probabilistic crash, optional latency before each op.
 *
 * <p>The point is not to break things — anyone can break things. The point is that the
 * ecosystem's recovery contracts are <em>testable</em>: tie {@link Twine} over a Sizzle-wrapped
 * sink, crash mid-batch, and Twine's journal + idempotent replay must still land the batch
 * exactly once. Sizzle turns "crash-atomic by design" into "crash-atomic, demonstrated, at
 * every crash point."</p>
 *
 * <p><b>The crash is thrown before the op reaches the real sink</b> — the faulted op did not
 * happen yet, which is the honest model of a process that died mid-apply. The op counter still
 * advances, so a caller that retries the same op through the same Sizzle gets past a
 * {@link ChaosPlan#crashOnceAtOp once-at} fault: transient fault, then recovery, exactly as a
 * real retry loop would see it.</p>
 *
 * <p>Deterministic (the plan is a pure function of the op index), loopback-only, thread-safe
 * (the op counter is atomic — a Sizzle sink may be shared). It owns nothing it wraps: closing is
 * the caller's, the real sink's lifecycle is the caller's.</p>
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class Sizzle<K, V> {

    /**
     * The injected fault: a checked {@link IOException} so it propagates through the sink
     * contract exactly like a real I/O failure would (Twine's {@code commit} already declares
     * it), rather than an unchecked surprise the recovery path was not written to expect.
     */
    public static final class Crash extends IOException {
        private static final long serialVersionUID = 1L;

        public Crash(String message) {
            super(message);
        }
    }

    private final Twine.PutSink<K, V> realPut;
    private final Twine.DeleteSink<K> realDelete;
    private final ChaosPlan plan;

    private final AtomicLong ops = new AtomicLong();
    private final AtomicLong crashes = new AtomicLong();

    private Sizzle(Twine.PutSink<K, V> realPut, Twine.DeleteSink<K> realDelete, ChaosPlan plan) {
        this.realPut = realPut;
        this.realDelete = realDelete;
        this.plan = plan;
    }

    /** Inject {@code plan} into an explicit put/delete sink pair (the general seam). */
    public static <K, V> Sizzle<K, V> inject(Twine.PutSink<K, V> putSink,
                                             Twine.DeleteSink<K> deleteSink, ChaosPlan plan) {
        Objects.requireNonNull(putSink, "putSink");
        Objects.requireNonNull(deleteSink, "deleteSink");
        Objects.requireNonNull(plan, "plan");
        return new Sizzle<>(putSink, deleteSink, plan);
    }

    /** Inject {@code plan} straight over a store's own {@code put}/{@code delete}. */
    public static <K, V> Sizzle<K, V> over(SmokeHouse<K, V> store, ChaosPlan plan) {
        Objects.requireNonNull(store, "store");
        return inject(store::put, store::delete, plan);
    }

    /** The fault-injecting put sink — hand this to {@code Twine.over(...)} in place of the real one. */
    public Twine.PutSink<K, V> puts() {
        return this::put;
    }

    /** The fault-injecting delete sink. */
    public Twine.DeleteSink<K> deletes() {
        return this::delete;
    }

    private void put(K key, V value) throws IOException {
        gate("put " + key);
        realPut.put(key, value);
    }

    private void delete(K key) throws IOException {
        gate("delete " + key);
        realDelete.delete(key);
    }

    /** Advance the op counter, apply any latency, and crash before delegating if the plan says so. */
    private void gate(String what) throws IOException {
        long op = ops.incrementAndGet();
        long latency = plan.latencyMillis();
        if (latency > 0) {
            try {
                Thread.sleep(latency);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while injecting latency before " + what, e);
            }
        }
        if (plan.crashesAt(op)) {
            crashes.incrementAndGet();
            throw new Crash("Sizzle injected a crash at op " + op + " (" + what + ")");
        }
    }

    /** Total ops that have passed through the wrapped sinks (crashed ops included). */
    public long opsSeen() {
        return ops.get();
    }

    /** Total faults injected so far. */
    public long crashesInjected() {
        return crashes.get();
    }
}
