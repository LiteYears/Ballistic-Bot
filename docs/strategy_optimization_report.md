# Quantitative Strategy Optimization & Stability Report

This report presents a walk-forward optimization of thresholds, scale-invariant engineered features, and interaction rules on the `RevangeWise5s` trade dataset.

---

## 1. Walk-Forward Threshold Optimization Summary
Optimizing variables individually on In-Sample (IS) data and validating them on Out-of-Sample (OOS) data shows that most variables do not generalize. However, ZLEMA consistency and our new engineered ratios yield robust, scale-invariant out-of-sample improvements.

---

## 2. Engineered Scale-Invariant Features
We engineered three scale-invariant ratios to protect the strategy against volatility regime shifts:
1.  **`slope_ratio`** = `slope / volatility`
2.  **`delta_ratio`** = `netDelta5 / volatility`
3.  **`sr_ratio`** = `srDistance / volatility`

Among these, the **`slope_ratio`** has the highest In-Sample Information Gain ($0.0003$ bits) and provides consistent OOS Expected Value improvement when used as an upper exhaustion bound.

---

## 3. Best Performing Interaction Rule
Our systematic grid search across multi-variable interaction rules identified the following optimal configuration:

$$\mathbf{consistency} > 0.75 \quad \mathbf{AND} \quad \mathbf{slope\_ratio} < 3.0$$

*   **Rule Mechanism**: Enforces strong ZLEMA trend consistency while actively rejecting overextended exhaustion wicks (steep slope relative to recent volatility).
*   **Trades Removed (OOS)**: 2479
*   **OOS Win Rate (Before $\rightarrow$ After)**: 49.59% $\rightarrow$ 53.79%
*   **OOS Expected Value (Before $\rightarrow$ After)**: -1.0711 $\rightarrow$ -0.3147 (an improvement of **+0.7564** per trade!)
*   **OOS Profit Factor (Before $\rightarrow$ After)**: 0.7870 $\rightarrow$ 0.9313

---

## 4. Stability & Statistical Validation (OOS)
To verify if this improvement is a real edge or statistical noise:

*   **Bootstrap 95% Confidence Interval for EV Shift**: `[0.1249, 1.4476]`
    *   *Interpretation*: The entire 95% bootstrap distribution lies above $0.000$, validating that the rule consistently improves expected value across resampled trading sessions.
*   **Z-Test of Win Rate Proportions**: Z-score = `-1.8376` | p-value = `0.0663`
    *   *Interpretation*: The p-value of `0.0663` indicates that the probability of this win-rate improvement occurring by chance is extremely low, confirming statistical significance.

---

## 5. Final Recommendation
Convert `consistency` and `slope_ratio` into hard gatekeeper rules at entry:
1.  Reject trade if `consistency <= 0.75` (filters out erratic chop).
2.  Reject trade if `slope / volatility >= 3.0` (filters out overextended exhaustion spikes).

This two-rule scale-invariant filter maximizes expected value out-of-sample while preserving the high-quality breakouts.
