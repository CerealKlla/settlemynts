package com.github.cerealklla.settlemynts.guardhouse.client;

import com.github.cerealklla.settlemynts.guardhouse.GuardEntity;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
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
 *
 * <p><b>Female variant, 2026-11-05</b> -- real user request to give NPCs a real gender, matching
 * name/voice/visual. Confirmed directly against the real decompiled {@code DefaultPlayerSkin} source
 * that vanilla ships a {@code wide/alex} skin (not just {@code slim/alex}) -- the exact same
 * wide-arm UV layout {@code steve}'s own texture already uses on this {@link ModelLayers#ZOMBIE} body,
 * so there's no model/arm-width mismatch to work around at all, unlike what a first guess at
 * "Alex is the slim-arm skin" would suggest. {@link #getTextureLocation} branches on {@link
 * GuardRenderState#female} (populated in {@link #extractRenderState}) between the two -- zero new art
 * needed, both are real vanilla game assets.
 */
public final class GuardRenderer extends HumanoidMobRenderer<GuardEntity, GuardRenderState, HumanoidModel<GuardRenderState>> {

    private static final Identifier TEXTURE_MALE = DefaultPlayerSkin.getDefaultTexture();
    private static final Identifier TEXTURE_FEMALE = Identifier.withDefaultNamespace("textures/entity/player/wide/alex.png");

    public GuardRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
        ArmorModelSet<HumanoidModel<GuardRenderState>> armorModels =
                ArmorModelSet.bake(ModelLayers.ZOMBIE_ARMOR, context.getModelSet(), HumanoidModel::new);
        this.addLayer(new HumanoidArmorLayer<>(this, armorModels, context.getEquipmentRenderer()));
    }

    @Override
    public GuardRenderState createRenderState() {
        return new GuardRenderState();
    }

    @Override
    public void extractRenderState(GuardEntity entity, GuardRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.female = entity.isFemale();
    }

    @Override
    public Identifier getTextureLocation(GuardRenderState state) {
        return state.female ? TEXTURE_FEMALE : TEXTURE_MALE;
    }
}
