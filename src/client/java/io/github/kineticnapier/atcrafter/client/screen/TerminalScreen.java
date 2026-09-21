package io.github.kineticnapier.atcrafter.client.screen;

import io.github.kineticnapier.atcrafter.client.runner.RunnerClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class TerminalScreen extends Screen {
    private static final Component TITLE = Component.literal("AtCrafter");

    public TerminalScreen() {
        super(TITLE);
    }

    @Override
    protected void init() {
        int buttonWidth = 120;
        int buttonHeight = 20;
        int x = (this.width - buttonWidth) / 2;
        int y = this.height / 2 + 35;

        addRenderableWidget(
            Button.builder(Component.literal("Refresh"), button -> RunnerClient.checkNow())
                .bounds(x, y - 25, buttonWidth, buttonHeight)
                .build()
        );

        addRenderableWidget(
            Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(x, y, buttonWidth, buttonHeight)
                .build()
        );
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int top = this.height / 2 - 55;
        RunnerClient.Status runnerStatus = RunnerClient.getStatus();

        graphics.drawCenteredString(this.font, TITLE, centerX, top, 0xFFFFFF);
        graphics.drawCenteredString(
            this.font,
            Component.literal(runnerStatus.label()),
            centerX,
            top + 28,
            runnerStatus.color()
        );
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
