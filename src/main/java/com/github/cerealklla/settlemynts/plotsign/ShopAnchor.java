package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Whatever a Shop-related client<->server payload resolves a plot through: a Plot Config Sign block
 * ({@link Sign}), or (added 2026-10-08, Part C of the "Natural settlements..." plan) a tagged trading
 * {@code Villager} acting as a natural village's own Shop interaction point ({@link Npc}). Every
 * payload that used to carry a bare {@code BlockPos signPos} (design doc Section 14a) now carries one
 * of these instead -- see {@code SettlemyntsMod#resolveShopContext}, the one place either variant is
 * turned into a real {@code PlotRecord}.
 */
public sealed interface ShopAnchor {
    record Sign(BlockPos pos) implements ShopAnchor {
    }

    record Npc(int entityId) implements ShopAnchor {
    }

    StreamCodec<ByteBuf, ShopAnchor> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, anchor -> anchor instanceof Sign,
            ByteBufCodecs.fromCodec(BlockPos.CODEC), anchor -> anchor instanceof Sign sign ? sign.pos() : BlockPos.ZERO,
            ByteBufCodecs.VAR_INT, anchor -> anchor instanceof Npc npc ? npc.entityId() : 0,
            (isSign, pos, entityId) -> isSign ? new Sign(pos) : new Npc(entityId));
}
