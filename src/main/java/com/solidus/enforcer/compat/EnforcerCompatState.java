package com.solidus.enforcer.compat;

import java.util.ArrayList;
import java.util.List;

/**
 * EnforcerCompatState — single source of truth for the Minecraft-version
 * compatibility of Enforcer's two mixin-backed pipelines.
 *
 * <p><b>Why this exists (WS-C C-2ج):</b> Enforcer hooks exactly two
 * high-churn Minecraft internals — {@code ServerPlayer#hurtServer} (damage
 * telemetry for alliance splits) and {@code ServerPlayer#die} (the bounty
 * claim pipeline). With the old {@code defaultRequire=1} mixin config, any
 * of those changing shape meant a <b>hard crash at server startup</b> for
 * every server owner who updated Minecraft before Enforcer shipped an
 * update — the exact failure mode Core fixed in 2.3.0 (audit W-3). This
 * class is the Enforcer transplant of that pattern: probes report
 * per-surface, the affected pipeline degrades loudly, everything else keeps
 * running.</p>
 *
 * <p><b>Per-surface, deliberately:</b> unlike Core's single GUI surface,
 * Enforcer has two independent pipelines. A broken {@code hurtServer} must
 * NOT take down bounty claims (claims only need the {@code die} hook); a
 * broken {@code die} must NOT stop damage telemetry from buffering
 * harmlessly. One surface going down never poisons the other.</p>
 *
 * <p>This class is deliberately dependency-free (no Minecraft imports) so
 * the state is readable from anywhere — the admin command, the mixin
 * callbacks, and the unit tests — without initializing any Minecraft
 * class.</p>
 *
 * @see EnforcerCompatProbes for the startup probes that produce this state
 * @see EnforcerCompat for the facade the rest of the mod talks to
 * @since 2.4.0
 */
public final class EnforcerCompatState {

    /**
     * The two mixin-backed surfaces. Each names a pipeline whose only
     * Minecraft touch-point is the corresponding injector; the flags tell
     * the rest of the mod whether that pipeline is safe to use on the
     * RUNNING Minecraft version.
     */
    public enum Surface {
        /** {@code ServerPlayer#hurtServer(ServerLevel, DamageSource, float)} —
         *  the ServerPlayerDamageMixin target. Feeds PvP damage telemetry
         *  (alliance splits, anti-exploit heuristics). */
        DAMAGE_TELEMETRY("ServerPlayer#hurtServer damage surface"),

        /** {@code ServerPlayer#die(DamageSource)} — the ServerPlayerDeathMixin
         *  target. Feeds the bounty claim pipeline (death-time valuation,
         *  settlements, payouts) — "the bounty/death-pool path". */
        KILL_PIPELINE("ServerPlayer#die kill pipeline");

        private final String description;

        Surface(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    private static volatile boolean verified = false;

    /** Per-surface probe failures. Write-once via the protocol below. */
    private static volatile List<String> damageFailures = List.of();
    private static volatile List<String> killFailures = List.of();

    /**
     * Per-surface write-once guard. The list emptiness CANNOT carry the
     * "not reported yet" signal — an empty list after the first report means
     * GREEN. A green first report must therefore be sticky against later
     * (bogus) reports, exactly like a red one — hence explicit flags.
     */
    private static volatile boolean damageReported = false;
    private static volatile boolean killReported = false;

    private EnforcerCompatState() {
    }

    // -- Reports (single call site: EnforcerCompatProbes) --------------------

    /**
     * Writes one surface's probe report. Called exactly once per surface
     * from {@link EnforcerCompatProbes#verifyCompatibility()} at mod
     * initialization, before any player can connect. Later calls are
     * ignored — the report is sticky for the lifetime of the process,
     * matching the mixin system's own apply-once behavior.
     *
     * @param surface       the pipeline the report describes
     * @param probeFailures human-readable list of failed probes (empty = green)
     */
    static void report(Surface surface, List<String> probeFailures) {
        synchronized (EnforcerCompatState.class) {
            switch (surface) {
                case DAMAGE_TELEMETRY -> {
                    if (!damageReported) {
                        damageFailures = List.copyOf(probeFailures);
                        damageReported = true;
                    }
                }
                case KILL_PIPELINE -> {
                    if (!killReported) {
                        killFailures = List.copyOf(probeFailures);
                        killReported = true;
                    }
                }
            }
            verified = true;
        }
    }

    // -- Runtime downgrade ----------------------------------------------------

    /**
     * Appends a post-init failure for one surface (e.g. the first-connection
     * cross-check found the mixin never applied although the probe passed).
     * Once a surface is downgraded it stays downgraded for the lifetime of
     * the process — a broken pipeline must not flappingly recover while a
     * bounty settlement is mid-flight.
     *
     * @param surface the pipeline that is going down
     * @param reason  human-readable, action-oriented cause
     */
    static void downgrade(Surface surface, String reason) {
        synchronized (EnforcerCompatState.class) {
            switch (surface) {
                case DAMAGE_TELEMETRY -> {
                    if (!damageFailures.contains(reason)) {
                        List<String> updated = new ArrayList<>(damageFailures);
                        updated.add(reason);
                        damageFailures = List.copyOf(updated);
                    }
                }
                case KILL_PIPELINE -> {
                    if (!killFailures.contains(reason)) {
                        List<String> updated = new ArrayList<>(killFailures);
                        updated.add(reason);
                        killFailures = List.copyOf(updated);
                    }
                }
            }
            verified = true;
        }
    }

    // -- Queries ---------------------------------------------------------------

    /**
     * @return true when the startup probe ran (and thus the state is meaningful)
     */
    public static boolean isVerified() {
        return verified;
    }

    /**
     * @return true when the hurtServer shape verified clean — PvP damage
     *         telemetry may record, and alliance splits may be computed
     */
    public static boolean isDamageTelemetryAvailable() {
        return verified && damageFailures.isEmpty();
    }

    /**
     * @return true when the die shape verified clean — bounty claims may
     *         settle and new bounty placements may be accepted
     */
    public static boolean isKillPipelineAvailable() {
        return verified && killFailures.isEmpty();
    }

    /** @return immutable list of failed damage-surface probes (empty when healthy) */
    public static List<String> damageFailures() {
        return damageFailures;
    }

    /** @return immutable list of failed kill-surface probes (empty when healthy) */
    public static List<String> killFailures() {
        return killFailures;
    }

    /**
     * One-line summary for one surface, for logs and the /enforcer compat
     * command.
     *
     * @param surface the pipeline to summarize
     * @return a human-readable status line
     */
    public static String summary(Surface surface) {
        List<String> failures =
            surface == Surface.DAMAGE_TELEMETRY ? damageFailures : killFailures;
        if (!verified) {
            return surface.description() + ": probe has not run yet";
        }
        if (failures.isEmpty()) {
            return surface.description() + ": OK";
        }
        return surface.description() + ": FAILED — " + String.join("; ", failures);
    }

    /** Test hook: restores the pre-probe state. Production code never calls this. */
    static synchronized void resetForTest() {
        verified = false;
        damageFailures = List.of();
        killFailures = List.of();
        damageReported = false;
        killReported = false;
    }
}
