/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.mojang.brigadier.CommandDispatcher
 *  net.fabricmc.api.DedicatedServerModInitializer
 *  net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
 *  net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
 *  net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
 *  net.minecraft.commands.CommandSourceStack
 *  net.minecraft.server.MinecraftServer
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package com.solidus.enforcer;

import com.mojang.brigadier.CommandDispatcher;
import com.solidus.enforcer.bounty.AutonomousBountyEngine;
import com.solidus.enforcer.bounty.BountyManager;
import com.solidus.enforcer.combat.DamageTracker;
import com.solidus.enforcer.combat.KillProcessor;
import com.solidus.enforcer.commands.BountyCommand;
import com.solidus.enforcer.commands.EnforcerAdminCommand;
import com.solidus.enforcer.commands.HunterCommand;
import com.solidus.enforcer.economy.TreasuryManager;
import com.solidus.enforcer.integration.SolidusBridge;
import com.solidus.enforcer.license.HunterLicenseManager;
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
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SolidusEnforcerMod
implements DedicatedServerModInitializer {
    public static final String MOD_ID = "solidus-enforcer";
    public static final String MOD_NAME = "Solidus Enforcer";
    public static final Logger LOGGER = LoggerFactory.getLogger((String)"Solidus Enforcer");
    private static SolidusEnforcerMod instance;
    private ConfigManager configManager;
    private EnforcerStorage storage;
    private TreasuryManager treasury;
    private BountyManager bountyManager;
    private HunterLicenseManager licenseManager;
    private DamageTracker damageTracker;
    private CollusionDetector collusionDetector;
    private AntiExploitEngine antiExploit;
    private KillProcessor killProcessor;
    private AutonomousBountyEngine autonomousEngine;
    private MinecraftServer server;
    private int tickCounter = 0;
    private static final int TICKS_PER_MINUTE = 1200;
    private static final int TICKS_PER_30_MINUTES = 36000;

    public void onInitializeServer() {
        LOGGER.info("Initializing Solidus Enforcer...");
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            BountyCommand.register((CommandDispatcher<CommandSourceStack>)dispatcher, this);
            HunterCommand.register((CommandDispatcher<CommandSourceStack>)dispatcher, this);
            EnforcerAdminCommand.register((CommandDispatcher<CommandSourceStack>)dispatcher, this);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        LOGGER.info("Solidus Enforcer initialized (awaiting server start for full activation)");
    }

    private void onServerStarted(MinecraftServer server) {
        this.server = server;
        instance = this;
        Path runDir = server.getServerDirectory();
        Path configDir = runDir.resolve("config");
        this.configManager = new ConfigManager(configDir);
        this.configManager.load();
        this.storage = new EnforcerStorage(configDir);
        try {
            this.storage.initialize().orTimeout(10L, TimeUnit.SECONDS).join();
        }
        catch (RuntimeException e) {
            LOGGER.error("Failed to initialize Enforcer storage within timeout. Enforcer remains disabled.", e);
            this.storage.shutdown();
            this.configManager = null;
            return;
        }
        this.treasury = new TreasuryManager(this.configManager);
        this.storage.getTreasuryBalance().thenAccept(balance -> this.treasury.loadFromStorage((double)balance, 0.0, 0.0, 0.0));
        this.bountyManager = new BountyManager(this.storage, this.configManager, this.treasury);
        this.licenseManager = new HunterLicenseManager(this.storage, this.configManager);
        this.damageTracker = new DamageTracker(this.storage, this.configManager);
        this.collusionDetector = new CollusionDetector(this.storage, this.configManager);
        this.antiExploit = new AntiExploitEngine(this.configManager, this.collusionDetector);
        this.killProcessor = new KillProcessor(this.storage, this.configManager, this.damageTracker, this.antiExploit, this.treasury, this.licenseManager);
        this.autonomousEngine = new AutonomousBountyEngine(this.storage, this.configManager, this.treasury, this.bountyManager);
        boolean coreAvailable = SolidusBridge.isAvailable();
        if (!coreAvailable) {
            LOGGER.warn("Solidus Core not found! Enforcer will operate in limited mode.");
        }
        this.bountyManager.processExpirations();
        LOGGER.info("Solidus Enforcer fully activated! Connected to Solidus Core: {}", (Object)coreAvailable);
    }

    private void onServerStopping(MinecraftServer server) {
        if (this.storage != null) {
            this.storage.shutdown();
        }
        LOGGER.info("Solidus Enforcer shut down");
    }

    private void onServerTick(MinecraftServer server) {
        if (this.configManager == null || this.damageTracker == null || this.bountyManager == null || this.autonomousEngine == null) {
            return;
        }
        ++this.tickCounter;
        if (this.tickCounter % 1200 == 0) {
            this.damageTracker.cleanOldRecords();
        }
        if (this.tickCounter % 36000 == 0) {
            this.bountyManager.processContractFees();
            this.bountyManager.processExpirations();
            this.autonomousEngine.runCheckCycle(server);
            this.tickCounter = 0;
        }
    }

    public static SolidusEnforcerMod getInstance() {
        return instance;
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

    public DamageTracker getDamageTracker() {
        return this.damageTracker;
    }

    public KillProcessor getKillProcessor() {
        return this.killProcessor;
    }

    public AutonomousBountyEngine getAutonomousEngine() {
        return this.autonomousEngine;
    }

    public AntiExploitEngine getAntiExploit() {
        return this.antiExploit;
    }
}
