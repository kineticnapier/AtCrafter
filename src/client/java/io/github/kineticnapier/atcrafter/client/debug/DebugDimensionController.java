package io.github.kineticnapier.atcrafter.client.debug;

import io.github.kineticnapier.atcrafter.AtCrafter;
import io.github.kineticnapier.atcrafter.client.runner.RunnerClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

/**
 * Moves the local singleplayer player into AtCrafter's dedicated debug dimension and owns
 * the real blocks and text-display nameplates used by the debug visualizer.
 */
public final class DebugDimensionController {
    public static final ResourceKey<Level> DEBUG_LEVEL = ResourceKey.create(
        Registries.DIMENSION,
        AtCrafter.id("debug")
    );

    static final BlockPos DEBUG_ORIGIN = new BlockPos(0, -63, 0);
    static final int WORKSPACE_MIN_X = -4;
    static final int WORKSPACE_MAX_X = 14;
    static final int WORKSPACE_MIN_Z = -36;
    static final int WORKSPACE_MAX_Z = 2;

    private static final double DEBUG_X = 0.5;
    private static final double DEBUG_Y = -62.0;
    private static final double DEBUG_Z = 4.5;
    private static final float DEBUG_YAW = 180.0f;
    private static final float DEBUG_PITCH = 0.0f;

    private static final Set<BlockPos> placedBlocks = new HashSet<>();
    private static final List<Display.TextDisplay> placedLabels = new ArrayList<>();
    private static boolean workspaceInitialized;

    private static volatile ReturnPoint returnPoint;
    private static volatile RunnerClient.DebugResult pendingResult;
    private static volatile int pendingStep = -1;
    private static volatile boolean waitingForDebugDimension;

    private DebugDimensionController() {
    }

    public static void enter(RunnerClient.DebugResult result, int step) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || result == null || result.steps().isEmpty()) {
            return;
        }

        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            minecraft.player.displayClientMessage(
                Component.literal("専用デバッグディメンションは現在シングルプレイ専用です。"),
                false
            );
            return;
        }

        UUID playerId = minecraft.player.getUUID();
        pendingResult = result;
        pendingStep = Math.max(0, Math.min(step, result.steps().size() - 1));
        waitingForDebugDimension = true;

        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            ServerLevel debugLevel = server.getLevel(DEBUG_LEVEL);
            if (player == null || debugLevel == null) {
                waitingForDebugDimension = false;
                minecraft.execute(() -> {
                    if (minecraft.player != null) {
                        minecraft.player.displayClientMessage(
                            Component.literal("atcrafter:debug を読み込めませんでした。"),
                            false
                        );
                    }
                });
                return;
            }

            if (!player.level().dimension().equals(DEBUG_LEVEL)) {
                returnPoint = new ReturnPoint(
                    player.level().dimension(),
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    player.getYRot(),
                    player.getXRot()
                );
            }

            player.teleportTo(
                debugLevel,
                DEBUG_X,
                DEBUG_Y,
                DEBUG_Z,
                DEBUG_YAW,
                DEBUG_PITCH
            );
        });
    }

    public static void tick(Minecraft minecraft) {
        if (!waitingForDebugDimension || pendingResult == null || minecraft.player == null || minecraft.level == null) {
            return;
        }

        if (!minecraft.level.dimension().equals(DEBUG_LEVEL)) {
            return;
        }

        RunnerClient.DebugResult result = pendingResult;
        int step = pendingStep;
        pendingResult = null;
        pendingStep = -1;
        waitingForDebugDimension = false;
        DebugWorldRenderer.activateHere(result, step);
    }

    /** Replace the current visualization with real blocks and server-side text displays. */
    static void replaceDebugBlocks(Map<BlockPos, BlockState> blocks, Map<BlockPos, Component> labels) {
        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }

        Map<BlockPos, BlockState> requestedBlocks = Map.copyOf(blocks);
        Map<BlockPos, Component> requestedLabels = Map.copyOf(labels);
        server.execute(() -> {
            ServerLevel debugLevel = server.getLevel(DEBUG_LEVEL);
            if (debugLevel == null) {
                return;
            }

            if (!workspaceInitialized) {
                clearWorkspace(debugLevel);
                workspaceInitialized = true;
            } else {
                clearPlacedObjects(debugLevel);
            }

            for (Map.Entry<BlockPos, BlockState> entry : requestedBlocks.entrySet()) {
                BlockPos position = entry.getKey().immutable();
                debugLevel.setBlockAndUpdate(position, entry.getValue());
                placedBlocks.add(position);
            }

            for (Map.Entry<BlockPos, Component> entry : requestedLabels.entrySet()) {
                spawnNameplate(debugLevel, entry.getKey(), entry.getValue());
            }
        });
    }

    private static void spawnNameplate(ServerLevel level, BlockPos position, Component text) {
        Display.TextDisplay display = EntityType.TEXT_DISPLAY.create(level);
        if (display == null) {
            return;
        }

        display.setPos(position.getX() + 0.5, position.getY() + 1.35, position.getZ() + 0.5);
        display.setText(text);
        display.setLineWidth(1000);
        display.setBackgroundColor(0x50000000);
        display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
        display.setScale(new Vector3f(0.8f, 0.8f, 0.8f));
        display.setViewRange(1.0F);
        level.addFreshEntity(display);
        placedLabels.add(display);
    }

    public static void exit() {
        Minecraft minecraft = Minecraft.getInstance();
        DebugWorldRenderer.deactivate();
        pendingResult = null;
        pendingStep = -1;
        waitingForDebugDimension = false;

        if (minecraft.player == null) {
            return;
        }

        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }

        UUID playerId = minecraft.player.getUUID();
        ReturnPoint destination = returnPoint;
        returnPoint = null;

        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                return;
            }

            ServerLevel debugLevel = server.getLevel(DEBUG_LEVEL);
            if (debugLevel != null) {
                clearPlacedObjects(debugLevel);
            }
            workspaceInitialized = false;

            if (destination != null) {
                ServerLevel target = server.getLevel(destination.dimension());
                if (target != null) {
                    player.teleportTo(
                        target,
                        destination.x(),
                        destination.y(),
                        destination.z(),
                        destination.yaw(),
                        destination.pitch()
                    );
                    return;
                }
            }

            ServerLevel overworld = server.overworld();
            BlockPos spawn = overworld.getSharedSpawnPos();
            player.teleportTo(
                overworld,
                spawn.getX() + 0.5,
                spawn.getY() + 1.0,
                spawn.getZ() + 0.5,
                player.getYRot(),
                player.getXRot()
            );
        });
    }

    public static boolean isInDebugDimension(Minecraft minecraft) {
        return minecraft.level != null && minecraft.level.dimension().equals(DEBUG_LEVEL);
    }

    private static void clearPlacedObjects(ServerLevel level) {
        for (BlockPos position : placedBlocks) {
            level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
        }
        placedBlocks.clear();

        for (Display.TextDisplay label : placedLabels) {
            if (!label.isRemoved()) {
                label.discard();
            }
        }
        placedLabels.clear();
    }

    private static void clearWorkspace(ServerLevel level) {
        clearPlacedObjects(level);
        for (int x = WORKSPACE_MIN_X; x <= WORKSPACE_MAX_X; x++) {
            for (int z = WORKSPACE_MIN_Z; z <= WORKSPACE_MAX_Z; z++) {
                level.setBlockAndUpdate(new BlockPos(x, DEBUG_ORIGIN.getY(), z), Blocks.AIR.defaultBlockState());
            }
        }
    }

    private record ReturnPoint(
        ResourceKey<Level> dimension,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
    ) {
    }
}
