# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`GLOSSARY.md`** at the repo root, or
- **`GLOSSARY-MAP.md`** at the repo root if it exists: it points at one `GLOSSARY.md` per context. Read each one relevant to the topic.
- **`docs/adr/`**: read ADRs that touch the area you're about to work in. In multi-context repos, also check `src/<context>/docs/adr/` for context-scoped decisions.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and `/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

## File structure

Single-context repo (most repos):

```
/
├── GLOSSARY.md
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── src/
```

Multi-context repo (presence of `GLOSSARY-MAP.md` at the root):

```
/
├── GLOSSARY-MAP.md
├── docs/adr/                          ← system-wide decisions
└── src/
    ├── ordering/
    │   ├── GLOSSARY.md
    │   └── docs/adr/                  ← context-specific decisions
    └── billing/
        ├── GLOSSARY.md
        └── docs/adr/
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `GLOSSARY.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal: either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (event-sourced orders), but worth reopening because…_

## Thor vendor CPU writes

Physical checks on 2026-11-13 used an AYN Thor in Standard mode, firmware
`qti/kalama/kalama:13/TKQ1.231222.001/eng.Thor.20260206.163241:user/release-keys`.
Its `/system/bin/pservice` SHA-256 was
`8a0b75b44f0139843f2608f1ac7946ed1184cb126ed2777ee2bc2fb509357be4`, matching
[ClusterTune's traced firmware](https://github.com/AurelioB/ClusterTune/blob/f2bcccadd10d9daebb38d0900b185b8265cb4779/docs/minimum-frequency-research-2026-10-02.md).

- Writable CPU floors of 556800 / 729600 kHz on policy0 / policy3 returned to
  902400 / 1651200 kHz in two controlled runs. A private kernel trace showed
  pservice thread 1503 spawning work about ten seconds apart, with the floor
  changes immediately following one such burst. This corroborates ClusterTune's
  attribution to pservice's periodic CPU writes; elapsed time since a PULSE write
  is not a fixed overwrite delay.
- Writing each floor once and removing write bits held both floors for 73.81
  seconds across 66 samples per policy. Direct root writes to the protected
  files failed. Protection persisted through sleep/wake. Lower CPU caps of
  1459200 / 1785600 kHz held throughout a separate 76.14-second run.
- The reported minimum can still rise without a direct file overwrite. After
  wake, policy0 briefly read 787200 kHz while its minimum file remained mode 444.
  A focused repeat traced PERFD-SERVER frequency-QoS requests raising the minimum
  from 556800 to 787200 kHz for about 30 ms, then returning it. Do not treat
  every higher readback as lost permission protection or chase independent
  kernel requests with repeated writes.

The probe changed only policy0 / policy3 minimums and, in the capped tests,
their maximums. Prime settings and governors were preserved. All original
frequencies and permissions were restored. These tests establish the mechanism
on this firmware, not a shipped PULSE feature or a battery/performance benefit.

## CPU permission ownership in PULSE

CPU floor and cap writers share `CpuClockProtection`. It saves original file
permissions before mutation and starts a detached root watcher before protecting
a limit. Ownership uses the app PID and process start time, not a heartbeat that
could expire during sleep. The journal is root-private and boot-specific.

- The same owned request makes no additional sysfs writes, even when a stronger
  independent frequency request raises the reported minimum. GPU cap/floor
  reassertion also skips unchanged values and permissions.
- Positive CPU floors exclude prime. Turning the floor off leaves a capped
  prime's minimum protected. Top selectable CPU caps release their permission
  ownership and permit vendor boost.
- Intentional release restores original permissions. Process death releases
  owned CPU permissions without replaying a captured effective frequency. If
  the watcher also dies, the next app launch recovers pending permissions.
- Pre-existing read-only nodes without a PULSE journal entry are not claimed.
  Release their previous owner first; locks left by an older PULSE version may
  need a reboot because that version did not record their original permissions.

The app's own write paths were checked on the Thor above. Both CPU floors held
for 65 one-second observations, including sleep/wake. Unchanged requests kept
protected-file timestamps unchanged. Lower CPU caps held across a vendor timer
interval; floor off, sleep-profile restoration, AutoTDP release, and restoration
of manual controls passed. Closing the main activity retained protection.
Force-stopping PULSE restored all six CPU files to their original writable
permissions in 1.672 seconds. Killing both the watcher and app left protection
in place until the next launch, which restored all six permissions. The device's
saved preferences, clock settings, governors and GPU levels were restored after
testing. No game-performance, battery-life or other-device benefit was measured.
