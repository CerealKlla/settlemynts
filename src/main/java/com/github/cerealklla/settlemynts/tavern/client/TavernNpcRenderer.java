package com.github.cerealklla.settlemynts.tavern.client;

import com.github.cerealklla.settlemynts.tavern.TavernNpcEntity;

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
 * {@link TavernNpcEntity}'s client renderer -- direct mirror of {@code guardhouse.GuardRenderer}'s
 * own "vanilla Zombie body mesh ({@link ModelLayers#ZOMBIE}, a plain {@link HumanoidModel} with
 * standard humanoid proportions -- the undead look came entirely from the texture) plus {@link
 * HumanoidArmorLayer}, reskinned to {@link DefaultPlayerSkin#getDefaultTexture()} (the real Steve
 * skin)" technique -- see that class's own doc for why ({@code PlayerModel} needs real plumbing
 * HumanoidMobRenderer doesn't have). "Most of the time human like Steve" (2026-10-31 user spec) is
 * satisfied by this single texture for now; a wider random-skin pool across the same body mesh is a
 * reasonable, cheap future follow-up, not required for "any humanoid" to already read as true here.
 */
public final class TavernNpcRenderer extends HumanoidMobRenderer<TavernNpcEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {

    private static final Identifier TEXTURE = DefaultPlayerSkin.getDefaultTexture();

    public TavernNpcRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
        ArmorModelSet<HumanoidModel<HumanoidRenderState>> armorModels =
                ArmorModelSet.bake(ModelLayers.ZOMBIE_ARMOR, context.getModelSet(), HumanoidModel::new);
        this.addLayer(new HumanoidArmorLayer<>(this, armorModels, context.getEquipmentRenderer()));
    }

    @Override
    public HumanoidRenderState createRenderState() {
        return new HumanoidRenderState();
    }

    /**
     * Real complaint, 2026-11-01: a Companion riding a real horse looked disconnected/floating no
     * matter what, even confirmed via RCON to have the exact same X/Z as the horse (a genuine, correct
     * riding relationship, not a positioning bug) -- vanilla's own bent-leg "riding" pose
     * ({@code HumanoidMobRenderer#extractRenderState}'s {@code state.isPassenger} branch) is what was
     * actually being rendered. That pose can't be fixed for a real vanilla {@code AbstractHorse} the
     * way it was for Lyfe's own {@code SeatEntity} (no mixin exists in this codebase to touch vanilla's
     * own attachment math, and the user explicitly wants the bent-leg pose KEPT for the stool case --
     * see Lyfe's own decisions.md). Narrower fix: suppress the pose only while riding a real
     * {@link net.minecraft.world.entity.LivingEntity} (a horse or any other live mount) -- a stool's
     * mount point is a non-living {@code Display} entity, so this leaves that case completely
     * untouched. A companion on a mount now stands normally instead (straight legs reaching down to
     * the mount's own back), the same tradeoff already made (then kept) for the standing-on-a-stool
     * look, just applied only where the bent-leg pose can't be fixed any other way.
     */
    @Override
    public void extractRenderState(TavernNpcEntity entity, HumanoidRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        if (entity.getVehicle() instanceof net.minecraft.world.entity.LivingEntity) {
            state.isPassenger = false;
        }
    }

    @Override
    public Identifier getTextureLocation(HumanoidRenderState state) {
        return TEXTURE;
    }
}
