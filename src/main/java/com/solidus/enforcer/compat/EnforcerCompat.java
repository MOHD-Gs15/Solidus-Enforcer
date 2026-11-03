package com.solidus.enforcer.compat;

import com.solidus.enforcer.compat.EnforcerCompatState.Surface;
import com.solidus.enforcer.util.TextUtil;
import net.minecraft.commands.CommandSourceStack;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * EnforcerCompat — the one facade the rest of the mod talks to about
 * Minecraft-version compatibility of the two mixin-backed pipelines
 * (WS-C C-2ج, transplant of Core's audit W-3 {@code Compat}).
 *
 * <p>Four jobs:</p>
 * <ol>
 *   <li>Expose {@link #verifyCompatibility()}, called once at mod
 *       initialization before any player can connect.</li>
 *   <li>Provide the fail-closed call-site gates the mixin callbacks check —
 *       a broken surface can never feed half-transformed data into the
 *       bounty economy.</li>
 *   <li>Provide {@link #ensureKillPipelineAvailable(CommandSourceStack)},
 *       the gate every bounty <i>placement</i> path must pass (manual
 *       {@code /bounty place} and the autonomous engine) — a degraded kill
 *       pipeline must refuse new placements with a clear message (their
 *       money would be escrowed forever), not silently accept them.</li>
 *   <li>Run the first-connection cross-check
 *       {@link #confirmMixinsAfterFirstConnection()} wired into the JOIN
 *       event: by then the ServerPlayer class has initialized, so a probe
 *       that passed but a mixin that never applied is caught and loudly
 *       downgraded before the first settlement can misbehave.</li>
 * </ol>
 *
 * <p><b>Scope of the degradation</b> (the contract /enforcer compat
 * reports): a broken KILL_PIPELINE refuses new placements and settles no
 * claims, while /bounty + /hunter views, /enforcer admin (treasury,
 * ledger, stats), storage and licenses keep working — no data is lost. A
 * broken DAMAGE_TELEMETRY flattens alliance splits to
 * killer-takes-all (the same legitimate state as a one-shot kill) while
 * bounty claims keep settling. The server never crashes over a Minecraft
 * shape change.</p>
 *
 * @since 2.4.0
 */
public final class EnforcerCompat {

    /** Guards the one-time "kill pipeline refused" log so the tick cadence cannot spam. */
    private static final AtomicBoolean KILL_REFUSED_LOGGED = new AtomicBoolean(false);

    /** Guards the one-time healthy cross-check confirmation line. */
    private static final AtomicBoolean CONFIRMED_LOGGED = new AtomicBoolean(false);

    private EnforcerCompat() {
    }

    // -- Initialization ---------------------------------------------------------

    /**
     * Runs the compatibility probes for both surfaces. Called once from
     * SolidusEnforcerMod initialization, before any player can connect.
     * Never throws: a probe failure degrades its pipeline loudly while the
     * rest of the mod keeps running.
     */
    public static void verifyCompatibility() {
        EnforcerCompatProbes.verifyCompatibility();
    }

    // -- Call-site gates (fail-closed) -------------------------------------------

    /**
     * The gate the ServerPlayerDamageMixin callback passes before recording
     * PvP damage. False when the hurtServer surface failed verification —
     * half-transformed damage data must never reach the alliance-split
     * computation.
     */
    public static boolean damageTelemetryAvailable() {
        return EnforcerCompatState.isDamageTelemetryAvailable();
    }

    /**
     * The gate the ServerPlayerDeathMixin callback (and the settlement code
     * behind it) passes. False when the die surface failed verification.
     */
    public static boolean killPipelineAvailable() {
        return EnforcerCompatState.isKillPipelineAvailable();
    }

    /**
     * The gate every bounty <i>placement</i> path must pass. On a degraded
     * kill pipeline it refuses with one clear, cooldown-free message
     * (placements escrow real money that could never be claimed) and
     * returns false.
     *
     * @param source the invoking admin/player source (never null)
     * @return true when placements may proceed
     */
    public static boolean ensureKillPipelineAvailable(CommandSourceStack source) {
        if (killPipelineAvailable()) {
            return true;
        }
        source.sendFailure(TextUtil.branded(
            "Bounties are disabled on this server (Minecraft internals changed — the "
                + "server owner must update Solidus Enforcer). Existing bounties stay safely in "
                + "escrow; /enforcer compat shows the details.",
            TextUtil.COLOR_BAD));
        return false;
    }

    /**
     * Once-per-boot WARN for code paths without a player to message
     * (KillProcessor's entry and the autonomous engine cycle), so a
     * degraded pipeline is visible in the log without per-kill spam.
     */
    public static void logKillPipelineRefusedOnce(Logger logger) {
        if (KILL_REFUSED_LOGGED.compareAndSet(false, true)) {
            logger.warn(
                "Solidus Enforcer: bounty claim pipeline is degraded on this Minecraft version — "
                    + "claims will not settle and placements are refused until Enforcer is updated. "
                    + "See /enforcer compat and the startup probe banner. Storage, licenses and admin "
                    + "views keep working; no data is lost.");
        }
    }

    // -- First-connection cross-check ----------------------------------------------

    /**
     * Cross-check wired into the first (every) player JOIN event: by that
     * moment the ServerPlayer class has necessarily been initialized, so
     * Mixin has either applied each mixin (EnforcerMixinPlugin saw the
     * postApply event) or definitively failed to (the require=0 injectors
     * logged a warning). If a probe passed but the apply never happened,
     * that pipeline is downgraded before any settlement can misbehave.
     *
     * <p>Self-limiting like Core's variant: once a surface is downgraded
     * its probe is no longer "passed", so later JOINs take the (silent)
     * healthy branch instead of re-appending the failure.</p>
     */
    public static void confirmMixinsAfterFirstConnection() {
        if (EnforcerCompatState.isDamageTelemetryAvailable()
            && !EnforcerMixinPlugin.isDamageMixinApplied()) {
            EnforcerCompatState.downgrade(Surface.DAMAGE_TELEMETRY,
                "ServerPlayerDamageMixin did not apply to its target (see Mixin warnings in the "
                    + "log above) — PvP damage telemetry cannot be trusted");
            org.slf4j.LoggerFactory.getLogger("Solidus Enforcer").error(
                "Solidus Enforcer: damage mixin did not apply even though the compatibility probe "
                    + "passed. Damage telemetry (alliance splits) is DISABLED for this session; "
                    + "bounty claims, storage and admin views keep working. Check for a conflicting "
                    + "mod transformer on ServerPlayer.");
        }
        if (EnforcerCompatState.isKillPipelineAvailable()
            && !EnforcerMixinPlugin.isDeathMixinApplied()) {
            EnforcerCompatState.downgrade(Surface.KILL_PIPELINE,
                "ServerPlayerDeathMixin did not apply to its target (see Mixin warnings in the "
                    + "log above) — bounty claims cannot settle");
            org.slf4j.LoggerFactory.getLogger("Solidus Enforcer").error(
                "Solidus Enforcer: death mixin did not apply even though the compatibility probe "
                    + "passed. The bounty claim pipeline is DISABLED for this session (placements are "
                    + "refused; existing bounties stay in escrow); storage, licenses and admin views "
                    + "keep working. Check for a conflicting mod transformer on ServerPlayer.");
        }

        // Healthy confirmation: one line per boot, the Enforcer twin of
        // Core's confirmClickMixinAfterFirstConnection evidence.
        if (EnforcerCompatState.isDamageTelemetryAvailable()
            && EnforcerCompatState.isKillPipelineAvailable()
            && EnforcerMixinPlugin.isDamageMixinApplied()
            && EnforcerMixinPlugin.isDeathMixinApplied()
            && CONFIRMED_LOGGED.compareAndSet(false, true)) {
            org.slf4j.LoggerFactory.getLogger("Solidus Enforcer").info(
                "Solidus Enforcer: both mixin surfaces confirmed LIVE after the first player "
                    + "connection (hurtServer + die applied) — damage telemetry, bounty claims and "
                    + "kill splits are fully operational.");
        }
    }
}
