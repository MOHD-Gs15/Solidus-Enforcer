package com.solidus.enforcer.mixin;

import com.solidus.enforcer.SolidusEnforcerMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entry point of the bounty claim pipeline.
 *
 * CRITICAL: the victim's inventory value is captured HERE, synchronously at
 * death time. The payout pipeline is async — by the time it evaluates the
 * value-drop check, the victim may already have respawned with a fresh
 * inventory, which used to distort payouts (players could die naked, respawn,
 * and claim "rich" payouts).
 *
 * <p>UPDATE RESILIENCE (WS-C C-2ج, Core's audit W-3 pattern transplanted):
 * this injector declares {@code require = 0}. When a future Minecraft
 * version reshapes {@code die}, the injection logs a WARNING and is
 * skipped — the server still boots. The probes + the
 * {@code EnforcerCompat.killPipelineAvailable()} gate at the top of this
 * callback + the first-JOIN cross-check degrade the bounty/death-pool path
 * loudly (placements refused, claims suspended, storage and admin views
 * untouched) instead of crashing startup — the exact failure mode that
 * made every un-updated server fall over on MC updates.</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDeathMixin {

    @Inject(method = {"die"}, at = {@At(value = "HEAD")}, require = 0)
    private void onPlayerDeath(DamageSource source, CallbackInfo ci) {
        // Fail-closed gate (C-2ج): a degraded kill pipeline must not settle
        // claims with half-transformed death data.
        if (!com.solidus.enforcer.compat.EnforcerCompat.killPipelineAvailable()) {
            return;
        }
        if (!(source.getEntity() instanceof ServerPlayer killer)) {
            return;
        }
        ServerPlayer victim = (ServerPlayer) (Object) this;
        if (killer.getUUID().equals(victim.getUUID())) {
            return;
        }
        SolidusEnforcerMod mod = SolidusEnforcerMod.getInstance();
        if (mod == null || !mod.isFullyActive()) {
            return;
        }
        // Death-time snapshot — must stay synchronous with this call.
        double inventoryValue = com.solidus.enforcer.economy.ValueCalculator
                .calculateInventoryValue(victim);
        mod.getKillProcessor().processKill(victim, killer, inventoryValue,
                victim.level().getServer());
    }
}
