package mchorse.bbs_mod.forms.structure;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.block.entity.FuelValues;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeAccess;
import net.minecraft.core.Holder;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.TickRateManager;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Fake {@link World} backing the block entity renderers of a structure form.
 *
 * <p>Block entity renderers (chests, signs, beds, BBS model blocks) call {@link #setWorld} and then
 * query the world for neighbors and light. Feeding them the real {@code mc.world} answers those
 * queries at unrelated real-world coordinates (double chests fail to pair, etc.). This world instead
 * redirects {@link #getBlockState}/{@link #getFluidState}/{@link #getBlockEntity} to the structure
 * data, so neighbor lookups resolve within the structure.</p>
 *
 * <p>Everything else is borrowed from the real client world: the constructor copies its properties,
 * dimension, registries and profiler, and the remaining abstract methods delegate to it (or no-op
 * for mutators that must never touch the real world). Construction needs a live client world for
 * those registries — {@link #create} returns {@code null} otherwise and the caller falls back to
 * {@code mc.world}.</p>
 */
public class StructureWorld extends Level
{
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The fallback below is a silent downgrade in quality, so it is worth saying once — but only once. */
    private static boolean reportedFailure;

    private final ClientLevel delegate;
    private final StructureRenderData data;
    private final Map<BlockPos, BlockEntity> blockEntities;

    private StructureWorld(ClientLevel delegate, StructureRenderData data, Map<BlockPos, BlockEntity> blockEntities)
    {
        super(
            (WritableLevelData) delegate.getLevelData(),
            delegate.dimension(),
            delegate.getRegistryManager(),
            delegate.dimensionTypeRegistration(),
            /* 1.21.11: the profiler supplier left the World constructor — profiling goes through
             * the global Profilers now, so there is nothing to hand down. */
            true,  /* client side */
            false, /* not a debug world */
            0L,    /* biome-zoomer seed; unused, biome comes from the structure view */
            0      /* no chained neighbor updates */
        );

        this.delegate = delegate;
        this.data = data;
        this.blockEntities = blockEntities;
    }

    /** Build a structure-backed world, or {@code null} if there is no client world to borrow from. */
    @Nullable
    public static Level create(StructureRenderData data, Map<BlockPos, BlockEntity> blockEntities)
    {
        ClientLevel world = Minecraft.getInstance().level;

        if (world == null)
        {
            return null;
        }

        try
        {
            return new StructureWorld(world, data, blockEntities);
        }
        catch (Exception e)
        {
            /* Properties not castable / accessor missing on this build. The caller falls back to
             * mc.world, where block entities resolve their neighbours at unrelated coordinates —
             * double chests stop pairing and the like. Worth knowing about when that shows up. */
            if (!reportedFailure)
            {
                reportedFailure = true;

                LOGGER.warn("Couldn't build a structure-backed world, block entities will see the real world instead", e);
            }

            return null;
        }
    }

    /* --- Structure-backed reads --------------------------------------------------------------- */

    @Override
    public BlockState getBlockState(BlockPos pos)
    {
        return this.data.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos)
    {
        return this.data.getBlockState(pos).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos)
    {
        return this.blockEntities.get(pos);
    }

    /* --- Borrowed from the real client world -------------------------------------------------- */

    @Override
    public ChunkSource getChunkManager()
    {
        return this.delegate.getChunkSource();
    }

    @Override
    public LevelTickAccess<net.minecraft.block.Block> getBlockTickScheduler()
    {
        return this.delegate.getBlockTickScheduler();
    }

    @Override
    public LevelTickAccess<net.minecraft.fluid.Fluid> getFluidTickScheduler()
    {
        return this.delegate.getFluidTickScheduler();
    }

    @Override
    public TickRateManager tickRateManager()
    {
        return this.delegate.getTickManager();
    }

    @Override
    public RecipeAccess recipeAccess()
    {
        return this.delegate.getRecipeManager();
    }

    @Override
    public Scoreboard getScoreboard()
    {
        return this.delegate.getScoreboard();
    }

    @Override
    public FeatureFlagSet getEnabledFeatures()
    {
        return this.delegate.getEnabledFeatures();
    }

    @Override
    public float getBrightness(Direction direction, boolean shaded)
    {
        return StructureLighting.getBrightness(direction, shaded);
    }

    @Override
    public int getLightLevel(LightLayer type, BlockPos pos)
    {
        return this.data.getLighting().getLightLevel(type, pos);
    }

    @Override
    public Holder<Biome> getGeneratorStoredBiome(int biomeX, int biomeY, int biomeZ)
    {
        return this.delegate.getGeneratorStoredBiome(biomeX, biomeY, biomeZ);
    }

    @Override
    public List<? extends Player> getPlayers()
    {
        return List.of();
    }

    /* --- Inert: a render-only world never mutates state or resolves entities/maps -------------- */

    @Override
    protected LevelEntityGetter<Entity> getEntities()
    {
        return null;
    }

    @Nullable
    @Override
    public Entity getEntity(int id)
    {
        return null;
    }

    @Nullable
    @Override
    public MapItemSavedData getMapData(MapId id)
    {
        return null;
    }

    /* putMapState and increaseAndGetMapId left World in 1.21.11 — a world that only feeds a
     * structure preview had nothing to say through them anyway. */

    /** Moved down to WorldAccess and takes a plain Entity now, not the excluded PlayerEntity. */
    @Override
    public void syncWorldEvent(@Nullable Entity player, int eventId, BlockPos pos, int data)
    {
    }

    @Override
    public PotionBrewing potionBrewing()
    {
        return this.delegate.getBrewingRecipeRegistry();
    }

    /* Both new abstracts in 1.21.11; the real world's answers serve the preview as well as anything. */

    @Override
    public FuelValues fuelValues()
    {
        return this.delegate.getFuelRegistry();
    }

    @Override
    public EnvironmentAttributeSystem environmentAttributes()
    {
        return this.delegate.environmentAttributes();
    }

    /* Four more abstracts World grew in 1.21.11. A world that only backs a structure preview has
     * nothing of its own to say through any of them: the two spawn ones and the dragon parts come
     * from the real world, and an explosion is simply not something a preview can be asked for. */

    /* Four more that World stopped implementing in 1.21.11 and left to its subclasses. */

    @Override
    public int getSeaLevel()
    {
        return this.delegate.getSeaLevel();
    }

    @Override
    public WorldBorder getWorldBorder()
    {
        return this.delegate.getWorldBorder();
    }

    /** Every chunk of a structure is present by construction — it IS the structure. */
    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ)
    {
        return true;
    }

    /** No entities live in a structure view, so nothing of theirs can be collided with. */
    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB box)
    {
        return List.of();
    }

    @Override
    public Collection<EnderDragonPart> dragonParts()
    {
        return this.delegate.dragonParts();
    }

    @Override
    public LevelData.RespawnData getRespawnData()
    {
        return this.delegate.getSpawnPoint();
    }

    @Override
    public void setRespawnData(LevelData.RespawnData spawnPoint)
    {
    }

    @Override
    public void explode(@Nullable Entity entity, @Nullable DamageSource damageSource, @Nullable ExplosionDamageCalculator behavior, double x, double y, double z, float power, boolean createFire, Level.ExplosionInteraction explosionSourceType, ParticleOptions smallParticle, ParticleOptions largeParticle, WeightedList<ExplosionParticleInfo> blockParticles, Holder<SoundEvent> sound)
    {
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags)
    {
    }

    @Override
    public void destroyBlockProgress(int entityId, BlockPos pos, int progress)
    {
    }

    /* Both take a plain Entity as the excluded listener since 1.21.11, not a PlayerEntity. */

    @Override
    public void playSeededSound(@Nullable Entity except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource category, float volume, float pitch, long seed)
    {
    }

    @Override
    public void playSeededSound(@Nullable Entity except, Entity entity, Holder<SoundEvent> sound, SoundSource category, float volume, float pitch, long seed)
    {
    }

    @Override
    public void emitGameEvent(Holder<GameEvent> event, Vec3 emitterPos, GameEvent.Context emitter)
    {
    }

    @Override
    public String gatherChunkSourceStats()
    {
        return "StructureWorld";
    }
}
