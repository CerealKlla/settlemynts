package com.github.cerealklla.settlemynts.watchtower;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Keeps every client's small cached copy of {@link WatchTowerIndex} up to date -- sent on login,
 * and re-sent to everyone whenever a new Beacon self-resolves its identity ({@code
 * WatchTowerBeaconBlockEntity#tryResolveIdentity}, the only other caller of {@link #broadcastAll}).
 * The whole index is always this small and changes this rarely, so a full resend is simpler than
 * diffing -- no per-client "what have they already got" bookkeeping needed.
 */
public final class WatchTowerBeaconSync {

    public static void broadcastAll(MinecraftServer server) {
        WatchTowerBeaconListPayload payload = new WatchTowerBeaconListPayload(WatchTowerIndex.get(server).all());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        PacketDistributor.sendToPlayer(serverPlayer,
                new WatchTowerBeaconListPayload(WatchTowerIndex.get(serverPlayer.level().getServer()).all()));
    }
}
