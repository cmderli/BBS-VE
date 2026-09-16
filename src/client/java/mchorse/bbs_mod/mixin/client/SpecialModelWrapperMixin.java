package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.client.renderer.item.ModelBlockSpecialRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the item display context back to BBS's special model renderers.
 *
 * <p>26.2 removed the {@code ItemDisplayContext} parameter from
 * {@code SpecialModelRenderer#submit(T, PoseStack, SubmitNodeCollector, int, int, boolean, int)} and
 * from {@code extractArgument(ItemStack)}, because vanilla does not need it there — the transform is
 * applied outside the renderer. BBS does need it: {@code ModelBlockSpecialRenderer} resolves a
 * <i>different form</i> and a different transform per context (GUI, first person, third person,
 * ground), so without the context every item would fall back to {@code NONE} and draw the base
 * form.</p>
 *
 * <p>{@code SpecialModelWrapper#update} is the last place the context is still visible before the
 * argument is extracted, so it is recorded here and read by both BBS special renderers during the
 * same call. It is intentionally a plain static field rather than something threaded through: the
 * record path is single-threaded on the render thread, and the value is only read between this
 * injection and the end of {@code update}.</p>
 */
@Mixin(SpecialModelWrapper.class)
public class SpecialModelWrapperMixin
{
    @Inject(method = "update", at = @At("HEAD"))
    private void bbs$captureDisplayContext(ItemStackRenderState state, ItemStack stack, ItemModelResolver resolver, ItemDisplayContext context, ClientLevel level, ItemOwner owner, int seed, CallbackInfo info)
    {
        ModelBlockSpecialRenderer.CURRENT_DISPLAY_CONTEXT = context;
    }
}
