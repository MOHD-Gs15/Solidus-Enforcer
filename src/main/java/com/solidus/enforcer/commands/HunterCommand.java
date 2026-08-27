package com.solidus.enforcer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.solidus.enforcer.SolidusEnforcerMod;
import com.solidus.enforcer.license.HunterLicenseManager;
import com.solidus.enforcer.license.LicenseData;
import com.solidus.enforcer.license.LicenseTier;
import com.solidus.enforcer.util.TextUtil;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class HunterCommand {
    private HunterCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, SolidusEnforcerMod mod) {
        dispatcher.register(Commands.literal("hunter")
            .requires(source -> source.getEntity() instanceof ServerPlayer)
            .then(Commands.literal("buy")
                .then(Commands.argument("tier", StringArgumentType.word())
                    .executes(context -> buy(context.getSource().getPlayerOrException(),
                        StringArgumentType.getString(context, "tier"), mod))))
            .then(Commands.literal("info")
                .executes(context -> info(context.getSource().getPlayerOrException(), mod))));
    }

    private static int buy(ServerPlayer player, String tierName, SolidusEnforcerMod mod) {
        LicenseTier tier = LicenseTier.fromString(tierName);
        HunterLicenseManager manager = mod.getLicenseManager();
        if (manager == null) {
            player.sendSystemMessage(TextUtil.branded("System not ready yet.", 0xFF5555));
            return 0;
        }
        manager.purchaseLicense(player, tier).thenAccept(result -> player.level().getServer().execute(() ->
            player.sendSystemMessage(TextUtil.branded(result.message(), result.success() ? 0x55FF55 : 0xFF5555))));
        return 1;
    }

    private static int info(ServerPlayer player, SolidusEnforcerMod mod) {
        HunterLicenseManager manager = mod.getLicenseManager();
        if (manager == null) {
            player.sendSystemMessage(TextUtil.branded("System not ready yet.", 0xFF5555));
            return 0;
        }
        manager.getLicense(player.getUUID()).thenAccept(optional -> player.level().getServer().execute(() -> sendInfo(player, optional)));
        return 1;
    }

    private static void sendInfo(ServerPlayer player, Optional<LicenseData> optional) {
        if (optional.isEmpty() || !optional.get().isValid()) {
            player.sendSystemMessage(TextUtil.branded("You do not have an active Hunter License.", 0xAAAAAA));
            return;
        }
        LicenseData license = optional.get();
        player.sendSystemMessage(Component.literal("Active Hunter License: " + license.tier().name()
            + " (expires " + license.expireTimestamp() + ")").withColor(0x55FF55));
    }
}
