# Settlemynts

Settlement-management mod for a modular Minecraft project (Minecraft Java Edition). Part of a suite of intercommunicating mods that expose public APIs so other mods — the user's own and third parties' — can integrate.

## Context directory — read this first

`context/` is a **separate private repo** (https://github.com/CerealKlla/settlemynts-context), not part of this one — it's listed in `.gitignore` here and must never be committed to this repo. It's cloned as a subdirectory at `context/` for local convenience. If this directory is missing (e.g. a fresh clone of just this repo), restore it with:

```
git clone https://github.com/CerealKlla/settlemynts-context.git context
```

Before searching source for architecture, ownership boundaries, API shape, or "why does this work this way," check `context/` first. It's maintained specifically to answer those questions cheaply:

- `context/design-document.md` — the authoritative design spec: full user design dump, organized and iterated on before implementation began. Start here for anything about intended shape or scope.
- `context/decisions.md` — dated log of decisions made during implementation that extend or override the design document, with rationale. Check this for anything that looks like it contradicts design-document.md — the doc should already reflect the current decision, but this explains why.
- `context/classes/` — one short markdown file per implemented class: public surface, key state, collaborators. Read the relevant file here before opening the actual source, and before editing a class update its file to match.

**Keep this system current as you work:**
- When a design decision is made that conflicts with or is absent from design-document.md, update design-document.md directly and add a dated entry to decisions.md explaining the change.
- When a class is added or its public surface changes, add or update its file in `context/classes/`.
- Don't let source and these docs drift — treat updating them as part of finishing the change, not optional cleanup.
- `context/` has its own git history, independent of this repo's commits. Commit and push changes there separately (`git -C context add . && git -C context commit -m "..." && git -C context push`) — editing the files alone doesn't back them up.

## Status

Scaffolded 2026-09-26 (see [context/decisions.md](context/decisions.md)):
- Loader: **NeoForge**
- Minecraft version: **26.1.2**
- Java: **JDK 25** (standalone Eclipse Temurin, JAVA_HOME set) — same toolchain as the rest of the suite
- Group ID: `com.github.cerealklla.settlemynts` / Mod ID: `settlemynts`
- **Cartographyr is a REQUIRED dependency** (`compileOnly`, mirroring Lyfe's dependency pattern, but `type="required"` in `neoforge.mods.toml` rather than optional) — settlement founding's distance check and settlement-polygon registration both need Cartographyr's real API, with no meaningful standalone mode.
- Project structure copied from Yconomics' own MDK setup (`build.gradle`, `settings.gradle`, wrapper).

**Design captured before implementation began** — the full design (a large user dump, then iterated on across several rounds: ownership boundaries with Blueprynts/Yconomics, the ghost-rendering mechanism researched against the decompiled source, a concrete perimeter auto-fit algorithm, ~~settlement~~ founding-distance/target-area numbers proposed and reconciled to real-world feet-to-block conversion) lives entirely in [context/design-document.md](context/design-document.md). Read that before extending anything here — it's long, but it's the single source of truth for scope questions.

**Milestone 1: settlement founding, implemented 2026-09-26** (see [context/decisions.md](context/decisions.md)) — design doc Sections 3 and 5 only (founding a settlement up through the Ghost Town Hall Core appearing). Explicitly does **not** yet cover staking/finalization (Section 6-8), protection, or Cartographyr registration of a finished settlement — those are later milestones.
- `founding.SettlementClaimFlagItem` — places on right-click, checks `founding.SettlementFounding#isFarEnoughFromExistingSettlements` (reuses Cartographyr's existing `Cartography.findEntities`/`Classification.CONSTRUCTED` query — no new Cartographyr API needed), blocks placement with an error message if too close, otherwise clears a 5x5 construction site and consumes the flag.
- `founding.SettlementFounding#MIN_DISTANCE_BLOCKS` = ~610 blocks (2000 real-world feet, converted at the suite's existing 1 block ≈ 1 meter ≈ 3.28 ft rate — design doc Section 14).
- `founding.SettlementFounding#clearConstructionSite` — **known v1 scope cut**: clears above-ground obstructions in the 5x5 footprint, does **not** re-terrain uneven ground (fill holes/level bumps) yet, despite the design doc's "cleared and flattened" language. Flagged, not silently dropped.
- `founding.GhostTownHallCoreEntity` — a marker entity (`noPhysics`, `isPickable`) spawned at the construction site's center. Real per-player visibility via `Entity#broadcastToPlayer(ServerPlayer)` (the same hook vanilla itself uses to hide spectators from non-spectators — confirmed against the decompiled source, design doc Section 2) — **currently founder-only**, since Section 6's Town Planner permission-granting UI doesn't exist yet. `interact()` is a placeholder chat message, not the real permissions/staking UI.
- `founding.client.GhostTownHallCoreRenderer` — floating `Items.BELL` icon, same technique as Yconomics' `LootBagRenderer`. **Not translucent yet** — the "ghost" visual (partial transparency) is deferred; the functionally important part (real per-player invisibility) is already correct.
- Debug-only: `SettlemyntsMod#onPlayerLoggedIn` grants 4 Settlement Claim Flags on every login (no real crafting recipe exists yet — design doc calls for one).
- `./gradlew build` — status pending first build attempt this session; update once confirmed.

**Live-tested 2026-09-26**: founding flow confirmed live — the distance check correctly blocked placement near an established natural village. Two real bugs found and fixed the same day: (1) a second claim flag could be placed right next to an already-placed (unfinalized) Ghost Town Hall Core, since the distance check only queried Cartographyr-registered settlements — fixed by also scanning the level directly for other in-progress `GhostTownHallCoreEntity` instances within range; (2) the "too close" error message gave no frame of reference for a raw block count — `SettlementFounding` now also returns an 8-point compass direction, so the message reads e.g. "the nearest one is 340 blocks (~1115 feet) to the northeast."

**Milestone 2: settlement staking, implemented 2026-09-26** (see [context/decisions.md](context/decisions.md)) — design doc Section 6 only (Town Planner permissions, settlement naming, Planned Perimeter Stakes), stopping short of Sections 7-8 (perimeter fit algorithm, finalization). `GhostTownHallCoreEntity` gained a real permission model (`townPlanners: Set<UUID>`, founder-only granting). New payload-driven UI following Lyfe's `knowledge.WritingScreen` pattern (no container menu needed) — `client.FoundingScreen` (name entry, granting, stake retrieval) and `client.StakeScreen` (absolute toggle capped at 5, remove). `GhostPerimeterStakeEntity` floats vanilla's own `Items.SOUL_TORCH` — a real blue-flamed item, matching "ghost torch with blue flame" exactly, no new art. `founding.PlannedPerimeterStakeItem` resolves "which settlement" by Town-Planner-permission-within-radius (≈152 blocks/500ft), not just nearest core. `./gradlew build` green, boot-smoke-tested clean with the full four-mod suite. **Live confirmation is still pending.**

**Perimeter auto-fit algorithm implemented, 2026-09-26** (see [context/decisions.md](context/decisions.md)) — new `founding.PerimeterFit` (design doc Section 7a, Radial Scale-Factor Bisection), pure 2D geometry with no live-world dependency — genuinely unit-tested (`PerimeterFitTest`, 8 cases), a first for this mod. Wired to a real "Finalize" button in `FoundingScreen`/`FinalizeSettlementPayload`: validates a name is set, at least 3 stakes exist, and the stake shape encompasses the core, then runs the fit against the design doc's proposed ~20,000 blocks² target and repositions each stake. **Deliberately stops there** — solidifying stakes/core, Cartographyr registration, and protection (design doc Section 8) are a later milestone, not built yet. `./gradlew build`/`test` green, boot-smoke-tested clean with the full suite.

Next: live-test Milestone 2 + the auto-fit (grant a planner, retrieve/place/toggle/remove stakes, then Finalize and confirm the perimeter visibly resizes toward the target area while absolute stakes stay put). After that: Section 8 (finalization effects — solidifying stakes/core, registering the settlement polygon with Cartographyr, enabling protection) is the next milestone.

See [context/classes/](context/classes/) for per-class reference.
