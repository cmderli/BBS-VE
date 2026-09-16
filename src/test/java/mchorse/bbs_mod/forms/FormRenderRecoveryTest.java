package mchorse.bbs_mod.forms;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.forms.forms.AnchorForm;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.ui.framework.UIContext;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Regression for swallowed render failures leaking into WorldRenderer.checkEmpty. */
public class FormRenderRecoveryTest
{
    public static void main(String[] args)
    {
        BBSSettings.recordingPoseTransformOverlays = new ValueInt("pose_transform_overlays", 0);

        for (boolean world : new boolean[] {false, true})
        {
            checkRender(world, false, false);
            checkRender(world, true, false);
            checkRender(world, true, true);
        }

        checkInvisibleState();
        System.out.println("Form render recovery checks passed");
    }

    private static void checkRender(boolean withWorld, boolean fail, boolean bodyParts)
    {
        TestForm form = new TestForm();
        FormRenderingContext context = context(withWorld);
        PoseStack.Pose caller = context.stack.last();
        Matrix4f position = new Matrix4f(caller.pose());
        Matrix3f normal = new Matrix3f(caller.normal());
        PoseStack.Pose worldCaller = withWorld ? context.world.last() : null;
        Matrix4f worldPosition = withWorld ? new Matrix4f(worldCaller.pose()) : null;
        Matrix3f worldNormal = withWorld ? new Matrix3f(worldCaller.normal()) : null;
        int light = context.light;
        RuntimeException failure = new RuntimeException("Deliberate nested render failure");

        FormRenderer<TestForm> renderer = new TestRenderer(form)
        {
            @Override
            protected void render3D(FormRenderingContext ctx)
            {
                if (!bodyParts)
                {
                    draw(ctx);
                }
            }

            @Override
            public void renderBodyParts(FormRenderingContext ctx)
            {
                if (bodyParts)
                {
                    draw(ctx);
                }
            }

            private void draw(FormRenderingContext ctx)
            {
                if (fail)
                {
                    ctx.stack.pushPose();
                    ctx.stack.translate(10, 20, 30);
                    ctx.stack.pushPose();
                    ctx.stack.scale(2, 3, 4);
                    if (ctx.world != null)
                    {
                        ctx.world.pushPose();
                        ctx.world.scale(4, 3, 2);
                        ctx.world.pushPose();
                    }
                    throw failure;
                }
            }
        };

        boolean thrown = false;
        try
        {
            renderer.render(context);
        }
        catch (RuntimeException e)
        {
            check(e == failure, "Cleanup must preserve the original render exception");
            thrown = true;
        }

        check(thrown == fail, "Expected render outcome");
        check(context.stack.last() == caller, "Caller stack depth must survive nested failures");
        check(position.equals(caller.pose()), "Caller position must be unchanged");
        check(normal.equals(caller.normal()), "Caller normals must be unchanged");
        if (withWorld)
        {
            check(context.world.last() == worldCaller, "World stack depth must be restored");
            check(worldPosition.equals(worldCaller.pose()), "World position must be unchanged");
            check(worldNormal.equals(worldCaller.normal()), "World normals must be unchanged");
            context.world.popPose();
            check(context.world.isEmpty(), "World stack must balance after caller pops");
        }
        check(context.light == light, "Lighting must be restored");
        check(!form.applied && form.resets == 1, "Temporary form states must be reset");
        context.stack.popPose();
        check(context.stack.isEmpty(), "WorldRenderer.checkEmpty must succeed after caller pops");
    }

    private static void checkInvisibleState()
    {
        TestForm form = new TestForm();
        form.visible.set(false);
        FormRenderingContext context = context(true);
        PoseStack.Pose caller = context.stack.last();

        new TestRenderer(form).render(context);

        check(!form.applied && form.resets == 1, "Invisible forms must also reset temporary states");
        check(context.stack.last() == caller, "Invisible forms must leave caller matrices intact");
    }

    private static FormRenderingContext context(boolean world)
    {
        FormRenderingContext context = new FormRenderingContext();
        context.stack = new PoseStack();
        context.stack.translate(1, 2, 3);
        context.stack.pushPose();
        context.stack.scale(2, 3, 4);
        if (world)
        {
            context.world = new PoseStack();
            context.world.pushPose();
            context.world.translate(4, 5, 6);
        }
        context.light = 0x00300020;
        return context;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static class TestRenderer extends FormRenderer<TestForm>
    {
        private TestRenderer(TestForm form)
        {
            super(form);
        }

        @Override
        protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
        {}
    }

    private static class TestForm extends AnchorForm
    {
        private boolean applied;
        private int resets;

        @Override
        public void applyStates(float transition)
        {
            this.applied = true;
        }

        @Override
        public void unapplyStates()
        {
            this.applied = false;
            this.resets++;
        }
    }
}
