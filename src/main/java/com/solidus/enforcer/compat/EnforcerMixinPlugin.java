package com.solidus.enforcer.compat;

import com.solidus.enforcer.SolidusEnforcerMod;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * EnforcerMixinPlugin — the apply-visibility channel for Enforcer's two
 * mixins (WS-C C-2ج, transplant of Core's {@code SolidusMixinPlugin}).
 *
 * <p><b>Why:</b> the compatibility probes in {@link EnforcerCompatProbes}
 * verify the Minecraft <i>shapes</i> we compiled against. But "the shapes
 * exist" and "our mixin actually transformed the class" are different
 * facts — a conflicting transformer from another mod could in principle
 * interfere even when the target looks intact.</p>
 *
 * <p>Mixin calls {@link #postApply} for every successfully applied
 * mixin:target pair. This plugin records both applications into flags, and
 * the first real player connection triggers the cross-check (see
 * {@link EnforcerCompat#confirmMixinsAfterFirstConnection()}): by that
 * moment the ServerPlayer class has necessarily been initialized, so if a
 * probe passed but the apply event never fired, the corresponding pipeline
 * is downgraded at runtime with a loud error — the same
 * graceful-degradation path as a failed probe. (The ServerPlayer class
 * initializes lazily — before the first connection the "applied" flags are
 * legitimately false, which is why the cross-check waits for a JOIN and
 * the /enforcer compat report says "pending first connection" rather than
 * "failed".)</p>
 *
 * <p>All other plugin hooks are intentionally permissive: this plugin
 * observes, it never vetoes. The call-site gates in
 * {@link EnforcerCompat} provide the fail-closed behavior.</p>
 *
 * @since 2.4.0
 */
public class EnforcerMixinPlugin implements IMixinConfigPlugin {

    /** Fully-qualified names of the mixins this plugin tracks. */
    static final String DAMAGE_MIXIN = "com.solidus.enforcer.mixin.ServerPlayerDamageMixin";
    static final String DEATH_MIXIN = "com.solidus.enforcer.mixin.ServerPlayerDeathMixin";

    /** Set by postApply when each mixin transformed its target class. */
    private static volatile boolean damageMixinApplied = false;
    private static volatile boolean deathMixinApplied = false;

    /**
     * @return true when Mixin reported a successful apply of
     *         ServerPlayerDamageMixin to its target class
     */
    public static boolean isDamageMixinApplied() {
        return damageMixinApplied;
    }

    /**
     * @return true when Mixin reported a successful apply of
     *         ServerPlayerDeathMixin to its target class
     */
    public static boolean isDeathMixinApplied() {
        return deathMixinApplied;
    }

    // -- IMixinConfigPlugin (observation only) --------------------------------

    @Override
    public void onLoad(String mixinConfig) {
        SolidusEnforcerMod.LOGGER.debug("Enforcer mixin config loaded: {}", mixinConfig);
    }

    @Override
    public String getRefMapperConfig() {
        return null; // unobfuscated Minecraft: no refmap needed
    }

    @Override
    public List<String> getMixins() {
        return null; // all mixins are declared statically in the JSON config
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // no-op: observe, never veto
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true; // observe, never veto — gates live at the call sites
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
        // no-op
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
        if (DAMAGE_MIXIN.equals(mixinClassName)) {
            damageMixinApplied = true;
            SolidusEnforcerMod.LOGGER.debug(
                "Enforcer compat: ServerPlayerDamageMixin applied to {}.", targetClassName);
        } else if (DEATH_MIXIN.equals(mixinClassName)) {
            deathMixinApplied = true;
            SolidusEnforcerMod.LOGGER.debug(
                "Enforcer compat: ServerPlayerDeathMixin applied to {}.", targetClassName);
        }
    }
}
