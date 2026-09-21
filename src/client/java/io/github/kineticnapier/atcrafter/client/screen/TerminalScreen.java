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
    private static final String PROBLEM_TEXT = "Sum all integers from one line and print the result.";

    private static final List<TestCase> SAMPLE_TESTS = List.of(
        new TestCase("Sample 1", "1 2 3 4 5\n", "15\n")
    );

    private static final List<TestCase> SUBMIT_TESTS = List.of(
        new TestCase("Test 1", "1 2 3 4 5\n", "15\n"),
        new TestCase("Test 2", "10\n", "10\n"),
        new TestCase("Test 3", "-5 2 9\n", "6\n"),
        new TestCase("Test 4", "100 200 300 400\n", "1000\n")
    );

    private Tab currentTab = Tab.CODE;

    private MultiLineEditBox codeBox;
    private MultiLineEditBox stdinBox;
    private Button problemTabButton;
    private Button codeTabButton;
    private Button judgeTabButton;
    private Button runButton;
    private Button sampleButton;
    private Button submitButton;
    private Button refreshButton;
    private Button closeButton;

    private boolean running;
    private String resultText = "No judge result yet.";
    private int resultColor = 0xA0A0A0;

    public TerminalScreen() {
        super(TITLE);
    }

    @Override
    protected void init() {
        int margin = 12;
        int contentWidth = Math.min(this.width - margin * 2, 720);
        int contentX = (this.width - contentWidth) / 2;

        int tabY = 35;
        int tabGap = 4;
        int tabWidth = Math.min(100, (contentWidth - tabGap * 2) / 3);
        int tabsWidth = tabWidth * 3 + tabGap * 2;
        int tabX = contentX + (contentWidth - tabsWidth) / 2;

        this.problemTabButton = addRenderableWidget(
            Button.builder(Component.literal("Problem"), button -> switchTab(Tab.PROBLEM))
                .bounds(tabX, tabY, tabWidth, 20)
                .build()
        );
        this.codeTabButton = addRenderableWidget(
            Button.builder(Component.literal("Code"), button -> switchTab(Tab.CODE))
                .bounds(tabX + tabWidth + tabGap, tabY, tabWidth, 20)
                .build()
        );
        this.judgeTabButton = addRenderableWidget(
            Button.builder(Component.literal("Judge"), button -> switchTab(Tab.JUDGE))
                .bounds(tabX + (tabWidth + tabGap) * 2, tabY, tabWidth, 20)
                .build()
        );

        int panelY = 66;
        int bottomY = this.height - 24;
        int panelBottom = bottomY - 8;
        int stdinHeight = 42;
        int stdinGap = 18;
        int codeHeight = Math.max(50, panelBottom - panelY - stdinHeight - stdinGap);
        int stdinY = panelY + codeHeight + stdinGap;

        this.codeBox = new MultiLineEditBox(
            this.font,
            contentX,
            panelY,
            contentWidth,
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
            contentWidth,
            stdinHeight,
            Component.literal("stdin"),
            Component.literal("Standard input")
        );
        this.stdinBox.setCharacterLimit(32_000);
        this.stdinBox.setValue("1 2 3 4 5");
        addRenderableWidget(this.stdinBox);

        int smallGap = 6;
        int smallWidth = Math.min(86, Math.max(58, (contentWidth - smallGap * 3) / 4));

        this.runButton = addRenderableWidget(
            Button.builder(Component.literal("Run"), button -> runCode())
                .bounds(contentX, bottomY, smallWidth, 20)
                .build()
        );

        this.sampleButton = addRenderableWidget(
            Button.builder(Component.literal("Sample"), button -> runJudge(SAMPLE_TESTS, "Sample"))
                .bounds(contentX, bottomY, smallWidth, 20)
                .build()
        );

        this.submitButton = addRenderableWidget(
            Button.builder(Component.literal("Submit"), button -> runJudge(SUBMIT_TESTS, "Submit"))
                .bounds(contentX + smallWidth + smallGap, bottomY, smallWidth, 20)
                .build()
        );

        this.refreshButton = addRenderableWidget(
            Button.builder(Component.literal("Refresh"), button -> RunnerClient.checkNow())
                .bounds(contentX + contentWidth - smallWidth * 2 - smallGap, bottomY, smallWidth, 20)
                .build()
        );

        this.closeButton = addRenderableWidget(
            Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(contentX + contentWidth - smallWidth, bottomY, smallWidth, 20)
                .build()
        );

        applyTabVisibility();
        updateActionButtons();
    }

    private void switchTab(Tab tab) {
        this.currentTab = tab;
        applyTabVisibility();
    }

    private void applyTabVisibility() {
        if (this.codeBox == null) {
            return;
        }

        boolean code = this.currentTab == Tab.CODE;
        boolean judge = this.currentTab == Tab.JUDGE;

        this.codeBox.visible = code;
        this.stdinBox.visible = code;
        this.runButton.visible = code;

        this.sampleButton.visible = judge;
        this.submitButton.visible = judge;

        this.problemTabButton.active = this.currentTab != Tab.PROBLEM;
        this.codeTabButton.active = this.currentTab != Tab.CODE;
        this.judgeTabButton.active = this.currentTab != Tab.JUDGE;
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
                        this.currentTab = Tab.JUDGE;
                        applyTabVisibility();
                        return;
                    }

                    showRunResult(result);
                    this.currentTab = Tab.JUDGE;
                    applyTabVisibility();
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

        int margin = 12;
        int contentWidth = Math.min(this.width - margin * 2, 720);
        int contentX = (this.width - contentWidth) / 2;
        int panelY = 66;
        int panelBottom = this.height - 32;

        RunnerClient.Status runnerStatus = RunnerClient.getStatus();
        graphics.drawString(this.font, TITLE, contentX, 12, 0xFFFFFF);
        graphics.drawString(
            this.font,
            Component.literal(runnerStatus.label()),
            contentX + contentWidth - this.font.width(runnerStatus.label()),
            12,
            runnerStatus.color()
        );

        switch (this.currentTab) {
            case PROBLEM -> renderProblem(graphics, contentX, panelY, contentWidth);
            case CODE -> renderCodeLabels(graphics, contentX);
            case JUDGE -> renderJudge(graphics, contentX, panelY, contentWidth, panelBottom);
        }
    }

    private void renderProblem(GuiGraphics graphics, int x, int y, int width) {
        graphics.drawString(this.font, Component.literal(PROBLEM_TITLE), x, y, 0xFFFF55);
        graphics.drawWordWrap(this.font, Component.literal(PROBLEM_TEXT), x, y + 18, width, 0xE0E0E0);

        int sampleY = y + 52;
        graphics.drawString(this.font, Component.literal("Sample Input"), x, sampleY, 0xAAAAAA);
        graphics.fill(x, sampleY + 12, x + width, sampleY + 34, 0x66000000);
        graphics.drawString(this.font, Component.literal("1 2 3 4 5"), x + 5, sampleY + 18, 0xFFFFFF);

        int outputY = sampleY + 48;
        graphics.drawString(this.font, Component.literal("Sample Output"), x, outputY, 0xAAAAAA);
        graphics.fill(x, outputY + 12, x + width, outputY + 34, 0x66000000);
        graphics.drawString(this.font, Component.literal("15"), x + 5, outputY + 18, 0xFFFFFF);
    }

    private void renderCodeLabels(GuiGraphics graphics, int x) {
        graphics.drawString(this.font, Component.literal("Python"), x, this.codeBox.getY() - 12, 0xE0E0E0);
        graphics.drawString(this.font, Component.literal("stdin"), x, this.stdinBox.getY() - 12, 0xE0E0E0);
    }

    private void renderJudge(GuiGraphics graphics, int x, int y, int width, int bottom) {
        graphics.drawString(this.font, Component.literal("Output / Judge"), x, y - 12, 0xE0E0E0);
        graphics.fill(x, y, x + width, bottom, 0x66000000);
        renderOutput(graphics, x + 6, y + 6, width - 12, bottom - y - 12);
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

    private enum Tab {
        PROBLEM,
        CODE,
        JUDGE
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
