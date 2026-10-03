package mchorse.bbs_mod.forms;

import mchorse.bbs_mod.forms.categories.FormCategory;
import mchorse.bbs_mod.forms.sections.ExtraFormSection;
import mchorse.bbs_mod.forms.sections.FormSection;
import mchorse.bbs_mod.forms.sections.ModelFormSection;
import mchorse.bbs_mod.forms.sections.ParticleFormSection;
import mchorse.bbs_mod.forms.sections.RecentFormSection;
import mchorse.bbs_mod.forms.sections.UserFormSection;
import mchorse.bbs_mod.utils.watchdog.IWatchDogListener;
import mchorse.bbs_mod.utils.watchdog.WatchDogEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class FormCategories implements IWatchDogListener
{
    /**
     * Sections an addon added to the palette.
     *
     * <p>Factories rather than sections, because {@link #setup()} runs again on every asset
     * reload and rebuilds its list from scratch — a section handed over once would be thrown
     * away the first time the user touched a file.</p>
     */
    private static final List<Function<FormCategories, FormSection>> EXTRA_SECTIONS = new ArrayList<>();

    public final CategoryPreferences preferences = new CategoryPreferences();

    private List<FormSection> sections = new ArrayList<>();
    private RecentFormSection recentForms = new RecentFormSection(this);
    private UserFormSection userForms = new UserFormSection(this);

    private long lastUpdate;

    /**
     * Whether {@link #setup()} has ever run.
     *
     * <p>This cannot be guessed from {@code sections}, which is reset on every reload and can be
     * empty for reasons other than "not set up yet".</p>
     */
    private boolean initialized;

    /**
     * Adds a section — a top-level tab — to the form palette.
     *
     * <p>Without this, an addon's forms worked but had nowhere to be picked from: the list of
     * sections was private and built from scratch in {@link #setup()}.</p>
     */
    public static void registerSection(Function<FormCategories, FormSection> factory)
    {
        EXTRA_SECTIONS.add(factory);
    }

    /* Setup */

    /**
     * Builds the palette if it has not been built yet.
     *
     * <p>26.2 cannot build it during client initialization. {@code ExtraFormSection} makes the
     * default {@code ItemStack} for its item form, and since item components became registered
     * data, the {@code ItemStack(Holder, int)} constructor reads the holder's component map — which
     * vanilla only binds once the client has received the server's registries
     * ({@code RegistryDataCollector.collectGameRegistries}). Constructing one earlier throws
     * "Components not bound yet", and this is not a timing detail that can be waited out: it fails
     * at {@code onInitializeClient}, at {@code CLIENT_STARTED} and on the first client tick too,
     * because no world is connected yet. A world join is the first moment it can work.</p>
     *
     * <p>{@code BBSResources.init()} is therefore called on world join, and it calls this. The
     * second caller is {@link #setup()} itself, which sets the flag before building: the flag has
     * to be up before the sections are initiated, or a section that reaches back for the palette
     * would recurse.</p>
     */
    public void ensureInitialized()
    {
        if (!this.initialized)
        {
            this.setup();
        }
    }

    /** Whether the palette has been built at least once. */
    public boolean isInitialized()
    {
        return this.initialized;
    }

    public void setup()
    {
        this.initialized = true;

        this.sections.clear();
        this.sections.add(this.recentForms);
        this.sections.add(this.userForms);
        this.sections.add(new ModelFormSection(this));
        this.sections.add(new ParticleFormSection(this));
        this.sections.add(new ExtraFormSection(this));

        for (Function<FormCategories, FormSection> factory : EXTRA_SECTIONS)
        {
            this.sections.add(factory.apply(this));
        }

        for (FormSection section : this.sections)
        {
            section.initiate();
        }

        this.markDirty();
        this.preferences.read();
    }

    public long getLastUpdate()
    {
        return lastUpdate;
    }

    public void markDirty()
    {
        this.lastUpdate = System.currentTimeMillis();
    }

    public RecentFormSection getRecentForms()
    {
        return this.recentForms;
    }

    public UserFormSection getUserForms()
    {
        return this.userForms;
    }

    public List<FormCategory> getAllCategories()
    {
        List<FormCategory> formCategories = new ArrayList<>();

        for (FormSection section : this.sections)
        {
            formCategories.addAll(section.getCategories());
        }

        return formCategories;
    }

    @Override
    public void accept(Path path, WatchDogEvent event)
    {
        for (FormSection section : this.sections)
        {
            section.accept(path, event);
        }
    }
}
