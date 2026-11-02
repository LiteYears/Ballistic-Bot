# Quant Report: Regime-Adaptive Reversal Strategy Backtest
This report evaluates the **72565** resolved candidate setups generated over the 1.1 million tick history.
*   **Raw Crossover Baseline:** 72565 trades | Win Rate: **49.96%**

## 🔄 1. Reversing Weak Regimes (Trade Flipping)
We analyze if reversing the trade direction in poor-performing categories yields high-probability outcomes.

| Feature | Category | Original Trades | Original WR | Reversed WR | Expected Delta |
| :--- | :--- | :---: | :---: | :---: | :---: |
| `activeLandmarkType` | REVERSAL_POINT | 24931 | 50.01% | **49.99%** | Keep Original |
| `activeLandmarkType` | SWING_LOW | 20324 | 50.18% | **49.82%** | Keep Original |
| `activeLandmarkType` | SWING_HIGH | 20138 | 49.99% | **50.01%** | Keep Original |
| `activeLandmarkType` | ROUND_LEVEL | 7172 | 49.14% | **50.86%** | Keep Original |
| `phase` | EXPANSION | 40554 | 49.83% | **50.17%** | Keep Original |
| `phase` | MOMENTUM | 19059 | 49.96% | **50.04%** | Keep Original |
| `phase` | LEVEL_TEST | 7722 | 50.19% | **49.81%** | Keep Original |
| `phase` | EXHAUSTION | 4383 | 50.79% | **49.21%** | Keep Original |
| `phase` | PRESSURE_BUILD | 842 | 50.24% | **49.76%** | Keep Original |
| `micro_urgency` | HESITANT | 58499 | 49.97% | **50.03%** | Keep Original |
| `micro_urgency` | STEADY | 14016 | 49.90% | **50.10%** | Keep Original |
| `micro_participation` | THIN | 70561 | 49.94% | **50.06%** | Keep Original |
| `micro_participation` | ACTIVE | 1427 | 49.75% | **50.25%** | Keep Original |
| `micro_participation` | DORMANT | 523 | 53.15% | **46.85%** | Keep Original |
| `energyState` | STABLE | 66605 | 49.96% | **50.04%** | Keep Original |
| `energyState` | INCREASING | 5050 | 50.10% | **49.90%** | Keep Original |
| `energyState` | EXPLODING | 508 | 48.82% | **51.18%** | Keep Original |
| `energyState` | LEAKING | 278 | 51.44% | **48.56%** | Keep Original |
| `energyState` | COLLAPSING | 124 | 50.00% | **50.00%** | Keep Original |
| `direction` | DOWN | 36551 | 50.21% | **49.79%** | Keep Original |
| `direction` | UP | 36014 | 49.72% | **50.28%** | Keep Original |

## 🧭 2. Regime-Adaptive Switching via `meanReversionProb`
HunterXHunter is designed for Trend Breakouts. However, when `meanReversionProb` is high, price is highly likely to revert rather than follow through. We test a threshold-based switch:
*   If `meanReversionProb < Threshold`: Trade **Breakout** (Original Direction)
*   If `meanReversionProb >= Threshold`: Trade **Mean Reversion** (Reversed Direction)

| Threshold | Breakout Trades | Breakout WR | Mean-Rev Trades | Mean-Rev WR | Combined Trades | Combined WR | Improvement |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 30% | 21705 | 49.38% | 50860 | 49.78% | 72565 | **49.66%** | **-0.30%** |
| 35% | 26469 | 49.25% | 46096 | 49.62% | 72565 | **49.49%** | **-0.48%** |
| 40% | 29592 | 49.23% | 42973 | 49.53% | 72565 | **49.40%** | **-0.56%** |
| 45% | 33566 | 49.30% | 38999 | 49.47% | 72565 | **49.39%** | **-0.57%** |
| 50% | 36994 | 49.44% | 35571 | 49.49% | 72565 | **49.46%** | **-0.50%** |
| 55% | 42364 | 49.52% | 30201 | 49.41% | 72565 | **49.47%** | **-0.49%** |
| 60% | 45030 | 49.58% | 27535 | 49.41% | 72565 | **49.51%** | **-0.45%** |
| 65% | 50383 | 49.77% | 22182 | 49.60% | 72565 | **49.72%** | **-0.24%** |
| 70% | 53473 | 49.77% | 19092 | 49.49% | 72565 | **49.69%** | **-0.27%** |
| 75% | 57741 | 49.87% | 14824 | 49.67% | 72565 | **49.83%** | **-0.14%** |

## 🚀 3. Composite Regime-Adaptive Strategy Backtest
We simulate a composite strategy where:
1.  **Flipped Categories:** If landmark is `SWING_HIGH` or microstructure urgency is `HESITANT`, we REVERSE the trade.
2.  **Mean Reversion Switch:** For other categories, if `meanReversionProb >= 75%`, we REVERSE the trade; otherwise we trade original direction.

*   **Total Composite Trades:** 72565 (100% trade coverage maintained!)
*   **Composite Win Rate:** **50.01%** (Wins: 36288 | Losses: 36277)
*   **Performance Improvement over Crossover Baseline:** **+0.04%** absolute win rate increase!

## 💡 Quant Conclusion
⚠️ **Conclusion: Adaptive reversing does not yield a significant statistical edge.** The underlying features remain descriptive.
