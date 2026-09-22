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
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

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
        Map<BlockPos, DebugDimensionController.MinecartSpec> minecarts = new LinkedHashMap<>();

        for (int row = 0; row < variables.size(); row++) {
            Map.Entry<String, RunnerClient.DebugValue> entry = variables.get(row);
            String name = entry.getKey();
            RunnerClient.DebugValue value = entry.getValue();
            BlockPos base = variableBase(row);

            if (value.isDict() && !value.entries().isEmpty()) {
                renderDict(name, value, base, blocks, labels, nameplates);
                continue;
            }

            if (value.isDeque() && !value.items().isEmpty()) {
                renderDeque(name, value, base, blocks, labels, nameplates, minecarts);
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
        DebugDimensionController.replaceDebugObjects(blocks, nameplates, minecarts);
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

    private static void renderDeque(
        String name,
        RunnerClient.DebugValue value,
        BlockPos base,
        Map<BlockPos, BlockState> blocks,
        Map<BlockPos, String> labels,
        Map<BlockPos, Component> nameplates,
        Map<BlockPos, DebugDimensionController.MinecartSpec> minecarts
    ) {
        int count = Math.min(MAX_SEQUENCE_ITEMS, value.items().size());
        boolean actualBackVisible = count == value.items().size() && !value.truncated();
        BlockState eastWestRail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST);
        DequeTransition transition = detectDequeTransition(name, value);
        RunnerClient.DebugValue previousDeque = previousValue(name);
        int previousCount = previousDeque != null && previousDeque.isDeque()
            ? Math.min(MAX_SEQUENCE_ITEMS, previousDeque.items().size())
            : count;

        int maxRailX = Math.max(4, Math.max(count, previousCount) * 2 + 4);
        for (int x = -4; x <= maxRailX; x++) {
            blocks.put(base.relative(RIGHT, x), eastWestRail);
        }

        for (int i = 0; i < count; i++) {
            RunnerClient.DebugValue item = value.items().get(i);
            RunnerClient.DebugValue previous = previousDequeItem(name, transition, i);
            boolean changed = previousDeque != null && previous == null;
            BlockPos position = base.relative(RIGHT, i * 2);

            boolean front = i == 0;
            boolean back = actualBackVisible && i == count - 1;
            String itemName;
            if (front && back) {
                itemName = name + "  FRONT / BACK";
            } else if (front) {
                itemName = name + "  FRONT";
            } else if (back) {
                itemName = name + "  BACK";
            } else {
                itemName = name + "[" + i + "]";
            }

            String detail = name + "[" + i + "] = " + truncateLabel(item.display(), 64)
                + "  (" + item.type() + ")"
                + changeSuffix(changed, previous);

            labels.put(position, detail);
            nameplates.put(
                position,
                twoLineNameplate(
                    itemName,
                    truncateLabel(item.display(), 28),
                    changed ? ChatFormatting.YELLOW : ChatFormatting.LIGHT_PURPLE
                )
            );

            boolean incomingAppend = transition.kind() == DequeOperation.APPEND && i == count - 1;
            boolean incomingAppendLeft = transition.kind() == DequeOperation.APPEND_LEFT && i == 0;
            Component minecartDetail = Component.literal(detail);

            if (incomingAppend) {
                BlockPos start = position.relative(RIGHT, 2);
                minecarts.put(
                    start,
                    DebugDimensionController.MinecartSpec.moving(minecartDetail, position, false)
                );
            } else if (incomingAppendLeft) {
                BlockPos start = position.relative(RIGHT, -2);
                minecarts.put(
                    start,
                    DebugDimensionController.MinecartSpec.moving(minecartDetail, position, false)
                );
            } else {
                minecarts.put(position, DebugDimensionController.MinecartSpec.stable(minecartDetail, position));
            }
        }

        if (transition.kind() == DequeOperation.POP_LEFT && transition.item() != null) {
            BlockPos start = base.relative(RIGHT, -1);
            BlockPos target = base.relative(RIGHT, -4);
            Component detail = Component.literal(
                name + ".popleft() → " + truncateLabel(transition.item().display(), 40)
            ).withStyle(ChatFormatting.RED);
            minecarts.put(start, DebugDimensionController.MinecartSpec.moving(detail, target, true));
        } else if (transition.kind() == DequeOperation.POP && transition.item() != null) {
            int oldLastIndex = previousDeque == null ? count : previousDeque.items().size() - 1;
            BlockPos start = base.relative(RIGHT, oldLastIndex * 2);
            BlockPos target = start.relative(RIGHT, 4);
            Component detail = Component.literal(
                name + ".pop() → " + truncateLabel(transition.item().display(), 40)
            ).withStyle(ChatFormatting.RED);
            minecarts.put(start, DebugDimensionController.MinecartSpec.moving(detail, target, true));
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

    private static RunnerClient.DebugValue previousDequeItem(
        String name,
        DequeTransition transition,
        int currentIndex
    ) {
        RunnerClient.DebugValue previous = previousValue(name);
        if (previous == null || !previous.isDeque()) {
            return null;
        }

        int previousIndex = switch (transition.kind()) {
            case APPEND, POP, NONE -> currentIndex;
            case APPEND_LEFT -> currentIndex - 1;
            case POP_LEFT -> currentIndex + 1;
        };

        if (previousIndex < 0 || previousIndex >= previous.items().size()) {
            return null;
        }
        return previous.items().get(previousIndex);
    }

    private static DequeTransition detectDequeTransition(String name, RunnerClient.DebugValue current) {
        RunnerClient.DebugValue previous = previousValue(name);
        if (previous == null || !previous.isDeque() || !current.isDeque()) {
            return DequeTransition.none();
        }
        if (previous.truncated() || current.truncated()) {
            return DequeTransition.none();
        }
        if (previous.items().size() > MAX_SEQUENCE_ITEMS || current.items().size() > MAX_SEQUENCE_ITEMS) {
            return DequeTransition.none();
        }

        List<RunnerClient.DebugValue> before = previous.items();
        List<RunnerClient.DebugValue> after = current.items();

        if (after.size() == before.size() + 1) {
            if (sameRange(before, 0, after, 0, before.size())) {
                return new DequeTransition(DequeOperation.APPEND, after.get(after.size() - 1));
            }
            if (sameRange(before, 0, after, 1, before.size())) {
                return new DequeTransition(DequeOperation.APPEND_LEFT, after.get(0));
            }
        }

        if (after.size() + 1 == before.size()) {
            if (sameRange(after, 0, before, 1, after.size())) {
                return new DequeTransition(DequeOperation.POP_LEFT, before.get(0));
            }
            if (sameRange(after, 0, before, 0, after.size())) {
                return new DequeTransition(DequeOperation.POP, before.get(before.size() - 1));
            }
        }

        return DequeTransition.none();
    }

    private static boolean sameRange(
        List<RunnerClient.DebugValue> left,
        int leftStart,
        List<RunnerClient.DebugValue> right,
        int rightStart,
        int length
    ) {
        if (leftStart < 0 || rightStart < 0
            || leftStart + length > left.size()
            || rightStart + length > right.size()) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (!sameValue(left.get(leftStart + i), right.get(rightStart + i))) {
                return false;
            }
        }
        return true;
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
        String dequeOperation = dequeOperationSummary(step);
        minecraft.player.displayClientMessage(
            Component.literal(
                "AtCrafter " + (stepIndex + 1) + "/" + debugResult.steps().size()
                    + "  行 " + step.line()
                    + (dequeOperation.isEmpty() ? "" : "  |  " + dequeOperation)
            ),
            true
        );
    }

    private static String dequeOperationSummary(RunnerClient.DebugStep step) {
        for (Map.Entry<String, RunnerClient.DebugValue> entry : step.typedLocals().entrySet()) {
            RunnerClient.DebugValue value = entry.getValue();
            if (!value.isDeque()) {
                continue;
            }
            DequeTransition transition = detectDequeTransition(entry.getKey(), value);
            if (transition.kind() == DequeOperation.NONE || transition.item() == null) {
                continue;
            }
            String item = truncateLabel(transition.item().display(), 24);
            return switch (transition.kind()) {
                case APPEND -> entry.getKey() + ".append(" + item + ")";
                case APPEND_LEFT -> entry.getKey() + ".appendleft(" + item + ")";
                case POP -> entry.getKey() + ".pop() → " + item;
                case POP_LEFT -> entry.getKey() + ".popleft() → " + item;
                case NONE -> "";
            };
        }
        return "";
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

        String label = null;
        if (minecraft.hitResult instanceof BlockHitResult blockHit) {
            label = hoverLabels.get(blockHit.getBlockPos());
        } else if (minecraft.hitResult instanceof EntityHitResult entityHit
            && entityHit.getEntity() instanceof Minecart minecart
            && minecart.getCustomName() != null) {
            label = minecart.getCustomName().getString();
        }

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

    private enum DequeOperation {
        NONE,
        APPEND,
        APPEND_LEFT,
        POP,
        POP_LEFT
    }

    private record DequeTransition(DequeOperation kind, RunnerClient.DebugValue item) {
        private static DequeTransition none() {
            return new DequeTransition(DequeOperation.NONE, null);
        }
    }
}
