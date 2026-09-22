package io.github.kineticnapier.atcrafter.client.debug;

import io.github.kineticnapier.atcrafter.client.runner.RunnerClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.ChatFormatting;
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
            Component.literal("AtCrafter デバッグ世界: [ 前へ / ] 次へ / \\ 終了 / 照準で詳細表示"),
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
        Map<BlockPos, Component> nameplates = new LinkedHashMap<>();

        for (int row = 0; row < variables.size(); row++) {
            Map.Entry<String, RunnerClient.DebugValue> entry = variables.get(row);
            String name = entry.getKey();
            RunnerClient.DebugValue value = entry.getValue();
            BlockPos base = variableBase(row);

            if (value.isDict() && !value.entries().isEmpty()) {
                renderDict(name, value, base, blocks, labels, nameplates);
                continue;
            }

            if (value.isSequence() && !value.items().isEmpty()) {
                renderSequence(name, value, base, blocks, labels, nameplates);
                continue;
            }

            renderScalar(name, value, base, blocks, labels, nameplates);
        }

        renderStdoutBoard(blocks, labels, nameplates);

        hoverLabels = Map.copyOf(labels);
        DebugDimensionController.replaceDebugBlocks(blocks, nameplates);
    }

    private static void renderScalar(
        String name,
        RunnerClient.DebugValue value,
        BlockPos position,
        Map<BlockPos, BlockState> blocks,
        Map<BlockPos, String> labels,
        Map<BlockPos, Component> nameplates
    ) {
        RunnerClient.DebugValue previous = previousValue(name);
        boolean changed = previous != null && !sameValue(previous, value);
        blocks.put(position, blockFor(value, changed));
        labels.put(
            position,
            name + " = " + truncateLabel(value.display(), 72)
                + "  (" + value.type() + ")"
                + changeSuffix(changed, previous)
        );
        nameplates.put(
            position,
            twoLineNameplate(
                name,
                truncateLabel(value.display(), 32),
                changed ? ChatFormatting.YELLOW : ChatFormatting.WHITE
            )
        );
    }

    private static void renderSequence(
        String name,
        RunnerClient.DebugValue value,
        BlockPos base,
        Map<BlockPos, BlockState> blocks,
        Map<BlockPos, String> labels,
        Map<BlockPos, Component> nameplates
    ) {
        int count = Math.min(MAX_SEQUENCE_ITEMS, value.items().size());
        boolean actualBackVisible = count == value.items().size() && !value.truncated();

        for (int i = 0; i < count; i++) {
            RunnerClient.DebugValue item = value.items().get(i);
            RunnerClient.DebugValue previous = previousSequenceItem(name, value, i, item);
            boolean changed = sequenceItemChanged(name, value, i, item, previous);
            BlockPos position = base.relative(RIGHT, i);
            blocks.put(position, collectionItemBlock(value, item, changed));

            String itemName;
            String hoverName;
            if (value.isSetLike()) {
                itemName = name;
                hoverName = name + " contains " + truncateLabel(item.display(), 48);
            } else if (value.isDeque()) {
                boolean front = i == 0;
                boolean back = actualBackVisible && i == count - 1;
                if (front && back) {
                    itemName = name + "  FRONT / BACK";
                } else if (front) {
                    itemName = name + "  FRONT";
                } else if (back) {
                    itemName = name + "  BACK";
                } else {
                    itemName = name;
                }
                hoverName = name + "[" + i + "]";
            } else {
                itemName = name + "[" + i + "]";
                hoverName = itemName;
            }

            labels.put(
                position,
                hoverName + " = " + truncateLabel(item.display(), 64)
                    + "  (" + item.type() + ")"
                    + changeSuffix(changed, previous)
            );
            nameplates.put(
                position,
                twoLineNameplate(
                    itemName,
                    truncateLabel(item.display(), 28),
                    changed ? ChatFormatting.YELLOW : ChatFormatting.WHITE
                )
            );
        }
    }

    private static void renderDict(
        String name,
        RunnerClient.DebugValue value,
        BlockPos base,
        Map<BlockPos, BlockState> blocks,
        Map<BlockPos, String> labels,
        Map<BlockPos, Component> nameplates
    ) {
        int count = Math.min(MAX_SEQUENCE_ITEMS, value.entries().size());
        RunnerClient.DebugValue previousDict = previousValue(name);

        for (int i = 0; i < count; i++) {
            RunnerClient.DebugEntry entry = value.entries().get(i);
            RunnerClient.DebugEntry previousEntry = previousDictEntry(name, entry.key());
            boolean dictExisted = previousDict != null && previousDict.isDict();
            boolean keyChanged = dictExisted && previousEntry == null;
            boolean valueChanged = dictExisted
                && (previousEntry == null || !sameValue(previousEntry.value(), entry.value()));

            BlockPos keyPosition = base.relative(RIGHT, i);
            BlockPos valuePosition = base.relative(FORWARD, 1).relative(RIGHT, i);

            blocks.put(
                keyPosition,
                keyChanged ? Blocks.YELLOW_CONCRETE.defaultBlockState() : Blocks.ORANGE_CONCRETE.defaultBlockState()
            );
            blocks.put(valuePosition, blockFor(entry.value(), valueChanged));

            labels.put(
                keyPosition,
                name + " key = " + truncateLabel(entry.key().display(), 64)
                    + "  (" + entry.key().type() + ")"
                    + (keyChanged ? "  ← new key" : "")
            );
            labels.put(
                valuePosition,
                name + "[" + truncateLabel(entry.key().display(), 36) + "] = "
                    + truncateLabel(entry.value().display(), 60)
                    + "  (" + entry.value().type() + ")"
                    + changeSuffix(valueChanged, previousEntry == null ? null : previousEntry.value())
            );

            nameplates.put(
                keyPosition,
                twoLineNameplate(
                    name + " key",
                    truncateLabel(entry.key().display(), 24),
                    keyChanged ? ChatFormatting.YELLOW : ChatFormatting.GOLD
                )
            );
            nameplates.put(
                valuePosition,
                twoLineNameplate(
                    name + "[" + truncateLabel(entry.key().display(), 18) + "]",
                    truncateLabel(entry.value().display(), 24),
                    valueChanged ? ChatFormatting.YELLOW : ChatFormatting.WHITE
                )
            );
        }
    }

    private static void renderStdoutBoard(
        Map<BlockPos, BlockState> blocks,
        Map<BlockPos, String> labels,
        Map<BlockPos, Component> nameplates
    ) {
        String stdout = stdoutAtStep(stepIndex);
        String hover = stdout.isEmpty()
            ? "stdout = (empty)"
            : "stdout = " + truncateLabel(stdout.replace('\n', ' '), 120);

        BlockPos boardBase = DebugDimensionController.DEBUG_ORIGIN.relative(RIGHT, -4);
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 2; y++) {
                BlockPos position = boardBase.relative(RIGHT, x).above(y);
                blocks.put(position, Blocks.BLACK_CONCRETE.defaultBlockState());
                labels.put(position, hover);
            }
        }

        BlockPos titlePosition = boardBase.relative(RIGHT, 1).above(1);
        nameplates.put(titlePosition, stdoutNameplate(stdout));
    }

    private static Component stdoutNameplate(String stdout) {
        String body = stdout.isEmpty() ? "(empty)" : compactStdout(stdout);
        return Component.literal("stdout\n")
            .withStyle(ChatFormatting.GREEN)
            .append(Component.literal(body).withStyle(ChatFormatting.WHITE));
    }

    private static String compactStdout(String stdout) {
        String normalized = stdout.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder result = new StringBuilder();
        int count = Math.min(3, lines.length);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                result.append('\n');
            }
            result.append(truncateLabel(lines[i], 42));
        }
        if (lines.length > count) {
            result.append("\n...");
        }
        return result.toString();
    }

    private static Component twoLineNameplate(String name, String value, ChatFormatting color) {
        return Component.literal(name + "\n" + value).withStyle(color);
    }

    private static BlockState collectionItemBlock(
        RunnerClient.DebugValue container,
        RunnerClient.DebugValue item,
        boolean changed
    ) {
        if (changed) {
            return Blocks.YELLOW_CONCRETE.defaultBlockState();
        }
        if (container.isDeque()) {
            return Blocks.PURPLE_CONCRETE.defaultBlockState();
        }
        if (container.isSetLike()) {
            return Blocks.CYAN_CONCRETE.defaultBlockState();
        }
        return blockFor(item, false);
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
            case "deque" -> Blocks.PURPLE_CONCRETE.defaultBlockState();
            case "dict" -> Blocks.ORANGE_CONCRETE.defaultBlockState();
            default -> Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        };
    }

    private static BlockPos variableBase(int row) {
        return DebugDimensionController.DEBUG_ORIGIN.relative(FORWARD, row * ROW_SPACING);
    }

    private static RunnerClient.DebugValue previousSequenceItem(
        String name,
        RunnerClient.DebugValue container,
        int index,
        RunnerClient.DebugValue item
    ) {
        RunnerClient.DebugValue previous = previousValue(name);
        if (previous == null || !previous.isSequence()) {
            return null;
        }

        if (container.isSetLike()) {
            for (RunnerClient.DebugValue oldItem : previous.items()) {
                if (sameValue(oldItem, item)) {
                    return oldItem;
                }
            }
            return null;
        }

        if (index >= previous.items().size()) {
            return null;
        }
        return previous.items().get(index);
    }

    private static boolean sequenceItemChanged(
        String name,
        RunnerClient.DebugValue container,
        int index,
        RunnerClient.DebugValue item,
        RunnerClient.DebugValue previousItem
    ) {
        RunnerClient.DebugValue previous = previousValue(name);
        if (previous == null || !previous.isSequence()) {
            return false;
        }
        if (container.isSetLike()) {
            return previousItem == null;
        }
        return previousItem == null || !sameValue(previousItem, item);
    }

    private static RunnerClient.DebugEntry previousDictEntry(String name, RunnerClient.DebugValue key) {
        RunnerClient.DebugValue previous = previousValue(name);
        if (previous == null || !previous.isDict()) {
            return null;
        }
        for (RunnerClient.DebugEntry entry : previous.entries()) {
            if (sameValue(entry.key(), key)) {
                return entry;
            }
        }
        return null;
    }

    private static RunnerClient.DebugValue previousValue(String name) {
        if (debugResult == null || stepIndex <= 0) {
            return null;
        }
        return debugResult.steps().get(stepIndex - 1).typedLocals().get(name);
    }

    private static boolean sameValue(RunnerClient.DebugValue left, RunnerClient.DebugValue right) {
        return left != null
            && right != null
            && left.type().equals(right.type())
            && left.display().equals(right.display());
    }

    private static String changeSuffix(boolean changed, RunnerClient.DebugValue previous) {
        if (!changed) {
            return "";
        }
        if (previous == null) {
            return "  ← changed  previous: (missing)";
        }
        return "  ← changed  previous: " + truncateLabel(previous.display(), 36);
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

        String visible = truncateLabel(label, 128);
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
