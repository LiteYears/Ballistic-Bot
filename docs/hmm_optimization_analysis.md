# Trade History Replay & Optimization Analysis

This document reports on the quantitative optimization search conducted by replaying the trades history log (`trades_history.json`) against the market tick stream (`tick_data.csv`).

---

## 1. Replay Summary
* **Total Simulated Trades Replayed**: 833
* **Completed Trades (Excluding Ties)**: 827
* **Base Wins**: 439
* **Base Losses**: 388
* **Base Win Rate**: 53.08%
* **Base Expected PnL (assuming $10 trade sizing)**: $-280.20

---

## 2. Top 5 Single Optimization Filters
Below are the single filter conditions that eliminate the most losses while preserving the most winning trades:

| Rank | Filter Name | Description | Wins Cut | Losses Cut | New Win Rate | Net PnL Gain | Final Expected PnL |
| :--- | :--- | :--- | :---: | :---: | :---: | :---: | :---: |
| 1 | **Min Consistency > 0.75** | Skip trade if tick consistency score is <= 0.75 | 206 | 215 | 57.39% | **+$460.80** | $180.60 |
| 2 | **Min Consistency > 0.80** | Skip trade if tick consistency score is <= 0.80 | 244 | 237 | 56.36% | **+$369.20** | $89.00 |
| 3 | **Min Consistency > 0.70** | Skip trade if tick consistency score is <= 0.70 | 163 | 170 | 55.87% | **+$363.40** | $83.20 |
| 4 | **Min HVOI > 50** | Skip trade if smoothed optimality index (HVOI) is <= 50 | 368 | 334 | 56.80% | **+$322.40** | $42.20 |
| 5 | **Min HVOI > 55** | Skip trade if smoothed optimality index (HVOI) is <= 55 | 409 | 367 | 58.82% | **+$316.20** | $36.00 |

---

## 3. High-Priority State Breakdown

### HMM State Mappings & Performance:
| HMM State ID | Regime Classification | Total Trades | Wins | Losses | Win Rate |
| :---: | :--- | :---: | :---: | :---: | :---: |
| 0 | Expansion | 222 | 114 | 108 | 51.35% |
| 1 | Reversal | 331 | 175 | 156 | 52.87% |
| 2 | Reversal | 28 | 15 | 13 | 53.57% |
| 3 | N/A | 0 | 0 | 0 | 0.00% |
| 4 | Calm | 6 | 5 | 1 | 83.33% |
| 5 | Trending | 230 | 122 | 108 | 53.04% |
| 6 | Calm | 10 | 8 | 2 | 80.00% |
| 7 | N/A | 0 | 0 | 0 | 0.00% |

---

## 4. Key Takeaways & Dominant Filter Recommendation
* **The Dominant Filter**: **Min Consistency > 0.75**
* **The Impact**: Applying this condition as a trade execution filter would have eliminated **215 losses** while only cutting **206 wins**, increasing the win rate from **53.08%** to **57.39%** and boosting overall expected profit by **+$460.80**.
