package com.github.cerealklla.settlemynts.guardhouse;

import java.util.Optional;
import java.util.UUID;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: opens {@code client.GuardKitStandScreen} (the "Kit Menu") -- sent directly from
 * {@link GuardKitStandEntity#interact}, no request payload needed for the initial open (unlike the
 * old "Configure Garrison" flow, a right-click is itself already a server-side event). {@code
 * boundKytName} empty means "Create Kyt" shows instead of "Edit Kyt". {@code guardAlive}/{@code
 * secondsUntilRespawn} drive the plain-text respawn-timer readout -- {@code secondsUntilRespawn} is
 * only meaningful when {@code !guardAlive}. {@code kytAvailable} (mirrors {@code
 * OpenConfigureGarrisonPayload}'s own field) gates whether Create/Edit/Select Kyt even appear -- a
 * Kyt-less server never shows them. {@code sparesOnHand} (added 2026-10-16, "Keep Spares On Hand")
 * pre-fills the Kit Menu's own numeric field -- see {@code GuardKitStandPreferredInventory}.
 */
public record OpenGuardKitStandMenuPayload(UUID standId, Optional<String> boundKytName, boolean guardAlive,
                                            long secondsUntilRespawn, boolean kytAvailable, int sparesOnHand) implements CustomPacketPayload {

    public static final Type<OpenGuardKitStandMenuPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_guard_kit_stand_menu"));

    public static final StreamCodec<ByteBuf, OpenGuardKitStandMenuPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, OpenGuardKitStandMenuPayload::standId,
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), OpenGuardKitStandMenuPayload::boundKytName,
            ByteBufCodecs.BOOL, OpenGuardKitStandMenuPayload::guardAlive,
            ByteBufCodecs.VAR_LONG, OpenGuardKitStandMenuPayload::secondsUntilRespawn,
            ByteBufCodecs.BOOL, OpenGuardKitStandMenuPayload::kytAvailable,
            ByteBufCodecs.VAR_INT, OpenGuardKitStandMenuPayload::sparesOnHand,
            OpenGuardKitStandMenuPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
