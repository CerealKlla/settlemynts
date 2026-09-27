package com.github.cerealklla.settlemynts.founding;

import java.util.Optional;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.Classification;
import com.github.cerealklla.cartographyr.geo.EntityDefinition;
import com.github.cerealklla.cartographyr.geo.EntityType;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.cartographyr.geo.Layer;
import com.github.cerealklla.cartographyr.geo.LifecycleState;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The Settlement Claim Flag (design doc Section 5) -- placed on the ground to found a new
 * settlement. Reuses vanilla's own flag-like icon (a plain banner) with {@code
 * DataComponents.ENCHANTMENT_GLINT_OVERRIDE} for the "glow purple like enchanted" look (see {@code
 * registration.ModItems}), same no-new-art treatment as Yconomics' Coin Purse.
 *
 * <p>Placement first checks {@link SettlementFounding#isFarEnoughFromExistingSettlements} --
 * blocked with an error message if too close to any existing settlement (natural or
 * player-founded). On success, clears a 5x5 construction site and spawns a {@link
 * GhostTownHallCoreEntity} at its center, visible only to the founder for now (Section 6's
 * permission-granting UI is a later milestone). Consumes the flag on success.
 *
 * <p><b>Also registers a placeholder Cartographyr entity immediately, at {@code
 * LifecycleState.PLANNED}</b> (fixed 2026-09-26, a real playtest-found bug -- see decisions.md):
 * before this, an unfinalized settlement's only record of its own existence was its live {@code
 * GhostTownHallCoreEntity}, and {@link SettlementFounding#findNearestSettlement} could only find it
 * by scanning currently-*loaded* entities. If that settlement's chunk happened to be unloaded (e.g.
 * the founder had walked far away to test a different spot), the distance check found nothing for
 * it at all and silently allowed placement arbitrarily close. Cartographyr's own registry is
 * persistent regardless of chunk-load state, so registering here (not just at finalization)
 * closes that gap entirely -- {@code SettlemyntsMod#registerWithCartographyr} then updates this
 * same entity in place at finalization rather than creating a duplicate.
 */
public class SettlementClaimFlagItem extends Item {

    public SettlementClaimFlagItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            // Client-side prediction: let the swing animation play, real work happens server-side.
            return InteractionResult.SUCCESS;
        }
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.FAIL;
        }

        BlockPos sitePos = context.getClickedPos().above();
        Optional<SettlementFounding.NearestSettlement> nearest =
                SettlementFounding.findNearestSettlement(serverLevel, sitePos.getX(), sitePos.getZ());
        if (nearest.isPresent() && nearest.get().distanceBlocks() < SettlementFounding.MIN_DISTANCE_BLOCKS) {
            SettlementFounding.NearestSettlement settlement = nearest.get();
            player.sendSystemMessage(Component.literal(
                    "Too close to another settlement -- the nearest one is "
                            + Math.round(settlement.distanceBlocks()) + " blocks (~"
                            + Math.round(SettlementFounding.blocksToFeet(settlement.distanceBlocks())) + " feet) to the "
                            + settlement.compassDirection() + ". Must be at least "
                            + SettlementFounding.MIN_DISTANCE_BLOCKS + " blocks (~"
                            + (int) SettlementFounding.MIN_DISTANCE_FEET + " feet) away."));
            return InteractionResult.FAIL;
        }

        SettlementFounding.clearConstructionSite(serverLevel, sitePos);
        GhostTownHallCoreEntity core = GhostTownHallCoreEntity.create(
                serverLevel, sitePos.getX() + 0.5, sitePos.getY(), sitePos.getZ() + 0.5, player.getUUID());

        GeographicEntity registered = Cartography.createEntity(serverLevel, new EntityDefinition(
                serverLevel.dimension(),
                Classification.CONSTRUCTED,
                EntityType.SETTLEMENT,
                Layer.SETTLEMENT_ID,
                Optional.empty(),
                new Geometry.Point(sitePos.getX(), sitePos.getZ()),
                LifecycleState.PLANNED,
                Optional.empty()));
        core.setCartographyrEntityId(registered.id().value());

        context.getItemInHand().shrink(1);
        return InteractionResult.SUCCESS_SERVER;
    }
}
