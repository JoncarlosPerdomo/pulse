# Glossary

**Global tuning**: the device-wide tuning mode — the active Tier, the side controls, and the Custom snapshot. Owned by `GlobalTuning`. _Avoid_: preset, mode.

**Tier**: Max, Balanced, Power Saving, or Custom (`PowerTier`). Max/Balanced/Power Saving are presets; switching to one clears the side controls.

**Side controls**: Power Target, GPU lock, GPU floor, CPU floor, prime-boost limit (`SideControlState`). A Power Target edit switches the Tier to Custom.

**CPU floor**: the lowest CPU frequency requested through Side controls for the non-prime CPU groups. The prime CPU group is excluded.

**CPU cap**: the upper CPU frequency setting for a CPU group. Values below the highest selectable frequency are firm limits; the highest selectable value permits vendor boost.

**Custom snapshot**: the saved Custom setup (`CustomTuning` plus the Custom values map) that switching back to Custom restores. Written only while Custom is the active Tier.

**Clock ownership**: who may write the CPU/GPU caps right now — free, a bound game with its own binding, or a following-global AutoTDP session (`ClockOwnership`). Global tuning defers to it.

**Manual passthrough**: fan_mode 6. The vendor fan controller stops regulating and whatever duty is written stays. A stranded passthrough is the one dangerous fan state.

**Custom fan**: Pulse driving the fan duty in Manual passthrough. Pulse enters passthrough only while the Fan watchdog is Armed. _Avoid_: Custom mode (ambiguous with the Custom Tier).

**Fan watchdog**: the mechanism that hands the fan back to vendor Smart if Pulse dies while driving the Custom fan — Pulse's heartbeat plus the Watcher.

**Watcher**: the detached root process inside the Fan watchdog that restores Smart once Pulse's heartbeat goes stale.

**Armed**: the Fan watchdog state where Pulse's heartbeat is fresh and the Watcher is confirmed alive. Only an Armed watchdog permits Manual passthrough.
