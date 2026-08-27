package com.solidus.enforcer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.solidus.enforcer.SolidusEnforcerMod;
import com.solidus.enforcer.economy.TreasuryManager;
import com.solidus.enforcer.util.TextUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;

public final class EnforcerAdminCommand {
    private EnforcerAdminCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, SolidusEnforcerMod mod) {
        dispatcher.register(Commands.literal("enforcer")
            .requires(source -> source.getEntity() == null || source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
            .then(Commands.literal("reload").executes(context -> {
                if (mod.getConfigManager() == null) {
                    context.getSource().sendFailure(TextUtil.branded("Enforcer is not active.", 0xFF5555));
                    return 0;
                }
                mod.getConfigManager().reload();
                context.getSource().sendSuccess(() -> TextUtil.branded("Solidus Enforcer configuration reloaded.", 0x55FF55), true);
                return 1;
            }))
            .then(Commands.literal("treasury").executes(context -> {
                TreasuryManager treasury = mod.getTreasuryManager();
                if (treasury == null) {
                    context.getSource().sendFailure(TextUtil.branded("Enforcer is not active.", 0xFF5555));
                    return 0;
                }
                context.getSource().sendSuccess(treasury::getTreasuryReport, false);
                return 1;
            })));
    }
}
