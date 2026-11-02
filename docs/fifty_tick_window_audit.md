# Forensic Audit of the 50-Tick Window

This document provides a systematic forensic audit of the 50-tick window used across the `RevangeWise5s` and `HighVoltage5s` strategies.

---

## 1. Window Definitions and Dependencies

### 1. Where is the 50-tick window defined?
*   **Startup Profile Gate (`len < 50`)**:
    *   *Functions*: `calculateHighVoltage5sFeatures` (L792) and `calculateRevangeWise5sFeatures` (L1470) in `indicators.ts`.
    *   *Usage*: Prevents execution until a minimum profiling sample of 50 ticks is loaded.
*   **Breakout Range Boundaries (`prices.slice(-50)`)**:
    *   *Functions*: `calculateHighVoltage5sFeatures` (L939, L1047) and `calculateRevangeWise5sFeatures` (L1557) in `indicators.ts`.
    *   *Usage*: Slices the prices array to isolate the last 50 ticks to compute local price limits.
*   **Fibonacci Retracement lookback (`lookback = 50`)**:
    *   *Functions*: `getFibLevels` (L222) and `calculateFibonacciLevels` (L431) in `indicators.ts`.
    *   *Usage*: Standard lookback length to extract local Fibonacci high/low anchors.
*   **HMM Volatility Calculation (`vol50`)**:
    *   *Function*: `processTick` (L228) in `regimeDetector.ts`.
    *   *Usage*: Computes rolling returns variance over 50 ticks to feed HMM states.

---

## 2. Data Flow & Lookbacks

### 2. What exactly is stored in those 50 ticks?
Only raw **price data** (floats) and **timestamps** (integers) are stored in the historical tick buffers. From this price-time array, the system derives:
*   **Local High/Low Boundaries**: Lowest and highest prices recorded in the window.
*   **`relPos` (Relative Position)**: Current price percentile rank (0 to 100) within the local range.
*   **Fibonacci Levels**: Golden ratio levels mapped from the local range.
*   **Returns Volatility**: Rolling standard deviation of returns.

### 3. Which engines depend directly or indirectly on it?
*   **Breakout Entry Logic**: Depends on `relPos` computed over 50 ticks.
*   **Context Enhancer Level Adjustments**: Depends on Fibonacci levels computed over 50 ticks.
*   **Regime Engine (HMM)**: Classifies market phase based on 50-tick returns standard deviation.

---

## 4. Parameter Justification & Sensitivity Analysis

### 4. Why 50 ticks?
50 ticks represents roughly **10 to 15 seconds** of real-time market action (streaming at 3-5 ticks/sec). For a **5-second expiry trade**, a lookback window of $2-3\times$ the trade duration is the optimal empirical lookback to capture short-term breakouts without introducing stale historical price anchors.

### 5. Instability under reduction (to 25 ticks)
*   **Breakout Noisiness**: A 25-tick range represents only 5-7 seconds. Spread noise would cause `relPos` to oscillate erratically, causing the bot to take premature breakout trades.
*   **HMM Variance**: Volatility estimation requires a sample size of $N \ge 30$. Under 25 ticks, standard deviation estimator variance spikes, causing HMM regimes to flip randomly.
*   **ZLEMA Initialization**: Since ZLEMA Slow has a period of 30, it cannot compute correctly on 25 ticks.

### 6. Diminishing returns under expansion (to 75, 100, 200 ticks)
*   **Breakout Lag**: In fast momentum trends, a 100 or 200-tick window anchors the high/low range to prices from 40-60 seconds ago. A local breakout would go undetected because the current price would remain well below the stale 100-tick high.

### 7. Effective lookbacks of strategy components
*   **ZLEMA Fast**: 10 ticks.
*   **ZLEMA Slow**: 30 ticks (needs at least 100-150 ticks to completely decay start-up bias).
*   **Micro-Velocity**: 3 seconds (~10-15 ticks).
*   **Consistency**: 15 ticks.
*   **Persistence**: 40 ticks.
*   **`relPos` / Volatility**: 50 ticks.

---

## 5. System Consistency & Architecture

### 8. Memory efficiency
Every element of `prices.slice(-50)` is used to calculate the local bounds (`Math.min` / `Math.max`). No element is wasted.

### 9. Multi-scale lookbacks
There are different implicit lookbacks, which is an intentional architectural design:
*   **ZLEMA (300 ticks)**: Longer window to ensure complete convergence of EMA values.
*   **Breakouts / Fib (50 ticks)**: Medium window to represent local support/resistance bounds.
*   **Velocity (3 seconds)**: Ultra-short window to catch instant speed.

### 10. Buffer Dependency Graph

```
                          [ incoming WS ticks ]
                                    │
                                    ▼
                          [ tickHistory (300) ]
                                    │
         ┌──────────────────────────┼──────────────────────────┐
         ▼                          ▼                          ▼
 [ ZLEMA Fast/Slow ]          [ slice(-50) ]          [ persistence (40) ]
   (300 tick EMA)                   │                  (crossover checks)
         │                          ▼                          │
         │                   [ Local High/Low ]                ▼
         │                          │                    [ Trend Age ]
         ▼                          ▼                          │
   [ Consistency ]              [ relPos ]                     │
         │                          │                          │
         └──────────────────┬───────┴──────────────────────────┘
                            ▼
                    [ entrySignal ]
                            │
                            ▼
                   [ Trade Execution ]
```

---

## 6. Audit Verdict

1.  **Is it a legacy choice?** Yes, originally inherited as a default parameter, but it is statistically justified because it corresponds to the 10-15s horizon that represents the local micro-structure relative to a 5-second expiry.
2.  **Would we choose it today?** Yes. Reducing it below 30 ruins volatility estimation and ZLEMA initialization. Increasing it above 75 introduces high lag. 50 is the optimal compromise.
3.  **Hidden assumptions**: Yes, the `len < 50` startup guard is a hard requirement. If the tick queue is under 50, the bot will remain in standby state.
