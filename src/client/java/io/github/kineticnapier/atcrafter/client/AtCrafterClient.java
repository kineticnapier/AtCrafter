package io.github.kineticnapier.atcrafter.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.kineticnapier.atcrafter.block.ModBlocks;
import io.github.kineticnapier.atcrafter.client.debug.DebugWorldRenderer;
import io.github.kineticnapier.atcrafter.client.runner.RunnerClient;
import io.github.kineticnapier.atcrafter.client.screen.TerminalScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;
import org.lwjgl.glfw.GLFW;

public class AtCrafterClient implements ClientModInitializer {
    private static final String KEY_CATEGORY = "key.categories.atcrafter";

    private KeyMapping debugPreviousKey;
    private KeyMapping debugNextKey;
    private KeyMapping debugExitKey;

    @Override
    public void onInitializeClient() {
        DebugWorldRenderer.register();

        this.debugPreviousKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.atcrafter.debug_previous",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_BRACKET,
            KEY_CATEGORY
        ));
        this.debugNextKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.atcrafter.debug_next",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT_BRACKET,
            KEY_CATEGORY
        ));
        this.debugExitKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.atcrafter.debug_exit",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_BACKSLASH,
            KEY_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            RunnerClient.tick();

            while (this.debugPreviousKey.consumeClick()) {
                DebugWorldRenderer.previousStep();
            }
            while (this.debugNextKey.consumeClick()) {
                DebugWorldRenderer.nextStep();
            }
            while (this.debugExitKey.consumeClick()) {
                DebugWorldRenderer.deactivate();
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide()) {
                return InteractionResult.PASS;
            }

            if (world.getBlockState(hitResult.getBlockPos()).is(ModBlocks.TERMINAL)) {
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.execute(() -> {
                    RunnerClient.checkNow();
                    minecraft.setScreen(new TerminalScreen());
                });
                return InteractionResult.SUCCESS;
            }

            return InteractionResult.PASS;
        });
    }
}
