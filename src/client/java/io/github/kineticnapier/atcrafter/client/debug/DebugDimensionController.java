package io.github.kineticnapier.atcrafter.client.debug;

import io.github.kineticnapier.atcrafter.AtCrafter;
import io.github.kineticnapier.atcrafter.client.runner.RunnerClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Moves the local singleplayer player into AtCrafter's dedicated debug dimension and owns
 * the real blocks, text-display nameplates and minecarts used by the debug visualizer.
 */
public final class DebugDimensionController {
    public static final ResourceKey<Level> DEBUG_LEVEL = ResourceKey.create(
        Registries.DIMENSION,
        AtCrafter.id("debug")
    );

    static final BlockPos DEBUG_ORIGIN = new BlockPos(0, -63, 0);
    static final int WORKSPACE_MIN_X = -4;
    static final int WORKSPACE_MAX_X = 28;
    static final int WORKSPACE_MIN_Z = -36;
    static final int WORKSPACE_MAX_Z = 2;

    private static final double DEBUG_X = 0.5;
    private static final double DEBUG_Y = -62.0;
    private static final double DEBUG_Z = 4.5;
    private static final float DEBUG_YAW = 180.0f;
    private static final float DEBUG_PITCH = 0.0f;
    private static final int MINECART_ANIMATION_TICKS = 12;

    private static final Set<BlockPos> placedBlocks = new HashSet<>();
    private static final List<Display.TextDisplay> placedLabels = new ArrayList<>();
    private static final List<Minecart> placedMinecarts = new ArrayList<>();
    private static final List<MinecartAnimation> minecartAnimations = new ArrayList<>();
    private static volatile boolean animationsActive;
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

            clearWorkspace(debugLevel);
            workspaceInitialized = true;

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
        tickMinecartAnimations(minecraft);

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

    /** Replace the current visualization with real blocks, labels and deque minecarts. */
    static void replaceDebugObjects(
        Map<BlockPos, BlockState> blocks,
        Map<BlockPos, Component> labels,
        Map<BlockPos, MinecartSpec> minecarts
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }

        Map<BlockPos, BlockState> requestedBlocks = Map.copyOf(blocks);
        Map<BlockPos, Component> requestedLabels = Map.copyOf(labels);
        Map<BlockPos, MinecartSpec> requestedMinecarts = Map.copyOf(minecarts);
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

            for (Map.Entry<BlockPos, MinecartSpec> entry : requestedMinecarts.entrySet()) {
                spawnMinecart(debugLevel, entry.getKey(), entry.getValue());
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
        display.setViewRange(1.0F);
        level.addFreshEntity(display);
        placedLabels.add(display);
    }

    private static void spawnMinecart(ServerLevel level, BlockPos railPosition, MinecartSpec spec) {
        Vec3 start = minecartPosition(railPosition);
        DebugMinecart minecart = new DebugMinecart(level, start.x, start.y, start.z);
        minecart.setDeltaMovement(Vec3.ZERO);
        minecart.setCustomName(spec.detail());
        minecart.setCustomNameVisible(spec.animate());
        minecart.setInvulnerable(true);
        minecart.setSilent(true);
        minecart.setNoGravity(true);
        minecart.noPhysics = true;
        level.addFreshEntity(minecart);
        placedMinecarts.add(minecart);

        if (spec.animate()) {
            minecartAnimations.add(new MinecartAnimation(
                minecart,
                start,
                minecartPosition(spec.target()),
                MINECART_ANIMATION_TICKS,
                spec.discardAtEnd()
            ));
            animationsActive = true;
        }
    }

    private static Vec3 minecartPosition(BlockPos railPosition) {
        return new Vec3(
            railPosition.getX() + 0.5,
            railPosition.getY() + 0.0625,
            railPosition.getZ() + 0.5
        );
    }

    private static void tickMinecartAnimations(Minecraft minecraft) {
        if (!animationsActive) {
            return;
        }

        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            animationsActive = false;
            return;
        }

        server.execute(() -> {
            if (minecartAnimations.isEmpty()) {
                animationsActive = false;
                return;
            }

            Iterator<MinecartAnimation> iterator = minecartAnimations.iterator();
            while (iterator.hasNext()) {
                MinecartAnimation animation = iterator.next();
                if (animation.minecart.isRemoved()) {
                    iterator.remove();
                    continue;
                }

                animation.elapsed++;
                double raw = Math.min(1.0, animation.elapsed / (double) animation.duration);
                double t = raw * raw * (3.0 - 2.0 * raw);
                Vec3 position = animation.start.lerp(animation.end, t);
                animation.minecart.setDeltaMovement(Vec3.ZERO);
                animation.minecart.setPos(position.x, position.y, position.z);

                if (animation.elapsed >= animation.duration) {
                    animation.minecart.setDeltaMovement(Vec3.ZERO);
                    if (animation.discardAtEnd) {
                        animation.minecart.discard();
                    } else {
                        animation.minecart.setCustomNameVisible(false);
                    }
                    iterator.remove();
                }
            }

            animationsActive = !minecartAnimations.isEmpty();
        });
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
        minecartAnimations.clear();
        animationsActive = false;

        for (BlockPos position : placedBlocks) {
            level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
        }
        placedBlocks.clear();

        AABB objectArea = new AABB(
            WORKSPACE_MIN_X,
            DEBUG_ORIGIN.getY(),
            WORKSPACE_MIN_Z,
            WORKSPACE_MAX_X + 1,
            DEBUG_ORIGIN.getY() + 6,
            WORKSPACE_MAX_Z + 1
        );

        // These entities persist with the dimension, while our in-memory lists do not.
        // Sweep the whole dedicated workspace so a crash/restart cannot leave ghosts behind.
        for (Display.TextDisplay label : level.getEntitiesOfClass(Display.TextDisplay.class, objectArea)) {
            label.discard();
        }
        for (Minecart minecart : level.getEntitiesOfClass(Minecart.class, objectArea)) {
            minecart.discard();
        }
        placedLabels.clear();
        placedMinecarts.clear();
    }

    private static void clearWorkspace(ServerLevel level) {
        clearPlacedObjects(level);
        for (int x = WORKSPACE_MIN_X; x <= WORKSPACE_MAX_X; x++) {
            for (int z = WORKSPACE_MIN_Z; z <= WORKSPACE_MAX_Z; z++) {
                level.setBlockAndUpdate(new BlockPos(x, DEBUG_ORIGIN.getY(), z), Blocks.AIR.defaultBlockState());
            }
        }
    }

    static record MinecartSpec(Component detail, BlockPos target, boolean animate, boolean discardAtEnd) {
        static MinecartSpec stable(Component detail, BlockPos position) {
            return new MinecartSpec(detail, position, false, false);
        }

        static MinecartSpec moving(Component detail, BlockPos target, boolean discardAtEnd) {
            return new MinecartSpec(detail, target, true, discardAtEnd);
        }
    }

    /**
     * A visual-only cart for the debugger. Vanilla minecarts push each other during their tick,
     * which made popleft/pop animations bump the stationary deque elements. These carts keep the
     * normal minecart renderer but opt out of entity collision; AtCrafter controls their animation
     * positions directly while gravity/normal collision movement is disabled.
     */
    private static final class DebugMinecart extends Minecart {
        private DebugMinecart(Level level, double x, double y, double z) {
            super(level, x, y, z);
        }

        @Override
        public boolean canCollideWith(Entity entity) {
            return false;
        }

        @Override
        public boolean isPushable() {
            return false;
        }

        @Override
        public void push(Entity entity) {
            // Debug visualization only: deque carts must never shove one another.
        }
    }

    private static final class MinecartAnimation {
        private final Minecart minecart;
        private final Vec3 start;
        private final Vec3 end;
        private final int duration;
        private final boolean discardAtEnd;
        private int elapsed;

        private MinecartAnimation(
            Minecart minecart,
            Vec3 start,
            Vec3 end,
            int duration,
            boolean discardAtEnd
        ) {
            this.minecart = minecart;
            this.start = start;
            this.end = end;
            this.duration = duration;
            this.discardAtEnd = discardAtEnd;
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
