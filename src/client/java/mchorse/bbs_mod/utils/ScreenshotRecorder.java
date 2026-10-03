package mchorse.bbs_mod.utils;

import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.utils.resources.Pixels;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;

import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Screenshot recorder
 *
 * This class is responsible for taking a screenshot and saving it in
 * the screenshot's directory
 */
public class ScreenshotRecorder
{
    public File screenshots;

    public boolean take;
    public boolean toBuffer;

    public ScreenshotRecorder(File screenshots)
    {
        this.screenshots = screenshots;
        this.screenshots.mkdirs();
    }

    public File getScreenshots()
    {
        return this.screenshots;
    }

    /**
     * Take a screenshot from a texture and save it to the designated file.
     *
     * <p>Device-neutral since the 26.2 port: this used to read the texture back with
     * {@code glGetTexImage} through the OpenGL backend's own name, which does not exist on Vulkan.
     * {@link Texture#pixelsFromTexture(Texture)} is the engine's replacement — it records a
     * {@code copyTextureToBuffer} and blocks on the encoder's completion callback — and it works on both
     * backends.</p>
     */
    public void takeScreenshot(File output, Texture texture)
    {
        Pixels pixels = Texture.pixelsFromTexture(texture);

        if (pixels == null)
        {
            LogUtils.getLogger().warn("[BBS] screenshot: the snapshot could not be read back");

            return;
        }

        try
        {
            this.saveScreenshot(pixels.getBuffer(), output, pixels.width, pixels.height);
        }
        finally
        {
            pixels.delete();
        }
    }

    /**
     * Take a screenshot of the framebuffer the game is drawing into, and save it to the designated file.
     *
     * <p>This used {@code glReadPixels} on whatever framebuffer happened to be bound, which has no meaning on
     * Vulkan. The engine's read is {@code copyTextureToBuffer}, which needs a texture, so the client's main
     * colour attachment is wrapped just for the call and read through
     * {@link Texture#readRegion(int, int, int, int)} — the same route
     * {@code Texture.pixelsFromTexture} takes for a whole texture.</p>
     */
    public void takeScreenshot(File output, int width, int height)
    {
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();

        if (target == null || target.getColorTexture() == null || width <= 0 || height <= 0)
        {
            return;
        }

        Texture wrapper = new Texture();

        wrapper.wrap(target.getColorTexture());

        Pixels pixels = wrapper.readRegion(0, 0, width, height);

        if (pixels == null)
        {
            LogUtils.getLogger().warn("[BBS] screenshot: the framebuffer could not be read back");

            return;
        }

        try
        {
            this.saveScreenshot(pixels.getBuffer(), output, pixels.width, pixels.height);
        }
        finally
        {
            pixels.delete();
            wrapper.unwrap();
        }
    }

    private void saveScreenshot(ByteBuffer pixelData, File output, int width, int height)
    {
        /* RGBA8 straight to packed ARGB. The old float path multiplied by 255 and then truncated, which is
         * exactly what an 8-bit read gives, so no conversion is lost — and the engine's read-back is 8-bit. */
        pixelData.rewind();

        int[] pixels = new int[width * height];

        for (int y = 0; y < height; ++y)
        {
            for (int x = 0; x < width; ++x)
            {
                int r = pixelData.get() & 0xFF;
                int g = pixelData.get() & 0xFF;
                int b = pixelData.get() & 0xFF;
                int a = pixelData.get() & 0xFF;

                pixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }

        ScreenshotRunner runner = new ScreenshotRunner(width, height, pixels, output);

        new Thread(runner).start();
    }

    /**
     * Get screenshot file for a screenshot
     */
    public File getScreenshotFile()
    {
        return new File(this.screenshots, StringUtils.createTimestampFilename() + ".png");
    }

    /**
     * Screenshot runner
     * <p>
     * This dude right here is responsible for saving given RGB data to
     * a PNG file to given designated file.
     */
    public static class ScreenshotRunner implements Runnable, ClipboardOwner
    {
        public int width;
        public int height;

        public int[] data;
        public File destination;

        public ScreenshotRunner(int width, int height, int[] data, File destination)
        {
            this.width = width;
            this.height = height;
            this.data = data;
            this.destination = destination;
        }

        @Override
        public void lostOwnership(Clipboard clipboard, Transferable contents)
        {}

        @Override
        public void run()
        {
            try
            {
                if (this.destination == null)
                {
                    /* Windows only */
                    BufferedImage image = new BufferedImage(this.width, this.height, BufferedImage.TYPE_INT_ARGB);

                    image.setRGB(0, 0, this.width, this.height, this.data, 0, this.width);
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new TransferableImage(image), this);
                }
                else
                {
                    Pixels pixels = Pixels.fromIntArray(this.width, this.height, this.data);

                    PNGEncoder.writeToFile(pixels, this.destination);

                    pixels.delete();
                }

                UIUtils.playClick();
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    public static class TransferableImage implements Transferable
    {
        private Image image;
        private DataFlavor flavor = DataFlavor.imageFlavor;

        public TransferableImage(Image image)
        {
            this.image = image;
        }

        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException
        {
            if (this.flavor.equals(flavor))
            {
                return this.image;
            }

            throw new UnsupportedFlavorException(flavor);
        }

        public DataFlavor[] getTransferDataFlavors()
        {
            return new DataFlavor[]{this.flavor};
        }

        public boolean isDataFlavorSupported(DataFlavor flavor)
        {
            return this.flavor.equals(flavor);
        }
    }
}