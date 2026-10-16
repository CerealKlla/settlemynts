package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: opens {@code client.NpcMenuScreen} -- the NPC Menu overhaul, explicit user spec,
 * 2026-10-16. Every boolean is resolved once, server-side, via {@code
 * SettlemyntsMod#computeNpcMenuOptions} (never re-derived client-side) -- "visible but re-validated"
 * discipline carries straight through: the screen only decides which buttons to *show*, and every
 * button's own follow-up payload (e.g. {@code RequestShopPayload}'s {@code manage} branch) still
 * re-checks permission for real on click.
 *
 * <p>{@code canOpenStore} -- "Open Store (Visible to all)." {@code canManageStore} -- "visible to
 * Owner if non-NPC, otherwise visible to Mayor or Town Planners" (narrower than the Plot Config
 * Sign's own "Manage Shop," which still uses the wider founder-always-wins {@code
 * zone.PlotPermissions#canManage} -- a deliberate, NPC-Menu-only narrowing; see {@code
 * computeNpcMenuOptions}'s own doc). {@code canChangeJob} -- "only available for an NPC on a private
 * property plot," visible only to that plot's own owner. {@code canFireNpc} -- "Only on owned plots,
 * visible only to owner," and only once an NPC is actually hired there. {@code canTalk} -- "Visible
 * only on non-owned plots," opens the existing wishlist/quest {@code client.ChatMenuScreen}
 * unchanged.
 */
public record OpenNpcMenuPayload(ShopAnchor.Npc anchor, String npcName, boolean canOpenStore, boolean canManageStore,
                                  boolean canChangeJob, boolean canFireNpc, boolean canTalk) implements CustomPacketPayload {

    public static final Type<OpenNpcMenuPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_npc_menu"));

    public static final StreamCodec<ByteBuf, OpenNpcMenuPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, payload -> payload.anchor().entityId(),
            ByteBufCodecs.STRING_UTF8, OpenNpcMenuPayload::npcName,
            ByteBufCodecs.BOOL, OpenNpcMenuPayload::canOpenStore,
            ByteBufCodecs.BOOL, OpenNpcMenuPayload::canManageStore,
            ByteBufCodecs.BOOL, OpenNpcMenuPayload::canChangeJob,
            ByteBufCodecs.BOOL, OpenNpcMenuPayload::canFireNpc,
            ByteBufCodecs.BOOL, OpenNpcMenuPayload::canTalk,
            (entityId, npcName, canOpenStore, canManageStore, canChangeJob, canFireNpc, canTalk) ->
                    new OpenNpcMenuPayload(new ShopAnchor.Npc(entityId), npcName, canOpenStore, canManageStore, canChangeJob, canFireNpc, canTalk));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
