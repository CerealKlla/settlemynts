package com.github.cerealklla.settlemynts.guardhouse.client;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.Identifier;

/**
 * Client-only -- {@link ModelLayerLocation} is a client-only vanilla type, same reasoning {@code
 * caryvan.client.ModModelLayers} already documents for its own identical shape. Just the one new
 * layer, for {@link GuardCloakModel} (backlog item 12).
 */
public final class ModGuardModelLayers {

    public static final ModelLayerLocation GUARD_CLOAK =
            new ModelLayerLocation(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "guard_cloak"), "main");

    private ModGuardModelLayers() {
    }
}
