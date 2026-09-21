package io.github.kineticnapier.atcrafter.client;

import io.github.kineticnapier.atcrafter.block.ModBlocks;
import io.github.kineticnapier.atcrafter.client.runner.RunnerClient;
import io.github.kineticnapier.atcrafter.client.screen.TerminalScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;

public class AtCrafterClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> RunnerClient.tick());

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
