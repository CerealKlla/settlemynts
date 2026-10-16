package com.github.cerealklla.settlemynts.guardhouse;

import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.kyt.loadout.LoadoutRecord;
import com.github.cerealklla.settlemynts.bridge.KytGuardhouseBridge;

import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * The Guard Kit Stand -- Guard Kit Stand overhaul (explicit user spec, 2026-10-16): "Custom
 * structure identical in visuals to the existing armor stand. Right clicking one of these opens the
 * Kit Menu... The selected armor will be visually mounted on the armor stand. This structure will
 * directly be tied to a single spawned Guard NPC via UUID." Literally {@code extends ArmorStand} (a
 * real vanilla entity, not a lookalike) so the visuals are genuinely identical with zero new render
 * code -- its own {@code EntityType} only exists so {@link #interact} can be overridden to open the
 * Kit Menu instead of vanilla's own pose/equip interaction. Replaces the old {@code GarrisonSlot}
 * abstraction entirely: this entity's own identity (its real {@link #getUUID()}) *is* the "slot," so
 * no separate stand-id field is needed the way {@code GuardhouseBlockEntity}'s old per-index
 * {@code garrisonGuardIds} list needed one.
 *
 * <p>Persisted fields mirror {@link GuardEntity}'s own {@code settlementCoreId}/{@code plotId}
 * save/load exactly (same identity/permission-check shape every other plot structure in this mod
 * uses). {@link #boundKytName} re-equips this stand's visual armor (helmet/chest/legs/boots/offhand
 * only, matching {@code loadout.client.LoadoutEditorScreen}'s own mannequin preview convention -- no
 * mainhand/weapon display on the stand itself) any time it changes. {@link #assignedGuardId} is the
 * currently-alive {@link GuardEntity} spawned from this stand, if any; {@link
 * #respawnReadyAtGameTime} is when {@code GuardSpawnTicker} is next allowed to spawn a replacement
 * (set by {@link GuardEntity#die} via {@link GuardRespawnFunding}).
 */
public class GuardKitStandEntity extends ArmorStand {

    private UUID settlementCoreId;
    private UUID plotId;
    private Optional<String> boundKytName = Optional.empty();
    private Optional<UUID> assignedGuardId = Optional.empty();
    private long respawnReadyAtGameTime;

    public GuardKitStandEntity(EntityType<? extends GuardKitStandEntity> type, Level level) {
        super(type, level);
        setInvulnerable(true);
        setNoGravity(false);
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

    public Optional<String> boundKytName() {
        return boundKytName;
    }

    /** "Select Kyt" on the Kit Menu -- {@code name} blank/empty clears the binding. Re-equips the visual preview immediately. */
    public void setBoundKytName(String name) {
        boundKytName = name == null || name.isBlank() ? Optional.empty() : Optional.of(name);
        refreshVisualEquipment();
    }

    public Optional<UUID> assignedGuardId() {
        return assignedGuardId;
    }

    /** Called by {@code GuardSpawnTicker} right after it spawns a fresh guard from this stand. */
    public void setAssignedGuardId(UUID id) {
        assignedGuardId = Optional.of(id);
    }

    /** Called by {@link GuardEntity#die} once its own assigned stand is known dead. */
    public void clearAssignedGuardId() {
        assignedGuardId = Optional.empty();
    }

    public long respawnReadyAtGameTime() {
        return respawnReadyAtGameTime;
    }

    public void setRespawnReadyAtGameTime(long gameTime) {
        respawnReadyAtGameTime = gameTime;
    }

    /** 4 armor slots + offhand, matching {@code LoadoutEditorScreen}'s own mannequin preview -- no mainhand/weapon shown on the stand itself. */
    public void refreshVisualEquipment() {
        LoadoutRecord record = boundKytName.flatMap(KytGuardhouseBridge::getKyt).orElse(null);
        if (record == null) {
            setItemSlot(EquipmentSlot.HEAD, net.minecraft.world.item.ItemStack.EMPTY);
            setItemSlot(EquipmentSlot.CHEST, net.minecraft.world.item.ItemStack.EMPTY);
            setItemSlot(EquipmentSlot.LEGS, net.minecraft.world.item.ItemStack.EMPTY);
            setItemSlot(EquipmentSlot.FEET, net.minecraft.world.item.ItemStack.EMPTY);
            setItemSlot(EquipmentSlot.OFFHAND, net.minecraft.world.item.ItemStack.EMPTY);
            return;
        }
        setItemSlot(EquipmentSlot.HEAD, record.helmet().copy());
        setItemSlot(EquipmentSlot.CHEST, record.chestplate().copy());
        setItemSlot(EquipmentSlot.LEGS, record.leggings().copy());
        setItemSlot(EquipmentSlot.FEET, record.boots().copy());
        setItemSlot(EquipmentSlot.OFFHAND, record.offhand().copy());
    }

    /** Opens the Kit Menu instead of vanilla's own armor-stand pose/equip interaction. */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (player instanceof ServerPlayer serverPlayer) {
            GuardKitStandInteraction.open(serverPlayer, this);
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("SettlementCoreId", UUIDUtil.CODEC, settlementCoreId);
        output.storeNullable("PlotId", UUIDUtil.CODEC, plotId);
        output.storeNullable("BoundKytName", com.mojang.serialization.Codec.STRING, boundKytName.orElse(null));
        output.storeNullable("AssignedGuardId", UUIDUtil.CODEC, assignedGuardId.orElse(null));
        output.putLong("RespawnReadyAtGameTime", respawnReadyAtGameTime);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        settlementCoreId = input.read("SettlementCoreId", UUIDUtil.CODEC).orElse(null);
        plotId = input.read("PlotId", UUIDUtil.CODEC).orElse(null);
        boundKytName = input.read("BoundKytName", com.mojang.serialization.Codec.STRING);
        assignedGuardId = input.read("AssignedGuardId", UUIDUtil.CODEC);
        respawnReadyAtGameTime = input.getLongOr("RespawnReadyAtGameTime", 0L);
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (level() instanceof ServerLevel serverLevel) {
            GuardKitStandIndex.get(serverLevel.getServer()).put(getUUID(), net.minecraft.core.GlobalPos.of(serverLevel.dimension(), blockPosition()));
        }
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        if (level() instanceof ServerLevel serverLevel) {
            GuardKitStandIndex.get(serverLevel.getServer()).remove(getUUID());
        }
    }
}
