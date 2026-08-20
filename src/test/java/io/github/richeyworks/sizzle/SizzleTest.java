package io.github.richeyworks.sizzle;

import io.github.richeyworks.smokehouse.SmokeHouse;
import io.github.richeyworks.smokehouse.SmokeHouseOptions;
import io.github.richeyworks.superbeefsort.external.SpillSerializer;
import io.github.richeyworks.twine.Twine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chaos against the recovery contract. The headline is exactly-once-under-crash: tie Twine over
 * a Sizzle-wrapped sink, crash the batch at every possible op index in turn, and after Twine's
 * journal replays, the store must equal the batch's net effect — no op lost, none applied twice.
 * If Sizzle can crash at any point and the answer never changes, "crash-atomic by design" has
 * become "crash-atomic, demonstrated."
 */
class SizzleTest {

    private static SmokeHouseOptions<Long, String> opts() {
        return SmokeHouseOptions.of(SpillSerializer.forLongs(), SpillSerializer.forStrings())
                .indexTier(SmokeHouseOptions.IndexTier.STATIC);
    }

    /** One op in a batch: a put (value != null) or a delete (value == null). */
    private record Op(long key, String value) { }

    private static final List<Op> BATCH = List.of(
            new Op(0, "a"), new Op(1, "b"), new Op(2, "c"), new Op(3, "d"),
            new Op(1, null),            // delete 1
            new Op(4, "e"), new Op(5, "f"),
            new Op(3, null));           // delete 3  → net keys {0,2,4,5}

    private static TreeMap<Long, String> netEffect() {
        TreeMap<Long, String> m = new TreeMap<>();
        for (Op op : BATCH) {
            if (op.value() == null) {
                m.remove(op.key());
            } else {
                m.put(op.key(), op.value());
            }
        }
        return m;
    }

    private static void stage(Twine<Long, String>.Batch batch) {
        for (Op op : BATCH) {
            if (op.value() == null) {
                batch.delete(op.key());
            } else {
                batch.put(op.key(), op.value());
            }
        }
    }

    private static TreeMap<Long, String> scan(SmokeHouse<Long, String> store) throws IOException {
        TreeMap<Long, String> out = new TreeMap<>();
        if (store.size() > 0) {
            store.range(store.firstKey(), store.lastKey(), out::put);
        }
        return out;
    }

    @Test
    void crashMidBatchStillLandsExactlyOnceAtEveryCrashPoint(@TempDir Path base) throws IOException {
        TreeMap<Long, String> expected = netEffect();

        for (int crashPoint = 1; crashPoint <= BATCH.size(); crashPoint++) {
            Path storeDir = base.resolve("store-" + crashPoint);
            Path journalDir = base.resolve("journal-" + crashPoint);

            // Crash the batch at op `crashPoint`, mid-apply.
            try (SmokeHouse<Long, String> store = SmokeHouse.open(storeDir, opts())) {
                Sizzle<Long, String> chaos = Sizzle.over(store, ChaosPlan.crashOnceAtOp(crashPoint));
                Twine<Long, String> twine = Twine.over(chaos.puts(), chaos.deletes(), journalDir,
                        SpillSerializer.forLongs(), SpillSerializer.forStrings());
                Twine<Long, String>.Batch batch = twine.batch();
                stage(batch);
                assertThrows(Sizzle.Crash.class, batch::commit,
                        "the injected crash must surface out of commit");
                assertEquals(1, chaos.crashesInjected(), "exactly one fault at crash point " + crashPoint);
            }

            // Reopen (the process 'restarts'): Twine replays the committed journal on construction.
            try (SmokeHouse<Long, String> revived = SmokeHouse.open(storeDir, opts())) {
                Twine.over(revived::put, revived::delete, journalDir,
                        SpillSerializer.forLongs(), SpillSerializer.forStrings());   // replay
                assertEquals(expected, scan(revived),
                        "after crash at op " + crashPoint + ", the batch landed exactly once");
            }
        }
    }

    @Test
    void aQuietBatchCommitsAndReplayIsANoop(@TempDir Path base) throws IOException {
        Path storeDir = base.resolve("store");
        Path journalDir = base.resolve("journal");
        TreeMap<Long, String> expected = netEffect();

        try (SmokeHouse<Long, String> store = SmokeHouse.open(storeDir, opts())) {
            Sizzle<Long, String> chaos = Sizzle.over(store, ChaosPlan.none());
            Twine<Long, String> twine = Twine.over(chaos.puts(), chaos.deletes(), journalDir,
                    SpillSerializer.forLongs(), SpillSerializer.forStrings());
            Twine<Long, String>.Batch batch = twine.batch();
            stage(batch);
            batch.commit();

            assertEquals(BATCH.size(), chaos.opsSeen(), "every op passed through the quiet sink");
            assertEquals(0, chaos.crashesInjected(), "none plan injects nothing");
            assertEquals(expected, scan(store), "the batch's net effect is present");
        }
        // Reopen: journal was deleted after a clean commit, so replay finds nothing to do.
        try (SmokeHouse<Long, String> revived = SmokeHouse.open(storeDir, opts())) {
            Twine.over(revived::put, revived::delete, journalDir,
                    SpillSerializer.forLongs(), SpillSerializer.forStrings());
            assertEquals(expected, scan(revived), "a clean commit leaves nothing to replay");
        }
    }

    @Test
    void probabilisticCrashesAreReproducible() {
        ChaosPlan a = ChaosPlan.crashWithProbability(1234, 0.5);
        ChaosPlan b = ChaosPlan.crashWithProbability(1234, 0.5);
        ChaosPlan other = ChaosPlan.crashWithProbability(9999, 0.5);
        int agree = 0, differ = 0, aCrashes = 0;
        for (long op = 1; op <= 2_000; op++) {
            assertEquals(a.crashesAt(op), b.crashesAt(op), "same seed → same decision at op " + op);
            if (a.crashesAt(op)) {
                aCrashes++;
            }
            if (a.crashesAt(op) == other.crashesAt(op)) {
                agree++;
            } else {
                differ++;
            }
        }
        assertTrue(aCrashes > 800 && aCrashes < 1200, "p=0.5 over 2000 ops is near half: " + aCrashes);
        assertTrue(differ > 0, "a different seed must diverge somewhere (agree=" + agree + ")");
    }

    @Test
    void planEdgeCases() {
        // Probability extremes: p=0 never crashes, p=1 always does — no off-by-boundary.
        ChaosPlan never = ChaosPlan.crashWithProbability(7, 0.0);
        ChaosPlan always = ChaosPlan.crashWithProbability(7, 1.0);
        for (long op = 1; op <= 500; op++) {
            assertTrue(!never.crashesAt(op), "p=0 must never crash (op " + op + ")");
            assertTrue(always.crashesAt(op), "p=1 must always crash (op " + op + ")");
        }

        // Composition: and() crashes when EITHER side does, and takes the larger latency.
        ChaosPlan combined = ChaosPlan.crashOnceAtOp(3).withLatencyMillis(2)
                .and(ChaosPlan.crashEveryNthOp(10).withLatencyMillis(5));
        assertTrue(combined.crashesAt(3), "left side's crash survives and()");
        assertTrue(combined.crashesAt(10), "right side's crash survives and()");
        assertTrue(combined.crashesAt(20), "periodic rule keeps firing through and()");
        assertTrue(!combined.crashesAt(4) && !combined.crashesAt(11), "quiet ops stay quiet");
        assertEquals(5, combinedLatency(combined), "and() takes the larger latency");

        // Argument domain: caller defects are refused loudly, at construction.
        assertThrows(IllegalArgumentException.class, () -> ChaosPlan.crashOnceAtOp(0));
        assertThrows(IllegalArgumentException.class, () -> ChaosPlan.crashEveryNthOp(0));
        assertThrows(IllegalArgumentException.class, () -> ChaosPlan.crashWithProbability(1, -0.1));
        assertThrows(IllegalArgumentException.class, () -> ChaosPlan.crashWithProbability(1, 1.1));
        assertThrows(IllegalArgumentException.class, () -> ChaosPlan.none().withLatencyMillis(-1));
    }

    private static long combinedLatency(ChaosPlan plan) {
        return plan.latencyMillis();                           // package-private accessor
    }

    @Test
    void aRetryGetsPastAOnceAtFault(@TempDir Path storeDir) throws IOException {
        // The op counter advances even on a crashed op, so a caller that retries the same
        // write through the same Sizzle sees: transient fault, then recovery — exactly the
        // contract the class documents.
        try (SmokeHouse<Long, String> store = SmokeHouse.open(storeDir, opts())) {
            Sizzle<Long, String> chaos = Sizzle.over(store, ChaosPlan.crashOnceAtOp(1));
            Twine.PutSink<Long, String> sink = chaos.puts();
            assertThrows(Sizzle.Crash.class, () -> sink.put(1L, "first try"));
            assertEquals(0, store.size(), "the crashed op never reached the store");
            sink.put(1L, "second try");                        // op 2: past the fault
            assertEquals(1, store.size(), "the retry lands");
            assertEquals("second try", store.get(1L));
            assertEquals(2, chaos.opsSeen());
            assertEquals(1, chaos.crashesInjected());
        }
    }

    @Test
    void aSlowedConsumerEarnsARealGap(@TempDir Path storeDir) throws IOException {
        // Sizzle.slow + a tiny tail ring: the wrapped listener genuinely lags, the ring
        // genuinely overruns, and onGap genuinely fires — chaos proving the drop-oldest
        // contract with no mocks and no fake gaps.
        java.util.concurrent.atomic.AtomicLong gaps = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong seen = new java.util.concurrent.atomic.AtomicLong();
        SmokeHouseOptions<Long, String> tinyRing =
                SmokeHouseOptions.of(SpillSerializer.forLongs(), SpillSerializer.forStrings())
                        .indexTier(SmokeHouseOptions.IndexTier.STATIC)
                        .tailRing(8);
        try (SmokeHouse<Long, String> store = SmokeHouse.open(storeDir, tinyRing)) {
            AutoCloseable sub = store.tail(0, Sizzle.slow(
                    new io.github.richeyworks.smokehouse.TailListener<Long, String>() {
                        @Override public void onEvent(
                                io.github.richeyworks.smokehouse.TailEvent<Long, String> e) {
                            seen.incrementAndGet();
                        }
                        @Override public void onGap() {
                            gaps.incrementAndGet();
                        }
                    }, 3));
            for (long k = 0; k < 400; k++) {
                store.put(k, "v" + k);
            }
            long deadline = System.currentTimeMillis() + 15_000;
            while (gaps.get() == 0 && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            assertTrue(gaps.get() > 0, "the slowed consumer was told about its gap");
            assertTrue(seen.get() < 400, "and it genuinely missed events");
            try {
                sub.close();
            } catch (Exception e) {
                throw new IOException("closing subscriber", e);
            }
        }
        // Argument domain, same discipline as ChaosPlan.
        assertThrows(IllegalArgumentException.class,
                () -> Sizzle.slow(new io.github.richeyworks.smokehouse.TailListener<Long, String>() {
                    @Override public void onEvent(
                            io.github.richeyworks.smokehouse.TailEvent<Long, String> e) { }
                }, -1));
    }

    @Test
    void latencyIsActuallyInjected(@TempDir Path storeDir) throws IOException {
        try (SmokeHouse<Long, String> store = SmokeHouse.open(storeDir, opts())) {
            Sizzle<Long, String> chaos = Sizzle.over(store,
                    ChaosPlan.none().withLatencyMillis(5));
            Twine.PutSink<Long, String> slow = chaos.puts();
            long start = System.nanoTime();
            for (long k = 0; k < 4; k++) {
                slow.put(k, "v" + k);
            }
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            assertEquals(4, chaos.opsSeen());
            assertTrue(elapsedMillis >= 15, "4 ops × 5ms latency should cost real time: " + elapsedMillis);
            assertEquals(4, store.size(), "the ops still land after the stall");
        }
    }
}
