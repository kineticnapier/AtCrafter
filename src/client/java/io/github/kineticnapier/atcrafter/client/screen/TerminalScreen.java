package io.github.kineticnapier.atcrafter.client.screen;

import io.github.kineticnapier.atcrafter.client.runner.RunnerClient;
import java.util.ArrayList;
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
    private Button debugButton;
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
    private String resultText = "まだ判定していません。";
    private int resultColor = 0xA0A0A0;

    private int problemScroll;
    private int problemContentHeight;

    private final List<JudgeCaseResult> judgeResults = new ArrayList<>();
    private int selectedJudgeIndex = -1;

    public TerminalScreen() {
        super(TITLE);
    }

    @Override
    protected void init() {
        int contentWidth = contentWidth();
        int contentX = contentX();

        int tabY = 35;
        int tabGap = 4;
        int tabWidth = Math.min(100, (contentWidth - tabGap * 2) / 3);
        int tabsWidth = tabWidth * 3 + tabGap * 2;
        int tabX = contentX + (contentWidth - tabsWidth) / 2;

        this.problemTabButton = addRenderableWidget(
            Button.builder(Component.literal("問題"), button -> switchTab(Tab.PROBLEM))
                .bounds(tabX, tabY, tabWidth, 20)
                .build()
        );
        this.codeTabButton = addRenderableWidget(
            Button.builder(Component.literal("コード"), button -> switchTab(Tab.CODE))
                .bounds(tabX + tabWidth + tabGap, tabY, tabWidth, 20)
                .build()
        );
        this.judgeTabButton = addRenderableWidget(
            Button.builder(Component.literal("判定"), button -> switchTab(Tab.JUDGE))
                .bounds(tabX + (tabWidth + tabGap) * 2, tabY, tabWidth, 20)
                .build()
        );

        int panelY = panelTop();
        int bottomY = this.height - 24;
        int panelBottom = panelBottom();
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
            Component.literal("Python コード"),
            Component.literal("Python コード入力欄")
        );
        this.codeBox.setCharacterLimit(32_000);
        addRenderableWidget(this.codeBox);

        this.stdinBox = new MultiLineEditBox(
            this.font,
            contentX,
            stdinY,
            contentWidth,
            stdinHeight,
            Component.literal("標準入力"),
            Component.literal("標準入力")
        );
        this.stdinBox.setCharacterLimit(32_000);
        addRenderableWidget(this.stdinBox);

        int gap = 6;
        int buttonWidth = 76;

        this.previousProblemButton = addRenderableWidget(
            Button.builder(Component.literal("< 前へ"), button -> changeProblem(-1))
                .bounds(contentX, bottomY, buttonWidth, 20)
                .build()
        );
        this.nextProblemButton = addRenderableWidget(
            Button.builder(Component.literal("次へ >"), button -> changeProblem(1))
                .bounds(contentX + buttonWidth + gap, bottomY, buttonWidth, 20)
                .build()
        );

        this.runButton = addRenderableWidget(
            Button.builder(Component.literal("実行"), button -> runCode())
                .bounds(contentX, bottomY, buttonWidth, 20)
                .build()
        );

        this.sampleButton = addRenderableWidget(
            Button.builder(Component.literal("サンプル"), button -> runJudge(currentSamples(), "サンプル"))
                .bounds(contentX, bottomY, buttonWidth, 20)
                .build()
        );
        this.submitButton = addRenderableWidget(
            Button.builder(Component.literal("提出"), button -> runJudge(currentTests(), "提出"))
                .bounds(contentX + buttonWidth + gap, bottomY, buttonWidth, 20)
                .build()
        );
        this.debugButton = addRenderableWidget(
            Button.builder(Component.literal("このケースをデバッグ"), button -> prepareSelectedCaseForDebug())
                .bounds(contentX + (buttonWidth + gap) * 2, bottomY, 140, 20)
                .build()
        );

        this.refreshButton = addRenderableWidget(
            Button.builder(Component.literal("再読込"), button -> {
                RunnerClient.checkNow();
                loadProblemList();
            })
                .bounds(contentX + contentWidth - buttonWidth * 2 - gap, bottomY, buttonWidth, 20)
                .build()
        );
        this.closeButton = addRenderableWidget(
            Button.builder(Component.literal("閉じる"), button -> onClose())
                .bounds(contentX + contentWidth - buttonWidth, bottomY, buttonWidth, 20)
                .build()
        );

        applyTabVisibility();
        updateActionButtons();
        loadProblemList();
    }

    private int contentWidth() {
        return Math.min(this.width - 24, 720);
    }

    private int contentX() {
        return (this.width - contentWidth()) / 2;
    }

    private int panelTop() {
        return 66;
    }

    private int panelBottom() {
        return this.height - 32;
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
                    this.problemError = "問題一覧の読み込みに失敗しました: " + rootMessage(error);
                    updateActionButtons();
                    return;
                }

                this.problems = loaded;
                if (loaded.isEmpty()) {
                    this.currentProblem = null;
                    this.problemIndex = -1;
                    this.problemError = "problems フォルダーに問題がありません。";
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
                    this.problemError = summary.title() + " の読み込みに失敗しました: " + rootMessage(error);
                    updateActionButtons();
                    return;
                }

                this.problemIndex = index;
                this.currentProblem = problem;
                this.problemError = "";
                this.problemScroll = 0;
                this.judgeResults.clear();
                this.selectedJudgeIndex = -1;

                String code = this.codeDrafts.getOrDefault(problem.id(), problem.defaultCode());
                this.codeBox.setValue(code);

                String stdin = this.stdinDrafts.get(problem.id());
                if (stdin == null) {
                    stdin = problem.samples().isEmpty() ? "" : problem.samples().get(0).stdin();
                }
                this.stdinBox.setValue(stdin);

                this.resultText = problem.title() + " はまだ判定していません。";
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
        this.debugButton.visible = judge;

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
            this.nextProblemButton.active =
                loaded && this.problemIndex >= 0 && this.problemIndex + 1 < this.problems.size();
            this.debugButton.active = available && selectedFailure() != null;
        }
    }

    private void setBusy(boolean busy, String label) {
        this.running = busy;
        this.runButton.setMessage(Component.literal(busy ? label : "実行"));
        updateActionButtons();
    }

    private void runCode() {
        if (this.running || this.currentProblem == null || RunnerClient.getStatus() != RunnerClient.Status.ONLINE) {
            return;
        }

        saveDrafts();
        setBusy(true, "実行中...");
        this.judgeResults.clear();
        this.selectedJudgeIndex = -1;
        this.resultText = "実行中...";
        this.resultColor = 0xE0E0E0;

        RunnerClient.run(this.codeBox.getValue(), this.stdinBox.getValue())
            .whenComplete((result, error) -> {
                if (this.minecraft == null) {
                    return;
                }

                this.minecraft.execute(() -> {
                    setBusy(false, "実行");
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
        setBusy(true, mode + "中...");
        this.judgeResults.clear();
        this.selectedJudgeIndex = -1;
        this.resultText = mode + "を判定中...";
        this.resultColor = 0xE0E0E0;
        runJudgeCase(tests, 0, true);
    }

    private void runJudgeCase(List<RunnerClient.TestCase> tests, int index, boolean allAccepted) {
        if (index >= tests.size()) {
            boolean accepted = allAccepted;
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    setBusy(false, "実行");
                    this.resultText = accepted ? "すべて AC" : "失敗したケースをクリックすると詳細を表示します。";
                    this.resultColor = accepted ? 0x55FF55 : 0xFFAA55;
                    if (!accepted) {
                        for (int i = 0; i < this.judgeResults.size(); i++) {
                            if (this.judgeResults.get(i).verdict() != Verdict.AC) {
                                this.selectedJudgeIndex = i;
                                break;
                            }
                        }
                    }
                    updateActionButtons();
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
                            setBusy(false, "実行");
                            showRunnerError(error);
                        });
                    }
                    return;
                }

                Verdict verdict = judge(result, test.expected());
                JudgeCaseResult caseResult = new JudgeCaseResult(test, verdict, result);
                if (this.minecraft != null) {
                    this.minecraft.execute(() -> this.judgeResults.add(caseResult));
                }
                runJudgeCase(tests, index + 1, allAccepted && verdict == Verdict.AC);
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

    private void showRunResult(RunnerClient.RunResult result) {
        StringBuilder text = new StringBuilder();
        if (!result.stdout().isEmpty()) {
            text.append("標準出力:\n").append(result.stdout());
        }
        if (!result.stderr().isEmpty()) {
            if (!text.isEmpty()) {
                text.append("\n");
            }
            text.append("標準エラー:\n").append(result.stderr());
        }
        if (text.isEmpty()) {
            text.append("(出力なし)");
        }

        text.append("\n\n");
        if (result.timedOut()) {
            text.append("TLE");
        } else {
            text.append("終了コード=").append(result.exitCode());
        }
        text.append("  ").append(String.format("%.3f ms", result.elapsedMs()));
        if (result.outputTruncated()) {
            text.append("  [出力を省略しました]");
        }

        this.resultText = text.toString();
        this.resultColor = result.timedOut() || (result.exitCode() != null && result.exitCode() != 0)
            ? 0xFFAA55
            : 0xFFFFFF;
    }

    private void showRunnerError(Throwable error) {
        this.judgeResults.clear();
        this.selectedJudgeIndex = -1;
        this.resultText = "Runner エラー:\n" + rootMessage(error);
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

    private JudgeCaseResult selectedFailure() {
        if (this.selectedJudgeIndex < 0 || this.selectedJudgeIndex >= this.judgeResults.size()) {
            return null;
        }
        JudgeCaseResult selected = this.judgeResults.get(this.selectedJudgeIndex);
        return selected.verdict() == Verdict.AC ? null : selected;
    }

    private void prepareSelectedCaseForDebug() {
        JudgeCaseResult selected = selectedFailure();
        if (selected == null) {
            return;
        }
        this.stdinBox.setValue(selected.test().stdin());
        saveDrafts();
        this.currentTab = Tab.CODE;
        this.resultText = selected.test().name() + " の入力を標準入力欄にセットしました。\n次はデバッグトレース機能を接続します。";
        this.resultColor = 0xFFFF55;
        applyTabVisibility();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        int contentWidth = contentWidth();
        int contentX = contentX();
        int panelY = panelTop();
        int panelBottom = panelBottom();

        RunnerClient.Status runnerStatus = RunnerClient.getStatus();
        graphics.drawString(this.font, TITLE, contentX, 12, 0xFFFFFF);
        String statusLabel = runnerStatus.label();
        graphics.drawString(
            this.font,
            Component.literal(statusLabel),
            contentX + contentWidth - this.font.width(statusLabel),
            12,
            runnerStatus.color()
        );

        switch (this.currentTab) {
            case PROBLEM -> renderProblem(graphics, contentX, panelY, contentWidth, panelBottom);
            case CODE -> renderCodeLabels(graphics, contentX);
            case JUDGE -> renderJudge(graphics, contentX, panelY, contentWidth, panelBottom, mouseX, mouseY);
        }
    }

    private void renderProblem(GuiGraphics graphics, int x, int y, int width, int bottom) {
        if (this.loadingProblems) {
            graphics.drawString(this.font, Component.literal("問題を読み込み中..."), x, y, 0xE0E0E0);
            return;
        }
        if (!this.problemError.isEmpty()) {
            graphics.drawWordWrap(this.font, Component.literal(this.problemError), x, y, width, 0xFF5555);
            return;
        }
        if (this.currentProblem == null) {
            graphics.drawString(this.font, Component.literal("問題が読み込まれていません。"), x, y, 0xA0A0A0);
            return;
        }

        graphics.drawString(this.font, Component.literal(this.currentProblem.title()), x, y, 0xFFFF55);
        String counter = (this.problemIndex + 1) + " / " + this.problems.size();
        graphics.drawString(this.font, Component.literal(counter), x + width - this.font.width(counter) - 8, y, 0xA0A0A0);

        int viewportTop = y + 16;
        int viewportBottom = bottom;
        int viewportHeight = Math.max(1, viewportBottom - viewportTop);

        graphics.fill(x, viewportTop, x + width, viewportBottom, 0x33000000);
        graphics.enableScissor(x, viewportTop, x + width, viewportBottom);

        int cursorY = viewportTop + 6 - this.problemScroll;
        int startY = cursorY;
        cursorY = renderStatement(graphics, this.currentProblem.statement(), x + 7, cursorY, width - 22);

        List<RunnerClient.TestCase> samples = this.currentProblem.samples();
        for (int i = 0; i < samples.size(); i++) {
            RunnerClient.TestCase sample = samples.get(i);
            cursorY += 8;
            graphics.drawString(this.font, Component.literal("サンプル " + (i + 1)), x + 7, cursorY, 0xFFFF55);
            cursorY += 15;
            cursorY = renderCodeBlock(graphics, "入力", sample.stdin(), x + 7, cursorY, width - 22);
            cursorY += 7;
            cursorY = renderCodeBlock(graphics, "出力", sample.expected(), x + 7, cursorY, width - 22);
        }

        this.problemContentHeight = Math.max(0, cursorY - startY + 8);
        graphics.disableScissor();

        int maxScroll = Math.max(0, this.problemContentHeight - viewportHeight);
        if (this.problemScroll > maxScroll) {
            this.problemScroll = maxScroll;
        }

        if (maxScroll > 0) {
            int trackX = x + width - 5;
            graphics.fill(trackX, viewportTop, trackX + 3, viewportBottom, 0x55000000);
            int thumbHeight = Math.max(16, viewportHeight * viewportHeight / this.problemContentHeight);
            int travel = viewportHeight - thumbHeight;
            int thumbY = viewportTop + (int) ((long) this.problemScroll * travel / maxScroll);
            graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbHeight, 0xFFAAAAAA);
        }
    }

    private int renderStatement(GuiGraphics graphics, String statement, int x, int y, int width) {
        String normalized = statement.replace("\r\n", "\n").replace('\r', '\n');
        for (String rawLine : normalized.split("\n", -1)) {
            String line = rawLine.stripTrailing();
            if (line.isBlank()) {
                y += 7;
                continue;
            }
            if (line.startsWith("## ")) {
                y += 4;
                graphics.drawString(this.font, Component.literal(line.substring(3)), x, y, 0xFFFF55);
                y += 15;
                continue;
            }
            String visibleLine = line.startsWith("- ") ? "・" + line.substring(2) : line;
            List<FormattedCharSequence> wrapped = this.font.split(Component.literal(visibleLine), Math.max(20, width));
            for (FormattedCharSequence part : wrapped) {
                graphics.drawString(this.font, part, x, y, 0xE0E0E0);
                y += this.font.lineHeight + 2;
            }
        }
        return y;
    }

    private int renderCodeBlock(GuiGraphics graphics, String label, String text, int x, int y, int width) {
        graphics.drawString(this.font, Component.literal(label), x, y, 0xAAAAAA);
        y += 12;

        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
        String[] sourceLines = normalized.isEmpty() ? new String[] {""} : normalized.split("\n", -1);
        int blockHeight = 8;
        for (String sourceLine : sourceLines) {
            List<FormattedCharSequence> wrapped = this.font.split(Component.literal(sourceLine), Math.max(20, width - 10));
            blockHeight += Math.max(1, wrapped.size()) * (this.font.lineHeight + 1);
        }
        blockHeight += 4;
        graphics.fill(x, y, x + width, y + blockHeight, 0x66000000);

        int textY = y + 5;
        for (String sourceLine : sourceLines) {
            List<FormattedCharSequence> wrapped = this.font.split(Component.literal(sourceLine), Math.max(20, width - 10));
            if (wrapped.isEmpty()) {
                textY += this.font.lineHeight + 1;
            } else {
                for (FormattedCharSequence part : wrapped) {
                    graphics.drawString(this.font, part, x + 5, textY, 0xFFFFFF);
                    textY += this.font.lineHeight + 1;
                }
            }
        }
        return y + blockHeight;
    }

    private void renderCodeLabels(GuiGraphics graphics, int x) {
        graphics.drawString(this.font, Component.literal("Python コード"), x, this.codeBox.getY() - 12, 0xE0E0E0);
        graphics.drawString(this.font, Component.literal("標準入力"), x, this.stdinBox.getY() - 12, 0xE0E0E0);
    }

    private void renderJudge(GuiGraphics graphics, int x, int y, int width, int bottom, int mouseX, int mouseY) {
        graphics.drawString(this.font, Component.literal("判定結果"), x, y - 12, 0xE0E0E0);
        graphics.fill(x, y, x + width, bottom, 0x66000000);

        if (this.judgeResults.isEmpty()) {
            renderOutput(graphics, x + 6, y + 6, width - 12, bottom - y - 12);
            return;
        }

        int listWidth = Math.min(250, Math.max(190, width / 3));
        int rowHeight = 18;
        int rowY = y + 6;
        for (int i = 0; i < this.judgeResults.size(); i++) {
            JudgeCaseResult caseResult = this.judgeResults.get(i);
            boolean selected = i == this.selectedJudgeIndex;
            boolean hovered = mouseX >= x + 4 && mouseX < x + listWidth && mouseY >= rowY && mouseY < rowY + rowHeight;
            if (selected) {
                graphics.fill(x + 4, rowY, x + listWidth, rowY + rowHeight, 0x664466AA);
            } else if (hovered && caseResult.verdict() != Verdict.AC) {
                graphics.fill(x + 4, rowY, x + listWidth, rowY + rowHeight, 0x44333333);
            }

            int verdictColor = verdictColor(caseResult.verdict());
            graphics.drawString(this.font, Component.literal(caseResult.test().name()), x + 9, rowY + 5, 0xFFFFFF);
            String verdictText = caseResult.verdict().label();
            int verdictX = x + listWidth - this.font.width(verdictText) - 58;
            graphics.drawString(this.font, Component.literal(verdictText), verdictX, rowY + 5, verdictColor);
            String elapsed = String.format("%.1fms", caseResult.result().elapsedMs());
            graphics.drawString(this.font, Component.literal(elapsed), x + listWidth - this.font.width(elapsed) - 6, rowY + 5, 0xAAAAAA);
            rowY += rowHeight + 2;
        }

        int dividerX = x + listWidth + 6;
        graphics.fill(dividerX, y + 5, dividerX + 1, bottom - 5, 0x55FFFFFF);
        renderJudgeDetails(graphics, dividerX + 8, y + 6, x + width - dividerX - 14, bottom - y - 12);
    }

    private void renderJudgeDetails(GuiGraphics graphics, int x, int y, int width, int height) {
        JudgeCaseResult selected = selectedFailure();
        if (selected == null) {
            graphics.drawWordWrap(this.font, Component.literal(this.resultText), x, y, width, this.resultColor);
            return;
        }

        int cursorY = y;
        graphics.drawString(this.font, Component.literal(selected.test().name() + " - " + selected.verdict().label()), x, cursorY, verdictColor(selected.verdict()));
        cursorY += 16;
        cursorY = renderJudgeField(graphics, "入力", selected.test().stdin(), x, cursorY, width, 3);
        cursorY += 5;

        if (selected.verdict() == Verdict.WA) {
            cursorY = renderJudgeField(graphics, "期待される出力", selected.test().expected(), x, cursorY, width, 3);
            cursorY += 5;
            renderJudgeField(graphics, "実際の出力", selected.result().stdout(), x, cursorY, width, 3);
        } else if (selected.verdict() == Verdict.RE) {
            renderJudgeField(graphics, "標準エラー", selected.result().stderr(), x, cursorY, width, 6);
        } else if (selected.verdict() == Verdict.TLE) {
            graphics.drawWordWrap(this.font, Component.literal("時間制限を超えました。"), x, cursorY, width, 0xFFAA55);
        }
    }

    private int renderJudgeField(GuiGraphics graphics, String label, String text, int x, int y, int width, int maxLines) {
        graphics.drawString(this.font, Component.literal(label), x, y, 0xAAAAAA);
        y += 12;
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
        List<FormattedCharSequence> lines = this.font.split(Component.literal(normalized.isEmpty() ? "(空)" : normalized), Math.max(20, width - 10));
        int count = Math.min(maxLines, Math.max(1, lines.size()));
        int blockHeight = count * (this.font.lineHeight + 1) + 10;
        graphics.fill(x, y, x + width, y + blockHeight, 0x66000000);
        for (int i = 0; i < count && i < lines.size(); i++) {
            graphics.drawString(this.font, lines.get(i), x + 5, y + 5 + i * (this.font.lineHeight + 1), 0xFFFFFF);
        }
        if (lines.size() > maxLines) {
            graphics.drawString(this.font, Component.literal("..."), x + width - 16, y + blockHeight - 10, 0xAAAAAA);
        }
        return y + blockHeight;
    }

    private static int verdictColor(Verdict verdict) {
        return switch (verdict) {
            case AC -> 0x55FF55;
            case WA -> 0xFF5555;
            case RE -> 0xFFAA55;
            case TLE -> 0xFFFF55;
        };
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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && this.currentTab == Tab.JUDGE && !this.judgeResults.isEmpty()) {
            int x = contentX();
            int y = panelTop();
            int width = contentWidth();
            int listWidth = Math.min(250, Math.max(190, width / 3));
            int rowHeight = 18;
            int rowY = y + 6;
            for (int i = 0; i < this.judgeResults.size(); i++) {
                if (mouseX >= x + 4 && mouseX < x + listWidth && mouseY >= rowY && mouseY < rowY + rowHeight) {
                    if (this.judgeResults.get(i).verdict() != Verdict.AC) {
                        this.selectedJudgeIndex = i;
                        updateActionButtons();
                        return true;
                    }
                    break;
                }
                rowY += rowHeight + 2;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.currentTab == Tab.PROBLEM) {
            int x = contentX();
            int width = contentWidth();
            int top = panelTop() + 16;
            int bottom = panelBottom();
            if (mouseX >= x && mouseX < x + width && mouseY >= top && mouseY < bottom) {
                int viewportHeight = Math.max(1, bottom - top);
                int maxScroll = Math.max(0, this.problemContentHeight - viewportHeight);
                if (maxScroll > 0) {
                    int delta = (int) Math.round(verticalAmount * 24.0);
                    this.problemScroll = Math.max(0, Math.min(maxScroll, this.problemScroll - delta));
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
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
            return this.label;
        }
    }

    private record JudgeCaseResult(
        RunnerClient.TestCase test,
        Verdict verdict,
        RunnerClient.RunResult result
    ) {
    }
}
