# Olymp Trade Bot Simulator: Project & Architecture Overview

This document provides a comprehensive technical overview of the automated trading bot simulator codebase, detailing its structure, modules, tech stack, current tasks, and system capabilities.

---

## 1. Project Overview
The primary purpose of this project is to simulate and automate high-frequency binary trades on the Olymp Trade platform. 

### Core Goals:
* **Low Latency Connection**: Connect directly to the broker's WebSocket server to consume real-time tick feeds.
* **Algorithmic Profiling**: Maintain sliding buffers of ticks and OHLC candles to compute technical indicators and swing structures in real-time.
* **Adaptive Filtering**: Leverage Hidden Markov Models (HMM) and Context Enhancers to identify market regimes (Trending, Reversal, Chaotic) and prevent trades in range chop.
* **Automated Order Execution**: Automatically place trades on Demo/Real accounts based on multi-strategy signals (such as `HIGHVOLTAGE5S`, `RIDE_THE_HORSE`, `FLASH`, or `SIMPLE30S`).

---

## 2. Architecture & Directory Structure

```
olymp-trade-bot-simulator/
├── cli_config.json               # Persistent configuration settings
├── hmm_model.json                # Pre-trained Hidden Markov Model (HMM) parameters
├── package.json                  # Dependencies and run scripts
├── src/                          # main source code (TypeScript)
│   ├── cli.ts                    # Entry point: Dashboard UI, keypress listener, WS loop
│   ├── indicators.ts             # Mathematical indicators & Optimality index (HVOI)
│   ├── contextEnhancer.ts        # Context diagnostics & entry block rules
│   ├── candleEngine.ts           # Tick-to-Candle OHLC aggregator
│   ├── structureEngine.ts        # Swing High/Low detection engine
│   ├── levelEngine.ts            # Psychological grid level tracker
│   ├── srMemoryEngine.ts         # Consolidated Support & Resistance memory zone
│   ├── regimeDetector.ts         # HMM model loader & state inference engine
│   └── trendAgeEngine.ts         # Trend age and duration tracker
```

### Key Modules:
1. **[cli.ts](file:///root/OlympBackup/olymp-trade-bot-simulator/src/cli.ts)**: Handles the central command loop. It manages the CLI dashboard, user shortcuts (toggle bot running `[S]`, configure settings `[P]`, toggle accounts `[D]`, toggle SuperBoost `[B]`), reads ticks from WebSockets, and dispatches them to indicators and execution paths.
2. **[indicators.ts](file:///root/OlympBackup/olymp-trade-bot-simulator/src/indicators.ts)**: Implements calculations like ZLEMA, Ride the Horse feature mappings, and the **HV Window** suitability score (which uses exponentially-decayed body ratios, dominance, and volatility metrics).
3. **[contextEnhancer.ts](file:///root/OlympBackup/olymp-trade-bot-simulator/src/contextEnhancer.ts)**: Evaluates structural danger zones (Bearish/Bullish Rejection wicks, entering directly into Support/Resistance zones) and adjusts signal confidence.
4. **[candleEngine.ts](file:///root/OlympBackup/olymp-trade-bot-simulator/src/candleEngine.ts)**: Stores up to 300 completed 5-second candles in a sliding window to provide historical context.
5. **[structureEngine.ts](file:///root/OlympBackup/olymp-trade-bot-simulator/src/structureEngine.ts)**: Finds swing peaks and troughs, cleaning up entries that fall outside the 300-candle active window.

---

## 3. Tech Stack

* **Language**: TypeScript / JavaScript (ES Module target).
* **Runtime / Compiler**:
  * **Node.js** as the execution environment.
  * **tsx** (TypeScript Execute) for running CLI files on the fly.
  * **TypeScript Compiler (tsc)** for type-checking.
* **Dependencies**:
  * `ws`: Low-overhead WebSocket client for low-latency JSON communication with the broker.
  * `puppeteer-core`: Automation library (used if logging in or retrieving tokens).
  * `dotenv`: Environment configuration variables.

---

## 4. Current Status & Recent Tasks

We have just completed several structural optimizations for the **`HIGHVOLTAGE5S`** strategy:
1. **SuperBoost Mode**: Added a high-momentum trading toggle (Key `B`) that executes trades when the HV Window score $>65\%$.
2. **HV Window Trade Alignment**: Configured the bot to block trades in standard mode if the HV Window indicates `AVOID` (suitability score $<45\%$).
3. **Time-Decay Recency Weighting**: Rewrote the HVOI indicators to use exponentially-decayed weighting ($\lambda = 0.82$) so that the latest candles determine the score, reducing lagging entry/exit traps.
4. **Adaptive S/R Corridor**: Converted the hardcoded corridor compression threshold to an adaptive formula: $\max(0.20, 4.0 \times \text{Average Candle Range})$.
5. **Structure Alignment**: Fixed the `Blocked: Trend Structure Contradiction` bug by clearing stale swings older than the 300-candle active window and bypassing blocks during breakouts.
6. **Round-Number Bounce (Psychological Grid)**: Implemented psychological level bounce trading, active only when Boost mode is toggled ON. When the price enters a psychological level's tolerance zone (multiples of spacing, e.g., 4515.00) and bounces (exits the same side it entered), the bot executes a trade in the direction of the bounce (opposite the approach) while respecting WS latency guard, trade limits, and cooldown.
7. **Low-Latency Socket & Serialization Optimizations**: Configured `TCP_NODELAY` (`setNoDelay(true)`) on the WebSocket socket client, disabling Nagle's algorithm to dispatch packets instantly. Replaced generic JSON serialization with a pre-formatted string-concatenation payload template inside `placeTrade()` to completely bypass memory copy and serialization overhead, achieving sub-millisecond execution times.
8. **Professional Trading Journal**: Integrated comprehensive trade journaling into the `trades_history.json` logger. The bot now logs strategy, account group/ID, WS latency, HMM state (index, name, confidence), HVOI index, indicator features (volatility, tape speed, consistency, compression, exhaustion, slope), nearest Support & Resistance levels/distances, and ZLEMA trend details (direction, age) at the exact moment of entry.

---

## 5. Tool Capabilities in this Workspace

* **Filesystem Operations**: Full access to read, modify, and create source files inside the workspace.
* **Terminal Shell**: Execution environment to run compiler checks (`npm run lint`), start the bot (`npm run cli`), or log in (`npm run login`).
* **Background Tasks**: Ability to run the bot or compilers in the background and receive status events.
