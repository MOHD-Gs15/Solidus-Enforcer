package com.solidus.enforcer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.solidus.enforcer.SolidusEnforcerMod;
import com.solidus.enforcer.bounty.BountyEntry;
import com.solidus.enforcer.util.TextUtil;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.permissions.Permissions;

/**
 * /enforcer treasury|ledger|bounties|cancel <id>|stats|reload
 *
 * The old build implemented adminCancelBounty() but never exposed it — admins
 * had no way to manage bounties at all. Everything is console/admin-safe.
 */
public final class EnforcerAdminCommand {
    private EnforcerAdminCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, SolidusEnforcerMod mod) {
        var enforcer = Commands.literal("enforcer")
                .requires(source -> source.getEntity() == null
                        || source.permissions().hasPermission(Permissions.COMMANDS_ADMIN));

        enforcer.then(Commands.literal("treasury").executes(ctx -> executeTreasury(ctx, mod)));
        enforcer.then(Commands.literal("ledger")
                .executes(ctx -> executeLedger(ctx, mod, 10))
                .then(Commands.argument("lines", IntegerArgumentType.integer(1, 50))
                        .executes(ctx -> executeLedger(ctx, mod, IntegerArgumentType.getInteger(ctx, "lines")))));
        enforcer.then(Commands.literal("bounties").executes(ctx -> executeBounties(ctx, mod)));
        enforcer.then(Commands.literal("cancel")
                .then(Commands.argument("bounty_id", IntegerArgumentType.integer(1))
                        .executes(ctx -> executeCancel(ctx, mod))));
        enforcer.then(Commands.literal("stats").executes(ctx -> executeStats(ctx, mod)));
        enforcer.then(Commands.literal("reload").executes(ctx -> executeReload(ctx, mod)));

        dispatcher.register(enforcer);
    }

    private static int executeTreasury(CommandContext<CommandSourceStack> ctx, SolidusEnforcerMod mod) {
        var treasury = mod.getTreasuryManager();
        if (mod.notReady(ctx) || treasury == null) {
            return 0;
        }
        ctx.getSource().sendSuccess(treasury::getTreasuryReport, false);
        return 1;
    }

    private static int executeLedger(CommandContext<CommandSourceStack> ctx, SolidusEnforcerMod mod, int lines) {
        if (mod.notReady(ctx)) {
            return 0;
        }
        mod.getStorage().readTreasuryLedger(lines).thenAccept(entries ->
                ctx.getSource().getServer().execute(() -> {
                    MutableComponent msg = TextUtil.prefix()
                            .append(Component.literal("Treasury Ledger (last " + entries.size() + ")")
                                    .withColor(TextUtil.COLOR_HEADER));
                    if (entries.isEmpty()) {
                        msg.append(Component.literal("\n  (no movements recorded yet)")
                                .withColor(TextUtil.COLOR_MUTED));
                    }
                    for (String entry : entries) {
                        msg.append(Component.literal("\n  " + entry).withColor(TextUtil.COLOR_INFO));
                    }
                    ctx.getSource().sendSuccess(() -> msg, false);
                }));
        return 1;
    }

    private static int executeBounties(CommandContext<CommandSourceStack> ctx, SolidusEnforcerMod mod) {
        if (mod.notReady(ctx)) {
            return 0;
        }
        mod.getBountyManager().getActiveBounties().thenAccept(bounties ->
                ctx.getSource().getServer().execute(() -> {
                    MutableComponent msg = TextUtil.prefix()
                            .append(Component.literal("Active Bounties (" + bounties.size() + ")")
                                    .withColor(TextUtil.COLOR_HEADER));
                    for (BountyEntry b : bounties) {
                        msg.append(Component.literal("\n  #" + b.id() + "  ").withColor(TextUtil.COLOR_MUTED))
                                .append(TextUtil.target(b.targetName()))
                                .append(Component.literal("  ").withColor(TextUtil.COLOR_MUTED))
                                .append(TextUtil.currency(b.totalAmount()))
                                .append(Component.literal("  by " + b.placedByName()
                                        + (b.autonomous() ? "  [AUTO]" : ""))
                                        .withColor(TextUtil.COLOR_INFO));
                    }
                    if (bounties.isEmpty()) {
                        msg.append(Component.literal("\n  (none)").withColor(TextUtil.COLOR_MUTED));
                    }
                    msg.append(Component.literal("\n  Cancel with /enforcer cancel <id>")
                            .withColor(TextUtil.COLOR_MUTED));
                    ctx.getSource().sendSuccess(() -> msg, false);
                }));
        return 1;
    }

    private static int executeCancel(CommandContext<CommandSourceStack> ctx, SolidusEnforcerMod mod) {
        if (mod.notReady(ctx)) {
            return 0;
        }
        int bountyId = IntegerArgumentType.getInteger(ctx, "bounty_id");
        String admin = ctx.getSource().getTextName();
        mod.getBountyManager().adminCancelBounty(bountyId, admin).thenAccept(result ->
                ctx.getSource().getServer().execute(() -> {
                    if (result.success()) {
                        ctx.getSource().sendSuccess(() -> TextUtil.branded(result.message(), TextUtil.COLOR_GOOD), true);
                    } else {
                        ctx.getSource().sendFailure(TextUtil.branded(result.message(), TextUtil.COLOR_BAD));
                    }
                }));
        return 1;
    }

    private static int executeStats(CommandContext<CommandSourceStack> ctx, SolidusEnforcerMod mod) {
        if (mod.notReady(ctx)) {
            return 0;
        }
        mod.getStorage().getTopKillers(10).thenAccept(killers ->
                ctx.getSource().getServer().execute(() -> {
                    MutableComponent msg = TextUtil.prefix()
                            .append(Component.literal("Top Hunters (by streak)").withColor(TextUtil.COLOR_HEADER));
                    if (killers.isEmpty()) {
                        msg.append(Component.literal("\n  (no kills recorded yet)")
                                .withColor(TextUtil.COLOR_MUTED));
                    }
                    int rank = 1;
                    for (var k : killers) {
                        msg.append(Component.literal("\n  " + rank + ". ").withColor(TextUtil.COLOR_MUTED))
                                .append(TextUtil.player(k.name()))
                                .append(Component.literal(String.format(
                                        "  — %d kills, %d deaths, streak %d",
                                        k.kills(), k.deaths(), k.currentStreak()))
                                        .withColor(TextUtil.COLOR_INFO));
                        rank++;
                    }
                    ctx.getSource().sendSuccess(() -> msg, false);
                }));
        return 1;
    }

    private static int executeReload(CommandContext<CommandSourceStack> ctx, SolidusEnforcerMod mod) {
        if (mod.getConfigManager() == null) {
            ctx.getSource().sendFailure(TextUtil.branded("Enforcer is not active.", TextUtil.COLOR_BAD));
            return 0;
        }
        mod.getConfigManager().reload();
        ctx.getSource().sendSuccess(() -> TextUtil.branded(
                "Solidus Enforcer configuration reloaded.", TextUtil.COLOR_GOOD), true);
        return 1;
    }
}
