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
