package com.github.cerealklla.settlemynts.guardhouse;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One garrison slot's configured permission (design doc Section 14a's "Configure Garrison" --
 * "lets the plot's managers set each individual guard slot's allotted Armor/Weapon loadout... grants
 * *permission* for the garrison to purchase that gear if/when it becomes available on the market, it
 * does not mean a spawned guard instantly has it equipped"). Simplified to a single {@code
 * maxGearTier} permission per slot rather than real Armor/Weapon item identifiers -- no gear-Tier
 * item catalog exists anywhere in this suite yet (design doc's own "Explicitly not yet decided"
 * list), so a per-item picker UI has nothing real to pick from. This is a deliberate v1 reduction of
 * the spec, not a misreading of it -- flagged here, not guessed at silently.
 *
 * @param maxGearTier the highest gear Tier this slot may purchase, clamped to {@code
 *                    GuardhouseConstants#maxGearTierForTier} by the server on every update (a
 *                    slot's own value can't exceed its Guardhouse's current cap).
 * @param allowNeighborPurchase design doc: "a checkbox toggling whether purchasing from a
 *                              *neighboring settlement's* market is permitted for that slot (more
 *                              expensive than local supply)."
 * @param kytLoadoutName the name of a Kyt (see {@code bridge.KytGuardhouseBridge}) bound to this
 *                       slot via "Select Kyt" on {@code client.ConfigureGarrisonScreen} -- empty
 *                       until set, and only ever settable/visible when Kyt is actually loaded. Not
 *                       validated against Kyt's own store on load (a Kyt can be renamed/deleted after
 *                       being bound here); a future guard-spawning system is expected to treat a
 *                       stale/missing name the same as unset.
 */
public record GarrisonSlot(int maxGearTier, boolean allowNeighborPurchase, Optional<String> kytLoadoutName) {

    public static final GarrisonSlot DEFAULT = new GarrisonSlot(1, false, Optional.empty());

    public GarrisonSlot(int maxGearTier, boolean allowNeighborPurchase) {
        this(maxGearTier, allowNeighborPurchase, Optional.empty());
    }

    public static final Codec<GarrisonSlot> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("max_gear_tier").forGetter(GarrisonSlot::maxGearTier),
            Codec.BOOL.fieldOf("allow_neighbor_purchase").forGetter(GarrisonSlot::allowNeighborPurchase),
            Codec.STRING.optionalFieldOf("kyt_loadout_name").forGetter(GarrisonSlot::kytLoadoutName)
    ).apply(i, GarrisonSlot::new));
}
