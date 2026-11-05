package com.github.cerealklla.settlemynts.npc;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Births a {@link NpcRole#WANDERING} identity for every real vanilla {@link WanderingTrader} the
 * instant it's first seen -- moved here from Audyble's own {@code npc.WanderingIdentityListener},
 * 2026-11-05, per the user's explicit direction that Settlemynts (not Audyble) should manage NPC
 * history even though this mod never spawns Wandering Traders itself (see {@link NpcIdentityStore}'s
 * own class doc for the full ownership-move writeup). Keying by the trader's own stable entity UUID
 * means "first sighting of this UUID" is a correct, side-effect-free birth trigger regardless of
 * whether this is a genuinely fresh spawn or just a chunk reload of a trader that's existed for days
 * -- {@link NpcIdentityBirth#resolveOrBeginBirth} is itself idempotent (a fast-path lookup on every
 * call after the first), so no separate "is this really new" check is needed here at all.
 *
 * <p><b>Nameplate push, 2026-11-05</b> -- a {@code WanderingTrader} can't implement Nuclyus's {@code
 * StatBarOwner} the way Settlemynts' own 5 worker/guard entity classes do (it's a real vanilla class,
 * and this suite deliberately has exactly one mixin, reserved for Lyfe's Unbreakable Equipment, not
 * extended casually), so it gets a genuinely different, simpler mechanism: once a real name is
 * generated, this entity's own vanilla {@code CustomName} is set directly, the same "any mod can name
 * any entity" API every other named mob in the game already uses. The generation callback fires off
 * this class's own single-thread executor, never the server thread, so the actual entity mutation is
 * scheduled back onto the server thread via {@link MinecraftServer#execute} -- touching a live
 * `Entity` from any other thread is unsafe. Also handles the already-generated case (a chunk reload
 * of a trader born before this feature existed, or before this exact join) by setting the name
 * immediately from whatever's already in the store, not just the newly-generated path.
 */
public final class WanderingIdentityListener {

    @SubscribeEvent
    public void onJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof WanderingTrader trader)) {
            return;
        }
        NpcIdentityKey key = NpcIdentityKey.wandering(trader.getUUID());
        MinecraftServer server = level.getServer();
        NpcIdentity identity = NpcIdentityBirth.resolveOrBeginBirth(level, key, null, "",
                generated -> server.execute(() -> applyName(server, key, generated.name())));
        // Already-generated case (a chunk reload of a trader born earlier) -- the join handler
        // already has the live entity reference in hand, no need to search for it.
        if (!identity.name().startsWith("Unnamed ")) {
            trader.setCustomName(Component.literal(identity.name()));
            trader.setCustomNameVisible(true);
        }
    }

    /** Called once generation completes, after this join's own handler has already returned -- the trader must be re-found by UUID since the original live reference isn't captured across the async gap. */
    private static void applyName(MinecraftServer server, NpcIdentityKey key, String name) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(key.ownerId()) instanceof WanderingTrader trader) {
                trader.setCustomName(Component.literal(name));
                trader.setCustomNameVisible(true);
                return;
            }
        }
    }
}
