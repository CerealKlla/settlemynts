package com.github.cerealklla.settlemynts.tavern;

import java.util.UUID;

import com.github.cerealklla.settlemynts.bridge.LyfeTavernBridge;
import com.github.cerealklla.settlemynts.bridge.MyssionsQuestBridge;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;
import com.github.cerealklla.settlemynts.registration.ModItems;

import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
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
 *
 * <p><b>Trial routed through a real Myssions quest, 2026-10-31 correction</b> -- the first pass built
 * a bespoke, Settlemynts-local kill-counter/timer ({@code TavernTrialTracker}, deleted), reasoning at
 * the time that Myssions had "no primitive for an NPC to originate an ad-hoc quest tied to one
 * specific interaction." That was wrong: Myssions already had exactly this --
 * {@code quest.QuestCompletionHooks}/{@code api.Myssions#assignQuest} let any mod register a real
 * quest definition and run its own code the instant it's won, and {@code quest.BuyItemTargets}
 * already established the "side table for per-instance data a capped {@code QuestInstance} can't
 * hold" pattern this now reuses too (here: {@link TavernTrialInstances}, instanceId -> which NPC).
 * {@code bridge.MyssionsQuestBridge} registers 5 real "Tavern Hiring Trial" definitions (one per
 * tier, since a kill milestone's required count is fixed per-definition) using the new {@code
 * QuestMilestone#killAnyHostile} (added the same day -- no existing kind matched "any hostile mob,"
 * only one specific entity type). This NPC is the Trial's subject, not a reusable quest-giver --
 * {@link #attemptHire} starts it directly via {@code assignQuest}, no board/NPC-offer step at all --
 * and the player gets Myssions' own real Quest Log/Active-Quest-HUD tracking for free, something the
 * old bespoke tracker never had.
 */
public final class TavernHireHandler {

    private TavernHireHandler() {
    }

    /** The action button on {@code client.TavernHireScreen} -- see {@link TavernHireAcceptPayload}'s own doc. Re-resolves the NPC fresh by entity id rather than trusting anything the client sent about it. */
    public static void attemptHire(ServerPlayer player, int npcEntityId) {
        if (!(player.level() instanceof ServerLevel level) || !(level.getEntity(npcEntityId) instanceof TavernNpcEntity npc)) {
            player.sendSystemMessage(Component.literal("That NPC is no longer available."));
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
            attemptStartTrial(player, npc, trial);
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

    private static void attemptStartTrial(ServerPlayer player, TavernNpcEntity npc, HireRequirement.TrialRequirement trial) {
        if (!MyssionsQuestBridge.isLoaded()) {
            player.sendSystemMessage(Component.literal("No quest system is available to run this Trial."));
            return;
        }
        if (MyssionsQuestBridge.hasActiveTavernTrial(player, npc.tier())) {
            player.sendSystemMessage(Component.literal("You already have a matching Trial in progress."));
            return;
        }
        var instanceId = MyssionsQuestBridge.startTavernTrial(player, npc.tier());
        if (instanceId.isEmpty()) {
            player.sendSystemMessage(Component.literal("You can't start another Trial right now."));
            return;
        }
        TavernTrialInstances.get(player.level().getServer()).put(instanceId.get(), npc.getUUID());
        player.sendSystemMessage(Component.literal("Trial started: " + trial.describe().getString()
                + " -- see your Quest Log to track it."));
    }

    /** {@code bridge.MyssionsQuestBridge}'s completion hook -- fired the instant any Tavern Trial is won. */
    public static void onTrialWon(ServerPlayer player, UUID instanceId) {
        MinecraftServer server = player.level().getServer();
        UUID npcId = TavernTrialInstances.get(server).remove(instanceId).orElse(null);
        if (npcId == null) {
            return;
        }
        for (ServerLevel candidate : server.getAllLevels()) {
            if (candidate.getEntity(npcId) instanceof TavernNpcEntity npc) {
                completeHire(player, candidate, npc);
                return;
            }
        }
        // The NPC was hired by someone else, or despawned, before this Trial finished -- expected,
        // not an error (user's own explicit spec: "if someone else buys the NPC before you, you
        // were too slow").
        player.sendSystemMessage(Component.literal("That NPC is no longer available -- someone else must have hired them first."));
    }

    static void completeHire(ServerPlayer player, ServerLevel level, TavernNpcEntity npc) {
        if (LyfeTavernBridge.isLoaded()) {
            LyfeTavernBridge.mintCompanionToken(player, npc, npc.getName().getString());
            player.sendSystemMessage(Component.literal("Hired " + npc.getName().getString() + "! Check your inventory for their Companion Token."));
        } else {
            player.sendSystemMessage(Component.literal("Hired, but no companion-summoning mod is loaded to give you a token."));
        }
        GlobalPos stoolPos = npc.stoolPos();
        npc.discard();
        if (stoolPos != null) {
            TavernStoolIndex.get(level.getServer()).clearSeatedNpc(stoolPos);
        }
    }

    /**
     * Sneak + right-clicking an indexed stool -- see {@link TavernStoolPickupListener}. Direct mirror
     * of {@code guardhouse.GuardKitStandInteraction#pickUp}, except for one real gap caught before
     * ever shipping: {@link TavernStoolPickupListener} was widened to allow picking up a still-occupied
     * stool (see its own doc), but this method never actually discarded the seated NPC -- it would
     * have just removed the stool block/index entry and handed the item back while leaving the NPC
     * alive and orphaned in midair where the stool used to be. Now discards any seated
     * {@link TavernNpcEntity} outright first (same no-token, no-payment "this is furniture" reasoning
     * as the listener's own doc).
     */
    public static void pickUpStool(ServerPlayer player, ServerLevel level, GlobalPos stoolPos) {
        TavernStoolIndex index = TavernStoolIndex.get(level.getServer());
        TavernStoolIndex.StoolInfo info = index.get(stoolPos).orElse(null);
        if (info == null) {
            return;
        }
        if (info.seatedNpcId().isPresent() && level.getEntity(info.seatedNpcId().get()) instanceof TavernNpcEntity seated) {
            seated.discard();
        }
        ItemStack item = new ItemStack(ModItems.TAVERN_STOOL.get());
        item.set(ModItems.TAVERN_STOOL_DATA.get(), new TavernStoolData(info.settlementCoreId(), info.plotId()));
        LyfeTavernBridge.removeStool(level, stoolPos.pos());
        index.remove(stoolPos);
        if (!player.getInventory().add(item)) {
            player.drop(item, false);
        }
    }
}
