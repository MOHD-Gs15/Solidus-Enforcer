package com.solidus.enforcer.economy;

import com.solidus.enforcer.util.TextUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * In-memory mirror of the treasury row. The database is the source of truth:
 * every mutation is applied by {@code EnforcerStorage.addToTreasury} inside the
 * single storage worker and the resulting snapshot is pushed into this mirror,
 * so memory and DB can never drift apart (the previous implementation mutated
 * memory eagerly and lost contract fees on every restart).
 */
public final class TreasuryManager {

    /** Ledger categories — every treasury movement must carry exactly one. */
    public enum Category {
        TAX("blood tax"),
        BURN("money burn"),
        FEE("contract fee"),
        CONFISCATION("confiscation"),
        AUTO_FUND("autonomous funding"),
        AUTO_REFUND("autonomous refund"),
        PAYOUT("bounty payout");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    /** Consistent snapshot returned by every storage treasury operation. */
    public record TreasurySnapshot(double balance, double totalCollectedTax,
                                   double totalBurned, double totalPaidBounties) {
    }

    private volatile TreasurySnapshot snapshot = new TreasurySnapshot(0.0, 0.0, 0.0, 0.0);

    /** Called from the storage worker (or startup load) with DB-backed values. */
    public void applySnapshot(TreasurySnapshot snapshot) {
        if (snapshot != null) {
            this.snapshot = snapshot;
        }
    }

    public TreasurySnapshot snapshot() {
        return this.snapshot;
    }

    public double getBalance() {
        return this.snapshot.balance();
    }

    public double getTotalCollectedTax() {
        return this.snapshot.totalCollectedTax();
    }

    public double getTotalBurned() {
        return this.snapshot.totalBurned();
    }

    public double getTotalPaidBounties() {
        return this.snapshot.totalPaidBounties();
    }

    public MutableComponent getTreasuryReport() {
        TreasurySnapshot s = this.snapshot;
        MutableComponent report = TextUtil.separator()
                .append(Component.literal(" TREASURY REPORT ").withColor(TextUtil.COLOR_HEADER))
                .append(TextUtil.separator())
                .append(Component.literal("\n  Balance: ").withColor(TextUtil.COLOR_INFO))
                .append(TextUtil.currency(s.balance()))
                .append(Component.literal("\n  Total Tax Collected: ").withColor(TextUtil.COLOR_INFO))
                .append(TextUtil.currency(s.totalCollectedTax()))
                .append(Component.literal("\n  Total Burned (money sink): ").withColor(0xAA0000))
                .append(TextUtil.currency(s.totalBurned()))
                .append(Component.literal("\n  Total Paid Bounties: ").withColor(TextUtil.COLOR_INFO))
                .append(TextUtil.currency(s.totalPaidBounties()))
                .append(Component.literal("\n"))
                .append(TextUtil.separator());
        return report;
    }
}
