package io.github.kineticnapier.atcrafter.client.screen;

import io.github.kineticnapier.atcrafter.client.runner.RunnerClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public final class TerminalScreen extends Screen {
    private static final Component TITLE = Component.literal("AtCrafter");

    private Tab currentTab = Tab.CODE;

    private MultiLineEditBox codeBox;
    private MultiLineEditBox stdinBox;
    private Button problemTabButton;
    private Button codeTabButton;
    private Button judgeTabButton;
    private Button previousProblemButton;
    private Button nextProblemButton;
    private Button runButton;
    private Button sampleButton;
    private Button submitButton;
    private Button refreshButton;
    private Button closeButton;

    private final Map<String, String> codeDrafts = new HashMap<>();
    private final Map<String, String> stdinDrafts = new HashMap<>();

    private List<RunnerClient.ProblemSummary> problems = List.of();
    private RunnerClient.ProblemData currentProblem;
    private int problemIndex = -1;
    private boolean loadingProblems;
    private boolean running;
    private String problemError = "";
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
        addRenderableWidget(this.stdinBox);

        int gap = 6;
        int buttonWidth = 76;

        this.previousProblemButton = addRenderableWidget(
            Button.builder(Component.literal("< Prev"), button -> changeProblem(-1))
                .bounds(contentX, bottomY, buttonWidth, 20)
                .build()
        );
        this.nextProblemButton = addRenderableWidget(
            Button.builder(Component.literal("Next >"), button -> changeProblem(1))
                .bounds(contentX + buttonWidth + gap, bottomY, buttonWidth, 20)
                .build()
        );

        this.runButton = addRenderableWidget(
            Button.builder(Component.literal("Run"), button -> runCode())
                .bounds(contentX, bottomY, buttonWidth, 20)
                .build()
        );

        this.sampleButton = addRenderableWidget(
            Button.builder(Component.literal("Sample"), button -> runJudge(currentSamples(), "Sample"))
                .bounds(contentX, bottomY, buttonWidth, 20)
                .build()
        );
        this.submitButton = addRenderableWidget(
            Button.builder(Component.literal("Submit"), button -> runJudge(currentTests(), "Submit"))
                .bounds(contentX + buttonWidth + gap, bottomY, buttonWidth, 20)
                .build()
        );

        this.refreshButton = addRenderableWidget(
            Button.builder(Component.literal("Refresh"), button -> {
                RunnerClient.checkNow();
                loadProblemList();
            })
                .bounds(contentX + contentWidth - buttonWidth * 2 - gap, bottomY, buttonWidth, 20)
                .build()
        );
        this.closeButton = addRenderableWidget(
            Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(contentX + contentWidth - buttonWidth, bottomY, buttonWidth, 20)
                .build()
        );

        applyTabVisibility();
        updateActionButtons();
        loadProblemList();
    }

    private void loadProblemList() {
        if (this.loadingProblems) {
            return;
        }

        saveDrafts();
        this.loadingProblems = true;
        this.problemError = "";

        RunnerClient.listProblems().whenComplete((loaded, error) -> {
            if (this.minecraft == null) {
                return;
            }
            this.minecraft.execute(() -> {
                this.loadingProblems = false;
                if (error != null) {
                    this.problems = List.of();
                    this.currentProblem = null;
                    this.problemIndex = -1;
                    this.problemError = "Failed to load problems: " + rootMessage(error);
                    updateActionButtons();
                    return;
                }

                this.problems = loaded;
                if (loaded.isEmpty()) {
                    this.currentProblem = null;
                    this.problemIndex = -1;
                    this.problemError = "No problems found in the problems directory.";
                    updateActionButtons();
                    return;
                }

                int wanted = 0;
                if (this.currentProblem != null) {
                    for (int i = 0; i < loaded.size(); i++) {
                        if (loaded.get(i).id().equals(this.currentProblem.id())) {
                            wanted = i;
                            break;
                        }
                    }
                }
                loadProblem(wanted);
            });
        });
    }

    private void loadProblem(int index) {
        if (index < 0 || index >= this.problems.size()) {
            return;
        }

        saveDrafts();
        RunnerClient.ProblemSummary summary = this.problems.get(index);
        this.loadingProblems = true;
        this.problemError = "";

        RunnerClient.loadProblem(summary.id()).whenComplete((problem, error) -> {
            if (this.minecraft == null) {
                return;
            }
            this.minecraft.execute(() -> {
                this.loadingProblems = false;
                if (error != null) {
                    this.problemError = "Failed to load " + summary.title() + ": " + rootMessage(error);
                    updateActionButtons();
                    return;
                }

                this.problemIndex = index;
                this.currentProblem = problem;
                this.problemError = "";

                String code = this.codeDrafts.getOrDefault(problem.id(), problem.defaultCode());
                this.codeBox.setValue(code);

                String stdin = this.stdinDrafts.get(problem.id());
                if (stdin == null) {
                    stdin = problem.samples().isEmpty() ? "" : problem.samples().get(0).stdin();
                }
                this.stdinBox.setValue(stdin);

                this.resultText = "No judge result yet for " + problem.title() + ".";
                this.resultColor = 0xA0A0A0;
                updateActionButtons();
            });
        });
    }

    private void changeProblem(int delta) {
        if (this.loadingProblems || this.problems.isEmpty()) {
            return;
        }
        int next = this.problemIndex + delta;
        if (next >= 0 && next < this.problems.size()) {
            loadProblem(next);
        }
    }

    private void saveDrafts() {
        if (this.currentProblem == null || this.codeBox == null || this.stdinBox == null) {
            return;
        }
        this.codeDrafts.put(this.currentProblem.id(), this.codeBox.getValue());
        this.stdinDrafts.put(this.currentProblem.id(), this.stdinBox.getValue());
    }

    private List<RunnerClient.TestCase> currentSamples() {
        return this.currentProblem == null ? List.of() : this.currentProblem.samples();
    }

    private List<RunnerClient.TestCase> currentTests() {
        return this.currentProblem == null ? List.of() : this.currentProblem.tests();
    }

    private void switchTab(Tab tab) {
        this.currentTab = tab;
        applyTabVisibility();
    }

    private void applyTabVisibility() {
        if (this.codeBox == null) {
            return;
        }

        boolean problem = this.currentTab == Tab.PROBLEM;
        boolean code = this.currentTab == Tab.CODE;
        boolean judge = this.currentTab == Tab.JUDGE;

        this.codeBox.visible = code;
        this.stdinBox.visible = code;
        this.previousProblemButton.visible = problem;
        this.nextProblemButton.visible = problem;
        this.runButton.visible = code;
        this.sampleButton.visible = judge;
        this.submitButton.visible = judge;

        this.problemTabButton.active = !problem;
        this.codeTabButton.active = !code;
        this.judgeTabButton.active = !judge;
    }

    @Override
    public void tick() {
        super.tick();
        updateActionButtons();
    }

    private void updateActionButtons() {
        boolean online = RunnerClient.getStatus() == RunnerClient.Status.ONLINE;
        boolean loaded = this.currentProblem != null && !this.loadingProblems;
        boolean available = !this.running && online && loaded;

        if (this.runButton != null) {
            this.runButton.active = available;
            this.sampleButton.active = available && !currentSamples().isEmpty();
            this.submitButton.active = available && !currentTests().isEmpty();
            this.previousProblemButton.active = loaded && this.problemIndex > 0;
            this.nextProblemButton.active = loaded && this.problemIndex >= 0 && this.problemIndex + 1 < this.problems.size();
        }
    }

    private void setBusy(boolean busy, String label) {
        this.running = busy;
        this.runButton.setMessage(Component.literal(busy ? label : "Run"));
        updateActionButtons();
    }

    private void runCode() {
        if (this.running || this.currentProblem == null || RunnerClient.getStatus() != RunnerClient.Status.ONLINE) {
            return;
        }

        saveDrafts();
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
                    } else {
                        showRunResult(result);
                    }
                    this.currentTab = Tab.JUDGE;
                    applyTabVisibility();
                });
            });
    }

    private void runJudge(List<RunnerClient.TestCase> tests, String mode) {
        if (this.running || this.currentProblem == null || tests.isEmpty()
            || RunnerClient.getStatus() != RunnerClient.Status.ONLINE) {
            return;
        }

        saveDrafts();
        setBusy(true, mode + "...");
        this.resultText = mode + " judging " + this.currentProblem.title() + "...";
        this.resultColor = 0xE0E0E0;
        runJudgeCase(tests, 0, new StringBuilder(), true);
    }

    private void runJudgeCase(
        List<RunnerClient.TestCase> tests,
        int index,
        StringBuilder report,
        boolean allAccepted
    ) {
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

        RunnerClient.TestCase test = tests.get(index);
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

                runJudgeCase(tests, index + 1, report, allAccepted && verdict == Verdict.AC);
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
        return text.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
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
        if (this.loadingProblems) {
            graphics.drawString(this.font, Component.literal("Loading problems..."), x, y, 0xE0E0E0);
            return;
        }
        if (this.currentProblem == null) {
            String message = this.problemError.isBlank() ? "No problem loaded." : this.problemError;
            graphics.drawWordWrap(this.font, Component.literal(message), x, y, width, 0xFF5555);
            return;
        }

        String position = (this.problemIndex + 1) + " / " + this.problems.size();
        graphics.drawString(this.font, Component.literal(this.currentProblem.title()), x, y, 0xFFFF55);
        graphics.drawString(
            this.font,
            Component.literal(position),
            x + width - this.font.width(position),
            y,
            0xAAAAAA
        );
        graphics.drawWordWrap(
            this.font,
            Component.literal(this.currentProblem.statement()),
            x,
            y + 18,
            width,
            0xE0E0E0
        );

        int sampleY = y + 62;
        if (this.currentProblem.samples().isEmpty()) {
            graphics.drawString(this.font, Component.literal("No samples."), x, sampleY, 0xAAAAAA);
            return;
        }

        RunnerClient.TestCase sample = this.currentProblem.samples().get(0);
        graphics.drawString(this.font, Component.literal("Sample Input"), x, sampleY, 0xAAAAAA);
        graphics.fill(x, sampleY + 12, x + width, sampleY + 42, 0x66000000);
        drawMultiline(graphics, sample.stdin(), x + 5, sampleY + 18, width - 10, 2, 0xFFFFFF);

        int outputY = sampleY + 50;
        graphics.drawString(this.font, Component.literal("Sample Output"), x, outputY, 0xAAAAAA);
        graphics.fill(x, outputY + 12, x + width, outputY + 42, 0x66000000);
        drawMultiline(graphics, sample.expected(), x + 5, outputY + 18, width - 10, 2, 0xFFFFFF);
    }

    private void renderCodeLabels(GuiGraphics graphics, int x) {
        if (this.currentProblem != null) {
            graphics.drawString(this.font, Component.literal(this.currentProblem.title()), x, 56, 0xAAAAAA);
        }
        graphics.drawString(this.font, Component.literal("Python"), x, this.codeBox.getY() - 12, 0xE0E0E0);
        graphics.drawString(this.font, Component.literal("stdin"), x, this.stdinBox.getY() - 12, 0xE0E0E0);
    }

    private void renderJudge(GuiGraphics graphics, int x, int y, int width, int bottom) {
        String heading = this.currentProblem == null ? "Output / Judge" : "Output / Judge - " + this.currentProblem.title();
        graphics.drawString(this.font, Component.literal(heading), x, y - 12, 0xE0E0E0);
        graphics.fill(x, y, x + width, bottom, 0x66000000);
        renderOutput(graphics, x + 6, y + 6, width - 12, bottom - y - 12);
    }

    private void drawMultiline(GuiGraphics graphics, String text, int x, int y, int width, int maxLines, int color) {
        List<FormattedCharSequence> lines = this.font.split(Component.literal(text.stripTrailing()), Math.max(10, width));
        int count = Math.min(maxLines, lines.size());
        for (int i = 0; i < count; i++) {
            graphics.drawString(this.font, lines.get(i), x, y + i * (this.font.lineHeight + 1), color);
        }
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
