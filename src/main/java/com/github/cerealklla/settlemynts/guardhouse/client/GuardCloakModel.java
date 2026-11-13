package com.github.cerealklla.settlemynts.guardhouse.client;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;

/**
 * A small flat cloak plane for the back of a {@link GuardRenderer}-rendered guard -- backlog item
 * 12. Deliberately a standalone {@code Model<Float>}, not nested under a real {@code HumanoidModel}
 * body part (unlike vanilla's own {@code PlayerCapeModel}, which nests its cape under {@code
 * PlayerModel}'s "body" part) -- {@code GuardRenderer}'s body model is a plain, unmodified {@code
 * HumanoidModel<GuardRenderState>}, so there's no "body" part of this class's own to attach to;
 * instead this mirrors {@code net.minecraft.client.model.object.banner.BannerFlagModel} exactly: one
 * root-level part, pivoted directly to the right spot on the entity itself.
 *
 * <p>Geometry/pivot numbers are a direct adaptation of vanilla's real {@code
 * PlayerCapeModel#createCapeLayer} cape box ({@code addBox(-5, 0, -1, 10, 16, 1)} at pivot {@code
 * (0, 0, 2)} rotated 180 degrees about Y, confirmed against the real decompiled source) -- same
 * width/depth/pivot, just a bit shorter (12 tall instead of 16) to sit comfortably on this mob's own
 * proportions without dragging past the knees.
 *
 * <p>Rendered via {@code net.minecraft.client.renderer.blockentity.BannerRenderer#submitPatterns}
 * directly (see {@link GuardCloakLayer}) -- reusing vanilla's own banner base-color-plus-pattern-
 * layers submission exactly, rather than baking a new dynamic texture: "a cloak tinted the
 * settlement color, with the settlement banner in a field of that color" is already precisely what
 * a banner's own base+pattern rendering means.
 */
public final class GuardCloakModel extends Model<Float> {

    private final ModelPart cloak;

    public GuardCloakModel(ModelPart root) {
        super(root, RenderTypes::entitySolid);
        this.cloak = root.getChild("cloak");
    }

    public static LayerDefinition createCloakLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild(
                "cloak",
                CubeListBuilder.create().texOffs(0, 0).addBox(-5.0F, 0.0F, -1.0F, 10.0F, 12.0F, 1.0F),
                PartPose.offsetAndRotation(0.0F, 0.0F, 2.0F, 0.0F, (float) Math.PI, 0.0F));
        return LayerDefinition.create(mesh, 16, 16);
    }

    @Override
    public void setupAnim(Float phase) {
        super.setupAnim(phase);
        // Same small constant flutter BannerFlagModel's own flag uses -- purely cosmetic, keeps a
        // static cloak from looking like a stiff painted-on cutout.
        this.cloak.xRot = (-0.0125F + 0.01F * Mth.cos((float) (Math.PI * 2) * phase)) * (float) Math.PI;
    }
}
