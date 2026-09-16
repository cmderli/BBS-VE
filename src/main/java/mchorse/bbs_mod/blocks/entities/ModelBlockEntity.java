package mchorse.bbs_mod.blocks.entities;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.blocks.ModelBlock;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.api.events.ModelBlockEntityUpdateCallback;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.forms.Form;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public class ModelBlockEntity extends BlockEntity
{
    private ModelProperties properties = new ModelProperties();
    private IEntity entity = new StubEntity();

    private float lastYaw = Float.NaN;
    private float currentYaw = Float.NaN;

    public ModelBlockEntity(BlockPos pos, BlockState state)
    {
        super(BBSMod.MODEL_BLOCK_ENTITY, pos, state);
    }

    public String getName()
    {
        BlockPos pos = this.getBlockPos();
        Form form = this.getProperties().getForm();
        String s = "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";

        if (form != null)
        {
            s += " " + form.getDisplayName();
        }

        return s;
    }

    public ModelProperties getProperties()
    {
        return this.properties;
    }

    public IEntity getEntity()
    {
        return this.entity;
    }

    public void setLookYaw(float yaw)
    {
        this.lastYaw = yaw;
        this.currentYaw = yaw;
    }

    public float updateLookYawContinuous(float yaw)
    {
        if (Float.isNaN(this.currentYaw))
        {
            this.setLookYaw(yaw);

            return this.currentYaw;
        }

        float diff = yaw - this.lastYaw;

        while (diff > Math.PI) diff -= (float) (Math.PI * 2);
        while (diff < -Math.PI) diff += (float) (Math.PI * 2);

        this.currentYaw += diff;
        this.lastYaw = yaw;

        return this.currentYaw;
    }

    public void resetLookYaw()
    {
        this.lastYaw = this.currentYaw = Float.NaN;
    }

    public void snapLookYawToBase(float lastYaw, float currentYaw)
    {
        this.lastYaw = lastYaw;
        this.currentYaw = currentYaw;
    }

    public void tick(Level world, BlockPos pos, BlockState state)
    {
        ModelBlockEntityUpdateCallback.EVENT.invoker().update(this);

        this.properties.getEquipment().apply(this.entity);

        this.entity.update();
        this.entity.setWorld(world);
        this.properties.update(this.entity);
    }

    /**
     * The block's physical shape, built from the body settings against the
     * current form and transform.
     */
    public VoxelShape getShape()
    {
        return this.properties.getBody().buildShape(this.properties.getForm(), this.properties.getTransform());
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket()
    {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registryLookup)
    {
        return this.saveWithoutMetadata(registryLookup);
    }

    @Override
    protected void saveAdditional(ValueOutput view)
    {
        super.saveAdditional(view);

        MapType data = this.properties.toData();
        CompoundTag nbt = new CompoundTag();

        DataStorageUtils.writeToNbtCompound(nbt, "Properties", data);

        view.store("Properties", CompoundTag.CODEC, nbt.getCompoundOrEmpty("Properties"));
    }

    @Override
    protected void loadAdditional(ValueInput view)
    {
        super.loadAdditional(view);

        CompoundTag nbt = new CompoundTag();

        view.read("Properties", CompoundTag.CODEC).ifPresent((compound) -> nbt.store("Properties", compound));

        this.readProperties(nbt);
    }

    /**
     * Read the properties out of a raw block-entity NBT compound — the shape {@link #writeData(WriteView)}
     * produces, and the shape the item's {@code BLOCK_ENTITY_DATA} component carries. The item renderer
     * hydrates its off-world entity through here: {@link #readData(ReadView)} is protected, and the
     * component hands out plain NBT rather than a {@link ReadView}.
     */
    public void readProperties(CompoundTag nbt)
    {
        BaseType baseType = DataStorageUtils.readFromNbtCompound(nbt, "Properties");

        if (baseType instanceof MapType mapType)
        {
            this.properties.fromData(mapType);
        }
    }

    public void updateForm(MapType data, Level world)
    {
        this.properties.fromData(data);

        BlockPos pos = this.getBlockPos();

        /* Light and sound live in the block STATE (the engine reads them from
         * there), but their source of truth is the body data — mirror it. */
        if (!world.isClientSide())
        {
            ModelBlock.mirrorBlockState(world, pos);
        }

        BlockState blockState = world.getBlockState(pos);

        world.sendBlockUpdated(pos, blockState, blockState, Block.UPDATE_CLIENTS);
        world.blockEntityChanged(pos);
    }
}