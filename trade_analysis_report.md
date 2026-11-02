# Quantitative Analysis of Trade History: Winning vs. Losing Characteristics

This report evaluates the **52** resolved trades to determine what winning and losing setups have in common.

**Baseline Stats:**
*   Total Trades: 52
*   Wins: 22 (42.31%)
*   Losses: 30 (57.69%)

## 📈 1. Continuous Features: Differences in Means
Ranked by statistical significance ($p$-value). Low $p$-values ($<0.05$) indicate that the feature is strongly linked to the outcome.

| Rank | Feature | Winner Mean | Loser Mean | Difference | $p$-value | Significance |
| :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| 1 | `meanReversionProb` | 58.0909 | 48.4000 | +9.6909 | 0.0108 | ⭐⭐ High |
| 2 | `persistence` | 12.9545 | 16.3667 | -3.4121 | 0.0605 | ⭐ Marginal |
| 3 | `lm_acceleration` | 3.9091 | -0.6000 | +4.5091 | 0.1445 | None |
| 4 | `micro_acceleration` | 3.9091 | -0.6000 | +4.5091 | 0.1445 | None |
| 5 | `tapeSpeed` | 5.0000 | 5.3000 | -0.3000 | 0.1928 | None |
| 6 | `velocity` | 1.6667 | 1.7667 | -0.1000 | 0.1928 | None |
| 7 | `consistency` | 0.8091 | 0.8822 | -0.0731 | 0.2064 | None |
| 8 | `displacement10` | 66.0243 | 53.3751 | +12.6493 | 0.2321 | None |
| 9 | `energy` | 97.8636 | 100.0000 | -2.1364 | 0.3287 | None |
| 10 | `microstructureHealth` | 86.3636 | 90.0000 | -3.6364 | 0.3287 | None |
| 11 | `micro_exhaustion` | 18.6364 | 15.0000 | +3.6364 | 0.3287 | None |
| 12 | `liquidityStability` | 92.0455 | 95.0000 | -2.9545 | 0.3287 | None |
| 13 | `breakoutProbability` | 59.1818 | 61.1000 | -1.9182 | 0.3692 | None |
| 14 | `bounceProbability` | 40.8182 | 38.9000 | +1.9182 | 0.3692 | None |
| 15 | `suitabilityScore` | 41.5000 | 43.7333 | -2.2333 | 0.3966 | None |
| 16 | `micro_hesitation` | 33.2727 | 29.3333 | +3.9394 | 0.4170 | None |
| 17 | `lm_momentumHealth` | 75.7273 | 79.0333 | -3.3061 | 0.5701 | None |
| 18 | `lm_instability` | 24.4091 | 21.7667 | +2.6424 | 0.6209 | None |
| 19 | `lm_directionalEfficiency` | 27.9545 | 26.4000 | +1.5545 | 0.7204 | None |
| 20 | `commitment` | 27.9545 | 26.4000 | +1.5545 | 0.7204 | None |
| 21 | `micro_randomness` | 72.0455 | 73.6000 | -1.5545 | 0.7204 | None |
| 22 | `noiseLevel` | 72.0455 | 73.6000 | -1.5545 | 0.7204 | None |
| 23 | `lm_randomness` | 72.0455 | 73.6000 | -1.5545 | 0.7204 | None |
| 24 | `breakoutProb` | 14.5909 | 13.7667 | +0.8242 | 0.7553 | None |
| 25 | `volatility` | 7.7804 | 7.8985 | -0.1181 | 0.8266 | None |
| 26 | `levelRespectRate` | 0.4091 | 0.4278 | -0.0187 | 0.8564 | None |
| 27 | `trendQuality` | 23.5455 | 24.2667 | -0.7212 | 0.8582 | None |
| 28 | `lm_pressure` | -7.7273 | -10.0333 | +2.3061 | 0.8752 | None |
| 29 | `distLandmark` | 3.0973 | 3.2847 | -0.1873 | 0.9100 | None |
| 30 | `micro_persistence` | -12.7273 | -14.6667 | +1.9394 | 0.9259 | None |
| 31 | `relPos` | 40.7830 | 41.5455 | -0.7625 | 0.9565 | None |
| 32 | `mci` | 53.1364 | 53.2000 | -0.0636 | 0.9794 | None |
| 33 | `slope` | -4.0602 | -4.0572 | -0.0030 | 0.9996 | None |

## 📊 2. Categorical Features & Win Rates
This section shows the performance of categorical market states.

### Feature: `direction`
| Category | Wins | Losses | Total Trades | Win Rate |
| :--- | :---: | :---: | :---: | :---: |
| DOWN | 13 | 18 | 31 | 41.94% |
| UP | 9 | 12 | 21 | 42.86% |

### Feature: `trend`
| Category | Wins | Losses | Total Trades | Win Rate |
| :--- | :---: | :---: | :---: | :---: |
| DOWN | 13 | 17 | 30 | 43.33% |
| UP | 9 | 13 | 22 | 40.91% |

### Feature: `compression`
| Category | Wins | Losses | Total Trades | Win Rate |
| :--- | :---: | :---: | :---: | :---: |
| False | 22 | 30 | 52 | 42.31% |

### Feature: `exhaustion`
| Category | Wins | Losses | Total Trades | Win Rate |
| :--- | :---: | :---: | :---: | :---: |
| False | 21 | 30 | 51 | 41.18% |
| True | 1 | 0 | 1 | 100.00% |

### Feature: `phase`
| Category | Wins | Losses | Total Trades | Win Rate |
| :--- | :---: | :---: | :---: | :---: |
| MOMENTUM | 16 | 25 | 41 | 39.02% |
| EXPANSION | 4 | 5 | 9 | 44.44% |
| EXHAUSTION | 1 | 0 | 1 | 100.00% |
| LEVEL_TEST | 1 | 0 | 1 | 100.00% |

### Feature: `activeLandmarkType`
| Category | Wins | Losses | Total Trades | Win Rate |
| :--- | :---: | :---: | :---: | :---: |
| SWING_LOW | 11 | 13 | 24 | 45.83% |
| ROUND_LEVEL | 6 | 8 | 14 | 42.86% |
| SWING_HIGH | 4 | 9 | 13 | 30.77% |
| REVERSAL_POINT | 1 | 0 | 1 | 100.00% |

### Feature: `decisionDelay`
| Category | Wins | Losses | Total Trades | Win Rate |
| :--- | :---: | :---: | :---: | :---: |
| False | 22 | 30 | 52 | 42.31% |

### Feature: `micro_participation`
| Category | Wins | Losses | Total Trades | Win Rate |
| :--- | :---: | :---: | :---: | :---: |
| THIN | 21 | 30 | 51 | 41.18% |
| DORMANT | 1 | 0 | 1 | 100.00% |

### Feature: `micro_urgency`
| Category | Wins | Losses | Total Trades | Win Rate |
| :--- | :---: | :---: | :---: | :---: |
| STEADY | 16 | 20 | 36 | 44.44% |
| HESITANT | 6 | 10 | 16 | 37.50% |

### Feature: `energyState`
| Category | Wins | Losses | Total Trades | Win Rate |
| :--- | :---: | :---: | :---: | :---: |
| STABLE | 18 | 23 | 41 | 43.90% |
| INCREASING | 4 | 7 | 11 | 36.36% |

## 💡 3. Key Findings & Strategic Insights
Based on the data, here are the most significant observations:

### Significant Continuous Variables:
*   **`meanReversionProb`**: Winners have a **higher** value (58.0909) compared to losers (48.4000) with $p=0.0108$.
*   **`persistence`**: Winners have a **lower** value (12.9545) compared to losers (16.3667) with $p=0.0605$.
