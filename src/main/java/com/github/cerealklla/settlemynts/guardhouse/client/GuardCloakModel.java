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
 * <p><b>Real fix, 2026-11-13</b> -- a live screenshot after this feature's first-ever real test
 * showed the cloak rendering as a crudely-stretched, barely-recognizable smear instead of a clean
 * banner image ("why didn't you give them actual cloaks?"). Root cause: the geometry here used to
 * be a direct adaptation of vanilla's real {@code PlayerCapeModel#createCapeLayer} cape box
 * ({@code addBox(-5, 0, -1, 10, 16, 1)}) declared against a 16x16 texture -- a reasonable-looking
 * *shape* to copy, but the wrong thing to copy UV from. {@link
 * net.minecraft.client.renderer.blockentity.BannerRenderer#submitPatterns} binds each pattern layer
 * as a banner-pattern sprite from the atlas (sized/laid out for vanilla's own real {@code
 * BannerFlagModel}, confirmed against its decompiled source: a {@code -10,0,-2, 20,40,1} box
 * declared against a <b>64x64</b> texture, {@code texOffs(0,0)}) -- a model baked with different
 * box dimensions *and* a different declared texture size computes completely different UV
 * fractions for the same {@code texOffs}, so every pattern sprite sampled onto this box's faces was
 * reading from the wrong region of itself, stretched ~4x in the process. Player capes were never
 * the relevant reference at all; they're textured from the player skin, a completely different UV
 * convention this renderer never touches.
 *
 * <p>Fixed by copying {@code BannerFlagModel}'s real box + 64x64 texture declaration verbatim (UV-
 * correct by construction, since it's the exact geometry {@code submitPatterns}' sprites are already
 * sized for) and scaling the whole part down via {@link PartPose#withScale} instead of hand-picking
 * new box numbers -- a pure scale-field change doesn't touch UV at all, so correctness survives
 * resizing for a guard's proportions. {@code 0.5F} keeps the flag's real 1:2 width:height ratio (10
 * wide x 20 tall x 0.5 thick), closer to an actual draping cloak than the original's stubbier 10x12
 * box. Pivot/rotation (offset {@code (0, 0, 2)}, 180 degrees about Y) is unchanged -- that part was
 * never the problem, it's genuinely the same placement {@code PlayerCapeModel}'s own cape uses
 * relative to a humanoid body, confirmed against the real decompiled source.
 *
 * <p><b>Real follow-up fix, same day</b> -- the first attempt at the above used {@link
 * PartPose#scaled} instead of {@link PartPose#withScale}, which made the cloak disappear entirely
 * ("Now I see no cape at all"). Confirmed against {@code PartPose}'s own decompiled source:
 * {@code scaled(factor)} multiplies the pivot's {@code x}/{@code y}/{@code z} offset by that same
 * factor <i>in addition to</i> the scale fields -- it's meant for resizing an already-positioned
 * part in place (e.g. a baby mob variant), not for "keep this pivot, just shrink the box." That
 * silently halved the cloak's outward offset from the back (2 -> 1), pulling it close enough to the
 * body/armor geometry to be fully depth-occluded from every normal camera angle -- geometrically
 * still "there," just never winning the depth test. {@code withScale(factor)} only replaces the
 * scale fields, leaving the pivot position exactly as declared.
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
                CubeListBuilder.create().texOffs(0, 0).addBox(-10.0F, 0.0F, -2.0F, 20.0F, 40.0F, 1.0F),
                PartPose.offsetAndRotation(0.0F, 0.0F, 2.0F, 0.0F, (float) Math.PI, 0.0F).withScale(0.5F));
        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(Float phase) {
        super.setupAnim(phase);
        // Same small constant flutter BannerFlagModel's own flag uses -- purely cosmetic, keeps a
        // static cloak from looking like a stiff painted-on cutout.
        this.cloak.xRot = (-0.0125F + 0.01F * Mth.cos((float) (Math.PI * 2) * phase)) * (float) Math.PI;
    }
}
