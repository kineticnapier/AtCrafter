package io.github.kineticnapier.atcrafter.client;

import io.github.kineticnapier.atcrafter.block.ModBlocks;
import io.github.kineticnapier.atcrafter.client.runner.RunnerClient;
import io.github.kineticnapier.atcrafter.client.screen.TerminalScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.world.InteractionResult;

public class AtCrafterClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> RunnerClient.tick());

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof TerminalScreen) {
                layoutTerminalButtons(screen, scaledWidth, scaledHeight);
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

    private static void layoutTerminalButtons(
        net.minecraft.client.gui.screens.Screen screen,
        int scaledWidth,
        int scaledHeight
    ) {
        int contentWidth = Math.min(scaledWidth - 24, 720);
        int contentX = (scaledWidth - contentWidth) / 2;
        int bottomY = scaledHeight - 24;
        int gap = 4;

        int debugTargetWidth = 120;
        int smallWidth = Math.max(52, Math.min(76, (contentWidth - debugTargetWidth - gap * 4) / 4));
        int debugWidth = Math.max(96, Math.min(140, contentWidth - smallWidth * 4 - gap * 4));
        int groupWidth = smallWidth * 4 + debugWidth + gap * 4;
        int x = contentX + Math.max(0, (contentWidth - groupWidth) / 2);

        Button sample = null;
        Button submit = null;
        Button debug = null;
        Button refresh = null;
        Button close = null;

        for (AbstractWidget widget : Screens.getButtons(screen)) {
            if (!(widget instanceof Button button)) {
                continue;
            }

            switch (button.getMessage().getString()) {
                case "サンプル" -> sample = button;
                case "提出" -> submit = button;
                case "このケースをデバッグ" -> debug = button;
                case "再読込" -> refresh = button;
                case "閉じる" -> close = button;
                default -> {
                }
            }
        }

        if (sample == null || submit == null || debug == null || refresh == null || close == null) {
            return;
        }

        x = placeButton(sample, x, bottomY, smallWidth, gap);
        x = placeButton(submit, x, bottomY, smallWidth, gap);
        x = placeButton(debug, x, bottomY, debugWidth, gap);
        x = placeButton(refresh, x, bottomY, smallWidth, gap);
        placeButton(close, x, bottomY, smallWidth, 0);
    }

    private static int placeButton(Button button, int x, int y, int width, int gap) {
        button.setX(x);
        button.setY(y);
        button.setWidth(width);
        return x + width + gap;
    }
}
