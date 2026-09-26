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

Next: confirm the build is clean, boot-smoke-test via `runServer`/`runClient`, then live-test the founding flow (place a flag near/far from an existing village, confirm the distance check and site clearing both work). After that: Section 6 (staking — Town Planner permissions, Planned Perimeter Stakes) is the next milestone, building on the confirmed perimeter auto-fit algorithm (design doc Section 7a) and the ghost-entity pattern established here.

See [context/classes/](context/classes/) for per-class reference.
