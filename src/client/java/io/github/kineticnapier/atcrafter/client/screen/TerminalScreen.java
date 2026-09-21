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
    private static final String PROBLEM_TITLE = "A - Sum";

    private static final List<TestCase> SAMPLE_TESTS = List.of(
        new TestCase("Sample 1", "1 2 3 4 5\n", "15\n")
    );

    private static final List<TestCase> SUBMIT_TESTS = List.of(
        new TestCase("Test 1", "1 2 3 4 5\n", "15\n"),
        new TestCase("Test 2", "10\n", "10\n"),
        new TestCase("Test 3", "-5 2 9\n", "6\n"),
        new TestCase("Test 4", "100 200 300 400\n", "1000\n")
    );

    private MultiLineEditBox codeBox;
    private MultiLineEditBox stdinBox;
    private Button runButton;
    private Button sampleButton;
    private Button submitButton;

    private boolean running;
    private String resultText = "Run some Python code.";
    private int resultColor = 0xA0A0A0;

    public TerminalScreen() {
        super(TITLE);
    }

    @Override
    protected void init() {
        int margin = 10;
        int gap = 8;
        int contentWidth = Math.min(this.width - margin * 2, 760);
        int contentX = (this.width - contentWidth) / 2;
        int leftWidth = Math.max(120, (contentWidth - 12) / 2);
        int rightX = contentX + leftWidth + 12;
        int rightWidth = contentWidth - leftWidth - 12;

        int codeY = 68;
        int controlsY = this.height - 24;
        int editorBottom = controlsY - 8;
        int stdinHeight = 36;
        int codeHeight = Math.max(45, editorBottom - codeY - stdinHeight - 22);
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
        this.codeBox.setValue("A = list(map(int, input().split()))\nprint(sum(A))");
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
        this.stdinBox.setValue("1 2 3 4 5");
        addRenderableWidget(this.stdinBox);

        int buttonCount = 5;
        int buttonWidth = Math.max(50, Math.min(90, (contentWidth - gap * (buttonCount - 1)) / buttonCount));
        int totalButtonWidth = buttonWidth * buttonCount + gap * (buttonCount - 1);
        int buttonX = contentX + (contentWidth - totalButtonWidth) / 2;

        this.runButton = addRenderableWidget(
            Button.builder(Component.literal("Run"), button -> runCode())
                .bounds(buttonX, controlsY, buttonWidth, 20)
                .build()
        );

        this.sampleButton = addRenderableWidget(
            Button.builder(Component.literal("Sample"), button -> runJudge(SAMPLE_TESTS, "Sample"))
                .bounds(buttonX + (buttonWidth + gap), controlsY, buttonWidth, 20)
                .build()
        );

        this.submitButton = addRenderableWidget(
            Button.builder(Component.literal("Submit"), button -> runJudge(SUBMIT_TESTS, "Submit"))
                .bounds(buttonX + (buttonWidth + gap) * 2, controlsY, buttonWidth, 20)
                .build()
        );

        addRenderableWidget(
            Button.builder(Component.literal("Refresh"), button -> RunnerClient.checkNow())
                .bounds(buttonX + (buttonWidth + gap) * 3, controlsY, buttonWidth, 20)
                .build()
        );

        addRenderableWidget(
            Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(buttonX + (buttonWidth + gap) * 4, controlsY, buttonWidth, 20)
                .build()
        );

        updateActionButtons();
    }

    @Override
    public void tick() {
        super.tick();
        updateActionButtons();
    }

    private void updateActionButtons() {
        boolean available = !this.running && RunnerClient.getStatus() == RunnerClient.Status.ONLINE;
        if (this.runButton != null) {
            this.runButton.active = available;
            this.sampleButton.active = available;
            this.submitButton.active = available;
        }
    }

    private void setBusy(boolean busy, String label) {
        this.running = busy;
        this.runButton.setMessage(Component.literal(busy ? label : "Run"));
        updateActionButtons();
    }

    private void runCode() {
        if (this.running || RunnerClient.getStatus() != RunnerClient.Status.ONLINE) {
            return;
        }

        setBusy(true, "Running...");
        this.resultText = "Running...";
        this.resultColor = 0xE0E0E0;

        RunnerClient.run(this.codeBox.getValue(), this.stdinBox.getValue())
            .whenComplete((result, error) -> {
                if (this.minecraft == null) {
                    return;
                }

                this.minecraft.execute(() -> {
                    setBusy(false, "Run");

                    if (error != null) {
                        showRunnerError(error);
                        return;
                    }

                    showRunResult(result);
                });
            });
    }

    private void runJudge(List<TestCase> tests, String mode) {
        if (this.running || RunnerClient.getStatus() != RunnerClient.Status.ONLINE) {
            return;
        }

        setBusy(true, mode + "...");
        this.resultText = mode + " judging...";
        this.resultColor = 0xE0E0E0;
        runJudgeCase(tests, 0, new StringBuilder(), true, mode);
    }

    private void runJudgeCase(List<TestCase> tests, int index, StringBuilder report, boolean allAccepted, String mode) {
        if (index >= tests.size()) {
            boolean accepted = allAccepted;
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    setBusy(false, "Run");
                    this.resultText = report.toString();
                    this.resultColor = accepted ? 0x55FF55 : 0xFFAA55;
                });
            }
            return;
        }

        TestCase test = tests.get(index);
        RunnerClient.run(this.codeBox.getValue(), test.stdin())
            .whenComplete((result, error) -> {
                if (error != null) {
                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> {
                            setBusy(false, "Run");
                            showRunnerError(error);
                        });
                    }
                    return;
                }

                Verdict verdict = judge(result, test.expected());
                report.append(test.name())
                    .append(": ")
                    .append(verdict.label())
                    .append("  ")
                    .append(String.format("%.3f ms", result.elapsedMs()))
                    .append('\n');

                if (verdict == Verdict.WA) {
                    report.append(" expected: ").append(oneLine(test.expected())).append('\n');
                    report.append(" actual:   ").append(oneLine(result.stdout())).append('\n');
                } else if (verdict == Verdict.RE && !result.stderr().isBlank()) {
                    report.append(" ").append(oneLine(result.stderr())).append('\n');
                }

                boolean stillAccepted = allAccepted && verdict == Verdict.AC;
                runJudgeCase(tests, index + 1, report, stillAccepted, mode);
            });
    }

    private static Verdict judge(RunnerClient.RunResult result, String expected) {
        if (result.timedOut()) {
            return Verdict.TLE;
        }
        if (result.exitCode() == null || result.exitCode() != 0) {
            return Verdict.RE;
        }
        return normalizeOutput(result.stdout()).equals(normalizeOutput(expected)) ? Verdict.AC : Verdict.WA;
    }

    private static String normalizeOutput(String text) {
        return text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .stripTrailing();
    }

    private static String oneLine(String text) {
        String normalized = normalizeOutput(text).replace("\n", "\\n");
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 77) + "...";
    }

    private void showRunResult(RunnerClient.RunResult result) {
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
    }

    private void showRunnerError(Throwable error) {
        this.resultText = "Runner error:\n" + rootMessage(error);
        this.resultColor = 0xFF5555;
        RunnerClient.checkNow();
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
        int contentWidth = Math.min(this.width - margin * 2, 760);
        int contentX = (this.width - contentWidth) / 2;
        int leftWidth = Math.max(120, (contentWidth - 12) / 2);
        int rightX = contentX + leftWidth + 12;
        int rightWidth = contentWidth - leftWidth - 12;
        int codeY = 68;
        int controlsY = this.height - 24;
        int editorBottom = controlsY - 8;
        RunnerClient.Status runnerStatus = RunnerClient.getStatus();

        graphics.drawCenteredString(this.font, TITLE, this.width / 2, 8, 0xFFFFFF);
        graphics.drawCenteredString(
            this.font,
            Component.literal(runnerStatus.label()),
            this.width / 2,
            21,
            runnerStatus.color()
        );
        graphics.drawCenteredString(
            this.font,
            Component.literal("Problem: " + PROBLEM_TITLE + "  |  Sum all integers from one line"),
            this.width / 2,
            36,
            0xFFFF55
        );
        graphics.drawCenteredString(
            this.font,
            Component.literal("Sample: 1 2 3 4 5  ->  15"),
            this.width / 2,
            48,
            0xA0A0A0
        );

        graphics.drawString(this.font, Component.literal("Code"), contentX, codeY - 12, 0xE0E0E0);
        graphics.drawString(this.font, Component.literal("stdin"), contentX, this.stdinBox.getY() - 12, 0xE0E0E0);
        graphics.drawString(this.font, Component.literal("Output / Judge"), rightX, codeY - 12, 0xE0E0E0);

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

    private record TestCase(String name, String stdin, String expected) {
    }

    private enum Verdict {
        AC("AC"),
        WA("WA"),
        RE("RE"),
        TLE("TLE");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
