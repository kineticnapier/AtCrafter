package io.github.kineticnapier.atcrafter.client.debug;

import io.github.kineticnapier.atcrafter.client.runner.RunnerClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class DebugWorldRenderer {
    private static final int MAX_VARIABLES = 12;
    private static final int MAX_SEQUENCE_ITEMS = 12;
    private static final int ROW_SPACING = 3;
    private static final Direction FORWARD = Direction.NORTH;
    private static final Direction RIGHT = Direction.EAST;

    private static RunnerClient.DebugResult debugResult;
    private static int stepIndex = -1;
    private static boolean active;
    private static Map<BlockPos, String> hoverLabels = Map.of();

    private DebugWorldRenderer() {
    }

    public static void register() {
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> renderHud(graphics));
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * Public entry point. In singleplayer this first moves the player into atcrafter:debug,
     * then the controller calls activateHere after the client has actually switched dimensions.
     */
    public static void activate(RunnerClient.DebugResult result, int requestedStep) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || result == null || result.steps().isEmpty()) {
            return;
        }

        if (!DebugDimensionController.isInDebugDimension(minecraft)) {
            DebugDimensionController.enter(result, requestedStep);
            return;
        }

        activateHere(result, requestedStep);
    }

    static void activateHere(RunnerClient.DebugResult result, int requestedStep) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || result == null || result.steps().isEmpty()) {
            return;
        }

        debugResult = result;
        active = true;
        setStep(requestedStep);
        minecraft.player.displayClientMessage(
            Component.literal("AtCrafter デバッグ世界: [ 前へ / ] 次へ / \\ 終了 / ブロックに照準で値表示"),
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
        rebuildBlocks();
        announceStep();
    }

    public static void deactivate() {
        active = false;
        hoverLabels = Map.of();
    }

    private static void rebuildBlocks() {
        if (!active || debugResult == null || stepIndex < 0 || stepIndex >= debugResult.steps().size()) {
            return;
        }

        RunnerClient.DebugStep step = debugResult.steps().get(stepIndex);
        List<Map.Entry<String, RunnerClient.DebugValue>> variables = step.typedLocals().entrySet().stream()
            .limit(MAX_VARIABLES)
            .toList();

        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        Map<BlockPos, String> labels = new LinkedHashMap<>();

        for (int row = 0; row < variables.size(); row++) {
            Map.Entry<String, RunnerClient.DebugValue> entry = variables.get(row);
            String name = entry.getKey();
            RunnerClient.DebugValue value = entry.getValue();
            BlockPos base = variableBase(row);

            if (value.isSequence() && !value.items().isEmpty()) {
                int count = Math.min(MAX_SEQUENCE_ITEMS, value.items().size());
                for (int i = 0; i < count; i++) {
                    RunnerClient.DebugValue item = value.items().get(i);
                    boolean changed = itemChanged(name, i, item);
                    BlockPos position = base.relative(RIGHT, i);
                    blocks.put(position, blockFor(item, changed));
                    labels.put(
                        position,
                        name + "[" + i + "] = " + truncateLabel(item.display(), 64)
                            + "  (" + item.type() + ")"
                            + (changed ? "  ← changed" : "")
                    );
                }
                continue;
            }

            boolean changed = valueChanged(name, value);
            blocks.put(base, blockFor(value, changed));
            labels.put(
                base,
                name + " = " + truncateLabel(value.display(), 72)
                    + "  (" + value.type() + ")"
                    + (changed ? "  ← changed" : "")
            );
        }

        BlockPos stdoutPosition = DebugDimensionController.DEBUG_ORIGIN.relative(RIGHT, -2);
        blocks.put(stdoutPosition, Blocks.GREEN_CONCRETE.defaultBlockState());
        String stdout = stdoutAtStep(stepIndex);
        labels.put(
            stdoutPosition,
            stdout.isEmpty()
                ? "stdout = (empty)"
                : "stdout = " + truncateLabel(stdout.replace('\n', ' '), 80)
        );

        hoverLabels = Map.copyOf(labels);
        DebugDimensionController.replaceDebugBlocks(blocks);
    }

    private static BlockState blockFor(RunnerClient.DebugValue value, boolean changed) {
        if (changed) {
            return Blocks.YELLOW_CONCRETE.defaultBlockState();
        }

        return switch (value.type()) {
            case "bool" -> Boolean.TRUE.equals(value.boolValue())
                ? Blocks.LIME_CONCRETE.defaultBlockState()
                : Blocks.RED_CONCRETE.defaultBlockState();
            case "int", "float" -> Blocks.LIGHT_BLUE_CONCRETE.defaultBlockState();
            case "str" -> Blocks.MAGENTA_CONCRETE.defaultBlockState();
            case "list", "tuple", "set", "frozenset" -> Blocks.CYAN_CONCRETE.defaultBlockState();
            case "dict" -> Blocks.ORANGE_CONCRETE.defaultBlockState();
            default -> Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        };
    }

    private static BlockPos variableBase(int row) {
        return DebugDimensionController.DEBUG_ORIGIN.relative(FORWARD, row * ROW_SPACING);
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

    private static void renderHud(GuiGraphics graphics) {
        if (!active) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.screen != null) {
            return;
        }
        if (!DebugDimensionController.isInDebugDimension(minecraft)) {
            return;
        }
        if (!(minecraft.hitResult instanceof BlockHitResult blockHit)) {
            return;
        }

        String label = hoverLabels.get(blockHit.getBlockPos());
        if (label == null || label.isBlank()) {
            return;
        }

        String visible = truncateLabel(label, 96);
        int textWidth = minecraft.font.width(visible);
        int x = (graphics.guiWidth() - textWidth) / 2;
        int y = graphics.guiHeight() / 2 + 18;

        graphics.fill(
            x - 5,
            y - 4,
            x + textWidth + 5,
            y + minecraft.font.lineHeight + 4,
            0xB0000000
        );
        graphics.drawString(minecraft.font, Component.literal(visible), x, y, 0xFFFFFF);
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
}
