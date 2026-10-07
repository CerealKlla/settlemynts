package com.github.cerealklla.settlemynts.guardhouse.client;

import com.github.cerealklla.settlemynts.guardhouse.GuardEntity;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;

/**
 * {@link GuardEntity}'s client renderer -- built on vanilla's own {@link ModelLayers#ZOMBIE} body
 * geometry (a plain {@link HumanoidModel}, standard proportions, no zombie-specific mesh quirks --
 * the undead look came entirely from the texture) plus {@link HumanoidArmorLayer} using {@link
 * ModelLayers#ZOMBIE_ARMOR} (the same baked-in vanilla layer defs {@code ZombieRenderer}/{@code
 * AbstractZombieRenderer} use for their own armor rendering -- no new model files needed).
 *
 * <p><b>Reskinned human, 2026-10-05</b> (real report: "they are currently Zombie guards... is there
 * no way to make them human-esque?") -- texture swapped from the vanilla zombie skin to {@link
 * DefaultPlayerSkin#getDefaultTexture()} (the real Steve skin, no {@code GameProfile}/skin-fetching
 * needed). Researched against the real decompiled MC source first: {@code PlayerModel} is hard-tied
 * to its own {@code AvatarRenderState} (cape/sleeve overlays, slim/wide arms), incompatible with
 * {@code HumanoidMobRenderer}'s plain {@code HumanoidRenderState} without real plumbing; the Illager
 * family (Vindicator etc.) uses a completely different non-humanoid model/renderer base with no
 * armor layer at all. A one-line texture swap on the exact same humanoid mesh the armor layer
 * already matches is the simplest genuine fix -- zero structural changes below.
 */
public final class GuardRenderer extends HumanoidMobRenderer<GuardEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {

    private static final Identifier TEXTURE = DefaultPlayerSkin.getDefaultTexture();

    public GuardRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
        ArmorModelSet<HumanoidModel<HumanoidRenderState>> armorModels =
                ArmorModelSet.bake(ModelLayers.ZOMBIE_ARMOR, context.getModelSet(), HumanoidModel::new);
        this.addLayer(new HumanoidArmorLayer<>(this, armorModels, context.getEquipmentRenderer()));
    }

    @Override
    public HumanoidRenderState createRenderState() {
        return new HumanoidRenderState();
    }

    @Override
    public Identifier getTextureLocation(HumanoidRenderState state) {
        return TEXTURE;
    }
}
