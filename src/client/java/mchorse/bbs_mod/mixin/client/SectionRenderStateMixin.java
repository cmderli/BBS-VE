package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.BBSSettings;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chroma-sky terrain toggle: when the chroma sky is on and terrain is set to hidden
 * ({@code chromaSkyTerrain} off), every section draw is skipped, leaving actors and forms
 * over the flat chroma colour.
 *
 * <p>On 1.21.1 this lived in {@code WorldRendererMixin#onRenderLayer}, which cancelled each
 * chunk {@code RenderLayer} draw. In 1.21.5+ terrain is drawn by
 * {@link SectionRenderState#renderSection} (called for every {@link BlockRenderLayerGroup}
 * from the main pass), so the kill switch moved here — one hook covers all layer groups.
 */
@Mixin(ChunkSectionsToRender.class)
public class SectionRenderStateMixin
{
    @Inject(method = "renderSection", at = @At("HEAD"), cancellable = true)
    private void onRenderSection(ChunkSectionLayerGroup group, GpuSampler terrainSampler, CallbackInfo info)
    {
        if (BBSSettings.chromaSkyEnabled.get() && !BBSSettings.chromaSkyTerrain.get())
        {
            info.cancel();
        }
    }
}
