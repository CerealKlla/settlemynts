package com.github.cerealklla.settlemynts.tavern;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Plain manual save/load for {@link HireRequirement} -- a sealed interface with three different
 * field shapes doesn't fit a single flat {@code RecordCodecBuilder} group cleanly, and this is only
 * ever persisted on one entity ({@link TavernNpcEntity}), so a small hand-written tag+fields helper
 * is simpler than a real {@code Codec<HireRequirement>} nobody else needs.
 */
final class HireRequirementCodec {

    private HireRequirementCodec() {
    }

    static void save(ValueOutput output, HireRequirement requirement) {
        if (requirement instanceof HireRequirement.ItemRequirement item) {
            output.putString("HireKind", "item");
            output.store("HireItemId", Identifier.CODEC, item.itemId());
            output.putInt("HireQuantity", item.quantity());
        } else if (requirement instanceof HireRequirement.GoldRequirement gold) {
            output.putString("HireKind", "gold");
            output.putInt("HireAmount", gold.amount());
        } else if (requirement instanceof HireRequirement.TrialRequirement trial) {
            output.putString("HireKind", "trial");
            output.putInt("HireRequiredKills", trial.requiredKills());
            output.putInt("HireTimeLimitTicks", trial.timeLimitTicks());
        }
    }

    static HireRequirement load(ValueInput input) {
        String kind = input.getStringOr("HireKind", "gold");
        return switch (kind) {
            case "item" -> new HireRequirement.ItemRequirement(
                    input.read("HireItemId", Identifier.CODEC).orElse(Identifier.withDefaultNamespace("gold_nugget")),
                    input.getIntOr("HireQuantity", 1));
            case "trial" -> new HireRequirement.TrialRequirement(
                    input.getIntOr("HireRequiredKills", 3), input.getIntOr("HireTimeLimitTicks", 3600));
            default -> new HireRequirement.GoldRequirement(input.getIntOr("HireAmount", 10));
        };
    }
}
