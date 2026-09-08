package com.dhj.actinium.compat.lumenized;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.stacks.BlendStateStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * Save/restore of the GLSM-tracked GL state around the GTCEu/Lumenized bloom pass.
 *
 * <p>The bloom pass ({@code BloomEffectUtil.renderBloomBlockLayer}) re-binds framebuffers,
 * runs external CCL shader programs and re-points texture units 0/1 at its own FBO
 * textures. Most of that state is repaired by the vanilla code that follows the
 * translucent pass, but not all of it: the first-person hand pass that runs right after
 * renders fully black when any of the pieces it relies on (program 0, main framebuffer,
 * texture-unit bindings/enables, blend/depth/cull state) is left disturbed.
 *
 * <p>Snapshots the tracked state on entry and restores it on exit, so the bloom pass
 * cannot leak state into clouds, the first-person hand or the GUI. The first restore
 * logs which fields actually diverged, to identify the leak source.
 */
public final class BloomStateGuard {

    private static final Logger LOGGER = LogManager.getLogger("ActiniumBloomStateGuard");

    private static final Saved SAVED = new Saved();
    private static boolean occupied;
    private static boolean divergenceLogged;

    private BloomStateGuard() {
    }

    /** Snapshots the GLSM-tracked state that the bloom pass is allowed to disturb. */
    public static void push() {
        if (occupied) {
            LOGGER.warn("Bloom GL state push while a snapshot is already held; discarding the older one");
        }
        SAVED.framebuffer = GLStateManager.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        SAVED.activeUnit = GLStateManager.getActiveTextureUnit();
        SAVED.binding0 = GLStateManager.getBoundTextureForServerState(0);
        SAVED.binding1 = GLStateManager.getBoundTextureForServerState(1);
        SAVED.texture0 = textureEnabled(0);
        SAVED.texture1 = textureEnabled(1);
        final BlendStateStack blend = GLStateManager.getBlendState();
        SAVED.blend = blend.isEnabled();
        SAVED.blendSrcRgb = blend.getSrcRgb();
        SAVED.blendDstRgb = blend.getDstRgb();
        SAVED.blendSrcAlpha = blend.getSrcAlpha();
        SAVED.blendDstAlpha = blend.getDstAlpha();
        SAVED.depthTest = GLStateManager.getDepthTest().isEnabled();
        SAVED.depthMask = GLStateManager.getDepthState().isMaskEnabled();
        SAVED.cull = GLStateManager.getCullState().isEnabled();
        occupied = true;
    }

    /** Restores the snapshot taken by {@link #push()}. */
    public static void pop() {
        if (!occupied) {
            LOGGER.error("Bloom GL state pop without a matching push; skipping restore");
            return;
        }
        occupied = false;
        logDivergenceOnce();
        // The bloom pass must run with program 0 afterwards; CCL releases its programs
        // but force it in case an early return skipped a release.
        GLStateManager.glUseProgram(0);
        GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, SAVED.framebuffer);
        restoreUnit(1, SAVED.binding1, SAVED.texture1);
        restoreUnit(0, SAVED.binding0, SAVED.texture0);
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + SAVED.activeUnit);
        if (SAVED.blend) {
            GLStateManager.enableBlend();
        } else {
            GLStateManager.disableBlend();
        }
        GLStateManager.tryBlendFuncSeparate(SAVED.blendSrcRgb, SAVED.blendDstRgb, SAVED.blendSrcAlpha, SAVED.blendDstAlpha);
        if (SAVED.depthTest) {
            GLStateManager.enableDepthTest();
        } else {
            GLStateManager.disableDepthTest();
        }
        GLStateManager.glDepthMask(SAVED.depthMask);
        if (SAVED.cull) {
            GLStateManager.enableCull();
        } else {
            GLStateManager.disableCull();
        }
    }

    private static boolean textureEnabled(int unit) {
        final int previous = GLStateManager.getActiveTextureUnit();
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        final boolean enabled = GLStateManager.glIsEnabled(GL11.GL_TEXTURE_2D);
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + previous);
        return enabled;
    }

    private static void restoreUnit(int unit, int binding, boolean enabled) {
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GLStateManager.bindTexture(binding);
        if (enabled) {
            GLStateManager.enableTexture();
        } else {
            GLStateManager.disableTexture();
        }
    }

    /** Logs the state fields the bloom pass actually disturbed, on the first restore only. */
    private static void logDivergenceOnce() {
        if (divergenceLogged) {
            return;
        }
        divergenceLogged = true;
        final StringBuilder report = new StringBuilder("Bloom pass GL state divergence on first frame:");
        appendDiff(report, "framebuffer", SAVED.framebuffer, GLStateManager.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING));
        appendDiff(report, "activeTextureUnit", SAVED.activeUnit, GLStateManager.getActiveTextureUnit());
        appendDiff(report, "textureBinding[0]", SAVED.binding0, GLStateManager.getBoundTextureForServerState(0));
        appendDiff(report, "textureBinding[1]", SAVED.binding1, GLStateManager.getBoundTextureForServerState(1));
        appendDiff(report, "texture2D[0]", SAVED.texture0, textureEnabled(0));
        appendDiff(report, "texture2D[1]", SAVED.texture1, textureEnabled(1));
        final BlendStateStack blend = GLStateManager.getBlendState();
        appendDiff(report, "blend", SAVED.blend, blend.isEnabled());
        appendDiff(report, "depthTest", SAVED.depthTest, GLStateManager.getDepthTest().isEnabled());
        appendDiff(report, "depthMask", SAVED.depthMask, GLStateManager.getDepthState().isMaskEnabled());
        appendDiff(report, "cull", SAVED.cull, GLStateManager.getCullState().isEnabled());
        LOGGER.info(report.toString());
    }

    private static void appendDiff(StringBuilder report, String name, Object saved, Object current) {
        if (!saved.equals(current)) {
            report.append(' ').append(name).append('=').append(saved).append("->").append(current).append(';');
        }
    }

    private static final class Saved {
        int framebuffer;
        int activeUnit;
        int binding0;
        int binding1;
        boolean texture0;
        boolean texture1;
        boolean blend;
        int blendSrcRgb;
        int blendDstRgb;
        int blendSrcAlpha;
        int blendDstAlpha;
        boolean depthTest;
        boolean depthMask;
        boolean cull;
    }
}
