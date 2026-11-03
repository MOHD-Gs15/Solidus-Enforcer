package com.solidus.enforcer.mixin;

import com.solidus.enforcer.SolidusEnforcerMod;
import com.solidus.enforcer.combat.DamageTracker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records successful PvP damage for the alliance split. Self-damage and
 * non-player sources are filtered here so the pipeline only sees real PvP.
 *
 * <p>UPDATE RESILIENCE (WS-C C-2ج, Core's audit W-3 pattern transplanted):
 * this injector declares {@code require = 0}. When a future Minecraft
 * version reshapes {@code hurtServer} (a 26.x line already moved
 * {@code Player#drop} behind {@code Prediction}), the injection logs a
 * WARNING and is skipped — the server still boots. The safety net is
 * layered: {@code com.solidus.enforcer.compat.EnforcerCompatProbes}
 * reflectively verifies the exact shape at startup, the
 * {@code EnforcerCompat.damageTelemetryAvailable()} gate at the top of
 * this callback fails closed, and the first JOIN cross-check catches a
 * silently-missing apply. A degraded damage surface flattens alliance
 * splits to killer-takes-all; it never crashes the server.</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDamageMixin {

    @Inject(method = {"hurtServer"}, at = {@At(value = "RETURN")}, require = 0)
    private void onPlayerHurt(ServerLevel level, DamageSource source, float amount,
                               CallbackInfoReturnable<Boolean> cir) {
        // Fail-closed gate (C-2ج): a surface that failed verification must
        // not feed half-transformed data into the alliance-split pipeline.
        if (!com.solidus.enforcer.compat.EnforcerCompat.damageTelemetryAvailable()) {
            return;
        }
        if (!Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        Entity sourceEntity = source.getEntity();
        if (!(sourceEntity instanceof ServerPlayer attacker)) {
            return;
        }
        ServerPlayer victim = (ServerPlayer) (Object) this;
        if (attacker.getUUID().equals(victim.getUUID())) {
            return;
        }
        SolidusEnforcerMod mod = SolidusEnforcerMod.getInstance();
        if (mod == null) {
            return;
        }
        DamageTracker tracker = mod.getDamageTracker();
        if (tracker != null) {
            tracker.recordDamage(victim.getUUID(), attacker.getUUID(),
                    attacker.getName().getString(), amount);
        }
    }
}
