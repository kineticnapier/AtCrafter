package io.github.kineticnapier.atcrafter.client.screen;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
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
 * The source code is copied to the OS clipboard before this screen opens.  AtCoder's
 * own submit page remains responsible for login, CAPTCHA/Turnstile, language choice,
 * and the final submit click.
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
    private MCEFBrowser browser;
    private boolean initScheduled;
    private String status = "MCEF を初期化中...";

    private AtCoderBrowserScreen(Screen parent, String submitUrl) {
        super(Component.literal("AtCrafter - AtCoder 提出"));
        this.parent = parent;
        this.submitUrl = submitUrl;
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
        minecraft.keyboardHandler.setClipboard(code);
        minecraft.setScreen(new AtCoderBrowserScreen(parent, url));
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
            this.status = "コードはクリップボードにコピー済みです。提出欄で Ctrl+V。";
        } catch (RuntimeException error) {
            this.status = "MCEF ブラウザを開けませんでした: " + rootMessage(error);
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

        graphics.fill(0, 0, this.width, TOP, 0xEE101010);
        super.render(graphics, mouseX, mouseY, partialTick);
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
