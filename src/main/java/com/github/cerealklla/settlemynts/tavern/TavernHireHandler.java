package com.github.cerealklla.settlemynts.tavern;

import java.util.UUID;

import com.github.cerealklla.settlemynts.bridge.LyfeTavernBridge;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;
import com.github.cerealklla.settlemynts.registration.ModItems;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Hiring a seated {@link TavernNpcEntity} -- design doc Section 17: "Hiring one of these NPCs will
 * give the player a companion summoning token." Dispatches on the NPC's own rolled {@link
 * HireRequirement}; on success, captures the NPC's current state into a fresh Lyfe Companion Token
 * (via {@link LyfeTavernBridge#mintCompanionToken}) and discards the NPC/clears its stool, exactly
 * mirroring how {@code guardhouse.GuardKitStandInteraction#pickUp} hands a stand's state back as an
 * item then discards the live entity.
 */
public final class TavernHireHandler {

    private TavernHireHandler() {
    }

    /** Right-clicking the seated NPC -- see {@code TavernHireInteractListener}. */
    public static void attemptHire(ServerPlayer player, TavernNpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return;
        }
        HireRequirement requirement = npc.hireRequirement();
        if (requirement == null) {
            return;
        }
        if (requirement instanceof HireRequirement.ItemRequirement item) {
            attemptItemHire(player, level, npc, item);
        } else if (requirement instanceof HireRequirement.GoldRequirement gold) {
            attemptGoldHire(player, level, npc, gold);
        } else if (requirement instanceof HireRequirement.TrialRequirement trial) {
            TavernTrialTracker.onInteract(player, level, npc, trial);
        }
    }

    private static void attemptItemHire(ServerPlayer player, ServerLevel level, TavernNpcEntity npc, HireRequirement.ItemRequirement item) {
        net.minecraft.world.item.Item wanted = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(item.itemId());
        Inventory inventory = player.getInventory();
        int have = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(wanted)) {
                have += stack.getCount();
            }
        }
        if (have < item.quantity()) {
            player.sendSystemMessage(Component.literal(npc.getName().getString() + " wants " + item.describe().getString()
                    + " (you have " + have + "/" + item.quantity() + ")."));
            return;
        }
        int remaining = item.quantity();
        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(wanted)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
        completeHire(player, level, npc);
    }

    private static void attemptGoldHire(ServerPlayer player, ServerLevel level, TavernNpcEntity npc, HireRequirement.GoldRequirement gold) {
        if (!YconomicsShopBridge.isAvailable()) {
            player.sendSystemMessage(Component.literal("No economy is available to pay this NPC's gold price."));
            return;
        }
        if (!YconomicsShopBridge.withdrawNuggets(player, gold.amount())) {
            player.sendSystemMessage(Component.literal(npc.getName().getString() + " wants " + gold.describe().getString()
                    + " (you have " + YconomicsShopBridge.getNuggetBalance(player) + ")."));
            return;
        }
        completeHire(player, level, npc);
    }

    static void completeHire(ServerPlayer player, ServerLevel level, TavernNpcEntity npc) {
        if (LyfeTavernBridge.isLoaded()) {
            LyfeTavernBridge.mintCompanionToken(player, npc, npc.getName().getString());
            player.sendSystemMessage(Component.literal("Hired " + npc.getName().getString() + "! Check your inventory for their Companion Token."));
        } else {
            player.sendSystemMessage(Component.literal("Hired, but no companion-summoning mod is loaded to give you a token."));
        }
        UUID stoolId = npc.stoolId();
        npc.discard();
        if (stoolId != null && level.getEntity(stoolId) instanceof TavernStoolEntity stool) {
            stool.clearSeatedNpcId();
        }
    }

    /** Right-clicking an already-empty stool -- see {@link TavernStoolEntity#interact}. Direct mirror of {@code guardhouse.GuardKitStandInteraction#pickUp}. */
    public static void pickUpStool(ServerPlayer player, ServerLevel level, TavernStoolEntity stool) {
        TavernStoolData data = new TavernStoolData(stool.getUUID(), stool.settlementCoreId(), stool.plotId(), stool.seatedNpcId());
        ItemStack item = new ItemStack(ModItems.TAVERN_STOOL.get());
        item.set(ModItems.TAVERN_STOOL_DATA.get(), data);
        stool.discard();
        TavernStoolIndex.get(level.getServer()).remove(stool.getUUID());
        if (!player.getInventory().add(item)) {
            player.drop(item, false);
        }
    }
}
