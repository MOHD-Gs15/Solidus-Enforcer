/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraft.world.damagesource.DamageSource
 *  net.minecraft.world.entity.Entity
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
 */
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

@Mixin(value={ServerPlayer.class})
public abstract class ServerPlayerDamageMixin {
    @Inject(method={"hurtServer"}, at={@At(value="RETURN")})
    private void onPlayerHurt(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        Entity entity = source.getEntity();
        if (entity instanceof ServerPlayer) {
            DamageTracker tracker;
            SolidusEnforcerMod mod;
            ServerPlayer attacker = (ServerPlayer)entity;
            ServerPlayer victim = (ServerPlayer)(Object)this;
            if (attacker.getUUID().equals(victim.getUUID())) {
                return;
            }
            if (Boolean.TRUE.equals(cir.getReturnValue()) && (mod = SolidusEnforcerMod.getInstance()) != null && (tracker = mod.getDamageTracker()) != null) {
                tracker.recordDamage(victim.getUUID(), attacker.getUUID(), amount);
            }
        }
    }
}
