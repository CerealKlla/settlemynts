package com.github.cerealklla.settlemynts.plotsign;

import java.util.List;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "here's what I can see near myself right now" -- replaces the old
 * server-side {@code PlotShopProximityTicker} design (every online player, every 10 ticks,
 * unconditionally re-scanning its own surroundings) with a client-triggered one, added 2026-10-12
 * per explicit user request: "let the client's machine do the time-based polling and only alert
 * the server when it's relevant." {@code client.ClientProximityScanner} does the actual local
 * scanning (cheap -- it's just distance math against entities/blocks the client already has loaded
 * for rendering, no extra network cost to obtain them) and sends this only when the candidate set
 * it sees actually changes, plus a slow unconditional heartbeat (see that class's own doc) so
 * server-side state riding on "nothing changed" can't go stale forever while a player stands still.
 *
 * <p>Deliberately NOT trusted outright -- the server re-validates every candidate's real distance
 * and real eligibility (shop/plot resolution) before acting on any of this; a modified client could
 * otherwise claim false proximity. See {@code PlotShopProximityTicker#handleProximityScan}.
 *
 * <p>{@code nearCandidates} -- every {@link ShopAnchor} (Plot Config Sign or Villager-like NPC)
 * within the client's own 4-block "Press G/H" interaction radius, mirroring the old ticker's own
 * {@code RADIUS_BLOCKS}. Deliberately over-inclusive (the client can't know which Villagers are
 * actually plot-tagged or shop-eligible -- that's server-only state) -- the server narrows this down.
 *
 * <p>{@code farVillagerEntityIds} -- every Villager-like entity id within the wider 48-block
 * personal-"!"-marker radius ({@code PERSONAL_MARKER_RADIUS_BLOCKS}), for the same reason split out
 * from {@code nearCandidates} rather than just taking the union: the two drive genuinely different
 * server-side checks (shop/chat eligibility vs. per-player urgent quest business) at different radii.
 */
public record ProximityScanPayload(List<ShopAnchor> nearCandidates, List<Integer> farVillagerEntityIds) implements CustomPacketPayload {

    public static final Type<ProximityScanPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "proximity_scan"));

    public static final StreamCodec<ByteBuf, ProximityScanPayload> STREAM_CODEC = StreamCodec.composite(
            ShopAnchor.STREAM_CODEC.apply(ByteBufCodecs.list()), ProximityScanPayload::nearCandidates,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), ProximityScanPayload::farVillagerEntityIds,
            ProximityScanPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
