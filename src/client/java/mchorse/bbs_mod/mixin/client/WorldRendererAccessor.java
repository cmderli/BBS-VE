package mchorse.bbs_mod.mixin.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.SortedSet;

@Mixin(ClientLevel.class)
public interface WorldRendererAccessor
{
    @Accessor("destructionProgress")
    public Long2ObjectMap<SortedSet<BlockDestructionProgress>> bbs$getBlockBreakingProgressions();
}
