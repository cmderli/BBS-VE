package mchorse.bbs_mod.utils;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.utils.resources.Pixels;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.utils.UIUtils;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import sun.misc.Unsafe;

import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class VideoRecorder
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Link RENDER_COMPLETE_SOUND = Link.assets("sounds/render_complete.ogg");

    private Process process;
    private WritableByteChannel channel;
    private boolean recording;

    /** Reused 4-byte-per-pixel scratch buffer; ffmpeg is fed rgba, the engine's read-back format. */
    private ByteBuffer buffer;

    /** The capture target the export reads every frame, or null when not recording. */
    private Texture texture;
    private int textureWidth;
    private int textureHeight;
    private int counter;

    public int serverTicks;
    public int lastServerTicks;

    public boolean isRecording()
    {
        return this.recording;
    }

    public int getCounter()
    {
        return this.counter;
    }

    /** One-shot report of a read-back that would not fit, so a broken export says why once. */
    private boolean reportedOversizedFrame;

    /**
     * Start recording the video using ffmpeg
     */
    public void startRecording(String movieName, File audioFile, Texture texture, int width, int height)
    {
        if (this.recording)
        {
            return;
        }

        this.counter = 0;
        this.reportedOversizedFrame = false;
        this.texture = texture;
        this.textureWidth = width;
        this.textureHeight = height;

        int size = width * height * 4;

        /* Exactly width * height * 4, the RGBA frame ffmpeg is told to expect. A recording that never
         * reached stopRecording leaves the previous buffer here, so a stale size is dropped rather than
         * reused - writing a larger frame into it would run off the end. */
        if (this.buffer != null && this.buffer.capacity() != size)
        {
            MemoryUtil.memFree(this.buffer);

            this.buffer = null;
        }

        if (this.buffer == null)
        {
            this.buffer = MemoryUtil.memAlloc(size);
        }

        try
        {
            File movies = BBSRendering.getVideoFolder();

            Path path = Paths.get(movies.toString());

            if (movieName == null || movieName.isEmpty())
            {
                movieName = StringUtils.createTimestampFilename();
            }

            String params = audioFile == null
                ? BBSSettings.videoArguments.get()
                : BBSSettings.videoArgumentsAudio.get();
            StringBuilder filters = new StringBuilder("vflip");
            float frameRate = (float) BBSRendering.getVideoFrameRate();

            int motionBlur = BBSRendering.getMotionBlur();

            for (int i = 0; i < motionBlur; i++)
            {
                filters.append(",tblend=all_mode=average,framestep=2");
            }

            List<String> args = new ArrayList<>();
            String encoder = FFMpegUtils.getFFMPEG();

            args.add(encoder);

            /* Tokens are substituted after splitting, so a movie name or an audio path
             * with spaces stays a single argument. ProcessBuilder passes quote characters
             * literally, so they must not be added around paths either. */
            for (String arg : params.split(" "))
            {
                if (arg.isEmpty())
                {
                    continue;
                }

                arg = arg.replace("%WIDTH%", String.valueOf(width));
                arg = arg.replace("%HEIGHT%", String.valueOf(height));
                arg = arg.replace("%FPS%", String.valueOf(frameRate));
                arg = arg.replace("%NAME%", movieName);
                arg = arg.replace("%FILTERS%", filters.toString());

                if (audioFile != null)
                {
                    arg = arg.replace("%AUDIO_TRACK%", audioFile.getAbsolutePath());
                }

                args.add(arg);
            }

            System.out.println("Recording video with following arguments: " + args);

            ProcessBuilder builder = new ProcessBuilder(args);
            File log = path.resolve(movieName.concat(".log")).toFile();

            if (!BBSSettings.videoEncoderLog.get())
            {
                log = BBSMod.getSettingsPath("video.log");
            }

            builder.directory(path.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(log);

            this.process = builder.start();

            /**
             * Java wraps the process output stream into a BufferedOutputStream,
             *
             * but its little buffer is just slowing everything down with the
             * huge amount of data we're dealing here, so unwrap it with this little
             * hack.
             */
            OutputStream os = this.process.getOutputStream();
            Unsafe unsafe = UnsafeUtils.getUnsafe();

            if (os instanceof FilterOutputStream)
            {
                try
                {
                    Field outField = FilterOutputStream.class.getDeclaredField("out");

                    os = (OutputStream) unsafe.getObject(os, unsafe.objectFieldOffset(outField));
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }

            this.channel = Channels.newChannel(os);
            this.recording = true;

            UIUtils.playClick(2F);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        this.serverTicks = this.lastServerTicks = 0;
    }

    /**
     * Stop recording
     */
    public void stopRecording()
    {
        this.stopRecording(true);
    }

    /**
     * Stop recording. With {@code finishEffects} false the completion sound and the
     * folder opening are skipped - the caller runs {@link #playFinishEffects()} itself
     * once the file is actually final (audio post pass).
     */
    public void stopRecording(boolean finishEffects)
    {
        if (!this.recording)
        {
            return;
        }

        this.texture = null;

        if (this.buffer != null)
        {
            MemoryUtil.memFree(this.buffer);

            this.buffer = null;
        }

        try
        {
            if (this.channel != null && this.channel.isOpen())
            {
                this.channel.close();
            }

            this.channel = null;
        }
        catch (IOException ex)
        {
            ex.printStackTrace();
        }

        try
        {
            if (this.process != null)
            {
                this.process.waitFor(1, TimeUnit.MINUTES);
                this.process.destroy();
            }

            this.process = null;
        }
        catch (InterruptedException ex)
        {
            ex.printStackTrace();
        }

        this.recording = false;

        if (finishEffects)
        {
            this.playFinishEffects();
        }

        this.serverTicks = this.lastServerTicks = 0;
    }

    /**
     * The end-of-export feedback (completion sound, opening the movies folder).
     */
    public void playFinishEffects()
    {
        if (BBSSettings.videoPlaySoundAfterExport.get())
        {
            if (BBSModClient.getSounds().play(RENDER_COMPLETE_SOUND) == null)
            {
                UIUtils.playClick(0.5F);
            }
        }

        if (BBSSettings.videoOpenFolderAfterExport.get())
        {
            File folder = BBSRendering.getVideoFolder();
            Minecraft.getInstance().execute(() -> UIUtils.openFolder(folder));
        }
    }

    /**
     * Record a frame.
     *
     * <p>Device-neutral since the 26.2 port. This used to be two OpenGL paths — an asynchronous
     * {@code glGetTexImage} into a ping-pong pair of pixel pack buffers off macOS, and a synchronous one on
     * it — and macOS needed the second because the PBO pipeline rendered black footage there. Both are gone:
     * {@link Texture#pixelsFromTexture(Texture)} records a {@code copyTextureToBuffer} and blocks on the
     * encoder's completion callback, which is the engine's own read-back and works on Vulkan and OpenGL
     * alike, so there is no longer a platform split.</p>
     *
     * <p>ffmpeg is fed {@code rgba}, matching the read-back, so no conversion happens here. A user whose saved
     * settings still say {@code -pix_fmt bgr24} must change that to {@code rgba} — the recorder reports the
     * mismatch once rather than producing colour-swapped footage silently.</p>
     */
    public void recordFrame()
    {
        if (!this.recording)
        {
            return;
        }

        if (this.captureFrame())
        {
            this.counter += 1;
        }
    }

    /**
     * Read the capture target back and hand it to ffmpeg as one RGBA frame.
     *
     * @return whether a frame was actually written
     */
    private boolean captureFrame()
    {
        Texture texture = this.texture;

        if (texture == null || this.buffer == null)
        {
            return false;
        }

        /* Backstop, not a branch we expect: the snapshot is sized in video pixels by
         * BBSRendering#blitIntoSnapshot, so these agree by construction. If they ever stop agreeing this
         * reports once instead of feeding ffmpeg a frame of the wrong geometry. */
        if (texture.width != this.textureWidth || texture.height != this.textureHeight)
        {
            if (!this.reportedOversizedFrame)
            {
                this.reportedOversizedFrame = true;

                LOGGER.error("[BBS video] captured frame is {}x{} but the recording is {}x{} — dropping frames",
                    texture.width, texture.height, this.textureWidth, this.textureHeight);
            }

            return false;
        }

        Pixels pixels = Texture.pixelsFromTexture(texture);

        if (pixels == null)
        {
            return false;
        }

        try
        {
            ByteBuffer source = pixels.getBuffer();
            ByteBuffer target = this.buffer;

            /* Fed to ffmpeg AS-IS. The read-back is RGBA8 and ffmpeg is told rgba, so there is no per-pixel
             * work here at all - one bulk copy.
             *
             * There used to be an RGBA -> BGR swizzle for the old bgr24 pipe, and it was a mistake to keep:
             * on a 2560x1350 recording it ran four direct-ByteBuffer get/put calls per pixel - about fourteen
             * million bound-checked calls per frame - on top of the read-back's blocking GPU wait. The frame
             * loop could not keep up, so the game appeared to freeze: no frames advanced and therefore no
             * ticks ran. Hence the format change rather than an optimisation of the loop.
             *
             * Rows stay in order: the texture's row 0 is the top, and the ffmpeg filter chain starts with
             * vflip to match the bottom-up frames the old GL read-back produced. */
            target.clear();
            source.rewind();
            target.put(source);
            target.flip();

            try
            {
                this.channel.write(target);

                return true;
            }
            catch (Exception e)
            {
                LOGGER.error("[BBS video] could not write a frame to ffmpeg", e);

                return false;
            }
        }
        finally
        {
            pixels.delete();
        }
    }


    /**
     * Toggle recording of the video
     */
    public void toggleRecording(Texture texture, int textureWidth, int textureHeight)
    {
        if (this.recording)
        {
            this.stopRecording();
        }
        else
        {
            this.startRecording(StringUtils.createTimestampFilename(), null, texture, textureWidth, textureHeight);
        }

        UIUtils.playClick();
    }
}