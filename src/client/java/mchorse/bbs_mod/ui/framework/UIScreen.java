package mchorse.bbs_mod.ui.framework;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.PixelArt;
import mchorse.bbs_mod.importers.IImportPathProvider;
import mchorse.bbs_mod.importers.ImporterContext;
import mchorse.bbs_mod.importers.Importers;
import mchorse.bbs_mod.importers.types.IImporter;
import mchorse.bbs_mod.mixin.client.RenderTickCounterAccessor;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.utils.UIModelRenderer;
import mchorse.bbs_mod.ui.utils.IFileDropListener;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.utils.FFMpegUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.DeltaTracker;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class UIScreen extends Screen implements IFileDropListener
{
    private UIBaseMenu menu;
    private UIRenderingContext context;

    public static void open(UIBaseMenu menu)
    {
        Minecraft.getInstance().gui.setScreen(new UIScreen(Component.empty(), menu));
    }

    public static UIBaseMenu getCurrentMenu()
    {
        Screen currentScreen = Minecraft.getInstance().gui.screen();

        if (currentScreen instanceof UIScreen uiScreen)
        {
            return uiScreen.menu;
        }

        return null;
    }

    public UIScreen(Component title, UIBaseMenu menu)
    {
        super(title);

        Minecraft mc = Minecraft.getInstance();

        this.menu = menu;
        /* Placeholder DrawContext just so the UIRenderingContext/Batcher2D exist for layout/event wiring.
         * It is NEVER drawn into: extractRenderState() swaps in vanilla's live per-frame context via
         * this.context.setContext(...) before any drawing happens (two-phase GUI, 1.21.6+). */
        this.context = new UIRenderingContext(new GuiGraphicsExtractor(mc, new GuiRenderState(), mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()));

        this.menu.context.setup(this.context);
    }

    public void update()
    {
        this.menu.update();
    }

    public void renderInWorld(LevelRenderContext context)
    {
        this.menu.renderInWorld(context);

        /* Render in-panel 3D model previews into their off-screen textures HERE — during the world phase,
         * OUTSIDE the two-phase-GUI recording window. The model's immediate entity RenderLayer.draw opens
         * its own GPU render pass, and during Screen.render the GUI colour-write mask has alpha disabled
         * (which would zero the FBO alpha); rendering here avoids both. Screen.render then only RECORDS the
         * cached blit (isolated on its own root layer so it composites correctly). */
        for (UIModelRenderer renderer : this.menu.getRoot().getChildren(UIModelRenderer.class))
        {
            try
            {
                renderer.renderModelToTexture(this.menu.context);
            }
            catch (Exception e)
            {
                /* Defensive: a failure in one preview must not abort the world-render loop or other previews. */
            }
        }
    }

    @Override
    public void onFilesDrop(List<Path> paths)
    {
        super.onFilesDrop(paths);

        String[] filePaths = new String[paths.size()];
        int i = 0;

        for (Path path : paths)
        {
            filePaths[i] = path.toAbsolutePath().toString();

            i += 1;
        }

        this.acceptFilePaths(filePaths);
    }

    @Override
    public void removed()
    {
        BBSModClient.setCustomGUIScale(false);
        Minecraft.getInstance().resizeGui();

        super.removed();

        this.menu.onClose(null);

        if (this.menu.canHideHUD())
        {
            Minecraft.getInstance().gui.hud.isHidden = false;
        }
    }

    @Override
    public void added()
    {
        BBSModClient.setCustomGUIScale(true);
        Minecraft.getInstance().resizeGui();

        super.added();

        this.menu.onOpen(null);

        if (this.menu.canHideHUD())
        {
            Minecraft.getInstance().gui.hud.isHidden = true;
        }
    }

    @Override
    public boolean isPauseScreen()
    {
        return this.menu.canPause();
    }

    @Override
    protected void init()
    {
        super.init();

        this.menu.resize(this.width, this.height);
    }

    @Override
    public void resize(int width, int height)
    {
        super.resize(width, height);

        this.menu.resize(width, height);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled)
    {
        try
        {
            return this.menu.mouseClicked((int) click.x(), (int) click.y(), click.button());
        }
        catch (RuntimeException | Error e)
        {
            return this.report("mouse click", e);
        }
    }

    /**
     * Log a failure of an input handler before Minecraft wraps it into a crash report: building
     * that report can itself fail (a mixin of another mod loading a class mid-transformation),
     * and then the original stack is gone with it.
     */
    private boolean report(String action, Throwable e)
    {
        System.err.println("[BBS UI] Unhandled exception on " + action + " in " + this.menu.getClass().getSimpleName());
        e.printStackTrace();

        if (e instanceof RuntimeException runtime)
        {
            throw runtime;
        }

        throw (Error) e;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        return this.menu.mouseScrolled((int) mouseX, (int) mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        try
        {
            return this.menu.mouseReleased((int) click.x(), (int) click.y(), click.button());
        }
        catch (RuntimeException | Error e)
        {
            return this.report("mouse release", e);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent input)
    {
        try
        {
            return this.menu.handleKey(input.key(), input.scancode(), BBSRendering.lastAction, input.modifiers());
        }
        catch (RuntimeException | Error e)
        {
            return this.report("key press", e);
        }
    }

    @Override
    public boolean keyReleased(KeyEvent input)
    {
        return this.menu.handleKey(input.key(), input.scancode(), GLFW.GLFW_RELEASE, input.modifiers());
    }

    @Override
    public boolean charTyped(CharacterEvent input)
    {
        this.menu.handleTextInput(input.codepoint());

        return true;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta)
    {}

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta)
    {
        /* Text is drawn with vanilla's programs, which are shared with the
         * world's text, so the pixel art ones are only allowed for the span
         * where BBS's UI is what's being drawn */
        PixelArt.setDrawingUI(true);

        try
        {
            /* Two-phase GUI (1.21.6+): vanilla only composites the GuiRenderState that belongs to the
             * GuiGraphicsExtractor it hands to extractRenderState(). Draw the whole BBS UI into THIS live context, not the
             * placeholder built in the constructor, or nothing reaches the screen. */
            this.context.setContext(context);

            DeltaTracker tick = this.minecraft.getDeltaTracker();

            this.menu.context.setTransition(tick instanceof RenderTickCounterAccessor accessor ? accessor.bbs$getTickDelta() : tick.getGameTimeDeltaPartialTick(false));
            this.menu.renderMenu(this.context, mouseX, mouseY);
            this.menu.context.render.executeRunnables();
        }
        finally
        {
            PixelArt.setDrawingUI(false);
        }
    }

    @Override
    public void acceptFilePaths(String[] paths)
    {
        if (this.menu != null)
        {
            if (!FFMpegUtils.checkFFMPEG())
            {
                this.menu.context.notifyError(UIKeys.IMPORTER_FFMPEG_NOTIFICATION);

                return;
            }

            File directory = null;
            boolean open = true;

            for (IImportPathProvider provider : this.menu.getRoot().getChildren(IImportPathProvider.class))
            {
                directory = provider.getImporterPath();

                if (directory != null)
                {
                    open = false;

                    break;
                }
            }

            List<File> files = new ArrayList<>();

            for (String path : paths)
            {
                File file = new File(path);

                if (file.exists())
                {
                    files.add(file);
                }
            }

            ImporterContext context = new ImporterContext(files, directory);

            for (IImporter importer : Importers.getImporters())
            {
                if (importer.canImport(context))
                {
                    importer.importFiles(context);

                    if (open)
                    {
                        UIUtils.openFolder(context.getDestination(importer));
                    }

                    this.menu.context.notifySuccess(UIKeys.IMPORTER_SUCCESS_NOTIFICATION.format(importer.getName()));

                    return;
                }
            }
        }
    }
}