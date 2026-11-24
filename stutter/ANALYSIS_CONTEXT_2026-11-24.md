# Project Zomboid Stutter Analysis Context — 2026-11-24

> Purpose: persistent hand-off note for future ChatGPT conversations. Read this file before proposing further stutter fixes. It summarizes the complete `stutter/current` snapshot inspected on 2026-11-24. It is a diagnostic snapshot, not proof that every listed mod is defective.

## Source snapshot

Repository: https://github.com/NantawatTOMOHA/EscapeFromZomboidFirearms

Folder: [stutter/current](https://github.com/NantawatTOMOHA/EscapeFromZomboidFirearms/tree/main/stutter/current)

Files inspected in full:
- `console.txt` — 2,168,215 bytes; 15,945 lines; blob `7a1efe729fa92f91b5b9eb65cb528de18e2b8579`
- `stutters_2026-11-24_15-20.log` — 11,690,752 bytes; 22,828 lines; blob `41c699c04ba2482badc7a571ee254b432a1d647c`
- `summary_2026-11-24_15-20.log` — 4,776,012 bytes; 53,273 lines; blob `1b1089acabe42492ad0818fba72297eb9f99ceff`
- `totals_latest.txt` — 9,456 bytes; blob `68729e237dabc6f42e074610edcb2f8b526bacae`
- `read.me` — 1 byte, effectively empty.

Reported environment in the related troubleshooting context: Project Zomboid Build 42.22.2, Steam, single-player. Do not assume this is a controlled benchmark; the captured session contains menu/startup, gameplay, world streaming, UI, and other activity.

## Executive conclusion

This is a multi-source hitch problem. The strongest Lua-side lead is **ProjectALifeNPCs**, especially its server bootstrap and shared handlers. The next group to investigate is **ProjectALifeJimmy, WaterRestoration, ALifeChatPlus, BulkPallets, and Bridge**. The data also shows substantial background Lua cost from ProjectRPGExpanded, Lifestyle, and EscapeFromZomboidFirearms, but their cumulative cost must not be confused with proof that they caused the worst individual hitches.

There are also large non-Lua/engine pauses and GC pauses. Some of the largest events cannot be explained by a single mod callback:
- 17,776.3 ms at 15:28:32: Lua 11,062.3 ms, engine 6,015.0 ms, GC 699 ms. ProjectALifeNPCs server bootstrap was the largest named callback (2,871 ms); BurdSurvivalJournals also had a 1,516.7 ms callback; BulkPallets and WaterRestoration were called roughly 23,104 times in that event.
- 5,698.7 ms at 15:26:33: Lua 2,743.4 ms and engine 2,955.3 ms. A-Qo-Life PublicMapServer was 1,219.4 ms; vanilla WorldGen attachments was 604.8 ms.
- 3,307 ms at 15:22:18: primarily menu/UI work — vanilla MainScreen 2,183.8 ms and KnoxInventoryBeta KnoxMenuScene 1,041.3 ms. This is not representative of normal in-world movement.
- 2,740.1 ms at 15:28:54: only 58.8 ms Lua and 2,681.3 ms engine. This is strong evidence of an engine-side pause, not a Lua-mod hitch.
- GC-only or GC-heavy pauses include 1,320.2 ms (GC 1,661 ms) and 1,323.3 ms (GC 680 ms), plus later pauses near 0.9–1.4 seconds with GC around 1 second.

## Quantitative overview

### Stutter event stream
- The stutter log contains 22,825 timestamped records; 22,751 have frame time >40 ms when independently checked against the file's stated threshold.
- 2,807 records exceed 100 ms; 31 exceed 500 ms; 8 exceed 1,000 ms.
- Time span in the file: approximately 15:22:09 to 19:38:33.
- The raw totals file separately reports 639,068 frames and 24,659 stutters (>40 ms). These counts do not match the event-log record count. Treat them as separate collector outputs/coverage windows; do not silently combine them or claim they represent identical populations.
- Summed attribution fields across the event log: Lua 1,260,663.8 ms; engine 380,178.9 ms; GC 473,650 ms. These are summed event-record fields, not wall-clock session duration.

### Top whole-session Lua totals (from totals_latest.txt)
| Mod | Total Lua time | Calls | Worst single call |
|---|---:|---:|---:|
| ProjectALifeNPCs | 1,653,810.2 ms | 36,130,418 | 2,871.0 ms |
| ProjectRPGExpanded | 715,221.1 ms | 4,444,813 | 321.4 ms |
| Lifestyle | 590,794.3 ms | 21,190,848 | 98.0 ms |
| ALifeChatPlus | 567,598.1 ms | 24,241,028 | 1,202.5 ms |
| KnoxInventoryBeta | 566,430.5 ms | 13,843,455 | 35,148.1 ms |
| EscapeFromZomboidFirearms | 491,993.5 ms | 42,896,720 | 507.4 ms |
| ProjectALifeJimmy | 409,656.2 ms | 23,079,529 | 152.0 ms |
| Bridge | 391,075.2 ms | 30,246,400 | 666.5 ms |
| StayWithMeViewpointPatch | 223,164.1 ms | 31,266,853 | 215.7 ms |
| vanilla | 191,771.0 ms | 93,494,080 | 2,183.8 ms |
| BulkPallets | 185,943.3 ms | 5,663,150 | 229.6 ms |
| WaterRestoration | 169,340.8 ms | 14,052,114 | 43.3 ms |

Important interpretation:
- These are accumulated profiler totals across the whole captured session. A high total can mean a very frequently called small callback; it does not automatically mean that mod caused the largest frame spikes.
- KnoxInventoryBeta's 35,148.1 ms worst call is an extreme UI/menu outlier (KnoxMenuScene); investigate menu behavior separately from in-world stutter.
- EscapeFromZomboidFirearms has a high cumulative total and very high call count, but its per-event top attribution is much smaller than the leading A-Life / BulkPallets / WaterRestoration callbacks. Do not label EFZ the primary hitch culprit without a targeted A/B test.

## Strongest Lua-side leads

### 1. ProjectALifeNPCs — highest priority
Whole-session: 1,653,810.2 ms / 36.13M calls / 2,871 ms worst.
Summary's game-update mod aggregation: 1,435,192.8 ms over 1,500 windows.
Top function totals:
- `server/ProjectALife/Core/ALifeServerBootstrap.lua:52` — 594,239 ms, 345,653 calls, worst 363.1 ms in the window summary; event-log aggregate 141,171 ms.
- `shared/ProjectALife/Core/ALifeHandlers.lua:283` — 517,319 ms in whole-session totals; 490,131 ms in summary aggregation; event-log aggregate 46,019 ms.
- `server/ProjectALife/Senses/ALifeInvestigation.lua:94` — 181,437 ms whole-session; 180,080 ms summary.
- `client/ProjectALife/UI2/ALifeUI2Hud.lua:97` — 82,634 ms whole-session.
- `server/ProjectALife/Core/ALifeServerBootstrap.lua:27` — the 2,871 ms callback in the 17.776-second hitch.

There are repeated A-Life null-index errors in console, including `getName` and `getX` on null/non-table values. These are correctness issues worth fixing, but the profiler data—not the mere existence of errors—is why this mod is the top performance lead.

### 2. ProjectALifeJimmy
Whole-session 409,656 ms / 23.08M calls. Stutter event attribution totals 113,367 ms.
Notable functions:
- `server/ProjectALifeJimmy/Features/Residents/Server.lua:3598` — 56,713 ms whole-session; 26,183 ms summary; 50,155 ms in stutter-event attribution.
- `server/ProjectALifeJimmy/Features/Residents/Adopt.lua:539` — 127.7 ms in the 759.8 ms hitch at 15:28:39.
- `server/ProjectALifeJimmy/Features/Residents/Manage.lua:2329` — 35.6 ms in that same hitch.

### 3. WaterRestoration
Whole-session 169,341 ms / 14.05M calls. Stutter event attribution totals 97,021 ms.
Notable functions:
- `server/WaterRestoration/WR_LaptopServer.lua:133` — 83,294 ms whole-session; 77,277 ms in stutter-event attribution; worst 43.3 ms.
- `server/WaterRestoration/WR_LaptopServer.lua:87` — 57,768 ms whole-session; 48,426 ms summary; 19,736 ms event attribution.
At 15:28:32, line 87 was called 23,104 times within the long hitch and accumulated 483.4 ms. This repeated-call pattern merits investigation (event registration, loops, per-object scans, or re-entrant scheduling), not an assumption that the function is inherently slow.

### 4. ALifeChatPlus
Whole-session 567,598 ms / 24.24M calls. Stutter event attribution totals 71,818 ms.
Notable:
- `shared/ALifeNPCOverhaul/AA_Health.lua:43` — 336,927 ms whole-session; 236,309 ms summary; 49,348 ms event attribution.
- `shared/ALifeNPCOverhaul/ALifeQuests.lua:1856` — 350.3 ms in the 17.776-second hitch.
- `client/ALifeLLMTalk/ALifeTalk_Window.lua:202` — 116.4 ms worst in summary.
Potential overlap with ProjectALifeNPCs/ProjectALifeJimmy must be considered; do not add their profiler totals as independent wall-clock time.

### 5. BulkPallets
Whole-session 185,943 ms / 5.66M calls. Stutter event attribution 43,681 ms.
- `server/BulkPallets/BP_Server.lua:779` — 85,264 ms whole-session; 79,702 ms summary; 29,717 ms event attribution.
- `client/BulkPallets/BP_Client.lua:908` — 91,419 ms whole-session; 63,736 ms summary; 13,963 ms event attribution.
At 15:28:32, server line 779 was called 23,104 times in the long hitch (799.8 ms accumulated). At 16:42:02 it was called 1,667 times (100.7 ms). This is a high-priority repeated-work/scheduling investigation.

### 6. Bridge
Whole-session 391,075 ms / 30.25M calls. Stutter event attribution 26,676 ms.
- `client/BridgeMain.lua:10515` — 163,797 ms whole-session; 156,316 ms summary; worst 327.4 ms; 18,130 ms event attribution.
- `client/BridgeMain.lua:9731` — 55,233 ms whole-session; worst 123 ms; 5,216 ms event attribution.
- `client/work/BridgeSleep.lua:478` — 666.5 ms in the 687 ms hitch at 15:46:41.
Investigate expensive bridge work and sleep/work state transitions; confirm which other mod(s) invoke it.

### Other sustained cost worth monitoring
- ProjectRPGExpanded: 715,221 ms whole-session; especially `client/PRPGE_PlayerCard.lua:512` (468,121 ms, 346,730 calls). Summary game-update total 150,835 ms; UI/render total 554,850 ms. Large cumulative UI work, not the strongest direct stutter attribution.
- Lifestyle: 590,794 ms whole-session; `client/LSMoodleManager.lua:239` 405,489 ms over 14.66M calls. UI/render summary 405,822 ms. Many calls are individually small.
- EscapeFromZomboidFirearms: 491,994 ms / 42.90M calls. Notable functions include `client/TW_LaserRender.lua:489` (27,031 ms), `client/TW_GrenadeThrow.lua:332` (26,191 ms), and `client/TW_ArmStamina.lua:187` (24,888 ms). The summary game-update total is 416,340 ms, but stutter-event top attribution is 6,740 ms. Review high-frequency tick hooks for optimization, but current evidence does not put EFZ ahead of the A-Life / WaterRestoration / BulkPallets group as the main hitch source.
- KnoxInventoryBeta: UI/render summary 508,402 ms; whole-session worst 35,148.1 ms at `client/Knox/KnoxMenuScene.lua:2013`. Initial menu event at 15:22:18 also shows 1,041.3 ms at `KnoxMenuScene.lua:3278`. Separate menu/scene initialization from gameplay testing.
- StayWithMeViewpointPatch: 223,164 ms whole-session; summary game-update 61,934 ms and UI/render 136,047 ms. `client/StayWithMeSmooth.lua:14` contributes 53,166 ms whole-session, 44,614 ms summary, worst 55.2 ms. Worth a separate camera/frame-smoothing A/B test, especially given existing Viewpoint stack.
- LGExtendedPlumbing: 61,288 ms whole-session, mostly `server/LGExtendedPlumbing/LGEPPlumbing.lua:660` (26,978 ms whole-session, 13,506 ms summary, 5,226 ms event attribution). Not currently a top overall hitch source from this capture.

## Engine, rendering, streaming, and GC evidence

The console contains 441 Tempo_PerfKit Render/Logic metric lines and 147 spike-attribution lines. An early sample at frame 113507 reported:
- Render mean 35.7 ms, p95 56.3 ms, p99 214.5 ms, p99.9 404.0 ms, 1% low 5 FPS, jitter 48%, microStut 86%.
- Logic mean 36.0 ms, p95 49.8 ms, p99 270.4 ms, p99.9 560.7 ms, 1% low 4 FPS, jitter 68%, microStut 90%.
- Spike attribution in that window: 95 spikes >50 ms; 28 chunk-tagged, 21 menu-tagged, 19 moving, 56 unclassified (categories may overlap); worst 425 ms.

Later windows also show chunk/movement correlation, e.g. 160 spikes with 141 moving and 137 chunk-tagged, and another with 166 spikes / 165 moving / 163 chunk-tagged. Other windows show many unclassified spikes even when chunk loads are zero. Therefore:
- World streaming/chunk loading is a real contributor in some windows, but it is not the only source.
- Some long pauses are engine-side or GC-side, even when Lua attribution is low.
- The log's `engine ms` is the remainder bucket; it does not identify the exact engine subsystem. Do not attribute it to a specific mod without another profiler or controlled test.

A-Life performance telemetry includes:
- Initial `[ALIFE-PERF] tick=187ms slowest=outposts:179ms`.
- Other windows show decision processing around 108 ms and automatic quality-tier reductions from Ultra down to Minimal when smoothed tick time exceeded budget. This indicates A-Life is itself observing workload pressure and shedding work.

## Console errors and correctness signals

The console has 127 lines beginning with `ERROR:`; this includes stack-dump marker lines, so it is not 127 unique independent faults. Important distinct signatures:
- A-Life NPC Overhaul null access: `getName` and `getX` on null/non-table values, repeated at multiple frames.
- Vanilla `isValid`: `getUnequippedWeight` on null.
- Viewpoint `harvest`: `getSquare` on null; A-Life Jimmy logs `viewpoint menu failed` immediately after.
- Civilian Transport Pack and Knox Market `isComputerObject`: `java.nio.BufferOverflowException` at `ByteBuffer.put`, around frame 200067. This is a serialization/buffer correctness signal; it may be unrelated to the sustained hitch pattern.
- Repeated missing function: `Vehicles.ContainerAccess.Tire` from LuaManager around frames 265883–275524. Likely compatibility/API registration issue; not enough evidence to connect it to the worst stutters.
- `Too many physics objects on gridsquare: 7192,8416,-1` appears repeatedly (at least 7 occurrences in the captured console). Investigate the vehicle/object cluster at that square separately.
- A `BaseVehicle.addToWorld` exception occurs near frame 312820.

Treat these as separate correctness/compatibility investigations unless timing correlation demonstrates that they trigger frame spikes.

## Recommended investigation order

1. **ProjectALifeNPCs**: inspect bootstrap scheduling at lines 27/52, handlers line 283, and investigation line 94. Determine whether expensive work runs every tick, scales with NPC count, repeats scans, or does duplicate work. Preserve behavior while adding profiling around sub-phases.
2. **BulkPallets + WaterRestoration**: inspect server lines 779 and 87/133. The very high invocation counts inside a single long frame are the strongest clue for repeated callbacks or unbounded iteration. Verify event registration is not duplicated and loops are bounded.
3. **ProjectALifeJimmy + ALifeChatPlus**: inspect resident server/adoption/management paths and AA_Health line 43. Check overlap with A-Life base handlers and avoid duplicated health/NPC scans.
4. **Bridge**: inspect BridgeMain 10515/9731 and BridgeSleep 478; identify the caller and the conditions that cause large bursts.
5. **Separate UI tests**: KnoxInventoryBeta menu/scene and ProjectRPGExpanded/Lifestyle UI should be tested with menu open/closed, not mixed with movement benchmarks.
6. **Separate camera test**: StayWithMeViewpointPatch and Viewpoint-related integrations; compare with the patch disabled while keeping the rest of the mod list unchanged.
7. **Engine/GC**: correlate engine-only and GC-heavy spikes with chunk-load counters, save activity, vehicle streaming, and world transitions. Do not try to fix engine-only pauses by editing arbitrary Lua mods.

## Controlled test protocol

For each candidate:
1. Back up the save and preserve the same world, character, location, weather/time, and mod list.
2. Capture a baseline for a fixed 5–10 minute route/action with the same camera and no menus.
3. Disable only one candidate (and its dependent integration only when necessary); restart the game and repeat the same route/action.
4. Compare stutters >40 ms, >100 ms, worst frame, Lua/engine/GC split, candidate callback totals/calls/worst, chunk loads, and NPC count.
5. Repeat each condition at least twice. Do not change several mods simultaneously.
6. For server-side callbacks in single-player, do not assume “client” vs “server” naming maps to separate processes; the profiler's measured execution context and call timing matter.

## Rules for future assistants

- Start with this report, then read the current raw logs if the user has uploaded a newer snapshot or asks for deeper analysis.
- The source folder is mutable. Verify file names, sizes, and blob SHAs before relying on this snapshot. If they changed, redo the analysis.
- Distinguish whole-session accumulated Lua totals, per-window summary totals, and per-stutter event attribution. They are different views and must not be compared as if they were identical counters.
- Never call a mod “the cause” from profiler totals alone. Use “top suspect / strongest lead” until an A/B test confirms it.
- Keep the user's save safe; prefer reversible, one-mod-at-a-time tests.
- Do not attribute engine remainder or GC pauses to a mod without direct evidence.

## Raw source links

- [console.txt](https://github.com/NantawatTOMOHA/EscapeFromZomboidFirearms/blob/main/stutter/current/console.txt)
- [stutters_2026-11-24_15-20.log](https://github.com/NantawatTOMOHA/EscapeFromZomboidFirearms/blob/main/stutter/current/stutters_2026-11-24_15-20.log)
- [summary_2026-11-24_15-20.log](https://github.com/NantawatTOMOHA/EscapeFromZomboidFirearms/blob/main/stutter/current/summary_2026-11-24_15-20.log)
- [totals_latest.txt](https://github.com/NantawatTOMOHA/EscapeFromZomboidFirearms/blob/main/stutter/current/totals_latest.txt)
