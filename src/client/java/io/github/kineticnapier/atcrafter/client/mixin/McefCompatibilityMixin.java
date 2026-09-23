package io.github.kineticnapier.atcrafter.client.mixin;

import java.util.Arrays;
import org.cef.CefSettings;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes MCEF behave more like a normal Chromium instance for sites that perform
 * browser-integrity checks such as Cloudflare Turnstile.
 *
 * MCEF 2.1.6 starts CEF with --disable-web-security and adds an MCEF/2 product
 * token to the user agent. Neither is needed by AtCrafter's browser screen.
 */
@Mixin(targets = "com.cinemamod.mcef.CefUtil", remap = false)
public abstract class McefCompatibilityMixin {
    private static final String DISABLE_WEB_SECURITY = "--disable-web-security";

    @ModifyArg(
        method = "init",
        at = @At(
            value = "INVOKE",
            target = "Lorg/cef/CefApp;startup([Ljava/lang/String;)Z"
        ),
        index = 0
    )
    private static String[] atcrafter$sanitizeStartupSwitches(String[] switches) {
        return atcrafter$sanitizeSwitches(switches);
    }

    @ModifyArg(
        method = "init",
        at = @At(
            value = "INVOKE",
            target = "Lorg/cef/CefApp;getInstance([Ljava/lang/String;Lorg/cef/CefSettings;)Lorg/cef/CefApp;"
        ),
        index = 0
    )
    private static String[] atcrafter$sanitizeInstanceSwitches(String[] switches) {
        return atcrafter$sanitizeSwitches(switches);
    }

    @Redirect(
        method = "init",
        at = @At(
            value = "FIELD",
            target = "Lorg/cef/CefSettings;user_agent_product:Ljava/lang/String;",
            opcode = Opcodes.PUTFIELD
        )
    )
    private static void atcrafter$omitMcefUserAgentProduct(CefSettings settings, String ignored) {
        // Leave this null so CEF uses its normal Chromium user agent without an
        // extra "MCEF/2" product token.
        settings.user_agent_product = null;
    }

    private static String[] atcrafter$sanitizeSwitches(String[] switches) {
        return Arrays.stream(switches)
            .filter(value -> !DISABLE_WEB_SECURITY.equals(value))
            .toArray(String[]::new);
    }
}
