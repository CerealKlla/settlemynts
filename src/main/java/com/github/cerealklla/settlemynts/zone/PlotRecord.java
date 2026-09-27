package com.github.cerealklla.settlemynts.zone;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;

/**
 * A finalized plot, persisted directly on the owning {@code founding.GhostTownHallCoreEntity} --
 * design doc Section 11a. {@code plotId} is the same id the stakes were placed under while the
 * plot was still in progress ({@link PlotSessionData#plotSessionId()}), reused as the plot's
 * permanent identity rather than minting a second one.
 *
 * <p>Stores **both** of the plot's Cartographyr entity ids (the real "plot" entity and its "Town
 * Proper" buffer entity) -- needed so a future plot-destruction feature can retire both, per the
 * requirement flagged in Cartographyr's own decisions.md, 2026-09-26 ("bake in an ID so we can say
 * 'plot X is being removed, remove it from the Settlement layer, and also remove it from the
 * settlement buffer layer'"). Destruction itself isn't implemented yet.
 */
public record PlotRecord(UUID plotId, String name, Identifier zoneTypeId, long cartographyrPlotEntityId, long cartographyrBufferEntityId) {

    public static final Codec<PlotRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("plot_id").forGetter(PlotRecord::plotId),
            Codec.STRING.fieldOf("name").forGetter(PlotRecord::name),
            Identifier.CODEC.fieldOf("zone_type_id").forGetter(PlotRecord::zoneTypeId),
            Codec.LONG.fieldOf("cartographyr_plot_entity_id").forGetter(PlotRecord::cartographyrPlotEntityId),
            Codec.LONG.fieldOf("cartographyr_buffer_entity_id").forGetter(PlotRecord::cartographyrBufferEntityId)
    ).apply(i, PlotRecord::new));
}
