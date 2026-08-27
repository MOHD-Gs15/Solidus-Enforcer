/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraft.world.damagesource.DamageSource
 *  net.minecraft.world.entity.Entity
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfo
 */
package com.solidus.enforcer.mixin;

import com.solidus.enforcer.SolidusEnforcerMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={ServerPlayer.class})
public abstract class ServerPlayerEntityMixin {
    @Inject(method={"die"}, at={@At(value="HEAD")})
    private void onPlayerDeath(DamageSource source, CallbackInfo ci) {
        ServerPlayer victim = (ServerPlayer)(Object)this;
        Entity entity = source.getEntity();
        if (entity instanceof ServerPlayer) {
            ServerPlayer killer = (ServerPlayer)entity;
            SolidusEnforcerMod mod = SolidusEnforcerMod.getInstance();
            if (mod != null) {
                mod.getKillProcessor().processKill(victim, killer, victim.level().getServer());
            }
        }
    }
}
