package com.github.cerealklla.settlemynts.plotsign;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.npc.NpcIdentity;
import com.github.cerealklla.settlemynts.npc.NpcIdentityKey;
import com.github.cerealklla.settlemynts.npc.NpcIdentityStore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * "Walk within range of a shop-eligible Plot Config Sign, or a shop-eligible NPC" prompt (design
 * doc Section 14a, added 2026-10-05; Villager case added 2026-10-08, Part C of the "Natural
 * settlements..." plan), plus the "!" personal-business head marker (2026-10-11).
 *
 * <p><b>Reworked 2026-10-12 from a server-side poll to a client-triggered scan</b>, per explicit
 * user request: "let the client's machine do the time-based polling and only alert the server when
 * it's relevant." The original design had every online player's server-side tick unconditionally
 * re-scanning a 4-block cube (signs) plus two Villager AABB queries, twice a second, regardless of
 * whether anything was nearby or had changed -- real, wasted recurring cost on the one machine that
 * can least afford it being multiplied by player count. {@code client.ClientProximityScanner} now
 * does that same scan on the client (free in practice -- it's just distance math against entities
 * and blocks the client already has loaded for rendering, no new data fetch required) and only
 * sends a {@link ProximityScanPayload} when its own candidate set changes, plus a slow heartbeat so
 * server-side state riding on "nothing changed" can't go stale forever while a player stands still
 * near something whose eligibility changes for an unrelated reason (see that class's own doc).
 *
 * <p>The server is never asked to simply trust the client's candidate list -- {@link
 * #handleProximityScan} re-validates every candidate's real distance (against the player's actual
 * server-side position) and real eligibility (shop/plot resolution, quest state) before acting on
 * any of it, exactly the same checks the old polling version already did. Only the *trigger* moved
 * client-side; the authoritative computation did not (see this mod's own CLAUDE.md on why Myssions
 * quest state and shop/plot data can never be resolved client-side at all).
 */
public final class PlotShopProximityTicker {

    public static final int RADIUS_BLOCKS = 4;

    // Wider than RADIUS_BLOCKS on purpose -- the personal "!" marker (2026-10-11) is meant to be
    // spotted from across a plot/settlement, same rough visibility as a normal nameplate, not just
    // once a player is already standing in interaction range. Not tied to RADIUS_BLOCKS at all; the
    // "Press G/H" proximity trigger and this marker's own visibility are two independent concerns.
    public static final int PERSONAL_MARKER_RADIUS_BLOCKS = 48;

    private static final Map<UUID, ShopAnchor> lastSent = new HashMap<>();
    private static final Map<UUID, Set<Integer>> lastSentMarkers = new HashMap<>();

    // The expensive per-(player, NPC) check (anchorHasUrgentPersonalBusiness walks into Myssions'
    // getQuestGiverMenu, a real quest-history scan) is still cached -- the 2026-10-12 rework cut how
    // *often* this whole path runs at all (client-triggered instead of every 10 ticks), but a
    // player standing still next to several candidates across repeated heartbeats still benefits
    // from not recomputing each pair every time. Same "don't need perfect freshness" reasoning
    // ShopWishlistCache already applies to the plot-wishlist side of this same feature.
    private static final long PERSONAL_BUSINESS_CACHE_TTL_TICKS = 60; // 3 seconds.
    private record BusinessKey(UUID playerId, int entityId) {
    }
    private record CachedBusiness(boolean hasUrgentBusiness, long expiresAtGameTime) {
    }
    private static final Map<BusinessKey, CachedBusiness> personalBusinessCache = new HashMap<>();

    private PlotShopProximityTicker() {
    }

    private static boolean hasUrgentPersonalBusinessCached(ServerPlayer player, ServerLevel level, ShopAnchor.Npc anchor) {
        BusinessKey key = new BusinessKey(player.getUUID(), anchor.entityId());
        long now = level.getGameTime();
        CachedBusiness cached = personalBusinessCache.get(key);
        if (cached != null && cached.expiresAtGameTime() > now) {
            return cached.hasUrgentBusiness();
        }
        boolean result = SettlemyntsMod.anchorHasUrgentPersonalBusiness(player, level, anchor);
        personalBusinessCache.put(key, new CachedBusiness(result, now + PERSONAL_BUSINESS_CACHE_TTL_TICKS));
        return result;
    }

    /**
     * Entry point from {@code SettlemyntsMod}'s handler for {@link ProximityScanPayload}. Everything
     * here re-derives its answer from real server-side state -- the client's candidate lists are
     * only ever used to decide *what to check*, never trusted as the answer itself.
     */
    public static void handleProximityScan(ServerPlayer player, ServerLevel level, List<ShopAnchor> nearCandidates, List<Integer> farVillagerEntityIds) {
        handleNearCandidates(player, level, nearCandidates);
        handleFarCandidates(player, level, farVillagerEntityIds);
    }

    /**
     * "Press G to talk" eligibility, plus the public "?" wishlist nameplate refresh for every NPC
     * candidate actually resolved here (preserves the old behavior where an NPC's nameplate got
     * refreshed as a side effect of some player merely walking near it).
     *
     * <p><b>NPC-only as of 2026-10-16</b> (the NPC Menu overhaul, explicit user spec: "I'd like to
     * adjust NPCs") -- Sign candidates are no longer considered at all here. A Plot Config Sign was
     * never actually gated by this prompt to begin with (right-clicking the physical sign block
     * always opened its own Main Menu directly, independent of this proximity system -- see {@code
     * PlotConfigSignBlock#useWithoutItem}), so dropping it changes nothing reachable, just the
     * (previously meaningless, since G already opened the Shop for a sign too) possibility of the
     * prompt/G-key firing for one.
     */
    private static void handleNearCandidates(ServerPlayer player, ServerLevel level, List<ShopAnchor> candidates) {
        // A little slack over the client's own scan radius absorbs the latency between the client's
        // scan and this server-side re-validation -- the client only ever reports what it locally
        // measured as in-range a tick or two ago, by which point the player may have moved slightly.
        double maxTrustedDistSq = (double) (RADIUS_BLOCKS + 2) * (RADIUS_BLOCKS + 2);

        ShopAnchor.Npc nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (ShopAnchor candidate : candidates) {
            if (!(candidate instanceof ShopAnchor.Npc npcAnchor)) {
                continue;
            }
            if (!(level.getEntity(npcAnchor.entityId()) instanceof Villager villager)) {
                continue;
            }
            // Refreshed for every resolved candidate, not just whichever ends up nearest -- same
            // "any nearby NPC gets its public state re-synced" behavior the old per-tick scan had.
            updateWishlistNameplate(level, villager, npcAnchor);
            // The NPC Menu can offer Talk/Change Job/Fire NPC even for an NPC with no openable Shop
            // at all (e.g. a plain Resident on a Private Residence) -- eligibility for the PROMPT
            // itself is deliberately broader than the old "has a Shop" gate: anything the menu could
            // show at all is enough to invite a press. SettlemyntsMod#npcMenuHasAnyOption is the same
            // computation requestNpcMenu itself uses, so the prompt and the menu's own real content
            // can never disagree about whether there's "something here."
            if (!SettlemyntsMod.npcMenuHasAnyOption(player, level, npcAnchor)) {
                continue;
            }
            double distSq = villager.distanceToSqr(player);
            if (distSq > maxTrustedDistSq || distSq >= nearestDistSq) {
                continue;
            }
            nearestDistSq = distSq;
            nearest = npcAnchor;
        }

        UUID playerId = player.getUUID();
        ShopAnchor previous = lastSent.get(playerId);
        if (Objects.equals(previous, nearest)) {
            return;
        }
        if (nearest != null) {
            lastSent.put(playerId, nearest);
            PacketDistributor.sendToPlayer(player, new PlotShopPromptPayload(true, nearest));
        } else {
            lastSent.remove(playerId);
            PacketDistributor.sendToPlayer(player, new PlotShopPromptPayload(false, new ShopAnchor.Sign(BlockPos.ZERO)));
        }
    }

    /**
     * The personal "!" head marker -- "every NPC within normal nameplate-visibility range that this
     * specific player personally has urgent business with," same semantics as the pre-2026-10-12
     * {@code scanPersonalMarkers}, just driven by the client's candidate list instead of a live
     * server-side AABB scan. Always sends the FULL current set on change (not a diff), matching
     * {@link PersonalQuestMarkerPayload}'s own doc.
     */
    private static void handleFarCandidates(ServerPlayer player, ServerLevel level, List<Integer> entityIds) {
        double maxTrustedDistSq = (double) (PERSONAL_MARKER_RADIUS_BLOCKS + 4) * (PERSONAL_MARKER_RADIUS_BLOCKS + 4);

        Set<Integer> marked = new HashSet<>();
        for (int entityId : entityIds) {
            if (!(level.getEntity(entityId) instanceof Villager villager)) {
                continue;
            }
            if (villager.distanceToSqr(player) > maxTrustedDistSq) {
                continue;
            }
            ShopAnchor.Npc anchor = new ShopAnchor.Npc(villager.getId());
            if (hasUrgentPersonalBusinessCached(player, level, anchor)) {
                marked.add(entityId);
            }
        }

        UUID playerId = player.getUUID();
        Set<Integer> previous = lastSentMarkers.get(playerId);
        if (Objects.equals(previous, marked)) {
            return;
        }
        lastSentMarkers.put(playerId, marked);
        PacketDistributor.sendToPlayer(player, new PersonalQuestMarkerPayload(new ArrayList<>(marked)));
    }

    private static final net.minecraft.network.chat.Component WISHLIST_MARKER =
            net.minecraft.network.chat.Component.literal("?").withStyle(net.minecraft.ChatFormatting.GREEN, net.minecraft.ChatFormatting.BOLD);

    /**
     * Sets/clears a plain vanilla nameplate above an NPC whose plot currently has an unmet Planned
     * Inventory deficit OR an unclaimed quest board offer (2026-10-10, "if they have needs they'd
     * have a ! floating over their heads"; recolored/reglyphed green "?" 2026-10-11 -- the user's own
     * correction, freeing up "!" for the new personal marker below: "Currently that results in a
     * Golden ! and I now want a Green ?") -- reuses vanilla's own nametag rendering entirely, no new
     * client rendering code needed. Public (visible to every player, not just whoever's asking),
     * server-authoritative, computed only for NPCs this class is already resolving for proximity
     * detection -- no additional world scanning beyond that.
     *
     * <p><b>Real bug, found live 2026-11-17: "I walked up to this NPC named Oscar and when I got
     * close it changed to just ?"</b> -- this used to set the bare marker as the WHOLE {@code
     * CustomName}, directly overwriting a real named NPC's identity the instant a player walked
     * close enough to trigger this scan (which is exactly when the user would notice). The marker is
     * now appended onto the NPC's own real identity name instead of replacing it -- the same
     * "combine, don't clobber" fix {@code client.PersonalQuestMarkerRenderer} already uses for the
     * sibling personal "!" marker, just via a real {@code CustomName} mutation here (server-
     * authoritative/public) instead of a client-only render hook (per-viewer/personal). Always
     * recomputes the desired name from scratch and only writes it if different -- simpler and more
     * robust than the old two-branch "was this marker, was this not" state tracking, which is what
     * let a tagged-but-un-restored name slip through in the first place (see the 2026-11-07/2026-11-16
     * history below).
     *
     * <p>History: originally cleared ANY non-null custom name once the NPC had no wishlist (2026-10-10),
     * wiping a freshly-named NPC's real name back to null on its very first approach -- "Names still
     * disappear on the NPCs when I walk up to them" (fixed 2026-11-07 by only clearing a name this
     * method itself had set). That fix then discarded the real name outright instead of restoring it
     * -- "Neutral village NPC names get erased and replaced with ? an !" (fixed 2026-11-16 by re-
     * reading the real name from {@link NpcIdentityStore} instead of trying to reconstruct it from the
     * live entity's own just-overwritten {@code CustomName}). This pass removes the overwrite itself.
     */
    private static void updateWishlistNameplate(ServerLevel level, Villager npc, ShopAnchor.Npc anchor) {
        boolean hasWishlist = SettlemyntsMod.anchorHasWishlist(level, anchor);
        Optional<String> realName = realIdentityName(npc);
        net.minecraft.network.chat.Component desired;
        if (hasWishlist) {
            desired = realName.isPresent()
                    ? net.minecraft.network.chat.Component.literal(realName.get() + " ").append(WISHLIST_MARKER)
                    : WISHLIST_MARKER;
        } else {
            desired = realName.map(net.minecraft.network.chat.Component::literal).orElse(null);
        }
        if (!Objects.equals(desired, npc.getCustomName())) {
            npc.setCustomName(desired);
            npc.setCustomNameVisible(desired != null);
        }
    }

    /**
     * An NPC's own real identity name, if it has one -- a {@code NpcRole#WANDERING} NPC's real name
     * is independently persisted in {@link NpcIdentityStore}, keyed by {@link
     * NpcIdentityKey#wandering}, the moment it's born (see {@code npc.VillagerIdentityListener}/
     * {@code npc.WanderingIdentityListener}), so it's read from there rather than from the live
     * entity's own {@code CustomName} (which this method's own caller is in the middle of deciding
     * whether to overwrite). Empty for a plot-bound worker (Farmer/Lumberjack/Miner/Resident/Guard)
     * reaching this same code path -- those roles are keyed by their owning plot/stand, not {@code
     * wandering}, have no entry under this key, and show their name via Nuclyus's floating stat bar
     * rather than a vanilla nameplate in the first place, so there's genuinely no real name for them
     * here (the bare "?" marker, with no name to prefix, is correct for them).
     */
    private static Optional<String> realIdentityName(Villager npc) {
        return NpcIdentityStore.find(NpcIdentityKey.wandering(npc.getUUID()))
                .map(NpcIdentity::name)
                .filter(name -> !name.startsWith("Unnamed "));
    }

    /**
     * Public entry point for refreshing one specific NPC's "?" nameplate on demand, outside the
     * client-triggered scan above -- added 2026-10-12, real report: a plot's quest-giving NPC kept
     * showing a stale "?" with "Press H" doing nothing, because nothing ever cleared the marker
     * except the next time some player happened to be scanned as standing within {@link
     * #RADIUS_BLOCKS} of that specific NPC again. Called right after an action that can make a
     * plot's wishlist/board state go from "has something" to "empty" (e.g. {@code SettlemyntsMod
     * #acceptBoardOffer}), so the nameplate can't outlive the real state it's supposed to reflect.
     * A no-op if {@code anchor} doesn't resolve to a live {@link Villager}.
     */
    public static void refreshNameplate(ServerLevel level, ShopAnchor.Npc anchor) {
        if (level.getEntity(anchor.entityId()) instanceof Villager npc) {
            updateWishlistNameplate(level, npc, anchor);
        }
    }
}
