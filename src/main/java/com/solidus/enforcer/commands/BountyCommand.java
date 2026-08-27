/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.mojang.brigadier.CommandDispatcher
 *  com.mojang.brigadier.arguments.ArgumentType
 *  com.mojang.brigadier.arguments.DoubleArgumentType
 *  com.mojang.brigadier.arguments.IntegerArgumentType
 *  com.mojang.brigadier.builder.LiteralArgumentBuilder
 *  com.mojang.brigadier.context.CommandContext
 *  net.minecraft.commands.CommandSourceStack
 *  net.minecraft.commands.Commands
 *  net.minecraft.commands.arguments.EntityArgument
 *  net.minecraft.network.chat.Component
 *  net.minecraft.network.chat.MutableComponent
 *  net.minecraft.server.level.ServerPlayer
 */
package com.solidus.enforcer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.solidus.enforcer.SolidusEnforcerMod;
import com.solidus.enforcer.bounty.BountyAnnouncer;
import com.solidus.enforcer.bounty.BountyEntry;
import com.solidus.enforcer.bounty.BountyManager;
import com.solidus.enforcer.license.HunterLicenseManager;
import com.solidus.enforcer.util.TextUtil;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

public final class BountyCommand {
    private BountyCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, SolidusEnforcerMod mod) {
        LiteralArgumentBuilder bounty = (LiteralArgumentBuilder)Commands.literal((String)"bounty").requires(src -> src.getEntity() instanceof ServerPlayer);
        bounty.then(Commands.literal((String)"place").then(Commands.argument((String)"target", (ArgumentType)EntityArgument.player()).then(Commands.argument((String)"amount", (ArgumentType)DoubleArgumentType.doubleArg((double)500.0)).executes(ctx -> {
            ServerPlayer placer = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
            ServerPlayer target = EntityArgument.getPlayer((CommandContext)ctx, (String)"target");
            double amount = DoubleArgumentType.getDouble((CommandContext)ctx, (String)"amount");
            BountyManager bountyManager = mod.getBountyManager();
            HunterLicenseManager licenseManager = mod.getLicenseManager();
            if (bountyManager == null || licenseManager == null) {
                placer.sendSystemMessage((Component)TextUtil.branded("System not ready yet.", 0xFF5555));
                return 0;
            }
            licenseManager.hasAnyLicense(placer.getUUID()).thenAccept(hasLicense -> {
                if (!hasLicense.booleanValue()) {
                    placer.sendSystemMessage((Component)TextUtil.branded("You need a Hunter License to place bounties! Use /hunter buy", 0xFF5555));
                    return;
                }
                bountyManager.placeBounty(placer, target, amount).thenAccept(result -> placer.level().getServer().execute(() -> {
                    if (result.success()) {
                        placer.sendSystemMessage((Component)TextUtil.branded(result.message(), 0x55FF55));
                        bountyManager.getBountiesForTarget(target.getUUID()).thenAccept(bounties -> {
                            if (!bounties.isEmpty()) {
                                BountyAnnouncer.announceNewBounty((BountyEntry)bounties.getLast(), placer.level().getServer());
                            }
                        });
                    } else {
                        placer.sendSystemMessage((Component)TextUtil.branded(result.message(), 0xFF5555));
                    }
                }));
            });
            return 1;
        }))));
        bounty.then(((LiteralArgumentBuilder)Commands.literal((String)"list").executes(ctx -> BountyCommand.executeList(((CommandSourceStack)ctx.getSource()).getPlayerOrException(), mod, 1))).then(Commands.argument((String)"page", (ArgumentType)IntegerArgumentType.integer((int)1)).executes(ctx -> BountyCommand.executeList(((CommandSourceStack)ctx.getSource()).getPlayerOrException(), mod, IntegerArgumentType.getInteger((CommandContext)ctx, (String)"page")))));
        bounty.then(Commands.literal((String)"info").then(Commands.argument((String)"target", (ArgumentType)EntityArgument.player()).executes(ctx -> {
            ServerPlayer viewer = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
            ServerPlayer target = EntityArgument.getPlayer((CommandContext)ctx, (String)"target");
            BountyCommand.executeInfo(viewer, target, mod);
            return 1;
        })));
        bounty.then(Commands.literal((String)"top").executes(ctx -> {
            ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
            BountyCommand.executeTop(player, mod);
            return 1;
        }));
        dispatcher.register(bounty);
    }

    private static int executeList(ServerPlayer player, SolidusEnforcerMod mod, int page) {
        BountyManager bountyManager = mod.getBountyManager();
        HunterLicenseManager licenseManager = mod.getLicenseManager();
        if (bountyManager == null || licenseManager == null) {
            player.sendSystemMessage((Component)TextUtil.branded("System not ready yet.", 0xFF5555));
            return 0;
        }
        int requestedPage = page;
        BountyManager bm = bountyManager;
        HunterLicenseManager lm = licenseManager;
        licenseManager.hasAnyLicense(player.getUUID()).thenAccept(hasLicense -> {
            if (!hasLicense.booleanValue()) {
                player.level().getServer().execute(() -> player.sendSystemMessage((Component)TextUtil.branded("You need a Hunter License to view bounties! Use /hunter buy", 0xFF5555)));
                return;
            }
            bountyManager.getActiveBounties().thenAccept(bounties -> player.level().getServer().execute(() -> {
                int perPage = 8;
                int totalPages = Math.max(1, (int)Math.ceil((double)bounties.size() / (double)perPage));
                int currentPage = Math.min(requestedPage, totalPages);
                int start = (currentPage - 1) * perPage;
                int end = Math.min(start + perPage, bounties.size());
                MutableComponent msg = TextUtil.bountyIcon().append((Component)Component.literal((String)"Active Bounties").withColor(16766720)).append((Component)Component.literal((String)(" (Page " + currentPage + "/" + totalPages + ")")).withColor(0xAAAAAA)).append((Component)Component.literal((String)"\n")).append((Component)TextUtil.separator());
                for (int i = start; i < end; ++i) {
                    BountyEntry b = (BountyEntry)bounties.get(i);
                    msg = msg.append((Component)Component.literal((String)("\n #" + b.id() + " ")).withColor(0x777777)).append((Component)TextUtil.target(b.targetName())).append((Component)Component.literal((String)" - ").withColor(0x777777)).append((Component)TextUtil.currency(b.totalAmount()));
                    if (!b.autonomous()) continue;
                    msg = msg.append((Component)Component.literal((String)" [AUTO]").withColor(0xFFAA00));
                }
                msg = msg.append((Component)Component.literal((String)"\n")).append((Component)TextUtil.separator()).append((Component)Component.literal((String)("\nUse /bounty list " + (currentPage + 1) + " for next page")).withColor(0x777777));
                player.sendSystemMessage((Component)msg);
            }));
        });
        return 1;
    }

    private static void executeInfo(ServerPlayer viewer, ServerPlayer target, SolidusEnforcerMod mod) {
        BountyManager bountyManager = mod.getBountyManager();
        HunterLicenseManager licenseManager = mod.getLicenseManager();
        if (bountyManager == null || licenseManager == null) {
            viewer.sendSystemMessage((Component)TextUtil.branded("System not ready yet.", 0xFF5555));
            return;
        }
        licenseManager.hasAnyLicense(viewer.getUUID()).thenAccept(hasLicense -> {
            if (!hasLicense.booleanValue()) {
                viewer.level().getServer().execute(() -> viewer.sendSystemMessage((Component)TextUtil.branded("You need a Hunter License!", 0xFF5555)));
                return;
            }
            bountyManager.getBountiesForTarget(target.getUUID()).thenAccept(bounties -> viewer.level().getServer().execute(() -> {
                if (bounties.isEmpty()) {
                    viewer.sendSystemMessage((Component)TextUtil.branded("No active bounties on " + target.getName().getString(), 0xAAAAAA));
                    return;
                }
                double total = bounties.stream().mapToDouble(BountyEntry::totalAmount).sum();
                MutableComponent msg = TextUtil.skullIcon().append((Component)Component.literal((String)"Bounty Info: ").withColor(16766720)).append((Component)TextUtil.target(target.getName().getString())).append((Component)Component.literal((String)"\n")).append((Component)TextUtil.thinSeparator()).append((Component)Component.literal((String)"\n  Total Bounty: ").withColor(0xAAAAAA)).append((Component)TextUtil.currency(total)).append((Component)Component.literal((String)"\n  Active Contracts: ").withColor(0xAAAAAA)).append((Component)Component.literal((String)String.valueOf(bounties.size())).withColor(0x55FFFF));
                for (BountyEntry b : bounties) {
                    msg = msg.append((Component)Component.literal((String)("\n    #" + b.id() + ": ")).withColor(0x777777)).append((Component)TextUtil.currency(b.totalAmount())).append((Component)Component.literal((String)" by ").withColor(0x777777)).append((Component)TextUtil.player(b.placedByName()));
                    if (!b.autonomous()) continue;
                    msg = msg.append((Component)Component.literal((String)(" [" + b.autonomousReason() + "]")).withColor(0xFFAA00));
                }
                msg = msg.append((Component)Component.literal((String)"\n")).append((Component)TextUtil.thinSeparator());
                viewer.sendSystemMessage((Component)msg);
            }));
        });
    }

    private static void executeTop(ServerPlayer player, SolidusEnforcerMod mod) {
        BountyManager bountyManager = mod.getBountyManager();
        if (bountyManager == null) {
            player.sendSystemMessage((Component)TextUtil.branded("System not ready yet.", 0xFF5555));
            return;
        }
        bountyManager.getActiveBounties().thenAccept(bounties -> player.level().getServer().execute(() -> {
            LinkedHashMap<String, Double> targetBounties = new LinkedHashMap<String, Double>();
            for (BountyEntry b : bounties) {
                targetBounties.merge(b.targetName(), b.totalAmount(), Double::sum);
            }
            List<Map.Entry<String, Double>> sorted = targetBounties.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(10)
                .toList();
            MutableComponent msg = TextUtil.bountyIcon().append((Component)Component.literal((String)"Most Wanted").withColor(16766720)).append((Component)Component.literal((String)"\n")).append((Component)TextUtil.separator());
            int rank = 1;
            for (Map.Entry<String, Double> entry : sorted) {
                msg = msg.append((Component)Component.literal((String)("\n " + rank + ". ")).withColor(0x777777)).append((Component)TextUtil.target(entry.getKey())).append((Component)Component.literal((String)" - ").withColor(0x777777)).append((Component)TextUtil.currency(entry.getValue()));
                ++rank;
            }
            msg = msg.append((Component)Component.literal((String)"\n")).append((Component)TextUtil.separator());
            player.sendSystemMessage((Component)msg);
        }));
    }
}
