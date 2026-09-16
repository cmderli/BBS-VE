package mchorse.bbs_mod.client.renderer.item;

import com.mojang.serialization.MapCodec;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.forms.FormRenderCapture;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.items.GunProperties;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.model_blocks.UIModelBlockEditorMenu;
import mchorse.bbs_mod.utils.pose.Transform;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3fc;

import java.util.function.Consumer;

/**
 * Gun item rendering on the 1.21.5+ item-model system: renders the gun's BBS {@link Form}
 * (with the first-person zoom form and the zoom-section editor preview, as the 1.21.1
 * {@code GunItemRenderer} did) by capturing the immediate form pipeline and replaying it
 * into the item command queue — see {@link FormRenderCapture}.
 */
public class GunSpecialRenderer implements SpecialModelRenderer<GunSpecialRenderer.Key>
{
    private static int generation;

    /**
     * Per-stack render data plus the GUI cache key — same invalidation rule as
     * {@link ModelBlockSpecialRenderer.Key}: a stable key caches the GUI atlas entry, a per-frame
     * generation while the transform editor is open keeps hotbar edits live.
     */
    public record Key(GunItemRenderer.Item item, int generation)
    {}

    @Override
    public Key extractArgument(ItemStack stack)
    {
        GunItemRenderer.Item item = BBSModClient.getGunItemRenderer().get(stack);

        if (item == null)
        {
            return null;
        }

        if (UIModelBlockEditorMenu.isEditing(item.properties))
        {
            /* See ModelBlockSpecialRenderer.getData — the edited entry must not expire mid-edit. */
            item.expiration = 20;

            return new Key(item, ++generation);
        }

        return new Key(item, 0);
    }

    @Override
    public void submit(Key key, PoseStack matrices, SubmitNodeCollector queue, int light, int overlay, boolean glint, int outlineColor)
    {
        if (key == null)
        {
            return;
        }

        /* 26.2 no longer hands the display context to submit; see
         * ModelBlockSpecialRenderer.CURRENT_DISPLAY_CONTEXT for where it comes from and what is still
         * missing. */
        ItemDisplayContext displayContext = ModelBlockSpecialRenderer.CURRENT_DISPLAY_CONTEXT;
        GunItemRenderer.Item item = key.item();
        GunProperties properties = item.properties;
        Form form = properties.getForm(displayContext);
        Transform transform = properties.getTransform(displayContext);
        boolean zoom = displayContext.firstPerson() && BBSModClient.getGunZoom() != null && properties.getZoomForm() != null;

        if (zoom)
        {
            form = properties.getZoomForm();
            transform = properties.zoomTransform;
        }

        /* Preview zoom form */
        if (UIScreen.getCurrentMenu() instanceof UIModelBlockEditorMenu editorMenu && editorMenu.currentSection == editorMenu.sectionZoom)
        {
            form = editorMenu.getGunProperties().getZoomForm();
            transform = editorMenu.getGunProperties().zoomTransform;
        }

        if (form != null)
        {
            item.expiration = 20;

            FormRenderCapture.submitForm(form, transform, item.formEntity, displayContext, matrices, queue, light, overlay);
        }
    }

    @Override
    public void getExtents(Consumer<Vector3fc> consumer)
    {
        FormRenderCapture.collectItemBounds(consumer);
    }

    public static class Unbaked implements SpecialModelRenderer.Unbaked
    {
        public static final MapCodec<GunSpecialRenderer.Unbaked> CODEC = MapCodec.unit(new GunSpecialRenderer.Unbaked());

        @Override
        public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext context)
        {
            return new GunSpecialRenderer();
        }

        @Override
        public MapCodec<? extends SpecialModelRenderer.Unbaked> type()
        {
            return CODEC;
        }
    }
}
