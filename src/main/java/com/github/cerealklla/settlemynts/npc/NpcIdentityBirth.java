package com.github.cerealklla.settlemynts.npc;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;

import net.minecraft.server.level.ServerLevel;

/**
 * The "born" concept -- moved here from Audyble's own {@code api.Audyble#resolveOrBeginBirth},
 * 2026-11-05 (see {@link NpcIdentityStore}'s own class doc for the full ownership-move writeup). No
 * longer takes a raw {@code Long settlementPlaceId} parameter at all -- {@code core} (the real,
 * already-in-hand {@link GhostTownHallCoreEntity}) is used directly wherever settlement context is
 * needed (lore, name-collision checking), since this now lives inside the same mod that owns that
 * data instead of needing it threaded in as a primitive across a mod boundary. {@code core} is {@code
 * null} for a non-settlement-bound identity ({@link NpcRole#WANDERING}).
 */
public final class NpcIdentityBirth {

    private static final LoreEntry GENERIC_LORE =
            new LoreEntry(-1, "An unremarkable settlement, like many others along the road.", Optional.empty());

    private NpcIdentityBirth() {
    }

    /**
     * Returns {@code key}'s existing identity if one already exists (every respawn after the first),
     * otherwise synchronously claims the key with a placeholder identity (so a second concurrent
     * caller this same tick can't double-trigger generation) and kicks off an async LLM call to fill
     * in a real name/backstory/key facts (see {@link NpcIdentityPrompting}), returning the placeholder
     * immediately either way -- callers should never block on this, and anything that needs the final
     * name should read it live from {@link NpcIdentityStore#find} later rather than hold onto this
     * call's own return value.
     *
     * @param level          needed only to resolve {@link NpcSettlementNames#inSettlement} when {@code core} is non-null
     * @param core           the owning settlement's real core entity, or {@code null} for a non-settlement-bound identity
     * @param settlementName the settlement's real, player-given name (e.g. "Starterville"), or blank/{@code null} if unknown -- so a freshly-born NPC's generated backstory references the real town instead of inventing one
     */
    public static NpcIdentity resolveOrBeginBirth(ServerLevel level, NpcIdentityKey key, GhostTownHallCoreEntity core, String settlementName) {
        return resolveOrBeginBirth(level, key, core, settlementName, null);
    }

    /**
     * Same as {@link #resolveOrBeginBirth(ServerLevel, NpcIdentityKey, GhostTownHallCoreEntity,
     * String)}, with an extra {@code onGenerated} callback -- see {@link
     * NpcIdentityPrompting#generateAsync}'s own doc for why this exists ({@link
     * WanderingIdentityListener}'s vanilla-nametag push is the only real caller today).
     */
    public static NpcIdentity resolveOrBeginBirth(ServerLevel level, NpcIdentityKey key, GhostTownHallCoreEntity core, String settlementName, Consumer<NpcIdentity> onGenerated) {
        Optional<NpcIdentity> existing = NpcIdentityStore.find(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        String roleName = key.role().name();
        String displayRole = roleName.charAt(0) + roleName.substring(1).toLowerCase(Locale.ROOT);
        // Gender here is a throwaway placeholder value -- nothing ever reads it before the real
        // generated identity (with its own freshly-rolled gender, see NpcIdentityPrompting) overwrites
        // this record; a synced entity field only ever copies from a resolved REAL (non-"Unnamed ")
        // identity in the first place (see e.g. GuardEntity#syncDisplayName's own guard).
        NpcIdentity placeholder = new NpcIdentity(key, "Unnamed " + displayRole, "", List.of(), true, Optional.empty(), NpcGender.MALE);
        NpcIdentityStore.put(placeholder);
        Long settlementPlaceId = core != null ? core.getCartographyrEntityId() : null;
        LoreEntry lore = settlementPlaceId != null ? SettlementLoreStore.getOrAssign(settlementPlaceId) : GENERIC_LORE;
        List<String> usedNames = core != null ? NpcSettlementNames.inSettlement(level, core) : List.of();
        NpcIdentityPrompting.generateAsync(placeholder, lore, settlementName, usedNames, onGenerated);
        return placeholder;
    }
}
