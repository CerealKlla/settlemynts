package com.github.cerealklla.settlemynts.plotsign.client;

import com.mojang.blaze3d.platform.NativeImage;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Holds {@code EstablishHighwayMapScreen}'s own terrain-snapshot GPU texture -- added 2026-10-13,
 * same one-texture-reused-for-the-session shape as Lyfe's own {@code map.ClientMapState}, just
 * without that class's zoom/pan bookkeeping (this picker's view never changes once the screen is
 * open). The texture is registered once and its pixels replaced on every subsequent screen open,
 * rather than re-registering a fresh {@link DynamicTexture} under the same {@link Identifier} each
 * time, which Lyfe's own doc for that class warns can tile/repeat if the declared size ever drifts
 * mid-swap -- a risk sidestepped here since {@code EstablishHighwayMapScreen} always builds a square
 * image sized to its own current viewport.
 */
final class EstablishHighwayTerrainTexture {

    private static final Identifier TEXTURE_ID =
            Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "establish_highway_terrain");

    private static DynamicTexture texture;

    private EstablishHighwayTerrainTexture() {
    }

    static Identifier upload(NativeImage image) {
        if (texture == null) {
            texture = new DynamicTexture(() -> "settlemynts_establish_highway_terrain", image);
            Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, texture);
        } else {
            texture.setPixels(image);
        }
        texture.upload();
        return TEXTURE_ID;
    }
}
