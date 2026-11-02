# Strategy Silent Assumptions Audit Report

This report documents a systematic audit of all silent assumptions in the `RevangeWise5s` trading logic, verifying their safety and the presence of appropriate fallback guards.

---

## 1. Core Assumption Scans

### 1. Volatility is Strictly Positive (`volatility > 0`)
*   **Dependency**: Spike filters, average difference calculations, and the exhaustion slope ratio.
*   **Risk**: If price is flat (closed markets, network drop, data gaps), `avgDiff` can equal `0`, leading to `NaN` or `Infinity` divisions.
*   **Verification**: **SAFE**. We implemented a strict division guard:
    ```typescript
    } else if (avgDiff <= 0) {
      lastFilterReason = "Zero Volatility Regime (Division Guard)";
    }
    ```
    This stops the execution chain before any division occurs.

### 2. A Valid Trend Direction Exists (`trend !== 'NONE'`)
*   **Dependency**: Crossover scoring and breakout validation logic.
*   **Risk**: ZLEMA Fast and Slow lines can converge exactly (`zFast === zSlow`), producing `NONE` trend.
*   **Verification**: **SAFE**. The breakout execution blocks explicitly check:
    ```typescript
    if (trend === 'UP') { ... } else if (trend === 'DOWN') { ... }
    ```
    If `trend === 'NONE'`, it naturally falls through with no signal generation.

### 3. Tick and Candle History Caches exceed Lookbacks (`candles.length >= lookback`)
*   **Dependency**: Indicator calculations (up to 150 ticks) and swing high/low structure confirmations (up to 50 candles).
*   **Risk**: At startup, the bot has zero historical ticks or candles.
*   **Verification**: **SAFE**. Strict size guards are enforced:
    *   `indicators.ts` startup guard: `if (len < 50) return ...`
    *   `backtest_revangewise.ts` startup guard: `if (tickHistory.length < 150) continue;`
    *   `structureEngine.ts` startup guard: `if (candles.length < 5) return;`
    *   `ContextEnhancer.ts` guards: `if (candles.length >= 2) { ... }`

### 4. ZLEMA curves are fully initialized
*   **Dependency**: EMA initialization curves.
*   **Risk**: Standard EMA calculations require price histories at least as long as the period.
*   **Verification**: **SAFE**. Since the slowest ZLEMA period is `30` (Slow), the strategy startup guard of `len >= 150` ticks guarantees the EMA curve has completely converged and is stable before any trades are allowed.

### 5. Consolidated Support and Resistance Memory Zones Exist
*   **Dependency**: Near support/resistance lookups in `ContextEnhancer` blocks.
*   **Risk**: At startup, before levels have been touched or swing points generated, `SRMemoryEngine` is empty (`zones = []`).
*   **Verification**: **SAFE**. `ContextEnhancer` checks strictly require object presence before checking properties:
    ```typescript
    if (nearSupport) { ... }
    if (nearResistance) { ... }
    ```
    If no zones exist, the distance checks are skipped.

### 6. Timestamps are Monotonic and Chronological
*   **Dependency**: `TrendAgeEngine` duration and `CandleEngine` boundary alignments.
*   **Risk**: Network packet jitter can occasionally cause ticks to arrive out-of-order (e.g. `tick[i].time < tick[i-1].time`).
*   **Verification**: **SAFE**:
    *   `TrendAgeEngine` checks for negative differences using `Math.max(0, ...)` bounds.
    *   `CandleEngine` includes a dedicated out-of-order routing loop to update past candles instead of corrupting current candles.

---

## 2. Recommendation Matrix

All analyzed assumptions are fully protected by active runtime guards. No additional logic or changes are required.
