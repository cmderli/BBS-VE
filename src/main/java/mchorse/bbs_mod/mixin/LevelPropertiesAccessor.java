package mchorse.bbs_mod.mixin;

import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PrimaryLevelData.class)
public interface LevelPropertiesAccessor
{
    /* Yarn called this field levelInfo, which is where the bbs$setLevelInfo method name comes from.
     * 26.2 calls the field settings. */
    @Accessor("settings")
    public void bbs$setLevelInfo(LevelSettings info);
}