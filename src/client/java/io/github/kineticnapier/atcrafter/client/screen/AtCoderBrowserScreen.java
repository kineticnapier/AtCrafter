package io.github.kineticnapier.atcrafter.client.screen;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Small MCEF-backed browser used for AtCoder's human-confirmed web submission flow.
 *
 * AtCrafter fills the selected task, a CPython language, and the current source code.
 * AtCoder's own page remains responsible for login, CAPTCHA/Turnstile, and the final
 * submit click.
 */
public final class AtCoderBrowserScreen extends Screen {
    private static final Pattern PROBLEM_ID = Pattern.compile(
        "atcoder:([a-z0-9_-]+):([a-z0-9_-]+)"
    );
    private static final int LEFT = 6;
    private static final int RIGHT = 6;
    private static final int TOP = 30;
    private static final int BOTTOM = 6;

    private final Screen parent;
    private final String submitUrl;
    private final String taskId;
    private final String sourceCode;
    private MCEFBrowser browser;
    private boolean initScheduled;
    private int autoFillTicker;
    private String status = "MCEF を初期化中...";

    private AtCoderBrowserScreen(Screen parent, String submitUrl, String taskId, String sourceCode) {
        super(Component.literal("AtCrafter - AtCoder 提出"));
        this.parent = parent;
        this.submitUrl = submitUrl;
        this.taskId = taskId;
        this.sourceCode = sourceCode;
    }

    public static void open(Screen parent, String problemId, String code) {
        Matcher match = PROBLEM_ID.matcher(problemId);
        if (!match.matches()) {
            throw new IllegalArgumentException("invalid AtCoder problem id: " + problemId);
        }

        String contestId = match.group(1);
        String taskId = match.group(2);
        String url = "https://atcoder.jp/contests/" + contestId
            + "/submit?taskScreenName=" + taskId;

        Minecraft minecraft = Minecraft.getInstance();
        // Keep the clipboard fallback in case AtCoder changes its submit form.
        minecraft.keyboardHandler.setClipboard(code);
        minecraft.setScreen(new AtCoderBrowserScreen(parent, url, taskId, code));
    }

    @Override
    protected void init() {
        addRenderableWidget(
            Button.builder(Component.literal("戻る"), button -> onClose())
                .bounds(LEFT, 4, 54, 20)
                .build()
        );
        addRenderableWidget(
            Button.builder(Component.literal("再読込"), button -> {
                if (this.browser != null) {
                    this.browser.reload();
                    this.autoFillTicker = 0;
                    this.status = "再読込中... 自動入力を待っています。";
                }
            })
                .bounds(LEFT + 58, 4, 62, 20)
                .build()
        );
        ensureBrowser();
    }

    private void ensureBrowser() {
        if (this.browser != null) {
            resizeBrowser();
            return;
        }

        if (MCEF.isInitialized()) {
            createBrowser();
            return;
        }

        this.status = "MCEF を初期化中...";
        if (this.initScheduled) {
            return;
        }
        this.initScheduled = true;
        MCEF.scheduleForInit(success -> Minecraft.getInstance().execute(() -> {
            this.initScheduled = false;
            if (Minecraft.getInstance().screen != this) {
                return;
            }
            if (!success) {
                this.status = "MCEF の初期化に失敗しました。";
                return;
            }
            createBrowser();
        }));
    }

    private void createBrowser() {
        if (this.browser != null) {
            return;
        }
        try {
            this.browser = MCEF.createBrowser(this.submitUrl, false);
            this.browser.useBrowserControls(true);
            resizeBrowser();
            this.status = "問題・Python・コードを自動入力します。CAPTCHA確認後に提出してください。";
        } catch (RuntimeException error) {
            this.status = "MCEF ブラウザを開けませんでした: " + rootMessage(error);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.browser == null) {
            return;
        }

        // The submit page may appear only after logging in, and AtCoder rebuilds parts
        // of the form after task/language changes. Retry until the page itself marks
        // the current document as completely filled.
        if (++this.autoFillTicker >= 10) {
            this.autoFillTicker = 0;
            injectAutoFill();
        }
    }

    private void injectAutoFill() {
        // Do not gate this on CefBrowser#isLoading(). Third-party widgets such as
        // Turnstile can keep the browser in a loading state after the submit form is
        // already usable. hasDocument() is enough; the script has its own readiness checks.
        if (this.browser == null || !this.browser.hasDocument()) {
            return;
        }

        String currentUrl = this.browser.getURL();
        if (currentUrl == null || !currentUrl.contains("/submit")) {
            return;
        }

        String taskJson = new JsonPrimitive(this.taskId).toString();
        String sourceJson = new JsonPrimitive(this.sourceCode).toString();
        String script = """
            (() => {
                const root = document.documentElement;
                if (!root || root.dataset.atcrafterFilled === '1') return;
                if (document.readyState === 'loading') return;

                const taskId = %s;
                const source = %s;
                const task = document.querySelector('select[name="data.TaskScreenName"]');
                const language = document.querySelector('select[name="data.LanguageId"]');
                if (!task || !language) return;

                const change = element => {
                    if (window.jQuery) {
                        window.jQuery(element).trigger('change');
                    } else {
                        element.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                };

                if (task.value !== taskId) {
                    const option = Array.from(task.options).find(o => o.value === taskId);
                    if (!option) return;
                    task.value = taskId;
                    change(task);
                    // AtCoder rebuilds the language list after changing the task.
                    // Wait for the next injection pass before touching anything else.
                    return;
                }

                const candidates = Array.from(language.options).filter(option => {
                    const text = (option.textContent || '').toLowerCase();
                    return option.value && text.includes('python');
                });
                if (candidates.length === 0) return;

                const score = option => {
                    const text = option.textContent || '';
                    const lower = text.toLowerCase();
                    let family = 0;
                    if (lower.includes('cpython')) family = 400;
                    else if (lower.includes('pypy')) family = 200;
                    else if (lower.includes('cython') || lower.includes('micropython')) family = 100;
                    else if (lower.includes('python')) family = 300;

                    const version = text.match(/(\\d+)\\.(\\d+)(?:\\.(\\d+))?/);
                    const major = version ? Number(version[1]) : 0;
                    const minor = version ? Number(version[2]) : 0;
                    const patch = version && version[3] ? Number(version[3]) : 0;
                    const id = Number(option.value) || 0;
                    return family * 100000000 + major * 1000000 + minor * 10000 + patch * 100 + id;
                };

                candidates.sort((a, b) => score(b) - score(a));
                const selectedLanguage = candidates[0];
                if (language.value !== selectedLanguage.value) {
                    language.value = selectedLanguage.value;
                    change(language);
                    // This was the important bug: AtCoder can recreate/reset the editor
                    // in the language-change handler. Filling sourceCode in the same pass
                    // meant our value was immediately wiped. Let that handler finish first.
                    return;
                }

                const code = document.querySelector('textarea[name="sourceCode"]');
                if (!code) return;

                const codeMirrorNode = document.querySelector('.CodeMirror');
                const codeMirror = codeMirrorNode && codeMirrorNode.CodeMirror;
                // If CodeMirror's DOM already exists but its JS instance is not attached yet,
                // wait instead of filling the hidden textarea just before initialization.
                if (codeMirrorNode && !codeMirror) return;

                let editorUpdated = false;
                if (codeMirror && typeof codeMirror.setValue === 'function') {
                    codeMirror.setValue(source);
                    editorUpdated = typeof codeMirror.getValue !== 'function' || codeMirror.getValue() === source;
                }

                // Keep the real form field in sync as well. This also covers the plain
                // textarea fallback if AtCoder disables its editor.
                code.value = source;
                code.dispatchEvent(new Event('input', { bubbles: true }));
                code.dispatchEvent(new Event('change', { bubbles: true }));

                // Some AtCoder editor revisions have used Ace. Support it without making
                // it a requirement for the normal CodeMirror/plain-textarea path.
                const aceNode = document.querySelector('.ace_editor');
                if (!editorUpdated && aceNode && window.ace && typeof window.ace.edit === 'function') {
                    try {
                        const aceEditor = window.ace.edit(aceNode);
                        aceEditor.setValue(source, -1);
                        editorUpdated = typeof aceEditor.getValue !== 'function' || aceEditor.getValue() === source;
                    } catch (_) {
                        return;
                    }
                }

                if (code.value !== source) return;
                if (codeMirrorNode && !editorUpdated) return;
                if (aceNode && !editorUpdated) return;

                root.dataset.atcrafterFilled = '1';
            })();
            """.formatted(taskJson, sourceJson);

        try {
            this.browser.executeJavaScript(script, currentUrl, 1);
        } catch (RuntimeException error) {
            this.status = "自動入力に失敗しました。Ctrl+V は使用できます: " + rootMessage(error);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private double guiScale() {
        return this.minecraft == null ? 1.0 : this.minecraft.getWindow().getGuiScale();
    }

    private int browserWidthPixels() {
        return Math.max(1, (int) ((this.width - LEFT - RIGHT) * guiScale()));
    }

    private int browserHeightPixels() {
        return Math.max(1, (int) ((this.height - TOP - BOTTOM) * guiScale()));
    }

    private int browserMouseX(double x) {
        return (int) ((x - LEFT) * guiScale());
    }

    private int browserMouseY(double y) {
        return (int) ((y - TOP) * guiScale());
    }

    private boolean insideBrowser(double x, double y) {
        return x >= LEFT && x < this.width - RIGHT && y >= TOP && y < this.height - BOTTOM;
    }

    private void resizeBrowser() {
        if (this.browser != null && this.width > LEFT + RIGHT && this.height > TOP + BOTTOM) {
            this.browser.resize(browserWidthPixels(), browserHeightPixels());
        }
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
        resizeBrowser();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Screen#render applies Minecraft's menu-background blur. Render it first so it
        // only affects the world behind this screen; drawing the MCEF texture before
        // super.render() makes Chromium itself get blurred into an unreadable image.
        super.render(graphics, mouseX, mouseY, partialTick);

        if (this.browser != null && this.browser.getRenderer().getTextureID() != 0) {
            RenderSystem.disableDepthTest();
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, this.browser.getRenderer().getTextureID());

            Tesselator tesselator = Tesselator.getInstance();
            BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            buffer.addVertex(LEFT, this.height - BOTTOM, 0)
                .setUv(0.0f, 1.0f)
                .setColor(255, 255, 255, 255);
            buffer.addVertex(this.width - RIGHT, this.height - BOTTOM, 0)
                .setUv(1.0f, 1.0f)
                .setColor(255, 255, 255, 255);
            buffer.addVertex(this.width - RIGHT, TOP, 0)
                .setUv(1.0f, 0.0f)
                .setColor(255, 255, 255, 255);
            buffer.addVertex(LEFT, TOP, 0)
                .setUv(0.0f, 0.0f)
                .setColor(255, 255, 255, 255);
            BufferUploader.drawWithShader(buffer.build());

            RenderSystem.setShaderTexture(0, 0);
            RenderSystem.enableDepthTest();
        } else {
            graphics.fill(LEFT, TOP, this.width - RIGHT, this.height - BOTTOM, 0xFF111111);
            graphics.drawCenteredString(
                this.font,
                Component.literal(this.status),
                this.width / 2,
                TOP + 24,
                0xFFFFFF
            );
        }

        // The browser starts below TOP, so widgets drawn by super.render() remain visible.
        // Only paint a small status strip that does not cover the two buttons.
        graphics.fill(LEFT + 124, 4, this.width - RIGHT, 24, 0xEE101010);
        graphics.drawString(this.font, Component.literal(this.status), LEFT + 128, 10, 0xE0E0E0);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (this.browser != null && insideBrowser(mouseX, mouseY)) {
            this.browser.sendMousePress(browserMouseX(mouseX), browserMouseY(mouseY), button);
            this.browser.setFocus(true);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.browser != null && insideBrowser(mouseX, mouseY)) {
            this.browser.sendMouseRelease(browserMouseX(mouseX), browserMouseY(mouseY), button);
            this.browser.setFocus(true);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (this.browser != null && insideBrowser(mouseX, mouseY)) {
            this.browser.sendMouseMove(browserMouseX(mouseX), browserMouseY(mouseY));
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.browser != null && insideBrowser(mouseX, mouseY)) {
            this.browser.sendMouseWheel(browserMouseX(mouseX), browserMouseY(mouseY), scrollY, 0);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (this.browser != null) {
            this.browser.sendKeyPress(keyCode, scanCode, modifiers);
            this.browser.setFocus(true);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (this.browser != null) {
            this.browser.sendKeyRelease(keyCode, scanCode, modifiers);
            this.browser.setFocus(true);
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (codePoint == 0) {
            return false;
        }
        if (this.browser != null) {
            this.browser.sendKeyTyped(codePoint, modifiers);
            this.browser.setFocus(true);
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private void closeBrowser() {
        if (this.browser != null) {
            this.browser.close();
            this.browser = null;
        }
    }

    @Override
    public void onClose() {
        closeBrowser();
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public void removed() {
        closeBrowser();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
