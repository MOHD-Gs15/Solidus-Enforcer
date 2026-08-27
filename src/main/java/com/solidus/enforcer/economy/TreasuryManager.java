/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.network.chat.Component
 *  net.minecraft.network.chat.MutableComponent
 */
package com.solidus.enforcer.economy;

import com.solidus.enforcer.util.ConfigManager;
import com.solidus.enforcer.util.TextUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class TreasuryManager {
    private double balance;
    private double totalCollectedTax;
    private double totalBurned;
    private double totalPaidBounties;
    private final ConfigManager config;

    public TreasuryManager(ConfigManager config) {
        this.config = config;
    }

    public TaxResult processBloodTax(double bountyAmount) {
        if (!Double.isFinite(bountyAmount) || bountyAmount < 0.0) {
            return new TaxResult(0.0, 0.0, 0.0, 0.0, 0.0);
        }
        if (!this.config.isBloodTaxEnabled()) {
            return new TaxResult(bountyAmount, 0.0, 0.0, 0.0, bountyAmount);
        }
        double taxRate = clamp(this.config.getBloodTaxRate(), 0.0, 1.0);
        double treasuryShareRate = clamp(this.config.getTreasuryShare(), 0.0, 1.0);
        double burnShareRate = clamp(this.config.getBurnShare(), 0.0, 1.0);
        double shareTotal = treasuryShareRate + burnShareRate;
        if (shareTotal > 1.0) {
            treasuryShareRate /= shareTotal;
            burnShareRate /= shareTotal;
        }
        double totalTax = bountyAmount * taxRate;
        double treasuryAmount = totalTax * treasuryShareRate;
        double burnAmount = totalTax * burnShareRate;
        double remainingBounty = bountyAmount - totalTax;
        return new TaxResult(bountyAmount, totalTax, treasuryAmount, burnAmount, remainingBounty);
    }

    public void applyTax(TaxResult taxResult) {
        if (taxResult == null || !Double.isFinite(taxResult.totalTax())
                || !Double.isFinite(taxResult.treasuryAmount())
                || !Double.isFinite(taxResult.burnAmount())) {
            return;
        }
        this.balance += taxResult.treasuryAmount();
        this.totalCollectedTax += taxResult.totalTax();
        this.totalBurned += taxResult.burnAmount();
    }

    public ContractFeeResult processContractFee(double currentAmount) {
        if (!Double.isFinite(currentAmount) || currentAmount < 0.0) {
            return new ContractFeeResult(0.0, 0.0, 0.0);
        }
        if (!this.config.isContractFeesEnabled() || currentAmount < this.config.getContractMinimumBounty()) {
            return new ContractFeeResult(currentAmount, 0.0, currentAmount);
        }
        double dailyRate = clamp(this.config.getContractDailyRate(), 0.0, 1.0);
        double fee = currentAmount * dailyRate;
        double newAmount = currentAmount - fee;
        if (newAmount < this.config.getContractMinimumBounty()) {
            newAmount = this.config.getContractMinimumBounty();
            fee = currentAmount - newAmount;
        }
        this.balance += fee;
        this.totalCollectedTax += fee;
        return new ContractFeeResult(currentAmount, fee, newAmount);
    }

    public boolean fundAutonomousBounty(double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0) {
            return false;
        }
        if (this.balance >= amount) {
            this.balance -= amount;
            this.totalPaidBounties += amount;
            return true;
        }
        return false;
    }

    public void refundAutonomousBounty(double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0) {
            return;
        }
        this.balance += amount;
        this.totalPaidBounties = Math.max(0.0, this.totalPaidBounties - amount);
    }

    public double getSuggestedAutoBounty(double baseAmount) {
        if (!Double.isFinite(baseAmount) || baseAmount <= 0.0) {
            return this.config.getMinAutoBounty();
        }
        double treasuryMultiplier = Math.min(2.0, this.balance / (baseAmount * 10.0));
        return Math.min(Math.max(baseAmount * treasuryMultiplier, this.config.getMinAutoBounty()), this.config.getMaxAutoBounty());
    }

    public MutableComponent getTreasuryReport() {
        return TextUtil.separator().append((Component)TextUtil.prefix().append((Component)Component.literal((String)"Treasury Report").withColor(16766720))).append((Component)Component.literal((String)"\n")).append((Component)TextUtil.separator()).append((Component)Component.literal((String)"\n  Balance: ").withColor(0xAAAAAA)).append((Component)TextUtil.currency(this.balance)).append((Component)Component.literal((String)"\n  Total Tax Collected: ").withColor(0xAAAAAA)).append((Component)TextUtil.currency(this.totalCollectedTax)).append((Component)Component.literal((String)"\n  Total Burned (Money Sink): ").withColor(0xAA0000)).append((Component)TextUtil.currency(this.totalBurned)).append((Component)Component.literal((String)"\n  Total Paid Bounties: ").withColor(0xAAAAAA)).append((Component)TextUtil.currency(this.totalPaidBounties));
    }

    public void loadFromStorage(double balance, double totalTax, double totalBurned, double totalPaid) {
        this.balance = balance;
        this.totalCollectedTax = totalTax;
        this.totalBurned = totalBurned;
        this.totalPaidBounties = totalPaid;
    }

    public double getBalance() {
        return this.balance;
    }

    public double getTotalCollectedTax() {
        return this.totalCollectedTax;
    }

    public double getTotalBurned() {
        return this.totalBurned;
    }

    public double getTotalPaidBounties() {
        return this.totalPaidBounties;
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    public record TaxResult(double originalAmount, double totalTax, double treasuryAmount, double burnAmount, double remainingBounty) {
    }

    public record ContractFeeResult(double previousAmount, double feeDeducted, double newAmount) {
    }
}
