package com.github.cerealklla.settlemynts.guardhouse;

/**
 * Implemented by any plot structure "Defense Spending" (the Town Hall Core's own screen, see
 * {@code SettlemyntsMod#requestDefenseSpending}/{@code #setDefenseSpending}) can fund at midnight --
 * originally Guardhouse-only, generalized 2026-10-22 when Watch Tower became eligible too (explicit
 * user request) so a Town Planner can top up its own Coal/Charcoal supply via the existing Planned
 * Inventory midnight-trading engine. See {@link DefenseSpendingTransfer#resolveRecipient} for how a
 * {@code PlotRecord} resolves to one of these.
 */
public interface DefenseSpendingRecipient {

    int defenseSpendingAllocation();

    void setDefenseSpendingAllocation(int allocation);
}
