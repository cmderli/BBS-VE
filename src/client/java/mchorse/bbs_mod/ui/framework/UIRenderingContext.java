package mchorse.bbs_mod.ui.framework;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.graphics.texture.TextureManager;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

public class UIRenderingContext
{
    public Batcher2D batcher;

    private List<Runnable> runnables = new ArrayList<>();

    public UIRenderingContext(GuiGraphicsExtractor context)
    {
        this.batcher = new Batcher2D(context);
    }

    /**
     * Swap in the live per-frame vanilla {@link GuiGraphicsExtractor}. Must be called once per frame (from
     * {@code UIScreen.extractRenderState}) before any drawing, so the batcher draws into the {@code GuiRenderState}
     * vanilla actually composites (two-phase GUI, 1.21.6+).
     */
    public void setContext(GuiGraphicsExtractor context)
    {
        this.batcher.setContext(context);
    }

    /* Rendering context implementations */

    public TextureManager getTextures()
    {
        return BBSModClient.getTextures();
    }

    public void postRunnable(Runnable runnable)
    {
        this.runnables.add(runnable);
    }

    public void executeRunnables()
    {
        for (Runnable runnable : this.runnables)
        {
            runnable.run();
        }

        this.runnables.clear();
    }
}
