package com.solidus.enforcer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.solidus.enforcer.SolidusEnforcerMod;
import com.solidus.enforcer.license.HunterLicenseManager;
import com.solidus.enforcer.license.LicenseData;
import com.solidus.enforcer.license.LicenseTier;
import com.solidus.enforcer.license.TrackerService;
import com.solidus.enforcer.util.CompassUtil;
import com.solidus.enforcer.util.ConfigManager;
import com.solidus.enforcer.util.TextUtil;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * /hunter [menu] | /hunter tiers | /hunter buy <tier> | /hunter info | /hunter track <target>
 *
 * Repairs over the old build: an unknown tier is rejected instead of silently
 * charging the player for BRONZE, tier names tab-complete, prices are shown
 * before purchase, expiry is human-readable, and the advertised tracking
 * compass actually exists now.
 */
public final class HunterCommand {
    private static final SuggestionProvider<CommandSourceStack> TIER_SUGGESTIONS = (ctx, builder) -> {
        for (LicenseTier tier : LicenseTier.values()) {
            builder.suggest(tier.name().toLowerCase(java.util.Locale.ROOT));
        }
        return builder.buildFuture();
    };

    private HunterCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, SolidusEnforcerMod mod) {
        var hunter = Commands.literal("hunter").requires(src -> src.getEntity() instanceof ServerPlayer);

        hunter.executes(ctx -> sendMenu(ctx, mod));
        hunter.then(Commands.literal("tiers").executes(ctx -> sendTiers(ctx, mod)));
        hunter.then(Commands.literal("buy")
                .then(Commands.argument("tier", StringArgumentType.word()).suggests(TIER_SUGGESTIONS)
                        .executes(ctx -> executeBuy(ctx, mod))));
        hunter.then(Commands.literal("info").executes(ctx -> executeInfo(ctx, mod)));
        hunter.then(Commands.literal("track")
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> executeTrack(ctx, mod))));

        dispatcher.register(hunter);
    }

    private static int sendMenu(CommandContext<CommandSourceStack> ctx, SolidusEnforcerMod mod) {
        MutableComponent menu = TextUtil.licenseIcon()
                .append(Component.literal("Hunter License").withColor(TextUtil.COLOR_HEADER))
                .append(Component.literal("\n")).append(TextUtil.separator())
                .append(Component.literal("\n  /hunter tiers   — compare license perks & prices").withColor(TextUtil.COLOR_INFO))
                .append(Component.literal("\n  /hunter buy <tier> — purchase (bronze | silver | gold)").withColor(TextUtil.COLOR_INFO))
                .append(Component.literal("\n  /hunter info    — your current license").withColor(TextUtil.COLOR_INFO))
                .append(Component.literal("\n  /hunter track <player> — live compass (silver+)").withColor(TextUtil.COLOR_INFO))
                .append(Component.literal("\n  /bounty place <player> <amount>").withColor(TextUtil.COLOR_INFO))
                .append(Component.literal("\n  /bounty list | info <name> | top").withColor(TextUtil.COLOR_INFO))
                .append(Component.literal("\n")).append(TextUtil.separator());
        ctx.getSource().sendSuccess(() -> menu, false);
        return 1;
    }

    private static int sendTiers(CommandContext<CommandSourceStack> ctx, SolidusEnforcerMod mod) {
        ConfigManager config = mod.getConfigManager();
        if (config == null) {
            ctx.getSource().sendFailure(TextUtil.branded("Enforcer is not ready yet.", TextUtil.COLOR_BAD));
            return 0;
        }
        MutableComponent tiers = TextUtil.licenseIcon()
                .append(Component.literal("License Tiers").withColor(TextUtil.COLOR_HEADER))
                .append(Component.literal("\n")).append(TextUtil.separator());
        for (LicenseTier tier : LicenseTier.values()) {
            double cost = config.getLicenseWeeklyCost(tier.name());
            tiers.append(Component.literal("\n  " + tier.name() + " ").withColor(TextUtil.COLOR_BRAND))
                    .append(TextUtil.currency(cost))
                    .append(Component.literal(" / " + config.getLicenseDurationDays() + " days")
                            .withColor(TextUtil.COLOR_MUTED))
                    .append(Component.literal("\n    " + tier.perksLine()).withColor(TextUtil.COLOR_INFO));
        }
        tiers.append(Component.literal("\n")).append(TextUtil.separator());
        ctx.getSource().sendSuccess(() -> tiers, false);
        return 1;
    }

    private static int executeBuy(CommandContext<CommandSourceStack> ctx, SolidusEnforcerMod mod) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }
        String tierName = StringArgumentType.getString(ctx, "tier");
        LicenseTier tier = LicenseTier.parse(tierName);
        HunterLicenseManager manager = mod.getLicenseManager();
        if (manager == null) {
            ctx.getSource().sendFailure(TextUtil.branded("Enforcer is not ready yet.", TextUtil.COLOR_BAD));
            return 0;
        }
        if (tier == null) {
            ctx.getSource().sendFailure(TextUtil.branded(
                    "Unknown tier \"" + tierName + "\" — valid tiers: bronze, silver, gold", TextUtil.COLOR_BAD));
            return 0;
        }
        final UUID buyer = player.getUUID();
        manager.purchaseLicense(player, tier).thenAccept(result ->
                ctx.getSource().getServer().execute(() -> {
                    ServerPlayer still = ctx.getSource().getServer().getPlayerList().getPlayer(buyer);
                    if (still != null) {
                        still.sendSystemMessage(TextUtil.branded(result.message(),
                                result.success() ? TextUtil.COLOR_GOOD : TextUtil.COLOR_BAD));
                    }
                }));
        return 1;
    }

    private static int executeInfo(CommandContext<CommandSourceStack> ctx, SolidusEnforcerMod mod) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }
        HunterLicenseManager manager = mod.getLicenseManager();
        if (manager == null) {
            ctx.getSource().sendFailure(TextUtil.branded("Enforcer is not ready yet.", TextUtil.COLOR_BAD));
            return 0;
        }
        final UUID viewer = player.getUUID();
        manager.getLicense(viewer).thenAccept(optional ->
                ctx.getSource().getServer().execute(() -> {
                    if (optional.isEmpty() || !optional.get().isValid()) {
                        ctx.getSource().sendSuccess(() -> TextUtil.branded(
                                "You do not have an active Hunter License. Compare tiers: /hunter tiers",
                                TextUtil.COLOR_INFO), false);
                        return;
                    }
                    LicenseData license = optional.get();
                    MutableComponent info = TextUtil.licenseIcon()
                            .append(Component.literal("Your License").withColor(TextUtil.COLOR_HEADER))
                            .append(Component.literal("\n  Tier: ").withColor(TextUtil.COLOR_INFO))
                            .append(Component.literal(license.tier().name()).withColor(TextUtil.COLOR_BRAND))
                            .append(Component.literal("\n  Expires in: ").withColor(TextUtil.COLOR_INFO))
                            .append(Component.literal(TextUtil.formatDuration(license.remainingMillis()))
                                    .withColor(TextUtil.COLOR_GOOD))
                            .append(Component.literal("\n  Perks: ").withColor(TextUtil.COLOR_INFO))
                            .append(Component.literal(license.tier().perksLine()).withColor(TextUtil.COLOR_MUTED));
                    ctx.getSource().sendSuccess(() -> info, false);
                }));
        return 1;
    }

    // ------------------------------------------------------------------
    // Tracking compass
    // ------------------------------------------------------------------

    private static final Map<UUID, Long> TRACK_COOLDOWNS = new ConcurrentHashMap<>();

    private static int executeTrack(CommandContext<CommandSourceStack> ctx, SolidusEnforcerMod mod) throws CommandSyntaxException {
        ServerPlayer hunter = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        HunterLicenseManager manager = mod.getLicenseManager();
        TrackerService tracker = mod.getTrackerService();
        if (manager == null || tracker == null || mod.getConfigManager() == null) {
            ctx.getSource().sendFailure(TextUtil.branded("Enforcer is not ready yet.", TextUtil.COLOR_BAD));
            return 0;
        }
        if (hunter.getUUID().equals(target.getUUID())) {
            ctx.getSource().sendFailure(TextUtil.branded("You cannot track yourself.", TextUtil.COLOR_BAD));
            return 0;
        }
        final UUID hunterUuid = hunter.getUUID();
        manager.getLicense(hunterUuid).thenAccept(licenseOpt -> ctx.getSource().getServer().execute(() -> {
            if (licenseOpt.isEmpty() || !licenseOpt.get().isValid()
                    || !licenseOpt.get().tier().canTrack()) {
                ctx.getSource().sendFailure(TextUtil.branded(
                        "Tracking requires a SILVER or GOLD license — /hunter tiers", TextUtil.COLOR_BAD));
                return;
            }
            LicenseTier tier = licenseOpt.get().tier();

            long now = System.currentTimeMillis();
            long cooldownMs = mod.getConfigManager().getTrackCooldownMinutes() * 60_000L;
            Long lastTrack = TRACK_COOLDOWNS.get(hunterUuid);
            if (lastTrack != null && now - lastTrack < cooldownMs) {
                long remaining = cooldownMs - (now - lastTrack);
                ctx.getSource().sendFailure(TextUtil.branded(
                        "Tracking on cooldown — available in " + TextUtil.formatDuration(remaining),
                        TextUtil.COLOR_WARN));
                return;
            }

            mod.getBountyManager().getTotalBountyForTarget(target.getUUID()).thenAccept(totalBounty ->
                    ctx.getSource().getServer().execute(() -> {
                        if (totalBounty <= 0.0) {
                            ctx.getSource().sendFailure(TextUtil.branded(
                                    target.getName().getString() + " carries no active bounty — nothing to track",
                                    TextUtil.COLOR_BAD));
                            return;
                        }
                        issueCompass(mod, hunter, target, tier);
                        TRACK_COOLDOWNS.put(hunterUuid, now);
                    }));
        }));
        return 1;
    }

    private static void issueCompass(SolidusEnforcerMod mod, ServerPlayer hunter, ServerPlayer target, LicenseTier tier) {
        ConfigManager config = mod.getConfigManager();
        int accuracy = tier.accuracyBlocks(config.getSilverAccuracyBlocks(), config.getGoldAccuracyBlocks());
        ServerLevel level = (ServerLevel) target.level();
        int x = CompassUtil.fuzz(target.getBlockX(), accuracy, java.util.random.RandomGenerator.getDefault());
        int z = CompassUtil.fuzz(target.getBlockZ(), accuracy, java.util.random.RandomGenerator.getDefault());
        int y = target.getBlockY();

        var compass = CompassUtil.createLiveTrackingCompass(target.getName().getString(), level,
                x, y, z, tier.name(), accuracy);
        if (!hunter.getInventory().add(compass)) {
            hunter.drop(compass, false);
        }
        trackerStart(mod, hunter, target, tier);
        hunter.sendSystemMessage(TextUtil.branded(
                "Tracking compass issued for " + target.getName().getString()
                        + " (\u00B1" + accuracy + " blocks)", TextUtil.COLOR_GOOD));
    }

    private static void trackerStart(SolidusEnforcerMod mod, ServerPlayer hunter, ServerPlayer target, LicenseTier tier) {
        if (tier == LicenseTier.GOLD) {
            mod.getTrackerService().startTracking(hunter.getUUID(), target.getUUID());
            hunter.sendSystemMessage(TextUtil.branded(
                    "GOLD live tracking active — the compass updates while the bounty stands.",
                    TextUtil.COLOR_INFO));
        }
    }
}
