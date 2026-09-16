package mchorse.bbs_mod.client.renderer.item;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Holder for the model-block item's per-stack render state (properties + form entity +
 * expiration). Rendering itself lives in {@link ModelBlockSpecialRenderer} (1.21.5+ item-model
 * system); this class keeps the data lifecycle (update ticking, stack lookup, block-entity
 * data hydration) that the renderer and the model-block editor share.
 */
public class ModelBlockItemRenderer
{
    private Map<ItemStack, Item> map = new HashMap<>();

    public void update()
    {
        Iterator<Item> it = this.map.values().iterator();

        while (it.hasNext())
        {
            Item item = it.next();

            if (item.expiration <= 0)
            {
                it.remove();
            }

            item.expiration -= 1;
            item.entity.getProperties().update(item.formEntity);
            item.formEntity.update();
        }
    }

    public Item get(ItemStack stack)
    {
        if (stack == null || stack.getItem() != BBSMod.MODEL_BLOCK_ITEM)
        {
            return null;
        }

        if (this.map.containsKey(stack))
        {
            return this.map.get(stack);
        }

        ModelBlockEntity entity = new ModelBlockEntity(BlockPos.ZERO, BBSMod.MODEL_BLOCK.defaultBlockState());
        Item item = new Item(entity);

        this.map.put(stack, item);

        TypedEntityData<?> beComponent = stack.get(DataComponents.BLOCK_ENTITY_DATA);

        if (beComponent == null)
        {
            return item;
        }

        entity.readProperties(beComponent.copyTagWithoutId());

        return item;
    }

    public static class Item
    {
        public ModelBlockEntity entity;
        public IEntity formEntity;
        public int expiration = 20;

        public Item(ModelBlockEntity entity)
        {
            this.entity = entity;
            this.formEntity = new StubEntity(Minecraft.getInstance().level);
        }
    }
}