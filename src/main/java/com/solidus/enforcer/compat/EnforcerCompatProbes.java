package com.solidus.enforcer.compat;

import com.solidus.enforcer.SolidusEnforcerMod;
import com.solidus.enforcer.compat.EnforcerCompatState.Surface;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * EnforcerCompatProbes — the startup "manual check" for the two Minecraft
 * shapes Enforcer's mixins hook (WS-C C-2ج, transplant of Core's audit W-3
 * {@code CompatProbes}).
 *
 * <p>The mixin config now sets {@code require = 0} on both injectors, so a
 * future Minecraft update that reshapes a target degrades to a <b>log
 * warning</b> instead of a startup crash. But a silently-dead mixin means
 * bounty claims would never settle and PvP damage would never be recorded —
 * money-adjacent features vanishing without a trace. Something has to
 * notice, before the first player is affected. That is this class.</p>
 *
 * <p>At mod initialization we reflectively re-verify both shapes:</p>
 * <ol>
 *   <li>{@code ServerPlayer#hurtServer(ServerLevel, DamageSource, float)}
 *       returning {@code boolean} — the ServerPlayerDamageMixin injection
 *       target (the handler's {@code CallbackInfoReturnable<Boolean>}
 *       needs exactly this shape);</li>
 *   <li>{@code ServerPlayer#die(DamageSource)} returning {@code void} — the
 *       ServerPlayerDeathMixin injection target (plain {@code CallbackInfo}).</li>
 * </ol>
 *
 * <p><b>Hierarchy walk:</b> both methods are declared up the
 * {@code ServerPlayer} hierarchy ({@code hurtServer} is a
 * {@code LivingEntity} override candidate, {@code die} too), so the probe
 * walks superclass chains instead of calling {@code getDeclaredMethod} on
 * {@code ServerPlayer} alone — the exact 2.3.2 Core lesson (an inherited
 * member made a {@code getDeclaredField} probe fail forever and wrongly
 * disabled the GUI layer on every 2.3.0/2.3.1 server).</p>
 *
 * <p>This works without any mappings knowledge because Minecraft 26.1+
 * ships unobfuscated — runtime names are Mojang's official names, exactly
 * the names we compiled against.</p>
 *
 * <p><b>Failure policy of the probes themselves:</b> fail-closed for the
 * affected pipeline only, never for the server. Any unexpected exception
 * inside a probe is caught, recorded as a failure with its cause, and the
 * server boots on. The probe can never become the crash it exists to
 * prevent. A DAMAGE_TELEMETRY failure does not touch the KILL_PIPELINE and
 * vice versa — the surfaces are probed and reported independently.</p>
 *
 * @see EnforcerCompatState for the resulting per-surface flags
 * @since 2.4.0
 */
public final class EnforcerCompatProbes {

    private EnforcerCompatProbes() {
    }

    /**
     * Runs all probes once and stores the per-surface reports in
     * {@link EnforcerCompatState}. Called from SolidusEnforcerMod
     * initialization, before any player can connect.
     *
     * <p>Idempotent: the first call wins and later calls are no-ops.</p>
     */
    public static synchronized void verifyCompatibility() {
        if (EnforcerCompatState.isVerified()) {
            return;
        }

        probeDamageSurface();
        probeKillSurface();
        logOutcome();
    }

    // -- Surface probes -------------------------------------------------------

    private static void probeDamageSurface() {
        List<String> failures = new ArrayList<>();

        Class<?> serverPlayer = softClass("net.minecraft.server.level.ServerPlayer");
        Class<?> level = softClass("net.minecraft.server.level.ServerLevel");
        Class<?> damageSource = softClass("net.minecraft.world.damagesource.DamageSource");

        if (serverPlayer == null) {
            failures.add("class net.minecraft.server.level.ServerPlayer -> not found");
        }
        if (level == null) {
            failures.add("class net.minecraft.server.level.ServerLevel -> not found");
        }
        if (damageSource == null) {
            failures.add("class net.minecraft.world.damagesource.DamageSource -> not found");
        }

        if (serverPlayer != null && level != null && damageSource != null) {
            final Class<?> sp = serverPlayer;
            probe(failures,
                "ServerPlayer#hurtServer(ServerLevel, DamageSource, float): boolean [hierarchy]",
                () -> {
                    Method m = findHierarchyMethod(sp, "hurtServer", level, damageSource, float.class);
                    // The mixin handler uses CallbackInfoReturnable<Boolean>, so the
                    // target must be the boolean-returning 3-arg overload our
                    // callback compiled against.
                    if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) {
                        throw new NoSuchMethodException(
                            "hurtServer(ServerLevel, DamageSource, float) exists but returns "
                                + m.getReturnType().getName() + " (need boolean)");
                    }
                    return m;
                });
            if (!failures.isEmpty()) {
                // Early signal for the update checklist: what DOES hurtServer
                // look like on the running version?
                logOverloads(serverPlayer, "hurtServer");
            }
        }

        EnforcerCompatState.report(Surface.DAMAGE_TELEMETRY, failures);
    }

    private static void probeKillSurface() {
        List<String> failures = new ArrayList<>();

        Class<?> serverPlayer = softClass("net.minecraft.server.level.ServerPlayer");
        Class<?> damageSource = softClass("net.minecraft.world.damagesource.DamageSource");

        if (serverPlayer == null) {
            failures.add("class net.minecraft.server.level.ServerPlayer -> not found");
        }
        if (damageSource == null) {
            failures.add("class net.minecraft.world.damagesource.DamageSource -> not found");
        }

        if (serverPlayer != null && damageSource != null) {
            final Class<?> sp = serverPlayer;
            probe(failures,
                "ServerPlayer#die(DamageSource): void [hierarchy]",
                () -> {
                    Method m = findHierarchyMethod(sp, "die", damageSource);
                    // The mixin handler uses a plain CallbackInfo, so the target
                    // must be the void-returning 1-arg overload.
                    if (m.getReturnType() != void.class) {
                        throw new NoSuchMethodException(
                            "die(DamageSource) exists but returns "
                                + m.getReturnType().getName() + " (need void)");
                    }
                    return m;
                });
            if (!failures.isEmpty()) {
                logOverloads(serverPlayer, "die");
            }
        }

        EnforcerCompatState.report(Surface.KILL_PIPELINE, failures);
    }

    // -- Reporting ------------------------------------------------------------

    /**
     * The loud, actionable outcome log — one INFO line when everything is
     * green, one box per broken surface naming WHAT broke, WHAT still
     * works, and WHAT to do next (mirrors Core's W-3 banner).
     */
    private static void logOutcome() {
        List<String> damage = EnforcerCompatState.damageFailures();
        List<String> kill = EnforcerCompatState.killFailures();

        if (damage.isEmpty() && kill.isEmpty()) {
            SolidusEnforcerMod.LOGGER.info(
                "Enforcer compatibility probe: both mixin surfaces verified against this "
                    + "Minecraft version (hurtServer + die) — bounty claims, damage telemetry "
                    + "and kill splits are live.");
            return;
        }

        if (!kill.isEmpty()) {
            SolidusEnforcerMod.LOGGER.error(
                "┌────────────────────────────────────────────────────────────────────────────┐");
            SolidusEnforcerMod.LOGGER.error(
                "│ Solidus Enforcer: BOUNTY / DEATH-POOL PIPELINE DISABLED (Minecraft        │");
            SolidusEnforcerMod.LOGGER.error(
                "│ internals changed). The server stays up. Failed probe(s):                  │");
            for (String failure : kill) {
                SolidusEnforcerMod.LOGGER.error("│   - {}", failure);
            }
            SolidusEnforcerMod.LOGGER.error(
                "│ Disabled: new bounty placements, claim settlements, PvP kill splits.      │");
            SolidusEnforcerMod.LOGGER.error(
                "│ Still running: /bounty + /hunter views, /enforcer admin (treasury, ledger,  │");
            SolidusEnforcerMod.LOGGER.error(
                "│ stats), storage, licenses — no data was lost.                               │");
            SolidusEnforcerMod.LOGGER.error(
                "│ Fix: update Solidus Enforcer to a build matching this Minecraft version     │");
            SolidusEnforcerMod.LOGGER.error(
                "└────────────────────────────────────────────────────────────────────────────┘");
        }

        if (!damage.isEmpty()) {
            SolidusEnforcerMod.LOGGER.error(
                "┌────────────────────────────────────────────────────────────────────────────┐");
            SolidusEnforcerMod.LOGGER.error(
                "│ Solidus Enforcer: DAMAGE TELEMETRY DISABLED (Minecraft internals changed).  │");
            SolidusEnforcerMod.LOGGER.error(
                "│ Failed probe(s):                                                            │");
            for (String failure : damage) {
                SolidusEnforcerMod.LOGGER.error("│   - {}", failure);
            }
            SolidusEnforcerMod.LOGGER.error(
                "│ Impact: alliance splits flatten to killer-takes-all (the same legitimate   │");
            SolidusEnforcerMod.LOGGER.error(
                "│ state as a one-shot kill); bounty claims keep settling via the die hook.   │");
            SolidusEnforcerMod.LOGGER.error(
                "│ Fix: update Solidus Enforcer to a build matching this Minecraft version.  │");
            SolidusEnforcerMod.LOGGER.error(
                "└────────────────────────────────────────────────────────────────────────────┘");
        }
    }

    /**
     * Early signal: every method with the expected name visible anywhere in
     * the ServerPlayer hierarchy, so a failing probe starts the update
     * checklist from the ACTUAL shape instead of a guess.
     */
    private static void logOverloads(Class<?> serverPlayer, String methodName) {
        try {
            List<String> overloads = new ArrayList<>();
            for (Class<?> c = serverPlayer; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(methodName) && !m.isSynthetic()) {
                        overloads.add(c.getSimpleName() + "#" + m.getName() + "("
                            + describe(m.getParameterTypes()) + ") : "
                            + m.getReturnType().getSimpleName()
                            + (Modifier.isPrivate(m.getModifiers()) ? " (private)" : ""));
                    }
                }
            }
            if (overloads.isEmpty()) {
                SolidusEnforcerMod.LOGGER.error(
                    "Enforcer update checklist: NO method named '{}' anywhere in the ServerPlayer hierarchy.",
                    methodName);
            } else {
                SolidusEnforcerMod.LOGGER.error(
                    "Enforcer update checklist: '{}' overloads actually present: {}",
                    methodName, String.join(" | ", overloads));
            }
        } catch (Throwable ignored) {
            // Diagnostics must never throw.
        }
    }

    // -- Helpers ----------------------------------------------------------------

    /**
     * Finds a method by name and exact parameter types anywhere in the
     * class's superclass chain (both targets are declared above
     * ServerPlayer in the vanilla hierarchy).
     *
     * @throws NoSuchMethodException when the shape is nowhere in the chain
     */
    private static Method findHierarchyMethod(Class<?> start, String name, Class<?>... params)
            throws NoSuchMethodException {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException inherited) {
                // walk up
            }
        }
        throw new NoSuchMethodException(name + "(" + describe(params) + ") is not declared anywhere in the "
            + start.getSimpleName() + " hierarchy");
    }

    /** Loads a class without initializing it; null (and a debug log) on failure. */
    private static Class<?> softClass(String name) {
        try {
            return Class.forName(name, false, EnforcerCompatProbes.class.getClassLoader());
        } catch (Throwable t) {
            SolidusEnforcerMod.LOGGER.debug("Enforcer compat probe: cannot load {} ({})", name, t.toString());
            return null;
        }
    }

    /** Runs one probe, appending a human-readable failure on error. */
    private static void probe(List<String> failures, String what, Probe probe) {
        try {
            probe.run();
        } catch (Throwable t) {
            failures.add(what + " -> " + t.getClass().getSimpleName()
                + (t.getMessage() != null ? ": " + t.getMessage() : ""));
        }
    }

    private static String describe(Class<?>... types) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(types[i].getSimpleName());
        }
        return sb.toString();
    }

    @FunctionalInterface
    private interface Probe {
        Object run() throws Throwable;
    }
}
