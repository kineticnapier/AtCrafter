package io.github.kineticnapier.atcrafter.client.debug;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.kineticnapier.atcrafter.client.runner.RunnerClient;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class DebugWorldRenderer {
    private static final String STDOUT_KEY = "$stdout";
    private static final int MAX_VARIABLES = 20;
    private static final int COLUMNS = 4;

    private static RunnerClient.DebugResult debugResult;
    private static int stepIndex = -1;
    private static boolean active;
    private static BlockPos origin = BlockPos.ZERO;
    private static Direction forward = Direction.NORTH;
    private static Direction right = Direction.EAST;

    private DebugWorldRenderer() {
    }

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(DebugWorldRenderer::render);
    }

    public static boolean isActive() {
        return active;
    }

    public static void activate(RunnerClient.DebugResult result, int requestedStep) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || result == null || result.steps().isEmpty()) {
            return;
        }

        debugResult = result;
        forward = minecraft.player.getDirection();
        right = forward.getClockWise();
        origin = minecraft.player.blockPosition().relative(forward, 4);
        active = true;
        setStep(requestedStep);
        minecraft.player.displayClientMessage(
            Component.literal("AtCrafter デバッグ世界: [ 前へ / ] 次へ / \\ 終了"),
            true
        );
    }

    public static void previousStep() {
        if (active) {
            setStep(stepIndex - 1);
        }
    }

    public static void nextStep() {
        if (active) {
            setStep(stepIndex + 1);
        }
    }

    public static void setStep(int requestedStep) {
        if (debugResult == null || debugResult.steps().isEmpty()) {
            return;
        }

        stepIndex = Math.max(0, Math.min(requestedStep, debugResult.steps().size() - 1));
        announceStep();
    }

    public static void deactivate() {
        if (!active) {
            return;
        }
        active = false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal("AtCrafter デバッグ世界を終了しました。"), true);
        }
    }

    private static void announceStep() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!active || minecraft.player == null || debugResult == null || stepIndex < 0) {
            return;
        }
        RunnerClient.DebugStep step = debugResult.steps().get(stepIndex);
        minecraft.player.displayClientMessage(
            Component.literal(
                "AtCrafter " + (stepIndex + 1) + "/" + debugResult.steps().size()
                    + "  行 " + step.line()
            ),
            true
        );
    }

    private static void render(WorldRenderContext context) {
        if (!active || debugResult == null || stepIndex < 0 || stepIndex >= debugResult.steps().size()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        PoseStack poseStack = context.matrixStack();
        MultiBufferSource consumers = context.consumers();
        if (poseStack == null || consumers == null) {
            return;
        }

        RunnerClient.DebugStep step = debugResult.steps().get(stepIndex);
        Vec3 camera = context.camera().getPosition();
        List<Map.Entry<String, String>> variables = step.locals().entrySet().stream()
            .filter(entry -> !entry.getKey().equals(STDOUT_KEY))
            .limit(MAX_VARIABLES)
            .toList();
        int totalVariables = countVisibleLocals(step);
        BlockPos stdoutPosition = origin.relative(right, -2);

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        // Draw every line primitive in one uninterrupted batch. Text rendering may switch/end
        // BufferSource builders, so keeping a VertexConsumer alive across drawInBatch() calls
        // can leave it pointing at a BufferBuilder that is no longer building.
        RenderType lineType = RenderType.lines();
        VertexConsumer lineConsumer = consumers.getBuffer(lineType);

        for (int i = 0; i < variables.size(); i++) {
            BlockPos position = variablePosition(i);
            AABB box = new AABB(position).inflate(0.002);
            LevelRenderer.renderLineBox(poseStack, lineConsumer, box, 0.2f, 0.85f, 1.0f, 0.9f);
        }

        LevelRenderer.renderLineBox(
            poseStack,
            lineConsumer,
            new AABB(stdoutPosition).inflate(0.002),
            0.3f,
            1.0f,
            0.4f,
            0.9f
        );

        if (consumers instanceof MultiBufferSource.BufferSource bufferSource) {
            bufferSource.endBatch(lineType);
        }

        // Render labels only after the line batch is complete.
        renderWorldLabel(
            poseStack,
            consumers,
            minecraft,
            origin.getX() + 0.5,
            origin.getY() + 3.0,
            origin.getZ() + 0.5,
            "Step " + (stepIndex + 1) + "/" + debugResult.steps().size() + "  line " + step.line(),
            0xFFFFFF55
        );

        for (int i = 0; i < variables.size(); i++) {
            Map.Entry<String, String> entry = variables.get(i);
            BlockPos position = variablePosition(i);
            renderWorldLabel(
                poseStack,
                consumers,
                minecraft,
                position.getX() + 0.5,
                position.getY() + 1.25,
                position.getZ() + 0.5,
                truncateLabel(entry.getKey() + " = " + entry.getValue(), 72),
                0xFFFFFFFF
            );
        }

        int hidden = totalVariables - variables.size();
        if (hidden > 0) {
            BlockPos position = variablePosition(variables.size());
            renderWorldLabel(
                poseStack,
                consumers,
                minecraft,
                position.getX() + 0.5,
                position.getY() + 1.0,
                position.getZ() + 0.5,
                "... +" + hidden + " variables",
                0xFFAAAAAA
            );
        }

        String stdout = stdoutAtStep(stepIndex);
        renderWorldLabel(
            poseStack,
            consumers,
            minecraft,
            stdoutPosition.getX() + 0.5,
            stdoutPosition.getY() + 1.25,
            stdoutPosition.getZ() + 0.5,
            stdout.isEmpty() ? "stdout: (empty)" : "stdout: " + truncateLabel(stdout.replace('\n', ' '), 72),
            0xFF88FF88
        );

        poseStack.popPose();
    }

    private static BlockPos variablePosition(int index) {
        int column = index % COLUMNS;
        int row = index / COLUMNS;
        return origin
            .relative(right, column * 2)
            .relative(forward, row * 2);
    }

    private static int countVisibleLocals(RunnerClient.DebugStep step) {
        int count = 0;
        for (String name : step.locals().keySet()) {
            if (!name.equals(STDOUT_KEY)) {
                count++;
            }
        }
        return count;
    }

    private static String stdoutAtStep(int index) {
        if (debugResult == null) {
            return "";
        }
        for (int i = Math.min(index, debugResult.steps().size() - 1); i >= 0; i--) {
            String value = debugResult.steps().get(i).locals().get(STDOUT_KEY);
            if (value != null) {
                return value;
            }
        }
        return "";
    }

    private static String truncateLabel(String text, int limit) {
        String normalized = text.replace('\r', ' ').replace('\n', ' ');
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, limit - 3)) + "...";
    }

    private static void renderWorldLabel(
        PoseStack poseStack,
        MultiBufferSource consumers,
        Minecraft minecraft,
        double x,
        double y,
        double z,
        String text,
        int color
    ) {
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        poseStack.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(-0.02f, -0.02f, 0.02f);
        float textX = -minecraft.font.width(text) / 2.0f;
        minecraft.font.drawInBatch(
            text,
            textX,
            0,
            color,
            false,
            poseStack.last().pose(),
            consumers,
            Font.DisplayMode.NORMAL,
            0,
            0x00F000F0
        );
        poseStack.popPose();
    }
}
