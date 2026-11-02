# OlympQuant Terminal (Android)

**OlympQuant Terminal** is a native Android quantitative algorithmic trading workstation for Olymp Trade built with Kotlin and Jetpack Compose.

## Key Capabilities

- **Dual-Layer Authentication**:
  - **Operator Vault Auth**: Local profile registration & sign-in backed by Room + SHA-256 credential hashing.
  - **Olymp Trade Broker Auth**: Supports both direct `access_token` configuration (or via the AI Studio Secrets Panel) and an integrated **Embedded WebView Token Interceptor** that extracts the `access_token` cookie automatically upon signing in.
- **Low-Latency WebSocket Engine (`OlympTradeWsClient`)**:
  - Full implementation of the Olymp Trade WebSocket OTP protocol (`e:98` startup subscriptions, `e:90` ping latency guard, `e:1068/1043/55` Demo & Real account discovery and live balance sync, `e:10/1003` historical 5s OHLC candles, `e:12/280/1` live tick stream, and `e:23/22/26` pre-formatted sub-millisecond order placement & settlement tracking).
  - Hardened against `pendingOrderCheck` reconnection locks and parallel reconnection storms.
- **Audited Quantitative & HMM Pipeline**:
  - **CandleEngine & StructureEngine**: 300-candle (5s OHLC) sliding window with out-of-order timestamp routing and fractal Swing High/Low detection.
  - **SRMemoryEngine & LevelEngine**: Consolidated Support/Resistance memory corridors with adaptive width $\max(0.20, 4.0 \times \text{AvgCandleRange})$ and Psychological Round-Number Grid bounce detection.
  - **IndicatorEngine & ContextEnhancer**: ZLEMA Fast (10) & Slow (30), 15-tick Consistency, 40-tick Persistence, 3-second Micro-Velocity, Scale-Invariant Slope Ratio ($\text{slope}/\text{volatility}$), 50-tick `relPos` breakout percentile, and exponentially time-decayed ($\lambda = 0.82$) **HVOI** suitability scoring.
  - **RegimeDetectionEngine**: 8-State, 12-Feature Gaussian Hidden Markov Model (`Expansion`, `Reversal`, `Calm`, `Trending`) with $10^{-9}$ variance floor and scaled forward induction.
- **Modular Strategy Studio**:
  - Ships with 6 audited preset strategies (`RevangeWise5s`, `HighVoltage5s`, `RideTheHorse`, `Flash Impulse 5s`, `PsychGrid Round-Number Bounce`, and `Simple30s Candle Dominance`).
  - Users can create, clone, edit, toggle, and remove custom modular strategies with configurable entry triggers, HMM regime adaptation modes (`OFF`, `SKIP_BAD`, `REVERSE_BAD`, `ADAPTIVE_ALL`), and 10 tunable gatekeeper filter modules.
- **Professional Trading Journal**:
  - Logs every trade with its complete entry telemetry snapshot in Room and computes live Win Rate, Net P&L, Expected Value (EV), and Profit Factor with JSON export.
