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
    private static final int MAX_VARIABLES = 12;
    private static final int MAX_SEQUENCE_ITEMS = 12;
    private static final int ROW_SPACING = 3;

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
        List<Map.Entry<String, RunnerClient.DebugValue>> variables = step.typedLocals().entrySet().stream()
            .limit(MAX_VARIABLES)
            .toList();
        Vec3 camera = context.camera().getPosition();
        BlockPos stdoutPosition = origin.relative(right, -2);

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        RenderType lineType = RenderType.lines();
        VertexConsumer lineConsumer = consumers.getBuffer(lineType);

        for (int i = 0; i < variables.size(); i++) {
            Map.Entry<String, RunnerClient.DebugValue> entry = variables.get(i);
            drawValueBoxes(poseStack, lineConsumer, entry.getKey(), entry.getValue(), i);
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
            Map.Entry<String, RunnerClient.DebugValue> entry = variables.get(i);
            drawValueLabels(poseStack, consumers, minecraft, entry.getKey(), entry.getValue(), i);
        }

        int hidden = step.typedLocals().size() - variables.size();
        if (hidden > 0) {
            BlockPos position = variableBase(variables.size());
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

    private static void drawValueBoxes(
        PoseStack poseStack,
        VertexConsumer consumer,
        String name,
        RunnerClient.DebugValue value,
        int row
    ) {
        BlockPos base = variableBase(row);
        if (value.isSequence() && !value.items().isEmpty()) {
            int count = Math.min(MAX_SEQUENCE_ITEMS, value.items().size());
            for (int i = 0; i < count; i++) {
                RunnerClient.DebugValue item = value.items().get(i);
                BlockPos position = base.relative(right, i);
                float[] color = valueColor(item, itemChanged(name, i, item));
                LevelRenderer.renderLineBox(
                    poseStack,
                    consumer,
                    new AABB(position).inflate(0.002),
                    color[0], color[1], color[2], 0.95f
                );
            }
            return;
        }

        float[] color = valueColor(value, valueChanged(name, value));
        LevelRenderer.renderLineBox(
            poseStack,
            consumer,
            new AABB(base).inflate(0.002),
            color[0], color[1], color[2], 0.95f
        );
    }

    private static void drawValueLabels(
        PoseStack poseStack,
        MultiBufferSource consumers,
        Minecraft minecraft,
        String name,
        RunnerClient.DebugValue value,
        int row
    ) {
        BlockPos base = variableBase(row);
        if (value.isSequence() && !value.items().isEmpty()) {
            renderWorldLabel(
                poseStack,
                consumers,
                minecraft,
                base.getX() + 0.5,
                base.getY() + 1.55,
                base.getZ() + 0.5,
                name + " : " + value.type() + "[" + value.items().size() + (value.truncated() ? "+" : "") + "]",
                0xFFFFFFFF
            );
            int count = Math.min(MAX_SEQUENCE_ITEMS, value.items().size());
            for (int i = 0; i < count; i++) {
                RunnerClient.DebugValue item = value.items().get(i);
                BlockPos position = base.relative(right, i);
                renderWorldLabel(
                    poseStack,
                    consumers,
                    minecraft,
                    position.getX() + 0.5,
                    position.getY() + 1.2,
                    position.getZ() + 0.5,
                    "[" + i + "] " + truncateLabel(item.display(), 28),
                    itemChanged(name, i, item) ? 0xFFFFFF55 : 0xFFFFFFFF
                );
            }
            if (value.items().size() > MAX_SEQUENCE_ITEMS || value.truncated()) {
                BlockPos more = base.relative(right, count);
                renderWorldLabel(
                    poseStack,
                    consumers,
                    minecraft,
                    more.getX() + 0.5,
                    more.getY() + 1.0,
                    more.getZ() + 0.5,
                    "...",
                    0xFFAAAAAA
                );
            }
            return;
        }

        renderWorldLabel(
            poseStack,
            consumers,
            minecraft,
            base.getX() + 0.5,
            base.getY() + 1.25,
            base.getZ() + 0.5,
            truncateLabel(name + " = " + value.display(), 72),
            valueChanged(name, value) ? 0xFFFFFF55 : 0xFFFFFFFF
        );
    }

    private static BlockPos variableBase(int row) {
        return origin.relative(forward, row * ROW_SPACING);
    }

    private static boolean valueChanged(String name, RunnerClient.DebugValue value) {
        RunnerClient.DebugValue previous = previousValue(name);
        return previous != null && (!previous.type().equals(value.type()) || !previous.display().equals(value.display()));
    }

    private static boolean itemChanged(String name, int index, RunnerClient.DebugValue value) {
        RunnerClient.DebugValue previous = previousValue(name);
        if (previous == null || !previous.isSequence() || index >= previous.items().size()) {
            return previous != null;
        }
        RunnerClient.DebugValue oldItem = previous.items().get(index);
        return !oldItem.type().equals(value.type()) || !oldItem.display().equals(value.display());
    }

    private static RunnerClient.DebugValue previousValue(String name) {
        if (debugResult == null || stepIndex <= 0) {
            return null;
        }
        return debugResult.steps().get(stepIndex - 1).typedLocals().get(name);
    }

    private static float[] valueColor(RunnerClient.DebugValue value, boolean changed) {
        if (changed) {
            return new float[] {1.0f, 0.85f, 0.15f};
        }
        return switch (value.type()) {
            case "bool" -> Boolean.TRUE.equals(value.boolValue())
                ? new float[] {0.2f, 1.0f, 0.3f}
                : new float[] {1.0f, 0.25f, 0.25f};
            case "int", "float" -> new float[] {0.25f, 0.85f, 1.0f};
            case "str" -> new float[] {0.9f, 0.4f, 1.0f};
            case "list", "tuple", "set", "frozenset" -> new float[] {0.25f, 0.7f, 1.0f};
            case "dict" -> new float[] {1.0f, 0.55f, 0.2f};
            default -> new float[] {0.75f, 0.75f, 0.75f};
        };
    }

    private static String stdoutAtStep(int index) {
        if (debugResult == null) {
            return "";
        }
        for (int i = Math.min(index, debugResult.steps().size() - 1); i >= 0; i--) {
            String value = debugResult.steps().get(i).stdout();
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
