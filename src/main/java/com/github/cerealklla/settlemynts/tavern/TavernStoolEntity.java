package com.github.cerealklla.settlemynts.tavern;

import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.settlemynts.founding.GhostBlockDisplays;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * The Tavern Stool -- design doc Section 17's "mechanism similar to GuardHouse to get special
 * stools... targeted spawn locations for new NPCs." Direct structural analog to {@code
 * guardhouse.GuardKitStandEntity} (a real, player-placed/picked-up marker, resuming its exact
 * identity when re-placed via {@link TavernStoolItem}), but a plain {@link Display.BlockDisplay}
 * (same real-block-model technique {@code zone.GhostPlotStakeEntity}/{@code roadway.RoadwayStakeEntity}
 * already use) instead of an {@code ArmorStand} -- there's no humanoid "empty" visual to show the way
 * an unassigned Guard Kit Stand has; the interesting visual here is always the seated {@link
 * TavernNpcEntity} itself, not the stool. {@code Blocks.OAK_SLAB} is a deliberate placeholder "seat"
 * visual -- no real stool block/model exists anywhere in this suite yet, same flagged-placeholder
 * footing as {@code GuardRenderer}'s own reskinned-Zombie guard model.
 *
 * <p><b>Deliberate difference from Guard Kit Stand</b>: an unhired Tavern NPC's stool seats a REAL,
 * already-spawned, already-visible NPC waiting to be hired -- not an empty stand awaiting
 * configuration. {@link #seatedNpcId} is set by {@link TavernDailySpawnTicker} once it spawns a
 * fresh {@link TavernNpcEntity} here, and cleared again once that NPC is hired or (if never hired)
 * despawned at the next daily cycle -- either way, an empty stool is simply refilled on the next
 * pass, up to {@link TavernConstants#stoolCountForTier}.
 */
public class TavernStoolEntity extends Display.BlockDisplay {

    private UUID settlementCoreId;
    private UUID plotId;
    private Optional<UUID> seatedNpcId = Optional.empty();

    public TavernStoolEntity(EntityType<? extends TavernStoolEntity> type, Level level) {
        super(type, level);
        GhostBlockDisplays.setBlockState(this, Blocks.OAK_SLAB.defaultBlockState());
    }

    public void setPlotIdentity(UUID settlementCoreId, UUID plotId) {
        this.settlementCoreId = settlementCoreId;
        this.plotId = plotId;
    }

    public UUID settlementCoreId() {
        return settlementCoreId;
    }

    public UUID plotId() {
        return plotId;
    }

    public Optional<UUID> seatedNpcId() {
        return seatedNpcId;
    }

    public void setSeatedNpcId(UUID id) {
        seatedNpcId = Optional.of(id);
    }

    public void clearSeatedNpcId() {
        seatedNpcId = Optional.empty();
    }

    /**
     * Only reachable when the stool itself is clicked directly (an occupied stool's seated NPC sits
     * right on top and is its own separate, clickable entity -- see {@link TavernHireInteractListener}
     * for the real hire interaction). An empty stool just offers "Pick Up," same as a vacated Guard
     * Kit Stand.
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(level() instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS_SERVER;
        }
        if (seatedNpcId.isPresent()) {
            serverPlayer.sendSystemMessage(Component.literal("Right-click the seated NPC to hire them."));
            return InteractionResult.SUCCESS_SERVER;
        }
        TavernHireHandler.pickUpStool(serverPlayer, serverLevel, this);
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("SettlementCoreId", UUIDUtil.CODEC, settlementCoreId);
        output.storeNullable("PlotId", UUIDUtil.CODEC, plotId);
        output.storeNullable("SeatedNpcId", UUIDUtil.CODEC, seatedNpcId.orElse(null));
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        settlementCoreId = input.read("SettlementCoreId", UUIDUtil.CODEC).orElse(null);
        plotId = input.read("PlotId", UUIDUtil.CODEC).orElse(null);
        seatedNpcId = input.read("SeatedNpcId", UUIDUtil.CODEC);
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (level() instanceof ServerLevel serverLevel) {
            TavernStoolIndex.get(serverLevel.getServer()).put(getUUID(), net.minecraft.core.GlobalPos.of(serverLevel.dimension(), blockPosition()));
        }
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        if (level() instanceof ServerLevel serverLevel) {
            TavernStoolIndex.get(serverLevel.getServer()).remove(getUUID());
        }
    }
}
