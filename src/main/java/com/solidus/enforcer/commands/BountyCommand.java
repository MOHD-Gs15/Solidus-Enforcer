package com.solidus.enforcer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.solidus.enforcer.SolidusEnforcerMod;
import com.solidus.enforcer.bounty.BountyEntry;
import com.solidus.enforcer.bounty.BountyManager;
import com.solidus.enforcer.license.HunterLicenseManager;
import com.solidus.enforcer.util.TextUtil;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * /bounty place|list|info|top
 *
 * UX repairs over the old build: the minimum amount comes from config (not a
 * hardcoded Brigadier literal), /bounty info resolves offline targets by
 * bounty records instead of requiring a live player, and pagination only
 * advertises pages that exist.
 */
public final class BountyCommand {
    private static final int PER_PAGE = 8;

    private BountyCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, SolidusEnforcerMod mod) {
        var bounty = Commands.literal("bounty").requires(src -> src.getEntity() instanceof ServerPlayer);

        bounty.then(Commands.literal("place")
                .then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                .executes(ctx -> executePlace(ctx, mod)))));

        bounty.then(Commands.literal("list")
                .executes(ctx -> executeList(ctx, mod, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> executeList(ctx, mod, IntegerArgumentType.getInteger(ctx, "page")))));

        bounty.then(Commands.literal("info")
                .then(Commands.argument("target_name", StringArgumentType.word())
                        .executes(ctx -> executeInfo(ctx, mod))));

        bounty.then(Commands.literal("top").executes(ctx -> executeTop(ctx, mod)));

        dispatcher.register(bounty);
    }

    private static int executePlace(CommandContext<CommandSourceStack> ctx, SolidusEnforcerMod mod) throws Exception {
        ServerPlayer placer = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        double amount = DoubleArgumentType.getDouble(ctx, "amount");

        BountyManager bountyManager = mod.getBountyManager();
        HunterLicenseManager licenseManager = mod.getLicenseManager();
        if (bountyManager == null || licenseManager == null) {
            ctx.getSource().sendFailure(TextUtil.branded("Enforcer is not ready yet.", TextUtil.COLOR_BAD));
            return 0;
        }

        licenseManager.hasActiveLicense(placer.getUUID()).thenAccept(licensed -> {
            if (!licensed) {
                ctx.getSource().sendFailure(() -> TextUtil.branded(
                        "You need a Hunter License to place bounties — /hunter tiers", TextUtil.COLOR_BAD));
                return;
            }
            bountyManager.placeBounty(placer, target, amount).thenAccept(result -> {
                ServerPlayer still = ctx.getSource().getServer().getPlayerList().getPlayer(placer.getUUID());
                if (still == null) {
                    return;
                }
                if (result.success()) {
                    still.sendSystemMessage(TextUtil.branded(result.message(), TextUtil.COLOR_GOOD));
                    mod.getAnnouncer().announceNewBounty(result.bounty(), still.level().getServer());
                } else {
                    still.sendSystemMessage(TextUtil.branded(result.message(), TextUtil.COLOR_BAD));
                }
            });
        });
        return 1;
    }

    private static int executeList(CommandContext<CommandSourceStack> ctx, SolidusEnforcerMod mod, int requestedPage) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }
        BountyManager bountyManager = mod.getBountyManager();
        HunterLicenseManager licenseManager = mod.getLicenseManager();
        if (bountyManager == null || licenseManager == null) {
            ctx.getSource().sendFailure(TextUtil.branded("Enforcer is not ready yet.", TextUtil.COLOR_BAD));
            return 0;
        }
        final UUID viewerUuid = player.getUUID();
        final MinecraftServerAlias server = new MinecraftServerAlias(ctx.getSource().getServer());
        licenseManager.hasActiveLicense(viewerUuid).thenAccept(licensed -> {
            if (!licensed) {
                server.execute(viewerUuid, () -> player.sendSystemMessage(TextUtil.branded(
                        "You need a Hunter License to view bounties — /hunter tiers", TextUtil.COLOR_BAD)));
                return;
            }
            bountyManager.getActiveBounties().thenAccept(bounties -> server.execute(viewerUuid, () -> {
                int totalPages = Math.max(1, (int) Math.ceil((double) bounties.size() / PER_PAGE));
                int currentPage = Math.min(requestedPage, totalPages);
                int start = (currentPage - 1) * PER_PAGE;
                int end = Math.min(start + PER_PAGE, bounties.size());

                MutableComponent msg = TextUtil.bountyIcon()
                        .append(Component.literal("Active Bounties").withColor(TextUtil.COLOR_HEADER))
                        .append(Component.literal("  (page " + currentPage + "/" + totalPages + ")")
                                .withColor(TextUtil.COLOR_MUTED))
                        .append(Component.literal("\n")).append(TextUtil.separator());
                if (bounties.isEmpty()) {
                    msg = msg.append(Component.literal("\n  No active bounties. Place one: /bounty place <player> <amount>")
                            .withColor(TextUtil.COLOR_MUTED));
                }
                for (int i = start; i < end; i++) {
                    BountyEntry b = bounties.get(i);
                    msg = msg.append(Component.literal("\n #" + b.id() + "  ").withColor(TextUtil.COLOR_MUTED))
                            .append(TextUtil.target(b.targetName()))
                            .append(Component.literal("  ").withColor(TextUtil.COLOR_MUTED))
                            .append(TextUtil.currency(b.totalAmount()))
                            .append(Component.literal("  expires in " + TextUtil.formatDuration(b.remainingTime()))
                                    .withColor(TextUtil.COLOR_MUTED));
                    if (b.autonomous()) {
                        msg = msg.append(Component.literal("  [AUTO]").withColor(TextUtil.COLOR_WARN));
                    }
                }
                msg = msg.append(TextUtil.pageFooter(currentPage, totalPages));
                player.sendSystemMessage(msg);
            }));
        });
        return 1;
    }

    /** Resolves by NAME against live bounty records — works for offline targets. */
    private static int executeInfo(CommandContext<CommandSourceStack> ctx, SolidusEnforcerMod mod) {
        ServerPlayer viewer;
        try {
            viewer = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }
        String targetName = StringArgumentType.getString(ctx, "target_name");
        BountyManager bountyManager = mod.getBountyManager();
        HunterLicenseManager licenseManager = mod.getLicenseManager();
        if (bountyManager == null || licenseManager == null) {
            ctx.getSource().sendFailure(TextUtil.branded("Enforcer is not ready yet.", TextUtil.COLOR_BAD));
            return 0;
        }
        final UUID viewerUuid = viewer.getUUID();
        licenseManager.hasActiveLicense(viewerUuid).thenAccept(licensed -> {
            if (!licensed) {
                ctx.getSource().getServer().execute(() -> viewer.sendSystemMessage(
                        TextUtil.branded("You need a Hunter License — /hunter tiers", TextUtil.COLOR_BAD)));
                return;
            }
            bountyManager.getActiveBounties().thenAccept(bounties -> ctx.getSource().getServer().execute(() -> {
                List<BountyEntry> matches = bounties.stream()
                        .filter(b -> b.targetName().equalsIgnoreCase(targetName))
                        .toList();
                if (matches.isEmpty()) {
                    viewer.sendSystemMessage(TextUtil.branded(
                            "No active bounties on \"" + targetName + "\".", TextUtil.COLOR_INFO));
                    return;
                }
                double total = matches.stream().mapToDouble(BountyEntry::totalAmount).sum();
                MutableComponent msg = TextUtil.skullIcon()
                        .append(Component.literal("Bounty Intel: ").withColor(TextUtil.COLOR_HEADER))
                        .append(TextUtil.target(targetName))
                        .append(Component.literal("\n")).append(TextUtil.thinSeparator())
                        .append(Component.literal("\n  Total price on their head: ").withColor(TextUtil.COLOR_INFO))
                        .append(TextUtil.currency(total))
                        .append(Component.literal("\n  Active contracts: ").withColor(TextUtil.COLOR_INFO))
                        .append(Component.literal(String.valueOf(matches.size())).withColor(TextUtil.COLOR_BRAND));
                for (BountyEntry b : matches) {
                    msg = msg.append(Component.literal("\n    #" + b.id() + "  ").withColor(TextUtil.COLOR_MUTED))
                            .append(TextUtil.currency(b.totalAmount()))
                            .append(Component.literal("  by ").withColor(TextUtil.COLOR_MUTED))
                            .append(TextUtil.player(b.placedByName()))
                            .append(Component.literal("  expires in " + TextUtil.formatDuration(b.remainingTime()))
                                    .withColor(TextUtil.COLOR_MUTED));
                    if (b.autonomous()) {
                        msg = msg.append(Component.literal("\n      [" + b.autonomousReason() + "]")
                                .withColor(TextUtil.COLOR_WARN));
                    }
                }
                msg = msg.append(Component.literal("\n")).append(TextUtil.thinSeparator());
                viewer.sendSystemMessage(msg);
            }));
        });
        return 1;
    }

    private static int executeTop(CommandContext<CommandSourceStack> ctx, SolidusEnforcerMod mod) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }
        BountyManager bountyManager = mod.getBountyManager();
        if (bountyManager == null) {
            ctx.getSource().sendFailure(TextUtil.branded("Enforcer is not ready yet.", TextUtil.COLOR_BAD));
            return 0;
        }
        bountyManager.getActiveBounties().thenAccept(bounties -> ctx.getSource().getServer().execute(() -> {
            LinkedHashMap<String, Double> mostWanted = new LinkedHashMap<>();
            for (BountyEntry b : bounties) {
                mostWanted.merge(b.targetName(), b.totalAmount(), Double::sum);
            }
            List<Map.Entry<String, Double>> top = mostWanted.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(10)
                    .toList();
            MutableComponent msg = TextUtil.bountyIcon()
                    .append(Component.literal("Most Wanted").withColor(TextUtil.COLOR_HEADER))
                    .append(Component.literal("\n")).append(TextUtil.separator());
            if (top.isEmpty()) {
                msg = msg.append(Component.literal("\n  Nobody is hunted right now.").withColor(TextUtil.COLOR_MUTED));
            }
            int rank = 1;
            for (Map.Entry<String, Double> entry : top) {
                msg = msg.append(Component.literal("\n  " + rank + ". ").withColor(TextUtil.COLOR_MUTED))
                        .append(TextUtil.target(entry.getKey()))
                        .append(Component.literal("  ").withColor(TextUtil.COLOR_MUTED))
                        .append(TextUtil.currency(entry.getValue()));
                rank++;
            }
            msg = msg.append(Component.literal("\n")).append(TextUtil.separator());
            player.sendSystemMessage(msg);
        }));
        return 1;
    }

    /** Tiny helper so callbacks can hop to the tick thread by player uuid. */
    private record MinecraftServerAlias(net.minecraft.server.MinecraftServer server) {
        void execute(UUID ignored, Runnable task) {
            this.server.execute(task);
        }
    }
}
