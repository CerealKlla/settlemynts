package com.github.cerealklla.settlemynts.guardhouse;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: opens {@code client.ConfigureGarrisonScreen} -- always carries the full {@link
 * GuardhouseConstants#MAX_GARRISON_SIZE}-length slot list, {@code capacity} (the current Tier's own
 * cap, see {@code GuardhouseConstants#capacityForTier}) says how many of those the screen should
 * actually render/allow editing. {@code tier <= 0} means no Blueprint is bound yet (unknown Tier) --
 * the screen shows a "bind a Blueprint first" message and no editable rows in that case. {@code
 * kytAvailable} (computed server-side via {@code bridge.KytGuardhouseBridge#isLoaded}) gates whether
 * the screen's own "Select Kyt" row button even appears -- a Kyt-less server never shows it.
 */
public record OpenConfigureGarrisonPayload(BlockPos guardhousePos, int tier, int capacity, List<GarrisonSlot> slots, boolean kytAvailable)
        implements CustomPacketPayload {

    public static final Type<OpenConfigureGarrisonPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_configure_garrison"));

    private static final StreamCodec<ByteBuf, GarrisonSlot> SLOT_STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, GarrisonSlot::maxGearTier,
            ByteBufCodecs.BOOL, GarrisonSlot::allowNeighborPurchase,
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), GarrisonSlot::kytLoadoutName,
            GarrisonSlot::new);

    public static final StreamCodec<ByteBuf, OpenConfigureGarrisonPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), OpenConfigureGarrisonPayload::guardhousePos,
            ByteBufCodecs.VAR_INT, OpenConfigureGarrisonPayload::tier,
            ByteBufCodecs.VAR_INT, OpenConfigureGarrisonPayload::capacity,
            ByteBufCodecs.collection(ArrayList::new, SLOT_STREAM_CODEC), OpenConfigureGarrisonPayload::slots,
            ByteBufCodecs.BOOL, OpenConfigureGarrisonPayload::kytAvailable,
            OpenConfigureGarrisonPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
