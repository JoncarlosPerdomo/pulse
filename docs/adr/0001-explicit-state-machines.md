# 1. State machines are explicit values with pure transitions

## Context

Much of PULSE's behavior is stateful: a foreground app being confirmed, AutoTDP parking the prime cores, a
permission round-trip through system Settings, an overlay window that may or may not exist yet. These were
often encoded as loose `Boolean`/`Int`/nullable fields kept consistent by hand (`primeParked` + `parkSteady` +
`parkCooldown` + `jankAtPark`; `showSettings` + `showPerApps`; four `pending*Enable` flags). Readers had to
reconstruct the legal states, illegal combinations compiled fine, and the rules couldn't be unit-tested apart
from Android.

## Decision

When a component has states, model them as one value:

- A `sealed interface` when states carry different data (`PrimePark.Online`/`Parked`, `GrantStep`), an
  `enum` when they don't (`DeviceLink`, `Screen`), or a `data class` when every state carries the same counters
  (`PrimeWallLatch`, `ForegroundState`).
- Transitions are pure functions of `(state, input) -> state` with no Android or device I/O, so they're
  unit-tested directly (`CustomFanGate.next`, `ForegroundDebounce.next`, `FeatureGrantFlow.resume`,
  `PlaySession.resume`). The Android or device caller keeps one `var` and applies the result.
- Put the type next to its only user, or in `model/` when the UI and the service share it.

## Consequences

- Illegal combinations don't compile, and `when` over the state is exhaustive.
- A behavior change to a transition shows up as a focused test failure, not a device-only regression.
- Changes to AutoTDP's sub-machines must still keep the replay fixtures in `app/src/test/resources/autotdp/`
  passing.
