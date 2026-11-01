package com.github.cerealklla.settlemynts.tavern;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: opens {@code client.TavernHireScreen} -- the "Talk" polished-UX hire prompt
 * (2026-10-31 correction, explicit user spec: "simply right clicking the npc and instantly having a
 * quest is shitty... the exact same GUI with 'Talk' and the quest details spelled out for the player
 * to decide"). Replaces the old instant right-click-attempts-payment flow ({@code
 * TavernHireInteractListener} used to call {@code TavernHireHandler#attemptHire} directly) --
 * right-clicking now only ever opens this preview; payment/Trial-start only happens once the player
 * explicitly clicks the action button, sending {@link TavernHireAcceptPayload} back.
 *
 * <p>{@code description} is {@link HireRequirement#describe()}'s own text, resolved once server-side
 * (never re-derived client-side). {@code alreadyActiveTrial} is only meaningful when the requirement
 * is a {@link HireRequirement.TrialRequirement} -- true if the player already has this exact Trial
 * in progress (see {@code bridge.MyssionsQuestBridge#hasActiveTavernTrial}), in which case the screen
 * shows a status line instead of a Start button.
 */
public record OpenTavernHireMenuPayload(int npcEntityId, String npcName, String description,
                                         boolean isTrial, boolean alreadyActiveTrial) implements CustomPacketPayload {

    public static final Type<OpenTavernHireMenuPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_tavern_hire_menu"));

    public static final StreamCodec<ByteBuf, OpenTavernHireMenuPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenTavernHireMenuPayload::npcEntityId,
            ByteBufCodecs.STRING_UTF8, OpenTavernHireMenuPayload::npcName,
            ByteBufCodecs.STRING_UTF8, OpenTavernHireMenuPayload::description,
            ByteBufCodecs.BOOL, OpenTavernHireMenuPayload::isTrial,
            ByteBufCodecs.BOOL, OpenTavernHireMenuPayload::alreadyActiveTrial,
            OpenTavernHireMenuPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
