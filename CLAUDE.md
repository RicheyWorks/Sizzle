# Sizzle — working notes for agents

Engine 14: the chaos engine. `Sizzle.over(store, plan)` (or `Sizzle.inject(put, delete, plan)`)
wraps Twine's `PutSink`/`DeleteSink` and injects deterministic faults. `ChaosPlan` is the
schedule; `Sizzle.Crash` is the injected fault. `SizzleTest`'s headline is exactly-once-under-
crash at every op index.

## Build & test
- Composite build including `../Twine` (transitively SmokeHouse + SuperBeefSort + CSRBT).
- Tests must stay deterministic. Probabilistic plans are seeded through a splitmix64 hash of
  `(seed, op)` — NOT `java.util.Random(seed+op)`, whose first draw correlates across nearby
  seeds and skews the distribution. If you add a probabilistic rule, hash, don't `new Random`.

## Git is host-side
Same as the siblings: agent sandboxes cannot write `.git`. Run all git commands from a host
terminal (PowerShell). Stale `.git/index.lock` fix: `Remove-Item .git\index.lock -Force`.

## Invariants (do not break)
- **Sizzle wraps only the seam Twine named.** No private back doors into any engine; the fault
  goes in where a real I/O failure would.
- **The crash precedes the delegate.** Throw before the op reaches the real sink — the faulted
  op did not happen yet. The op counter still advances so retries get past a once-at fault.
- **`Crash` is a checked `IOException`.** It must propagate through the sink contract like a
  real failure; never make it unchecked.
- **Plans are pure functions of the op index.** Same seed → same crashes, every run. No mutable
  state in a `ChaosPlan`.
- Findings go upstream + into WholeHog's ledger. Sizzle exists to make the recovery contracts
  answer for themselves — if a crash point ever changes the answer, that is the finding.
