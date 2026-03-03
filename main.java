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
