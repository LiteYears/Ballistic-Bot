# Quantitative Research: Systematic Horizon Analysis

This study evaluates if expanding the option expiration/holding period beyond 5 seconds reveals a persistent predictive edge in `HUNTERXHUNTER`'s indicators.

### 📊 Horizon Comparison Table

| Horizon | Raw Trades | Raw Win Rate | Filtered Trades | Filtered Win Rate | Filtered Edge |
| :---: | :---: | :---: | :---: | :---: | :---: |
| 5s | 72338 | 50.00% | 34398 | **50.13%** | **+0.13%** |
| 10s | 72395 | 49.75% | 34412 | **49.78%** | **+0.03%** |
| 15s | 72424 | 49.99% | 34436 | **49.97%** | **-0.02%** |
| 30s | 72468 | 49.98% | 34453 | **49.71%** | **-0.27%** |
| 60s | 72516 | 50.05% | 34468 | **49.79%** | **-0.26%** |

## 💡 Key Quant Takeaways

*   **Optimal Holding Horizon:** **5 seconds** produced the highest filtered win rate of **50.13%**.
*   ⚠️ **No Edge Found at Any Horizon:** Even at longer holding periods, performance remains locked near 50-51%. This confirms that the current feature set has no structural predictive power across any short-term duration.
