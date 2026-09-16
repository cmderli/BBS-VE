package mchorse.bbs_mod.client.render.special;

import mchorse.bbs_mod.forms.renderers.FormRenderer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

/**
 * Render state for a BBS form thumbnail drawn as a vanilla special GUI element — the same mechanism
 * vanilla uses for 3D-in-GUI thumbnails (entities, banners, the inventory player). One is submitted per
 * form-list cell via {@code DrawContext.state.addSpecialElement}; {@link BbsFormGuiElementRenderer} calls
 * back into {@link FormRenderer#renderUIPreview} to render the form off-screen, and the deferred GUI
 * composites it into the cell. This indirection is needed because the list draws each cell in the GUI
 * record phase, where a direct immediate 3D draw cannot composite (two-phase GUI). The {@code renderer} is
 * the generic {@link FormRenderer} so every 3D form type (model/billboard/extruded/block/particle) can use
 * this one path; each supplies its own {@link FormRenderer#renderUIPreview}.
 *
 * <p>{@code pose} is the live 2D GUI matrix captured at submit time (it carries the list's scroll translate),
 * applied to the composite quad so the thumbnail scrolls with the cell — faithful to the original, which
 * rendered the model directly onto {@code context.batcher.getContext().getMatrices()}. {@code angle}
 * (cursor-driven yaw) and {@code transition} are likewise captured at submit; the remaining accessors
 * (x0/x1/y0/y1/scale/scissorArea/bounds) are record components.</p>
 *
 * <p>26.2 spells the picture-in-picture rectangle {@code x0/y0/x1/y1} (left/top/right/bottom); 1.21.11 spelled
 * the same four numbers {@code x1/y1/x2/y2}. The components are named for 26.2 so that the record's own
 * accessors are the ones {@link PictureInPictureRenderState} demands.</p>
 */
public record BbsFormGuiElementRenderState(
    FormRenderer<?> renderer,
    float angle,
    float transition,
    Matrix3x2f pose,
    int x0, int y0, int x1, int y1,
    float scale,
    @Nullable ScreenRectangle scissorArea,
    @Nullable ScreenRectangle bounds
) implements PictureInPictureRenderState
{
    public BbsFormGuiElementRenderState(FormRenderer<?> renderer, float angle, float transition, Matrix3x2f pose, int x0, int y0, int x1, int y1, float scale, @Nullable ScreenRectangle scissorArea)
    {
        /* The cell rect (x0..y1) is unscrolled content space; the composite quad is placed on-screen by `pose`
         * (which carries the list's scroll translate), and `scissorArea` was likewise captured already scrolled.
         * So the on-screen bounds must be computed from the POSE-SHIFTED cell, not the raw cell — otherwise the
         * raw (unscrolled) cell intersected with the scrolled scissor shrinks as you scroll and goes empty (null)
         * once the scroll exceeds the cell height, culling the thumbnail entirely (crop-then-vanish on scroll).
         * Shift the cell by the pose translate so bounds, scissor and the drawn geometry share one screen space. */
        this(renderer, angle, transition, pose, x0, y0, x1, y1, scale, scissorArea,
            PictureInPictureRenderState.getBounds(
                x0 + (int) pose.m20(), y0 + (int) pose.m21(),
                x1 + (int) pose.m20(), y1 + (int) pose.m21(), scissorArea));
    }
}
