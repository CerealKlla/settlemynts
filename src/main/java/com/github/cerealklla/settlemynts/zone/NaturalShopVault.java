package com.github.cerealklla.settlemynts.zone;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.box.BoxAttachments;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Creates and resolves each natural settlement's per-zone-type underground shop vault -- a real
 * vanilla Chest, explicit user request 2026-10-08 ("a real, physical, vanilla chest. so all the logic
 * regarding reading chests for their inventories can still work as designed"). Tagged as a
 * Cartographyr "Box" exactly the way a player-placed one would be (see {@code
 * box.BoxPlacementListener}, whose two lines this replicates) so the *existing* box-scanning stock
 * code works completely unmodified -- the only change needed elsewhere is {@code
 * api.Settlemynts#resolvePlotBoxes} falling back here when a plot has no player-founded settlement
 * polygon to scan (i.e. it's a natural-village plot).
 *
 * <p>Placed 2 blocks below the surface near the first plot of that zone type to ask for one, and
 * tracked in {@link NaturalShopVaultIndex} so {@link NaturalShopVaultProtectionListener} can block
 * both breaking and opening it -- a player who somehow digs down and finds it can't interact with it
 * at all, despite it being an entirely ordinary Chest block otherwise.
 */
public final class NaturalShopVault {

    private NaturalShopVault() {
    }

    /**
     * Returns the existing vault for this (settlement, zone type), or creates one near {@code
     * nearAnchor} if none exists yet. {@code nearAnchor}'s own Y is used as the floor/ground
     * reference directly -- callers must pass a position already known to sit at or near actual
     * ground level (a structure piece's {@code BoundingBox#minY()}, or a standing villager's own
     * feet position), **not** a heightmap query. A real bug found live, 2026-10-08: {@code
     * Heightmap.Types.MOTION_BLOCKING_NO_LEAVES} at a column still covered by the building's own roof
     * returns the roof's height, not the terrain underneath it, so a vault placed "3 below the
     * heightmap" actually landed partway up the building's own wall, not underground at all.
     */
    static BlockPos ensureVault(ServerLevel level, SettlementKey settlement, Identifier zoneTypeId, BlockPos nearAnchor) {
        NaturalShopVaultIndex index = NaturalShopVaultIndex.get(level.getServer());
        Optional<BlockPos> existing = index.get(settlement, zoneTypeId);
        if (existing.isPresent()) {
            return existing.get();
        }

        int vaultY = Math.max(level.getMinY() + 1, nearAnchor.getY() - 3);
        BlockPos vaultPos = new BlockPos(nearAnchor.getX(), vaultY, nearAnchor.getZ());
        level.setBlock(vaultPos, Blocks.CHEST.defaultBlockState(), 3);

        if (level.getBlockEntity(vaultPos) instanceof BlockEntity blockEntity && blockEntity instanceof Container) {
            UUID boxId = UUID.randomUUID();
            blockEntity.setData(BoxAttachments.BOX_ID, boxId);
            Cartography.registerBox(level, vaultPos, boxId);
        }

        index.put(settlement, zoneTypeId, vaultPos);
        return vaultPos;
    }

    /** The vault container for {@code plotId}'s own (settlement, zone type), if {@code plotId} resolves to a natural-village plot with one. Empty otherwise (including for a player-founded plot -- that's still resolved the normal polygon-scan way by the caller). */
    public static List<Container> resolveBoxesForPlot(ServerLevel level, UUID plotId) {
        return NaturalSettlementPlotStore.get(level.getServer()).findByPlotId(plotId)
                .flatMap(entry -> resolveVaultContainer(level, entry.getKey(), entry.getValue().zoneTypeId()))
                .map(List::of)
                .orElse(List.of());
    }

    private static Optional<Container> resolveVaultContainer(ServerLevel level, SettlementKey settlement, Identifier zoneTypeId) {
        return NaturalShopVaultIndex.get(level.getServer()).get(settlement, zoneTypeId)
                .flatMap(pos -> level.getBlockEntity(pos) instanceof Container container ? Optional.of(container) : Optional.empty());
    }
}
