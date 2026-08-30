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
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDeathMixin {

    @Inject(method = {"die"}, at = {@At(value = "HEAD")})
    private void onPlayerDeath(DamageSource source, CallbackInfo ci) {
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
