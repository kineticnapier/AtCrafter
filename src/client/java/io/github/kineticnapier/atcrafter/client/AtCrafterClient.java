package io.github.kineticnapier.atcrafter.client;

import io.github.kineticnapier.atcrafter.block.ModBlocks;
import io.github.kineticnapier.atcrafter.client.screen.TerminalScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;

public class AtCrafterClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.getBlockState(hitResult.getBlockPos()).is(ModBlocks.TERMINAL)) {
                Minecraft.getInstance().setScreen(new TerminalScreen());
                return InteractionResult.SUCCESS;
            }

            return InteractionResult.PASS;
        });
    }
}
