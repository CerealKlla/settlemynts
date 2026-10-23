package com.github.cerealklla.settlemynts.roadway;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.registration.ModMobEffects;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Applies the real movement-speed bonus for the "Traveler" buff (design doc Section 16, 2026-10-24)
 * -- {@code 2 + (5 * road tier)}% movement speed, road tier read back out of the {@link
 * TravelerMobEffect} instance's own amplifier (amplifier = tier - 1, stamped on by {@link
 * RoadwayConnectionMarkerEntity#onPing}). Same per-tick remove-then-reapply-if-applicable transient
 * modifier idiom as Lyfe's own {@code durability.UnbreakableArmorListener}.
 */
public final class TravelerSpeedListener {

    private static final Identifier MODIFIER_ID = Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "traveler_speed");

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        AttributeInstance instance = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (instance == null) {
            return;
        }
        instance.removeModifier(MODIFIER_ID);

        MobEffectInstance effect = player.getEffect(ModMobEffects.TRAVELER);
        if (effect == null) {
            return;
        }
        int tier = effect.getAmplifier() + 1;
        double fraction = (2 + 5 * tier) / 100.0;
        instance.addTransientModifier(new AttributeModifier(MODIFIER_ID, fraction, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }
}
