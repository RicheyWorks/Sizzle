# Sizzle

[![CI](https://github.com/RicheyWorks/Sizzle/actions/workflows/ci.yml/badge.svg)](https://github.com/RicheyWorks/Sizzle/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)

Engine fourteen of the ecosystem: **the chaos engine**. Every other engine proves a contract
holds. Sizzle proves the contract holds *while the floor is on fire* — it injects deterministic
faults at the write seam and lets the recovery contracts answer for themselves.

```java
// Tie Twine over a Sizzle-wrapped sink, crash the batch mid-apply...
Sizzle<Long, String> chaos = Sizzle.over(store, ChaosPlan.crashOnceAtOp(4));
Twine<Long, String> twine = Twine.over(chaos.puts(), chaos.deletes(), journal, keys, vals);
twine.batch().put(1, "a").put(2, "b").put(3, "c").delete(2).commit();   // throws Sizzle.Crash

// ...reopen, and Twine's journal replay lands the whole batch exactly once.
Twine.over(store::put, store::delete, journal, keys, vals);             // replays
```

## The point is not to break things

Anyone can break things. The point is that the ecosystem's recovery contracts are **testable**.
Sizzle turns "crash-atomic by design" into "crash-atomic, demonstrated, at every crash point" —
`SizzleTest` crashes the same batch at every op index in turn, and after replay the store's
contents never change. No op lost, none applied twice.

## The plan is a pure function

`ChaosPlan` is a deterministic fault schedule keyed on the 1-based op index:

- `crashOnceAtOp(k)` — a single crash, mid-batch.
- `crashEveryNthOp(n)` — a steady drumbeat.
- `crashWithProbability(seed, p)` — each op crashes independently, but reproducibly: the
  decision is a splitmix64 hash of `(seed, op)`, so the same seed crashes at exactly the same
  ops every run. Chaos you cannot replay is a flake, not a test.
- `.withLatencyMillis(ms)` — stall before each op, for exercising lag-aware consumers.

Plans compose with `.and(...)`.

## The crash is honest

The fault is thrown **before** the op reaches the real sink — the faulted op has not happened
yet, the honest model of a process that died mid-apply. The op counter still advances, so a
caller that **retries** the same op through the same Sizzle gets past a `crashOnceAtOp` fault:
transient fault, then recovery, exactly as a real retry loop would see it. `Sizzle.Crash` is a
checked `IOException`, so it propagates through the sink contract like any real I/O failure —
never an unchecked surprise the recovery path wasn't written to expect.

## The house rule it keeps

Sizzle wraps the write seam **Twine named** (`PutSink`/`DeleteSink`) — it invents no private
back door into any engine. It re-arms from what WholeHog discovered: the sink seam was WholeHog's
first finding, and the chaos engine is what makes that seam earn its keep.

## The ecosystem

Engines 1–6: [CSRBT](https://github.com/RicheyWorks/CSRBT) (index) · [SuperBeefSort](https://github.com/RicheyWorks/SuperBeefSort) (intake) · [SmokeHouse](https://github.com/RicheyWorks/SmokeHouse) (store) · [Carver](https://github.com/RicheyWorks/Carver) (read planner) · [Renderer](https://github.com/RicheyWorks/Renderer) (materialized views) · [Brine](https://github.com/RicheyWorks/Brine) (adaptive cache).
Engines 7–11: [PitBoss](https://github.com/RicheyWorks/PitBoss) (fleet conductor) · [DryAge](https://github.com/RicheyWorks/DryAge) (time travel) · [Twine](https://github.com/RicheyWorks/Twine) (atomic batches) · [SmokeSignal](https://github.com/RicheyWorks/SmokeSignal) (the wire) · [Jerky](https://github.com/RicheyWorks/Jerky) (cold archives).
Engine 12: [WholeHog](https://github.com/RicheyWorks/WholeHog) (the integration organism).
Engines 13–14: [Rub](https://github.com/RicheyWorks/Rub) (observability) · **Sizzle** (this repo, chaos).

## Build

```bash
# Requires Twine (and its siblings) cloned alongside — composite build.
./gradlew build     # chaos against the recovery contract
```

Java 17+, Gradle 9.5.1 (bundled wrapper). MIT license.
