package io.github.kineticnapier.atcrafter.client.debug;

import io.github.kineticnapier.atcrafter.AtCrafter;
import io.github.kineticnapier.atcrafter.client.runner.RunnerClient;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Moves the local singleplayer player into AtCrafter's dedicated debug dimension.
 *
 * <p>v0.1 is intentionally singleplayer-first, so this talks directly to the integrated
 * server instead of introducing networking just for dimension travel. The renderer stays
 * client-side; only the player's location/dimension is changed on the server.</p>
 */
public final class DebugDimensionController {
    public static final ResourceKey<Level> DEBUG_LEVEL = ResourceKey.create(
        Registries.DIMENSION,
        AtCrafter.id("debug")
    );

    private static final double DEBUG_X = 0.5;
    private static final double DEBUG_Y = -62.0;
    private static final double DEBUG_Z = 0.5;

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
                Component.literal("専用デバッグディメンションは現在シングルプレイ専用です。通常世界で表示します。"),
                false
            );
            DebugWorldRenderer.activate(result, step);
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
                            Component.literal("atcrafter:debug を読み込めませんでした。通常世界で表示します。"),
                            false
                        );
                    }
                    DebugWorldRenderer.activate(result, step);
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
                player.getYRot(),
                player.getXRot()
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
        DebugWorldRenderer.activate(result, step);
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

            // Recovery path: if Minecraft was restarted/crashed while inside atcrafter:debug,
            // there is no in-memory return point. Backslash still brings the player home.
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
