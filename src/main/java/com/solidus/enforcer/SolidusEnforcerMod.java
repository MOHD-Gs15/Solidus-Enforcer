package com.solidus.enforcer;

import com.mojang.brigadier.CommandDispatcher;
import com.solidus.enforcer.bounty.AutonomousBountyEngine;
import com.solidus.enforcer.bounty.BountyAnnouncer;
import com.solidus.enforcer.bounty.BountyManager;
import com.solidus.enforcer.combat.DamageTracker;
import com.solidus.enforcer.combat.KillProcessor;
import com.solidus.enforcer.commands.BountyCommand;
import com.solidus.enforcer.commands.EnforcerAdminCommand;
import com.solidus.enforcer.commands.HunterCommand;
import com.solidus.enforcer.economy.TreasuryManager;
import com.solidus.enforcer.integration.SolidusBridge;
import com.solidus.enforcer.license.HunterLicenseManager;
import com.solidus.enforcer.license.TrackerService;
import com.solidus.enforcer.security.AntiExploitEngine;
import com.solidus.enforcer.security.CollusionDetector;
import com.solidus.enforcer.storage.EnforcerStorage;
import com.solidus.enforcer.util.ConfigManager;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Solidus Enforcer — bounty hunting, hunter licensing, alliance payouts,
 * anti-exploit and collusion enforcement for the Solidus economy ecosystem.
 *
 * Lifecycle: components are built once on SERVER_STARTED; until then mixins
 * see {@link #isFullyActive()} == false and no-op. All scheduled work runs in
 * time buckets (minute / 5-minute / configured autonomous cycle) and every
 * heavy operation stays on the storage worker — the tick thread only performs
 * constant-time bookkeeping.
 */
public final class SolidusEnforcerMod implements DedicatedServerModInitializer {
    public static final String MOD_ID = "solidus-enforcer";
    public static final Logger LOGGER = LoggerFactory.getLogger("Solidus Enforcer");

    private static SolidusEnforcerMod instance;

    private ConfigManager configManager;
    private EnforcerStorage storage;
    private TreasuryManager treasury;
    private BountyManager bountyManager;
    private HunterLicenseManager licenseManager;
    private TrackerService trackerService;
    private BountyAnnouncer announcer;
    private DamageTracker damageTracker;
    private CollusionDetector collusionDetector;
    private AntiExploitEngine antiExploit;
    private KillProcessor killProcessor;
    private AutonomousBountyEngine autonomousEngine;
    private boolean fullyActive;

    private long tickCounter = 0L;

    @Override
    public void onInitializeServer() {
        instance = this;
        LOGGER.info("Initializing Solidus Enforcer...");
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            BountyCommand.register((CommandDispatcher<CommandSourceStack>) dispatcher, this);
            HunterCommand.register((CommandDispatcher<CommandSourceStack>) dispatcher, this);
            EnforcerAdminCommand.register((CommandDispatcher<CommandSourceStack>) dispatcher, this);
        });
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        LOGGER.info("Solidus Enforcer initialized (awaiting server start for full activation)");
    }

    private void onServerStarted(MinecraftServer server) {
        Path configDir = server.getServerDirectory().resolve("config");
        this.configManager = new ConfigManager(configDir);
        this.configManager.load();

        this.storage = new EnforcerStorage(configDir);
        try {
            this.storage.initialize().orTimeout(10L, TimeUnit.SECONDS).join();
        } catch (Exception e) {
            LOGGER.error("Enforcer storage failed to initialize — Enforcer stays DISABLED this run", e);
            this.storage.shutdown();
            this.storage = null;
            this.configManager = null;
            return;
        }

        this.treasury = new TreasuryManager();
        this.treasury.applySnapshot(this.storage.loadTreasury().join());

        this.bountyManager = new BountyManager(this.storage, this.configManager, this.treasury);
        this.licenseManager = new HunterLicenseManager(this.storage, this.configManager);
        this.trackerService = new TrackerService(this.configManager, this.licenseManager);
        this.announcer = new BountyAnnouncer(this.storage, this.licenseManager);
        this.damageTracker = new DamageTracker(this.storage, this.configManager);
        this.collusionDetector = new CollusionDetector(this.storage, this.configManager);
        this.antiExploit = new AntiExploitEngine(this.configManager, this.collusionDetector);
        this.killProcessor = new KillProcessor(this.storage, this.configManager, this.damageTracker,
                this.antiExploit, this.bountyManager, this.treasury);
        this.autonomousEngine = new AutonomousBountyEngine(this.storage, this.configManager,
                this.treasury, this.announcer);
        this.fullyActive = true;

        boolean coreAvailable = SolidusBridge.isAvailable();
        if (!coreAvailable) {
            LOGGER.warn("Solidus Core not detected — economy features are DISABLED (fail-closed). "
                    + "Bounties, licenses and payouts will refuse to run until Core is installed.");
        }
        this.bountyManager.processExpirations();
        LOGGER.info("Solidus Enforcer fully activated! Solidus Core available: {}", coreAvailable);
    }

    private void onServerStopping(MinecraftServer server) {
        this.fullyActive = false;
        if (this.storage != null) {
            this.storage.shutdown();
            this.storage = null;
        }
        LOGGER.info("Solidus Enforcer shut down");
    }

    // ------------------------------------------------------------------
    // Tick scheduler (constant-time on the tick thread)
    // ------------------------------------------------------------------

    private static final long TICKS_PER_MINUTE = 1200L;

    private void onServerTick(MinecraftServer server) {
        if (!this.fullyActive) {
            return;
        }
        this.tickCounter++;
        if (this.tickCounter % TICKS_PER_MINUTE == 0L) {
            this.damageTracker.cleanOldCacheRecords();
            this.damageTracker.cleanOldDatabaseRecords();
            this.storage.sweepExpiredLicenses();
            this.storage.cleanupOldKillEvents(24L * 60L * 60L * 1000L);
        }
        if (this.tickCounter % this.configManager.getCompassUpdateIntervalTicks() == 0L) {
            this.trackerService.refreshTick(server);
        }
        long cycleTicks = this.configManager.getAutonomousCheckIntervalMinutes() * TICKS_PER_MINUTE;
        if (this.tickCounter % cycleTicks == 0L) {
            this.bountyManager.processContractFees();
            this.bountyManager.processExpirations();
            this.autonomousEngine.runCheckCycle(server);
        }
        // Wrap long before modular overflow could ever matter (2^63 ticks is ~10^15 years).
        if (this.tickCounter >= 9_007_199_254_740_992L) { // 2^53
            this.tickCounter = 0L;
        }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public static SolidusEnforcerMod getInstance() {
        return instance;
    }

    /** False until storage is up and all managers are wired; mixins check this. */
    public boolean isFullyActive() {
        return this.fullyActive;
    }

    /** Shared readiness check used by every command. */
    public boolean notReady(CommandContext<CommandSourceStack> ctx) {
        if (!this.fullyActive) {
            ctx.getSource().sendFailure(TextUtil.branded("Solidus Enforcer is not active.", 0xFF5555));
            return true;
        }
        return false;
    }

    public ConfigManager getConfigManager() {
        return this.configManager;
    }

    public EnforcerStorage getStorage() {
        return this.storage;
    }

    public TreasuryManager getTreasuryManager() {
        return this.treasury;
    }

    public BountyManager getBountyManager() {
        return this.bountyManager;
    }

    public HunterLicenseManager getLicenseManager() {
        return this.licenseManager;
    }

    public TrackerService getTrackerService() {
        return this.trackerService;
    }

    public BountyAnnouncer getAnnouncer() {
        return this.announcer;
    }

    public DamageTracker getDamageTracker() {
        return this.damageTracker;
    }

    public AntiExploitEngine getAntiExploit() {
        return this.antiExploit;
    }

    public KillProcessor getKillProcessor() {
        return this.killProcessor;
    }

    public AutonomousBountyEngine getAutonomousEngine() {
        return this.autonomousEngine;
    }
}
