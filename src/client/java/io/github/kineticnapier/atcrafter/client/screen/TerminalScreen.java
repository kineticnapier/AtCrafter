package io.github.kineticnapier.atcrafter.client.screen;

import io.github.kineticnapier.atcrafter.client.runner.RunnerClient;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public final class TerminalScreen extends Screen {
    private static final Component TITLE = Component.literal("AtCrafter");

    private MultiLineEditBox codeBox;
    private MultiLineEditBox stdinBox;
    private Button runButton;

    private boolean running;
    private String resultText = "Run some Python code.";
    private int resultColor = 0xA0A0A0;

    public TerminalScreen() {
        super(TITLE);
    }

    @Override
    protected void init() {
        int margin = 10;
        int gap = 12;
        int contentWidth = Math.min(this.width - margin * 2, 760);
        int contentX = (this.width - contentWidth) / 2;
        int leftWidth = Math.max(120, (contentWidth - gap) / 2);
        int rightWidth = contentWidth - leftWidth - gap;

        int codeY = 55;
        int controlsY = this.height - 24;
        int editorBottom = controlsY - 8;
        int stdinHeight = 36;
        int codeHeight = Math.max(54, editorBottom - codeY - stdinHeight - 22);
        int stdinY = codeY + codeHeight + 17;

        this.codeBox = new MultiLineEditBox(
            this.font,
            contentX,
            codeY,
            leftWidth,
            codeHeight,
            Component.literal("Python code"),
            Component.literal("Python code editor")
        );
        this.codeBox.setCharacterLimit(32_000);
        this.codeBox.setValue("print(1 + 2)");
        addRenderableWidget(this.codeBox);

        this.stdinBox = new MultiLineEditBox(
            this.font,
            contentX,
            stdinY,
            leftWidth,
            stdinHeight,
            Component.literal("stdin"),
            Component.literal("Standard input")
        );
        this.stdinBox.setCharacterLimit(32_000);
        addRenderableWidget(this.stdinBox);

        int buttonWidth = Math.min(90, Math.max(60, (contentWidth - gap * 2) / 3));
        int totalButtonWidth = buttonWidth * 3 + gap * 2;
        int buttonX = contentX + (contentWidth - totalButtonWidth) / 2;

        this.runButton = addRenderableWidget(
            Button.builder(Component.literal("Run"), button -> runCode())
                .bounds(buttonX, controlsY, buttonWidth, 20)
                .build()
        );

        addRenderableWidget(
            Button.builder(Component.literal("Refresh"), button -> RunnerClient.checkNow())
                .bounds(buttonX + buttonWidth + gap, controlsY, buttonWidth, 20)
                .build()
        );

        addRenderableWidget(
            Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(buttonX + (buttonWidth + gap) * 2, controlsY, buttonWidth, 20)
                .build()
        );

        this.runButton.active = RunnerClient.getStatus() == RunnerClient.Status.ONLINE;
    }

    @Override
    public void tick() {
        super.tick();
        this.runButton.active = !this.running && RunnerClient.getStatus() == RunnerClient.Status.ONLINE;
    }

    private void runCode() {
        if (this.running || RunnerClient.getStatus() != RunnerClient.Status.ONLINE) {
            return;
        }

        this.running = true;
        this.runButton.active = false;
        this.runButton.setMessage(Component.literal("Running..."));
        this.resultText = "Running...";
        this.resultColor = 0xE0E0E0;

        RunnerClient.run(this.codeBox.getValue(), this.stdinBox.getValue())
            .whenComplete((result, error) -> {
                if (this.minecraft == null) {
                    return;
                }

                this.minecraft.execute(() -> {
                    this.running = false;
                    this.runButton.setMessage(Component.literal("Run"));
                    this.runButton.active = RunnerClient.getStatus() == RunnerClient.Status.ONLINE;

                    if (error != null) {
                        this.resultText = "Runner error:\n" + rootMessage(error);
                        this.resultColor = 0xFF5555;
                        RunnerClient.checkNow();
                        return;
                    }

                    StringBuilder text = new StringBuilder();
                    if (!result.stdout().isEmpty()) {
                        text.append("stdout:\n").append(result.stdout());
                    }
                    if (!result.stderr().isEmpty()) {
                        if (!text.isEmpty()) {
                            text.append("\n");
                        }
                        text.append("stderr:\n").append(result.stderr());
                    }
                    if (text.isEmpty()) {
                        text.append("(no output)");
                    }

                    text.append("\n\n");
                    if (result.timedOut()) {
                        text.append("TLE");
                    } else {
                        text.append("exit=").append(result.exitCode());
                    }
                    text.append("  ").append(String.format("%.3f ms", result.elapsedMs()));
                    if (result.outputTruncated()) {
                        text.append("  [output truncated]");
                    }

                    this.resultText = text.toString();
                    this.resultColor = result.timedOut() || (result.exitCode() != null && result.exitCode() != 0)
                        ? 0xFFAA55
                        : 0xFFFFFF;
                });
            });
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        int margin = 10;
        int gap = 12;
        int contentWidth = Math.min(this.width - margin * 2, 760);
        int contentX = (this.width - contentWidth) / 2;
        int leftWidth = Math.max(120, (contentWidth - gap) / 2);
        int rightX = contentX + leftWidth + gap;
        int rightWidth = contentWidth - leftWidth - gap;
        int codeY = 55;
        int controlsY = this.height - 24;
        int editorBottom = controlsY - 8;
        RunnerClient.Status runnerStatus = RunnerClient.getStatus();

        graphics.drawCenteredString(this.font, TITLE, this.width / 2, 12, 0xFFFFFF);
        graphics.drawCenteredString(
            this.font,
            Component.literal(runnerStatus.label()),
            this.width / 2,
            28,
            runnerStatus.color()
        );

        graphics.drawString(this.font, Component.literal("Code"), contentX, codeY - 12, 0xE0E0E0);
        graphics.drawString(this.font, Component.literal("stdin"), contentX, this.stdinBox.getY() - 12, 0xE0E0E0);
        graphics.drawString(this.font, Component.literal("Output"), rightX, codeY - 12, 0xE0E0E0);

        graphics.fill(rightX, codeY, rightX + rightWidth, editorBottom, 0x66000000);
        renderOutput(graphics, rightX + 5, codeY + 5, rightWidth - 10, editorBottom - codeY - 10);
    }

    private void renderOutput(GuiGraphics graphics, int x, int y, int width, int height) {
        int lineHeight = this.font.lineHeight + 1;
        int maxLines = Math.max(1, height / lineHeight);
        List<FormattedCharSequence> lines = this.font.split(Component.literal(this.resultText), Math.max(10, width));

        int count = Math.min(maxLines, lines.size());
        for (int i = 0; i < count; i++) {
            graphics.drawString(this.font, lines.get(i), x, y + i * lineHeight, this.resultColor);
        }

        if (lines.size() > maxLines && maxLines > 0) {
            graphics.drawString(this.font, "...", x, y + (maxLines - 1) * lineHeight, 0xA0A0A0);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
