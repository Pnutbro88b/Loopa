/*
 * Loopa — Yield aggregation vault engine: best DeFi rates across crypto.
 * Multi-strategy routing, rebalancing, share accounting. Single-file Java.
 * Not an on-chain contract; a simulation/orchestration model for vault logic.
 */

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// -----------------------------------------------------------------------------
// EXCEPTIONS (LPA = Loopa)
// -----------------------------------------------------------------------------

final class LPAException extends RuntimeException {
    private final String code;

    LPAException(String code, String message) {
        super(message);
        this.code = code;
    }

    String getCode() {
        return code;
    }
}

// -----------------------------------------------------------------------------
// ERROR CODES (unique to Loopa)
// -----------------------------------------------------------------------------

final class LPAErrorCodes {
    static final String LPA_ZERO_AMOUNT = "LPA_ZERO_AMOUNT";
    static final String LPA_ZERO_ADDRESS = "LPA_ZERO_ADDRESS";
    static final String LPA_INSUFFICIENT_SHARES = "LPA_INSUFFICIENT_SHARES";
    static final String LPA_INSUFFICIENT_LIQUIDITY = "LPA_INSUFFICIENT_LIQUIDITY";
    static final String LPA_STRATEGY_EXISTS = "LPA_STRATEGY_EXISTS";
    static final String LPA_STRATEGY_MISSING = "LPA_STRATEGY_MISSING";
    static final String LPA_STRATEGY_PAUSED = "LPA_STRATEGY_PAUSED";
    static final String LPA_VAULT_PAUSED = "LPA_VAULT_PAUSED";
    static final String LPA_ASSET_MISMATCH = "LPA_ASSET_MISMATCH";
    static final String LPA_ABOVE_CAP = "LPA_ABOVE_CAP";
    static final String LPA_INVALID_FEE = "LPA_INVALID_FEE";
    static final String LPA_INVALID_RISK_BAND = "LPA_INVALID_RISK_BAND";
    static final String LPA_REENTRANT = "LPA_REENTRANT";
    static final String LPA_INDEX_OUT_OF_RANGE = "LPA_INDEX_OUT_OF_RANGE";
    static final String LPA_MAX_STRATEGIES = "LPA_MAX_STRATEGIES";

    static String describe(String code) {
        if (code == null) return "Unknown";
        switch (code) {
            case LPA_ZERO_AMOUNT: return "Amount must be positive";
            case LPA_ZERO_ADDRESS: return "Address invalid";
            case LPA_INSUFFICIENT_SHARES: return "Insufficient shares to withdraw";
            case LPA_INSUFFICIENT_LIQUIDITY: return "Insufficient vault liquidity";
            case LPA_STRATEGY_EXISTS: return "Strategy already registered";
            case LPA_STRATEGY_MISSING: return "Strategy not found";
            case LPA_STRATEGY_PAUSED: return "Strategy is paused";
            case LPA_VAULT_PAUSED: return "Vault is paused";
            case LPA_ASSET_MISMATCH: return "Strategy asset does not match vault";
            case LPA_ABOVE_CAP: return "Above strategy or vault capacity";
            case LPA_INVALID_FEE: return "Fee out of range 0..1";
            case LPA_INVALID_RISK_BAND: return "Invalid risk band";
            case LPA_REENTRANT: return "Reentrant call";
            case LPA_INDEX_OUT_OF_RANGE: return "Index out of range";
            case LPA_MAX_STRATEGIES: return "Max strategies reached";
            default: return "Unknown: " + code;
        }
    }
}

// -----------------------------------------------------------------------------
// CONSTANTS
// -----------------------------------------------------------------------------

final class LPAConstants {
    static final int LPA_MAX_STRATEGIES = 32;
    static final int LPA_MAX_SNAPSHOTS = 512;
    static final int LPA_MAX_APR_SAMPLES = 64;
    static final BigDecimal LPA_ONE = new BigDecimal("1", new MathContext(18, RoundingMode.HALF_UP));
    static final BigDecimal LPA_1E18 = new BigDecimal("1000000000000000000", new MathContext(18, RoundingMode.HALF_UP));
    static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
}

// -----------------------------------------------------------------------------
// ASSET & RISK
// -----------------------------------------------------------------------------

enum LPAAsset {
    USDC(6),
    DAI(18),
    WETH(18),
    WBTC(8),
    USDT(6);

    private final int decimals;

    LPAAsset(int decimals) {
        this.decimals = decimals;
    }

    public int getDecimals() {
        return decimals;
    }
}

enum LPARiskBand {
    CONSERVATIVE,
    BALANCED,
    AGGRESSIVE
}

enum LPAStrategyState {
    ACTIVE,
    PAUSED,
    RETIRED
}

// -----------------------------------------------------------------------------
// APR SAMPLE
// -----------------------------------------------------------------------------

final class LPAAprSample {
    private final long timestamp;
    private final BigDecimal baseApr;
    private final BigDecimal boostApr;

    LPAAprSample(long timestamp, BigDecimal baseApr, BigDecimal boostApr) {
        this.timestamp = timestamp;
        this.baseApr = baseApr;
        this.boostApr = boostApr;
    }

    public long getTimestamp() { return timestamp; }
    public BigDecimal getBaseApr() { return baseApr; }
    public BigDecimal getBoostApr() { return boostApr; }
}

// -----------------------------------------------------------------------------
// STRATEGY
// -----------------------------------------------------------------------------

final class LPAStrategy {
    private final String id;
    private final String name;
    private final LPAAsset asset;
    private final LPARiskBand riskBand;
    private final String protocolLabel;
    private final String chainLabel;
    private final BigDecimal baseApr;
    private final BigDecimal boostApr;
    private final BigDecimal performanceFee;
    private final BigDecimal maxCapacity;
    private volatile LPAStrategyState state;
    private volatile BigDecimal tvl;
    private final Deque<LPAAprSample> aprHistory = new ArrayDeque<>();

    LPAStrategy(
            String id,
            String name,
            LPAAsset asset,
            LPARiskBand riskBand,
            String protocolLabel,
            String chainLabel,
            BigDecimal baseApr,
            BigDecimal boostApr,
            BigDecimal performanceFee,
            BigDecimal maxCapacity
    ) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.asset = Objects.requireNonNull(asset);
        this.riskBand = Objects.requireNonNull(riskBand);
        this.protocolLabel = Objects.requireNonNull(protocolLabel);
        this.chainLabel = Objects.requireNonNull(chainLabel);
        this.baseApr = Objects.requireNonNull(baseApr);
        this.boostApr = Objects.requireNonNull(boostApr);
        this.performanceFee = Objects.requireNonNull(performanceFee);
        this.maxCapacity = Objects.requireNonNull(maxCapacity);
        this.state = LPAStrategyState.ACTIVE;
        this.tvl = BigDecimal.ZERO;
    }

    synchronized void updateApr(BigDecimal newBase, BigDecimal newBoost) {
        LPAAprSample sample = new LPAAprSample(
                Instant.now().getEpochSecond(),
                newBase,
                newBoost
        );
        aprHistory.addLast(sample);
        while (aprHistory.size() > LPAConstants.LPA_MAX_APR_SAMPLES) {
            aprHistory.removeFirst();
        }
    }

    synchronized List<LPAAprSample> getAprHistory() {
        return new ArrayList<>(aprHistory);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public LPAAsset getAsset() { return asset; }
    public LPARiskBand getRiskBand() { return riskBand; }
    public String getProtocolLabel() { return protocolLabel; }
    public String getChainLabel() { return chainLabel; }
    public BigDecimal getBaseApr() { return baseApr; }
    public BigDecimal getBoostApr() { return boostApr; }
    public BigDecimal getPerformanceFee() { return performanceFee; }
    public BigDecimal getMaxCapacity() { return maxCapacity; }
    public LPAStrategyState getState() { return state; }
    public void setState(LPAStrategyState state) { this.state = Objects.requireNonNull(state); }

    synchronized BigDecimal getTvl() {
        return tvl;
    }

    synchronized void addTvl(BigDecimal delta) {
        if (delta.signum() <= 0) return;
        BigDecimal newTvl = tvl.add(delta, LPAConstants.MC);
        if (maxCapacity.signum() > 0 && newTvl.compareTo(maxCapacity) > 0) {
            throw new LPAException(LPAErrorCodes.LPA_ABOVE_CAP, "Strategy " + id + " above cap");
        }
        tvl = newTvl;
    }

    synchronized void removeTvl(BigDecimal delta) {
        if (delta.signum() <= 0) return;
        tvl = tvl.subtract(delta, LPAConstants.MC);
        if (tvl.signum() < 0) tvl = BigDecimal.ZERO;
    }

    BigDecimal effectiveApr() {
        if (state != LPAStrategyState.ACTIVE) return BigDecimal.ZERO;
        return baseApr.add(boostApr.multiply(new BigDecimal("0.5"), LPAConstants.MC), LPAConstants.MC);
    }

    @Override
    public String toString() {
        return "LPAStrategy{id=" + id + ", protocol=" + protocolLabel + ", chain=" + chainLabel
                + ", asset=" + asset + ", band=" + riskBand + ", apr=" + effectiveApr()
                + ", tvl=" + tvl + ", state=" + state + "}";
    }
}

// -----------------------------------------------------------------------------
// VAULT SHARE
// -----------------------------------------------------------------------------

final class LPAVaultShare {
    private final String owner;
    private BigDecimal shares;

    LPAVaultShare(String owner, BigDecimal shares) {
        this.owner = owner;
        this.shares = shares;
    }

    public String getOwner() { return owner; }
    public BigDecimal getShares() { return shares; }

    void addShares(BigDecimal s) {
        shares = shares.add(s, LPAConstants.MC);
    }

    void removeShares(BigDecimal s) {
        shares = shares.subtract(s, LPAConstants.MC);
        if (shares.signum() < 0) shares = BigDecimal.ZERO;
    }
}

// -----------------------------------------------------------------------------
// VAULT CONFIG
// -----------------------------------------------------------------------------

final class LPAVaultConfig {
    private final LPAAsset asset;
    private final LPARiskBand defaultBand;
    private final BigDecimal managementFee;
    private final BigDecimal withdrawalFee;
    private final BigDecimal maxTotalTvl;

    LPAVaultConfig(
            LPAAsset asset,
            LPARiskBand defaultBand,
            BigDecimal managementFee,
            BigDecimal withdrawalFee,
            BigDecimal maxTotalTvl
    ) {
        this.asset = Objects.requireNonNull(asset);
        this.defaultBand = Objects.requireNonNull(defaultBand);
        this.managementFee = Objects.requireNonNull(managementFee);
        this.withdrawalFee = Objects.requireNonNull(withdrawalFee);
        this.maxTotalTvl = maxTotalTvl != null ? maxTotalTvl : BigDecimal.ZERO;
    }

    public LPAAsset getAsset() { return asset; }
    public LPARiskBand getDefaultBand() { return defaultBand; }
    public BigDecimal getManagementFee() { return managementFee; }
    public BigDecimal getWithdrawalFee() { return withdrawalFee; }
    public BigDecimal getMaxTotalTvl() { return maxTotalTvl; }
}

// -----------------------------------------------------------------------------
// VAULT SNAPSHOT
// -----------------------------------------------------------------------------

final class LPAVaultSnapshot {
    private final long timestamp;
    private final BigDecimal totalTvl;
    private final BigDecimal sharePrice;
    private final int userCount;
    private final int strategyCount;

    LPAVaultSnapshot(long timestamp, BigDecimal totalTvl, BigDecimal sharePrice, int userCount, int strategyCount) {
        this.timestamp = timestamp;
        this.totalTvl = totalTvl;
        this.sharePrice = sharePrice;
        this.userCount = userCount;
        this.strategyCount = strategyCount;
    }

    public long getTimestamp() { return timestamp; }
    public BigDecimal getTotalTvl() { return totalTvl; }
    public BigDecimal getSharePrice() { return sharePrice; }
    public int getUserCount() { return userCount; }
    public int getStrategyCount() { return strategyCount; }

    @Override
    public String toString() {
        return "LPAVaultSnapshot{t=" + timestamp + ", tvl=" + totalTvl + ", price=" + sharePrice
                + ", users=" + userCount + ", strategies=" + strategyCount + "}";
    }
}

// -----------------------------------------------------------------------------
// LOOPA VAULT ENGINE
// -----------------------------------------------------------------------------

public final class Loopa {

    private final LPAVaultConfig config;
    private final Map<String, LPAVaultShare> sharesByUser = new ConcurrentHashMap<>();
    private final Map<String, LPAStrategy> strategiesById = new ConcurrentHashMap<>();
    private final List<LPAVaultSnapshot> snapshots = Collections.synchronizedList(new ArrayList<>());

    private volatile BigDecimal totalShares = BigDecimal.ZERO;
    private volatile BigDecimal unallocatedTvl = BigDecimal.ZERO;
    private volatile boolean paused = false;
    private volatile int reentrancyLock = 0;

    public Loopa(LPAVaultConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    private void requireNotPaused() {
        if (paused) throw new LPAException(LPAErrorCodes.LPA_VAULT_PAUSED, "Vault is paused");
    }

    private void requireNotReentrant() {
        if (reentrancyLock != 0) throw new LPAException(LPAErrorCodes.LPA_REENTRANT, "Reentrant call");
    }

    public boolean isPaused() { return paused; }
    public void setPaused(boolean p) { this.paused = p; }

    // -------------------------------------------------------------------------
    // STRATEGY MGMT
    // -------------------------------------------------------------------------

    public synchronized void addStrategy(LPAStrategy strategy) {
        requireNotReentrant();
        reentrancyLock = 1;
        try {
            if (strategy == null) throw new LPAException(LPAErrorCodes.LPA_STRATEGY_MISSING, "Strategy null");
            if (strategiesById.containsKey(strategy.getId())) {
                throw new LPAException(LPAErrorCodes.LPA_STRATEGY_EXISTS, "Strategy " + strategy.getId());
            }
            if (strategiesById.size() >= LPAConstants.LPA_MAX_STRATEGIES) {
                throw new LPAException(LPAErrorCodes.LPA_MAX_STRATEGIES, "Max strategies");
            }
            if (strategy.getAsset() != config.getAsset()) {
                throw new LPAException(LPAErrorCodes.LPA_ASSET_MISMATCH, "Asset mismatch");
            }
            strategiesById.put(strategy.getId(), strategy);
        } finally {
            reentrancyLock = 0;
        }
    }

    public synchronized void removeStrategy(String strategyId) {
        requireNotReentrant();
        reentrancyLock = 1;
        try {
            LPAStrategy s = strategiesById.remove(strategyId);
            if (s == null) return;
            BigDecimal tvl = s.getTvl();
            if (tvl.signum() > 0) {
                s.removeTvl(tvl);
                unallocatedTvl = unallocatedTvl.add(tvl, LPAConstants.MC);
            }
        } finally {
            reentrancyLock = 0;
        }
    }

    public List<LPAStrategy> listStrategies() {
        return new ArrayList<>(strategiesById.values());
    }

    public LPAStrategy getStrategy(String id) {
        return strategiesById.get(id);
    }

    public int getStrategyCount() {
        return strategiesById.size();
    }

    // -------------------------------------------------------------------------
    // SHARE PRICE & TVL
    // -------------------------------------------------------------------------

    public synchronized BigDecimal getSharePrice() {
        BigDecimal tvl = totalVaultTvl();
        if (totalShares.signum() == 0) return LPAConstants.LPA_1E18;
        return tvl.multiply(LPAConstants.LPA_1E18, LPAConstants.MC).divide(totalShares, LPAConstants.MC);
    }

    public synchronized BigDecimal totalVaultTvl() {
        BigDecimal tvl = unallocatedTvl;
        for (LPAStrategy s : strategiesById.values()) {
            tvl = tvl.add(s.getTvl(), LPAConstants.MC);
        }
        return tvl;
    }

    public synchronized BigDecimal getUnallocatedTvl() {
        return unallocatedTvl;
    }

    public synchronized BigDecimal getTotalShares() {
        return totalShares;
    }

    // -------------------------------------------------------------------------
    // DEPOSIT
    // -------------------------------------------------------------------------

    public synchronized BigDecimal deposit(String user, BigDecimal amount) {
        requireNotReentrant();
        requireNotPaused();
        if (user == null || user.isEmpty()) throw new LPAException(LPAErrorCodes.LPA_ZERO_ADDRESS, "User null");
        if (amount == null || amount.signum() <= 0) {
            throw new LPAException(LPAErrorCodes.LPA_ZERO_AMOUNT, "Amount must be positive");
        }
        reentrancyLock = 1;
        try {
            BigDecimal tvl = totalVaultTvl();
            BigDecimal maxTvl = config.getMaxTotalTvl();
            if (maxTvl.signum() > 0 && tvl.add(amount, LPAConstants.MC).compareTo(maxTvl) > 0) {
                throw new LPAException(LPAErrorCodes.LPA_ABOVE_CAP, "Vault at cap");
            }
            BigDecimal price = getSharePrice();
            BigDecimal newShares = amount.multiply(LPAConstants.LPA_1E18, LPAConstants.MC).divide(price, LPAConstants.MC);
            totalShares = totalShares.add(newShares, LPAConstants.MC);
            unallocatedTvl = unallocatedTvl.add(amount, LPAConstants.MC);

            LPAVaultShare vs = sharesByUser.get(user);
            if (vs == null) {
                vs = new LPAVaultShare(user, BigDecimal.ZERO);
                sharesByUser.put(user, vs);
            }
            vs.addShares(newShares);

            recordSnapshot();
            return newShares;
        } finally {
            reentrancyLock = 0;
        }
    }

    // -------------------------------------------------------------------------
    // WITHDRAW
    // -------------------------------------------------------------------------

    public synchronized BigDecimal withdraw(String user, BigDecimal shares) {
        requireNotReentrant();
        requireNotPaused();
        if (user == null || user.isEmpty()) throw new LPAException(LPAErrorCodes.LPA_ZERO_ADDRESS, "User null");
        if (shares == null || shares.signum() <= 0) {
            throw new LPAException(LPAErrorCodes.LPA_ZERO_AMOUNT, "Shares must be positive");
        }
        reentrancyLock = 1;
        try {
            LPAVaultShare vs = sharesByUser.get(user);
            if (vs == null || vs.getShares().compareTo(shares) < 0) {
                throw new LPAException(LPAErrorCodes.LPA_INSUFFICIENT_SHARES, "Insufficient shares");
            }
            BigDecimal price = getSharePrice();
            BigDecimal amount = shares.multiply(price, LPAConstants.MC).divide(LPAConstants.LPA_1E18, LPAConstants.MC);

            BigDecimal fee = amount.multiply(config.getWithdrawalFee(), LPAConstants.MC);
            BigDecimal afterFee = amount.subtract(fee, LPAConstants.MC);
            totalWithdrawalFeesCollected = totalWithdrawalFeesCollected.add(fee, LPAConstants.MC);

            vs.removeShares(shares);
            if (vs.getShares().signum() == 0) {
                sharesByUser.remove(user);
            }
            totalShares = totalShares.subtract(shares, LPAConstants.MC);
            if (totalShares.signum() < 0) totalShares = BigDecimal.ZERO;

            if (unallocatedTvl.compareTo(amount) < 0) {
                BigDecimal needed = amount.subtract(unallocatedTvl, LPAConstants.MC);
                drainFromStrategies(needed);
            }
            unallocatedTvl = unallocatedTvl.subtract(amount, LPAConstants.MC);
            if (unallocatedTvl.signum() < 0) unallocatedTvl = BigDecimal.ZERO;

            recordSnapshot();
            return afterFee;
        } finally {
            reentrancyLock = 0;
        }
    }

    private void drainFromStrategies(BigDecimal needed) {
        if (needed.signum() <= 0) return;
        List<LPAStrategy> list = new ArrayList<>(strategiesById.values());
        list.sort(Comparator.comparing(LPAStrategy::effectiveApr));
        BigDecimal remaining = needed;
        for (LPAStrategy s : list) {
            BigDecimal tvl = s.getTvl();
            if (tvl.signum() == 0) continue;
            BigDecimal take = tvl.min(remaining);
            s.removeTvl(take);
            unallocatedTvl = unallocatedTvl.add(take, LPAConstants.MC);
            remaining = remaining.subtract(take, LPAConstants.MC);
            if (remaining.signum() <= 0) break;
        }
    }

    // -------------------------------------------------------------------------
    // REBALANCE
    // -------------------------------------------------------------------------

    public synchronized void rebalance() {
        requireNotReentrant();
        if (unallocatedTvl.signum() <= 0) return;

        List<LPAStrategy> active = strategiesById.values().stream()
                .filter(s -> s.getState() == LPAStrategyState.ACTIVE)
                .collect(Collectors.toList());
        if (active.isEmpty()) return;

        LPAStrategy best = null;
        for (LPAStrategy s : active) {
            if (s.getRiskBand() != config.getDefaultBand()) continue;
            if (best == null || s.effectiveApr().compareTo(best.effectiveApr()) > 0) {
                best = s;
            }
        }
        if (best == null) {
            for (LPAStrategy s : active) {
                if (best == null || s.effectiveApr().compareTo(best.effectiveApr()) > 0) {
                    best = s;
                }
            }
        }
        if (best == null) return;
        BigDecimal move = unallocatedTvl;
        unallocatedTvl = BigDecimal.ZERO;
        best.addTvl(move);
        if (rebalanceEvents.size() < LPA_MAX_EVENTS) {
            rebalanceEvents.add(new LPARebalanceEvent(best.getId(), move, Instant.now().getEpochSecond()));
        }
        recordSnapshot();
    }

    public synchronized void rebalanceBands(Map<LPARiskBand, BigDecimal> targetWeights) {
        requireNotReentrant();
        BigDecimal tvl = totalVaultTvl();
        if (tvl.signum() == 0) return;

        Map<LPARiskBand, List<LPAStrategy>> byBand = new EnumMap<>(LPARiskBand.class);
        for (LPARiskBand rb : LPARiskBand.values()) {
            byBand.put(rb, new ArrayList<>());
        }
        for (LPAStrategy s : strategiesById.values()) {
            if (s.getState() != LPAStrategyState.ACTIVE) continue;
            byBand.get(s.getRiskBand()).add(s);
        }

        BigDecimal total = unallocatedTvl;
        for (LPAStrategy s : strategiesById.values()) {
            total = total.add(s.getTvl(), LPAConstants.MC);
            s.removeTvl(s.getTvl());
        }
        unallocatedTvl = BigDecimal.ZERO;

        for (Map.Entry<LPARiskBand, BigDecimal> e : targetWeights.entrySet()) {
            LPARiskBand band = e.getKey();
            BigDecimal weight = e.getValue();
            if (weight.signum() <= 0) continue;
            List<LPAStrategy> bandStrategies = byBand.get(band);
