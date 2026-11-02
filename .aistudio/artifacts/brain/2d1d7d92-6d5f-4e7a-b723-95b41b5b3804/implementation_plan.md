# OlympQuant Terminal: Modular Quantitative Trading Bot for Android

Transform the legacy CLI and Python Olymp Trade bot ecosystem into a native Android quantitative trading workstation built with Jetpack Compose, featuring dual-layer authentication, real-time low-latency WebSocket execution, an audited quantitative indicator and Hidden Markov Model (HMM) pipeline, and a hot-swappable modular strategy builder.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The following architectural and product decisions reflect your confirmed choices from Phase 1 and govern the full Android transformation:

- **Confirmed Decision 1 — Authentication & Broker Session**: Dual-layer security combining an **App Account Login** (local profile & credential vault with PIN/biometric lock) and **Olymp Trade Broker Authentication** supporting both direct `access_token` entry and an integrated **Embedded WebView Token Interceptor** that extracts the session `access_token` cookie automatically upon signing into Olymp Trade.
- **Confirmed Decision 2 — Modular Strategy System**: Ships with all audited built-in strategies (`RevangeWise5s`, `HighVoltage5s`, `RideTheHorse`, `Flash`, `Simple30s`, and `PsychGridBounce`) as modular plugins, alongside a **Custom Rule & Filter Composer** where users can create, clone, toggle, reorder, and delete custom strategies by combining entry triggers and hard gatekeeper filters.
- **Confirmed Decision 3 — Market Data & Execution Mode**: **Live Olymp Trade WebSocket Engine Only** (`wss://ws.olymptrade.com/otp`), implementing the complete event protocol (`e:98` startup subscriptions, `e:12/280` live tick feeds, `e:10/1003` historical candles, `e:55/1068/1043` Demo/Real account discovery and balance sync, `e:90` latency heartbeat, and `e:23/21/22/26` sub-millisecond order placement and settlement tracking).
- **Confirmed Decision 4 — Unified Multi-Mode Jetpack Compose UI**: Combines all three visual paradigms into a cohesive multi-tab workstation with a switchable density toggle:
  1. **Pro Dark Quant Terminal**: Real-time Canvas candlestick + tick micro-chart with ZLEMA Fast/Slow curves, Support/Resistance memory corridors, Psychological Grid lines, and HVOI / HMM gauges.
  2. **Minimalist Execution Deck**: Focused high-contrast signal cards, instant Demo/Real account switcher, SuperBoost (`B`) mode toggle, one-tap manual/auto execution controls, and live P&L ticker.
  3. **Deep Analytics & HMM Workstation**: 8-state HMM probability distribution matrix, decision pipeline gatekeeper audit inspector, Modular Strategy Studio, and searchable Trade Journal with exportable telemetry.

---

## 1. Overview & Core Concept

- **What It Does**: **OlympQuant Terminal** is a native Android algorithmic trading application that connects directly to Olymp Trade's real-time WebSocket stream, processes every incoming tick through a multi-engine quantitative pipeline (5-second OHLC aggregation, ZLEMA Fast/Slow trend tracking, Swing High/Low structure memory, Psychological Round-Number Grid, and an 8-state Gaussian Hidden Markov Model), and automatically or semi-automatically executes binary/digital trades across Demo and Real accounts using user-configurable modular strategies.
- **Target Audience / Persona**: Quantitative and algorithmic traders who want full mobile command over their Olymp Trade execution pipeline, complete visibility into why a trade was accepted or blocked, and the freedom to add, tune, or remove strategy modules on the fly without editing code.
- **Key Value**: Eliminates the fragility of desktop headless scripts and terminal-only dashboards by delivering sub-millisecond pre-formatted WebSocket execution, mathematically audited anti-chop filters (removing score inflation and redundant checks discovered in the codebase audit), and an interactive modular strategy engine in a smooth 60fps Jetpack Compose interface.

---

## 2. User Experience & Visual Design

### Key User Flows

1. **Onboarding, App Auth & Broker Connection Flow**:
   - User creates or signs into their local **Trader Vault Account** (email/username + password with optional quick PIN).
   - User connects their Olymp Trade session via two seamless options:
     - **Option A — Embedded WebView Sign-In**: Opens the official Olymp Trade login page inside a sandboxed Android `WebView` equipped with `CookieManager` inspection that detects the `access_token` cookie the moment authentication succeeds and auto-populates the secure credential store.
     - **Option B — Direct Token Paste**: Paste an existing `access_token` string and custom WebSocket URI parameters.
   - Upon connection, the app runs the browser-mimicking initialization sequence (`e:98` subscriptions, `e:90` initial pings, `e:1068` Demo & Real account discovery, and `e:1043/55` real-time balance synchronization).

2. **Terminal & Execution Deck Flow (Compact vs. Pro View)**:
   - User selects the active asset pair (e.g., `LATAM_X`, `ASIA_X`, `EURUSD`, `GBPUSD`, `CRYPTO_X`) and toggles between **Pro Quant Terminal** and **Minimalist Execution Deck** via a top-bar view switcher.
   - **Pro Quant Terminal** displays a custom-drawn `Canvas` chart rendering either the 300-candle (5s OHLC) window or the 150-tick micro-window, overlaid with ZLEMA-10 (cyan), ZLEMA-30 (amber), S/R memory corridors (shaded bands), and Psychological Grid levels.
   - Below the chart, real-time telemetry pills display **WS Ping Latency (ms)**, **HVOI Session Quality (`OPTIMAL` / `NEUTRAL` / `AVOID`)**, **HMM Regime State & Confidence**, and the **Live Pipeline Gatekeeper Checklist** (showing in real time which filter is passing or blocking entry).
   - User toggles **Bot Running (`START / STOP`)**, **SuperBoost Mode** (executes high-momentum setups when HVOI $> 65\%$ and enables Psychological Grid Round-Number Bounce), and **Account Group (`DEMO` vs `REAL`)** with a confirmation guard when switching to Real funds.

3. **Modular Strategy Studio Flow (Add, Edit, Clone, Remove)**:
   - User navigates to the **Strategies** tab to view all installed strategies as interactive cards showing active status, target duration (e.g., 5s, 30s, 60s), stake amount, cooldown, and active filter count.
   - User can **Toggle ON/OFF** any preset strategy (`RevangeWise5s`, `HighVoltage5s`, `RideTheHorse`, `Flash`, `Simple30s`, `PsychGridBounce`) or tap **"+ New Strategy"** / **"Clone & Customize"** to launch the **Modular Strategy Composer**.
   - Inside the Composer, users configure:
     - **Core Execution Parameters**: Strategy Name, Trade Duration ($5\text{s} - 300\text{s}$), Stake Amount, Cooldown ($\text{ms}$), and Minimum Confidence Threshold.
     - **Entry Trigger Module**: Select from Breakout (`relPos` bounds), ZLEMA Crossover, Candlestick Body Momentum, or Psychological Level Bounce.
     - **Modular Filter Chain (Add / Remove / Tune Thresholds)**: Add or remove individual filter modules—`ZLEMA Consistency Gate` ($> 0.75$), `Scale-Invariant Slope Ratio Gate` ($\text{slope}/\text{volatility} < 3.0$), `Macro HTF Bias Alignment`, `NetDelta5 Confirmation`, `Trend Persistence Gate` ($\ge 8$), `Micro-Velocity Gate` ($\ge 1.5\text{ T/s}$), `Latency Guard` ($\le 120\text{ms}$), `HVOI Session Quality Guard`, `Context Enhancer S/R Block`, and `HMM Regime Adaptive Mode` (`OFF`, `SKIP_BAD`, `REVERSE_BAD`, `ADAPTIVE_ALL`).

4. **Deep HMM Telemetry & Trade Journal Flow**:
   - In the **HMM & Audit** tab, users inspect the live 8-state HMM probability bars (`Expansion`, `Reversal`, `Calm`, `Trending`), transition persistence ($A_{ii}$), and the live 12-feature scaled vector (`return_1`, `return_5`, `return_10`, `rolling_vol_10/20/50`, `streak_length`, `velocity`, `acceleration`, etc.).
   - In the **Journal** tab, every executed trade and its exact entry snapshot (strategy name, account group, WS latency, HMM state/confidence, HVOI score, volatility, consistency, slope ratio, nearest S/R distance, ZLEMA trend age, and WIN/LOSS/TIE outcome from `e:26`) is stored locally in Room and summarized with live Win Rate, Expected Value (EV), Profit Factor, and Max Drawdown metrics.

### Visual Identity & Theme

- **Aesthetic Direction**: **Institutional Quant Dark Terminal**—precision-engineered dark surfaces, crisp 1dp hairline borders, luminous semantic signal accents, and zero visual clutter.
- **Color Palette & Mood**:
  - `--background` (`#070B12`): Deep obsidian slate canvas for zero eye strain during high-frequency monitoring.
  - `--surface` (`#0F1623`) & `--surface-variant` (`#162032`): Elevated card containers with subtle top-edge luminance.
  - `--primary` (`#00E5FF`): Electric cyan for active indicators, ZLEMA Fast curves, and primary interactive controls.
  - `--secondary` (`#F59E0B`): Amber gold for ZLEMA Slow curves, SuperBoost mode indicators, and warning gates.
  - `--bullish / --success` (`#00E676`): High-visibility emerald green for `UP` signals, passing gatekeepers, and winning trades.
  - `--bearish / --error` (`#FF3D57`): Razor crimson for `DOWN` signals, active Context Enhancer blocks, and losing trades.
- **Typography & Hierarchy**:
  - **Display & Section Headers**: `Space Grotesk` (bundled locally via `font-util`) for crisp geometric headers and strategy titles.
  - **Telemetry, Prices & Order Tickers**: `JetBrains Mono` (bundled locally via `font-util`) with tabular numerals for zero-jitter live tick prices, latency counters, and HMM probability matrices.
  - **Body & Controls**: `Inter` for clean legibility across filter descriptions and settings.
- **Component Styling & Layout**:
  - Material 3 adaptive scaffold supporting both handheld portrait ergonomics and wide-screen split panes (`BoxWithConstraints`), edge-to-edge system bar insets (`enableEdgeToEdge()`), and spring-driven micro-animations (`spring(stiffness = Spring.StiffnessMediumLow)`) when gatekeepers flip between `BLOCKED` and `READY`.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Audited Pruned Confidence & Gatekeeper Pipeline vs. Legacy Score Inflation**
  - *Chosen Approach*: Replace the legacy additive confidence checklist (where passing mandatory filters automatically inflated every trade's score to 95/100) with the audited two-tier architecture: **Hard Gatekeeper Modules** (binary pass/reject with explicit block reason telemetry) followed by a **Scale-Invariant Continuous Confidence Score** ($\text{Consistency} \times 50 + \text{AssetTrendiness} \times 100$, modulated by `ContextEnhancer`).
  - *Why*: Our codebase audit (`decision_pipeline_optimization.md` & `strategy_optimization_report.md`) proved that enforcing `consistency > 0.75` and `slope / volatility < 3.0` as hard gatekeepers eliminates over 3,200 erratic chop entries and improves out-of-sample Expected Value by $+0.7564$ per trade.
  - *Alternatives Considered*: Porting the raw un-audited `indicators.ts` scoring directly, which would preserve dead code paths (`WEAK_PERSISTENCE` double-gating) and non-selective confidence scores.

- **Decision 2: Composable Strategy Chain-of-Responsibility Pattern**
  - *Chosen Approach*: Represent every trading strategy as a serializable `StrategyDefinition` composed of a `SignalTriggerConfig` and an ordered list of `FilterModuleConfig` items evaluated by a unified `ModularStrategyEngine`.
  - *Why*: Allows both the 6 preset strategies from the repository and any user-created custom strategy to execute through the exact same zero-allocation pipeline, while letting users add, remove, or tune individual filter modules in the UI and persist them in Room.
  - *Alternatives Considered*: Hardcoding separate monolithic classes per strategy, which would prevent users from mixing filters or creating custom strategies.

- **Decision 3: Hardened Concurrency, Numerical Stability & Pre-Formatted Socket Payloads**
  - *Chosen Approach*: Implement single-dispatcher atomic tick processing (`StateFlow` + Kotlin Coroutines with mutex-guarded order dispatch), pre-allocated `DoubleArray` buffers for the 150-tick HMM forward algorithm with a $10^{-9}$ variance floor to prevent `NaN` log-emission poisoning, and pre-formatted string template order payloads (`e:23`) with automatic `pendingCheck` reset on WebSocket disconnect.
  - *Why*: Directly incorporates all fixes from `hmm_audit_report.md` and `asynchronous_concurrency_audit.md` so the Android app never suffers from `NaN` state corruption, parallel reconnection storms, or permanent trade locks after a mobile network handoff.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                        JETPACK COMPOSE UI WORKSTATION                        │
│  ┌────────────────┐ ┌──────────────────┐ ┌────────────────┐ ┌──────────────┐ │
│  │ Auth & Broker  │ │ Quant Terminal & │ │ Modular        │ │ HMM Audit &  │ │
│  │ Token WebView  │ │ Execution Deck   │ │ Strategy Studio│ │ Trade Journal│ │
│  └───────┬────────┘ └────────┬─────────┘ └───────┬────────┘ └──────┬───────┘ │
└──────────┼───────────────────┼───────────────────┼─────────────────┼─────────┘
           │                   │                   │                 │
           ▼                   ▼                   ▼                 ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                     VIEWMODEL & STATE HOLDER LAYER (MVVM)                    │
│   AuthViewModel          TerminalViewModel      StrategyViewModel  JournalVM │
└──────────┬───────────────────┬───────────────────┬─────────────────┬─────────┘
           │                   │                   │                 │
           ▼                   ▼                   ▼                 ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                  CORE QUANTITATIVE & EXECUTION ENGINE LAYER                  │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │ OlympTradeWsClient (OkHttp WebSocket + Coroutine Event Dispatcher)     │  │
│  │ • Startup Handshake (e:98)  • Tick Feed (e:1, e:12/280)                │  │
│  │ • Ping/Latency Guard (e:90) • Account & Balance Sync (e:55, 1068, 1043)│  │
│  │ • Low-Latency Order Template Dispatch (e:23) & Settlement (e:21/22/26) │  │
│  └───────────────────────────────────┬────────────────────────────────────┘  │
│                                      │ Atomic Tick Stream                    │
│                                      ▼                                       │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │ Real-Time Quantitative Profiling Pipeline                              │  │
│  │ ┌──────────────┐ ┌───────────────┐ ┌──────────────┐ ┌────────────────┐ │  │
│  │ │ CandleEngine │ │StructureEngine│ │ LevelEngine  │ │ SRMemoryEngine │ │  │
│  │ │ (300x 5s)    │ │(Swing Hi/Lo)  │ │ (Psych Grid) │ │ (Adaptive S/R) │ │  │
│  │ └──────┬───────┘ └───────┬───────┘ └──────┬───────┘ └───────┬────────┘ │  │
│  │        └─────────────────┴────────┬───────┴─────────────────┘          │  │
│  │                                   ▼                                    │  │
│  │ ┌────────────────────────────────────────────────────────────────────┐ │  │
│  │ │ IndicatorEngine (ZLEMA 10/30, HVOI λ=0.82 Decay, Velocity, Slope)  │ │  │
│  │ └─────────────────────────────────┬──────────────────────────────────┘ │  │
│  │                                   ▼                                    │  │
│  │ ┌────────────────────────────────────────────────────────────────────┐ │  │
│  │ │ RegimeDetectionEngine (8-State Gaussian HMM, 150-Tick Buffer)      │ │  │
│  │ └─────────────────────────────────┬──────────────────────────────────┘ │  │
│  │                                   ▼                                    │  │
│  │ ┌────────────────────────────────────────────────────────────────────┐ │  │
│  │ │ ModularStrategyEngine & ContextEnhancer                            │ │  │
│  │ │ Evaluates Active Strategy Trigger + User's Modular Filter Chain    │ │  │
│  │ └────────────────────────────────────────────────────────────────────┘ │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────┬───────────────────────────────────────┘
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                     LOCAL PERSISTENCE LAYER (ROOM + DATASTORE)               │
│  • UserAccountEntity & Encrypted Session Preferences (Token, WS URI, Active) │
│  • StrategyEntity (Preset & Custom JSON Filter Chains, Stake, Duration)      │
│  • TradeJournalEntity (Full Entry Telemetry Snapshot + Final Settlement P&L) │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Data Model & State

1. **Authentication & Session Store (`DataStore` + `UserAccountEntity`)**:
   - Stores local app user credentials (`email`, `username`, `passwordHash`, `pinCode`), active Olymp Trade `accessToken`, configurable WebSocket endpoint URI, selected `assetPair`, `accountGroup` (`demo` / `real`), `demoAccountId`, `realAccountId`, and live balances.
2. **Quantitative Engine State (`MarketTelemetryState`)**:
   - Immutable snapshot emitted on every tick containing:
     - **Price & Candle Buffers**: Latest tick price/timestamp, up to 300 completed 5-second `Candle` objects, and the last 150 raw ticks.
     - **Indicators**: `zFast` (10), `zSlow` (30), `trend` (`UP`/`DOWN`/`NONE`), `trendAgeSeconds`, `persistence`, `consistency`, `volatility` (`avgDiff`), `velocity`, `slope`, `slopeRatio` (`slope / volatility`), `netDelta5`, `relPos` (50-tick percentile), and `hvoiScore` / `sessionQuality` (`OPTIMAL`, `NEUTRAL`, `AVOID`).
     - **Structural & Grid Context**: Active Swing Highs/Lows, consolidated `SRZone` list with adaptive corridor width $\max(0.20, 4.0 \times \text{AvgCandleRange})$, Psychological Grid bounce state, and active `ContextEnhancer` block reasons.
     - **HMM Regime State**: `stateIndex` ($0..7$), `regimeName` (`Expansion`, `Reversal`, `Calm`, `Trending`), `confidence` ($0..1$), `duration`, `persistence`, and the 8-state forward probability distribution.
3. **Modular Strategy Model (`StrategyEntity`)**:
   - Persisted in Room and pre-seeded with the 6 repository strategies (`RevangeWise5s`, `HighVoltage5s`, `RideTheHorse`, `Flash`, `Simple30s`, `PsychGridBounce`).
   - Contains `id`, `name`, `description`, `isPreset`, `isEnabled`, `durationSeconds`, `stakeAmount`, `cooldownMs`, `minConfidence`, `superBoostEnabled`, `regimeAdaptiveMode`, `triggerType`, and a serialized list of modular `StrategyFilterRule` objects (each with `filterType`, `enabled`, `threshold`, and `comparisonOperator`).
4. **Comprehensive Trade Journal (`TradeJournalEntity`)**:
   - Captures every field from the original `trades_history.json` specification: `tradeId`, `timestamp`, `strategyName`, `pair`, `direction`, `stake`, `duration`, `accountGroup`, `accountId`, `wsLatencyMs`, `hmmState`, `hmmRegime`, `hmmConfidence`, `hvoiScore`, `volatility`, `velocity`, `consistency`, `slopeRatio`, `srDistance`, `trendAge`, `entryPrice`, `exitPrice`, `outcome` (`WIN`, `LOSS`, `TIE`, `PENDING`), and `profitLoss`.

### Interactive Component & State Mapping

- **Auth & Broker Setup Screen**:
  - Validates local account registration/login -> unlocks the Broker Connection sheet -> offers **"Sign In with Olymp Trade WebView"** (intercepting `CookieManager.getInstance().getCookie("https://olymptrade.com")` for `access_token=...`) or **"Paste Access Token"** -> tests live WebSocket handshake (`e:90` ping response) before transitioning to the main workstation.
- **Terminal & Execution Deck Screen**:
  - **View Mode Toggle**: Switches smoothly between the **Pro Quant Terminal** (Canvas chart + full gatekeeper matrix), **Minimalist Execution Deck** (large signal HUD + quick trade controls), and **Split Overview**.
  - **Bot Control Bar**: Toggles `botRunning` (`[S]` equivalent), `superBoost` (`[B]` equivalent), and `accountGroup` (`[D]` Demo/Real toggle with safety confirmation dialog).
  - **Manual Order Override Buttons**: Instant `CALL (UP)` and `PUT (DOWN)` execution buttons that dispatch `e:23` orders using the active stake and duration while respecting the WebSocket connection guard.
- **Modular Strategy Studio Screen**:
  - **Strategy List & Quick Toggles**: Enable/disable multiple strategies, select the primary active strategy, or swipe/tap to delete custom strategies.
  - **Interactive Strategy Builder Sheet**: Add/remove filter blocks from a catalog of 10 quantitative gatekeepers, adjust slider thresholds (e.g., ZLEMA Consistency $0.50 - 0.95$, Max Slope Ratio $1.5 - 5.0$, Max Ping Latency $50 - 300\text{ms}$, Relative Position Breakout $60\% - 95\%$), and save directly to Room with immediate hot-reload in the live engine.
- **HMM & Trade Journal Screen**:
  - Displays real-time KPI summary cards (Win Rate, Total Net P&L, Expected Value per Trade, Profit Factor, Max Drawdown), filterable trade history cards with expandable entry telemetry badges, and one-tap JSON/CSV export to share or backtest externally.
