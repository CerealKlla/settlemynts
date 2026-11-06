package com.github.cerealklla.settlemynts.npc;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Births a {@link NpcRole#WANDERING} identity for every plain vanilla {@link Villager} the instant
 * it's first seen -- added 2026-11-05, real user report: "I just found a brand new NPC village; no
 * one had names, no one responded to my voice." Direct mirror of {@link WanderingIdentityListener}'s
 * own join-listener/nameplate-push mechanism (see that class's own doc for the full reasoning), just
 * targeting a different vanilla entity type -- reusing {@link NpcIdentityKey#wandering} rather than
 * adding a dedicated new role, since "identified by its own UUID, not plot-bound" already describes
 * this case exactly as well as it describes a Wandering Trader.
 *
 * <p><b>{@code getClass() == Villager.class}, not {@code instanceof}</b> -- deliberately excludes
 * every one of Settlemynts' own 4 plot-bound worker/resident entity classes, which all {@code extends
 * Villager} and already have a completely separate, working identity/nameplate mechanism ({@code
 * farm.FarmerWorkerEntity#syncDisplayName} and its 3 siblings) keyed by their owning plot instead of
 * their own entity UUID -- an {@code instanceof} check here would double-identity them. Same exact-
 * class idiom this mod's own {@code zone.NaturalVillagePlotGenerator} already uses to tell "a real
 * plain villager" apart from one of this mod's own subclasses.
 *
 * <p><b>Covers more than just naturally-generated villages</b> -- this is a pure function of entity
 * type, not of which mod or system spawned it, so it equally covers a plain {@code Villager} TinyWyrlds
 * happens to spawn from one of its data-driven FRIENDLY entity pools (confirmed: TinyWyrlds has no
 * identity system of its own at all, and its spawned entities are already fresh, independent per-
 * generation instances with their own UUID -- no special-casing needed for that mod either). Does
 * <b>not</b> yet cover a non-{@code Villager} "friendly" TinyWyrld spawn (e.g. an Allay) -- flagged,
 * not built, since there's no settlement-independent "is this one of the sensible NPC-like species"
 * allowlist anywhere in this suite yet.
 */
public final class VillagerIdentityListener {

    @SubscribeEvent
    public void onJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || event.getEntity().getClass() != Villager.class) {
            return;
        }
        Villager villager = (Villager) event.getEntity();
        NpcIdentityKey key = NpcIdentityKey.wandering(villager.getUUID());
        MinecraftServer server = level.getServer();
        NpcIdentity identity = NpcIdentityBirth.resolveOrBeginBirth(level, key, null, "",
                generated -> server.execute(() -> applyName(server, key, generated.name())));
        // Already-generated case (a chunk reload of a villager born earlier) -- the join handler
        // already has the live entity reference in hand, no need to search for it.
        if (!identity.name().startsWith("Unnamed ")) {
            villager.setCustomName(Component.literal(identity.name()));
            villager.setCustomNameVisible(true);
        }
    }

    /** Called once generation completes, after this join's own handler has already returned -- the villager must be re-found by UUID since the original live reference isn't captured across the async gap. */
    private static void applyName(MinecraftServer server, NpcIdentityKey key, String name) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(key.ownerId()) instanceof Villager villager && villager.getClass() == Villager.class) {
                villager.setCustomName(Component.literal(name));
                villager.setCustomNameVisible(true);
                return;
            }
        }
    }
}
