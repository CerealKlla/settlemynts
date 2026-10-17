package com.github.cerealklla.settlemynts.zone;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Auto-Drop" keybind (2026-10-17) -- deposits every main-inventory item the
 * player is standing on a plot they own, and that plot's boxes already stock, into those boxes.
 * No fields; the server resolves the plot from the player's own live position. See {@link
 * AutoDropper} for the actual logic.
 */
public record RequestAutoDropPayload() implements CustomPacketPayload {

    public static final Type<RequestAutoDropPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_auto_drop"));

    public static final StreamCodec<io.netty.buffer.ByteBuf, RequestAutoDropPayload> STREAM_CODEC =
            StreamCodec.unit(new RequestAutoDropPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
