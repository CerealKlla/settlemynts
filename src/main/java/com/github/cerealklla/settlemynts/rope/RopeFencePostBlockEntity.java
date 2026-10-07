package com.github.cerealklla.settlemynts.rope;

import com.github.cerealklla.settlemynts.registration.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A real, player-placed Rope Fence Post's live state -- at most two rope links to other posts
 * ({@link #linkA}/{@link #linkB}), each a {@link BlockPos} of another {@link RopeFencePostBlock}.
 * "Open" (fewer than 2 links) is what {@code RopeFencePostItem} checks to decide whether
 * right-clicking this post can resume/extend construction from it -- see that class's own doc.
 */
public class RopeFencePostBlockEntity extends BlockEntity {

    private BlockPos linkA;
    private BlockPos linkB;

    public RopeFencePostBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ROPE_FENCE_POST.get(), pos, state);
    }

    public boolean hasOpenSlot() {
        return linkA == null || linkB == null;
    }

    /** No-op if both slots are already taken -- callers should check {@link #hasOpenSlot()} first. */
    public void addLink(BlockPos other) {
        if (linkA == null) {
            linkA = other;
        } else if (linkB == null) {
            linkB = other;
        }
        setChanged();
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        linkA = input.read("LinkA", BlockPos.CODEC).orElse(null);
        linkB = input.read("LinkB", BlockPos.CODEC).orElse(null);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("LinkA", BlockPos.CODEC, linkA);
        output.storeNullable("LinkB", BlockPos.CODEC, linkB);
    }
}
