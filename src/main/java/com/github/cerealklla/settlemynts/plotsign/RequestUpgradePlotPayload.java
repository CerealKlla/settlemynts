package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.construction.PlotTierUpgradeFunding;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Upgrade Plot" funding button click on {@code client.UpgradePlotScreen} (added
 * 2026-10-09, explicit request: "All Plot Manager menus for plots need an 'Upgrade Plot' button,
 * same options as upgrading structures" -- mirrors Lyfe's own three-funding-option station upgrade).
 * Server re-checks {@code canManage} itself (same precedent as "Configure Garrison"/"Plot
 * Management") before handing off to {@code construction.PlotTierUpgradeFunding#fund}, which raises
 * {@code zone.PlotRecord#tier} by one on success -- this never touches the Construction Box or its
 * bound Blueprint at all, see that field's own class doc for why the two are deliberately separate.
 */
public record RequestUpgradePlotPayload(BlockPos signPos, PlotTierUpgradeFunding.FundingOption option) implements CustomPacketPayload {

    public static final Type<RequestUpgradePlotPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_upgrade_plot"));

    public static final StreamCodec<ByteBuf, RequestUpgradePlotPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), RequestUpgradePlotPayload::signPos,
            ByteBufCodecs.VAR_INT.map(i -> PlotTierUpgradeFunding.FundingOption.values()[i], PlotTierUpgradeFunding.FundingOption::ordinal),
            RequestUpgradePlotPayload::option,
            RequestUpgradePlotPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
