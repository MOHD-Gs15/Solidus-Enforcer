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
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDamageMixin {

    @Inject(method = {"hurtServer"}, at = {@At(value = "RETURN")})
    private void onPlayerHurt(ServerLevel level, DamageSource source, float amount,
                               CallbackInfoReturnable<Boolean> cir) {
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
