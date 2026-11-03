package com.solidus.enforcer.compat;

import com.solidus.enforcer.compat.EnforcerCompatState.Surface;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EnforcerCompatStateTest — the write-once / downgrade / per-surface
 * isolation semantics of the C-2ج state machine. Pure JVM: the state class
 * is deliberately Minecraft-free, so these tests run in any environment.
 *
 * @since 2.4.0
 */
@DisplayName("Enforcer compat state (C-2ج): per-surface write-once reports and sticky downgrades")
class EnforcerCompatStateTest {

    @AfterEach
    void reset() {
        EnforcerCompatState.resetForTest();
    }

    @Test
    @DisplayName("before the probe runs nothing is available, but the summaries stay readable")
    void unverifiedStateIsClosedAndReadable() {
        assertFalse(EnforcerCompatState.isVerified());
        assertFalse(EnforcerCompatState.isDamageTelemetryAvailable());
        assertFalse(EnforcerCompatState.isKillPipelineAvailable());
        assertTrue(EnforcerCompatState.summary(Surface.DAMAGE_TELEMETRY).contains("probe has not run yet"));
    }

    @Test
    @DisplayName("a green report opens both surfaces; the report is write-once")
    void greenReportOpensBothSurfaces() {
        EnforcerCompatState.report(Surface.DAMAGE_TELEMETRY, List.of());
        EnforcerCompatState.report(Surface.KILL_PIPELINE, List.of());
        assertTrue(EnforcerCompatState.isVerified());
        assertTrue(EnforcerCompatState.isDamageTelemetryAvailable());
        assertTrue(EnforcerCompatState.isKillPipelineAvailable());
        assertTrue(EnforcerCompatState.summary(Surface.KILL_PIPELINE).endsWith(": OK"));

        // Write-once: a later (bogus) report must not overwrite the first green one.
        EnforcerCompatState.report(Surface.KILL_PIPELINE, List.of("late probe failure"));
        assertTrue(EnforcerCompatState.isKillPipelineAvailable());
    }

    @Test
    @DisplayName("one broken surface never poisons the other")
    void surfacesAreIsolated() {
        EnforcerCompatState.report(Surface.DAMAGE_TELEMETRY,
            List.of("ServerPlayer#hurtServer(...) -> NoSuchMethodException: gone"));
        EnforcerCompatState.report(Surface.KILL_PIPELINE, List.of());

        assertFalse(EnforcerCompatState.isDamageTelemetryAvailable());
        assertTrue(EnforcerCompatState.isKillPipelineAvailable(),
            "a broken hurtServer surface must NOT take down bounty claims");

        EnforcerCompatState.resetForTest();
        EnforcerCompatState.report(Surface.DAMAGE_TELEMETRY, List.of());
        EnforcerCompatState.report(Surface.KILL_PIPELINE,
            List.of("ServerPlayer#die(DamageSource) -> NoSuchMethodException: gone"));
        assertTrue(EnforcerCompatState.isDamageTelemetryAvailable(),
            "a broken die surface must NOT take down damage telemetry");
        assertFalse(EnforcerCompatState.isKillPipelineAvailable());
    }

    @Test
    @DisplayName("a downgrade is sticky and idempotent (the first-JOIN cross-check contract)")
    void downgradeIsStickyAndDeduplicated() {
        EnforcerCompatState.report(Surface.KILL_PIPELINE, List.of());
        assertTrue(EnforcerCompatState.isKillPipelineAvailable());

        String reason = "ServerPlayerDeathMixin did not apply to its target";
        EnforcerCompatState.downgrade(Surface.KILL_PIPELINE, reason);
        EnforcerCompatState.downgrade(Surface.KILL_PIPELINE, reason); // JOIN fires more than once
        assertFalse(EnforcerCompatState.isKillPipelineAvailable());
        assertEquals(1, EnforcerCompatState.killFailures().size(),
            "the same downgrade reason must not be appended twice");
        assertTrue(EnforcerCompatState.summary(Surface.KILL_PIPELINE).contains(reason));

        // A downgraded surface stays downgraded — no flapping recovery.
        EnforcerCompatState.report(Surface.KILL_PIPELINE, List.of());
        assertFalse(EnforcerCompatState.isKillPipelineAvailable());
    }
}
