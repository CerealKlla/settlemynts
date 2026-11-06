package com.github.cerealklla.settlemynts.tavern.client;

import com.github.cerealklla.settlemynts.tavern.TavernNpcEntity;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;

/**
 * {@link TavernNpcEntity}'s client renderer -- direct mirror of {@code guardhouse.GuardRenderer}'s
 * own "vanilla Zombie body mesh ({@link ModelLayers#ZOMBIE}, a plain {@link HumanoidModel} with
 * standard humanoid proportions -- the undead look came entirely from the texture) plus {@link
 * HumanoidArmorLayer}, reskinned to {@link DefaultPlayerSkin#getDefaultTexture()} (the real Steve
 * skin)" technique -- see that class's own doc for why ({@code PlayerModel} needs real plumbing
 * HumanoidMobRenderer doesn't have). "Most of the time human like Steve" (2026-10-31 user spec) is
 * satisfied by this single texture for now; a wider random-skin pool across the same body mesh is a
 * reasonable, cheap future follow-up, not required for "any humanoid" to already read as true here.
 *
 * <p><b>Female variant, 2026-11-05</b> -- direct mirror of {@code guardhouse.client.GuardRenderer}'s
 * own same-day change (see that class's own doc for the real-vanilla-asset research: {@code
 * wide/alex.png} is a genuine wide-arm skin, the same UV layout {@code steve}'s texture already uses
 * on this body, so there's no model mismatch to work around).
 */
public final class TavernNpcRenderer extends HumanoidMobRenderer<TavernNpcEntity, TavernRenderState, HumanoidModel<TavernRenderState>> {

    private static final Identifier TEXTURE_MALE = DefaultPlayerSkin.getDefaultTexture();
    private static final Identifier TEXTURE_FEMALE = Identifier.withDefaultNamespace("textures/entity/player/wide/alex.png");

    public TavernNpcRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
        ArmorModelSet<HumanoidModel<TavernRenderState>> armorModels =
                ArmorModelSet.bake(ModelLayers.ZOMBIE_ARMOR, context.getModelSet(), HumanoidModel::new);
        this.addLayer(new HumanoidArmorLayer<>(this, armorModels, context.getEquipmentRenderer()));
    }

    @Override
    public TavernRenderState createRenderState() {
        return new TavernRenderState();
    }

    @Override
    public void extractRenderState(TavernNpcEntity entity, TavernRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.female = entity.isFemale();
    }

    @Override
    public Identifier getTextureLocation(TavernRenderState state) {
        return state.female ? TEXTURE_FEMALE : TEXTURE_MALE;
    }
}
