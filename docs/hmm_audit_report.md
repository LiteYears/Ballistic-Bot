# HMM Layer Audit & Calibration Report

This document presents a comprehensive audit, math breakdown, parameter analysis, and numerical calibration of the Hidden Markov Model (HMM) layer within the simulator.

---

## 1. Source Code Audit ([regimeDetector.ts](file:///root/OlympBackup/olymp-trade-bot-simulator/src/regimeDetector.ts))

The core logic resides in `RegimeDetectionEngine`. Below is the complete implementation highlighting how features are extracted, normalized, and run through the forward algorithm.

```typescript
import { EventEmitter } from 'events';
import * as fs from 'fs';
import { Tick } from './types';

export interface RegimeOutput {
  state: number;
  confidence: number;
  duration: number;
  persistence: number;
  regimeName: string;
  historicalEdge: {
    horizon: number;
    count: number;
    p_up: number;
    ci_lower: number;
    ci_upper: number;
    stat_sig: boolean;
    edge_strength: number;
  }[];
}

interface HMMModel {
  n_states: number;
  features: string[];
  scaler: {
    mean: number[];
    scale: number[];
  };
  startprob: number[];
  transmat: number[][];
  means: number[][];
  covars: (number[] | number[][])[];
  regimes: Record<string, string>;
  historical_edges: Record<string, any[]>;
}

export class RegimeDetectionEngine extends EventEmitter {
  private model!: HMMModel;
  private ticksBuffer: Tick[] = [];
  private currentOutput: RegimeOutput | null = null;
  private lastState: number | null = null;
  private stateDuration = 0;

  // Pre-allocated static buffers to avoid GC allocations on every tick
  private pricesBuf = new Float64Array(150);
  private timesBuf = new Float64Array(150);
  private tickDirectionsBuf = new Int8Array(150);
  private dtsBuf = new Float64Array(150);
  private dtCleansBuf = new Float64Array(150);
  private velocitiesBuf = new Float64Array(150);
  private accelerationsBuf = new Float64Array(150);
  private streakLengthsBuf = new Int16Array(150);
  private directionChangesBuf = new Int8Array(150);
  private return1Buf = new Float64Array(150);

  constructor(modelPath: string) {
    super();
    this.loadModel(modelPath);
  }

  private loadModel(modelPath: string) {
    try {
      const fileContent = fs.readFileSync(modelPath, 'utf8');
      this.model = JSON.parse(fileContent) as HMMModel;
    } catch (error) {
      console.error(`Failed to load HMM model from ${modelPath}:`, error);
      throw error;
    }
  }

  public reset() {
    this.ticksBuffer = [];
    this.currentOutput = null;
    this.lastState = null;
    this.stateDuration = 0;
  }

  public getTicksBuffer(): Tick[] {
    return this.ticksBuffer;
  }

  public processTick(tick: Tick): RegimeOutput | null {
    this.ticksBuffer.push(tick);
    if (this.ticksBuffer.length > 150) {
      this.ticksBuffer = this.ticksBuffer.slice(-150);
    }

    // Minimum 150 ticks required to ensure 100 mixing steps for the HMM forward algorithm.
    if (this.ticksBuffer.length < 150) {
      return null;
    }

    try {
      const output = this.runInference();
      if (!output) return null;

      if (this.lastState === null || output.state !== this.lastState) {
        const oldState = this.lastState;
        this.lastState = output.state;
        this.stateDuration = 1;
        output.duration = 1;

        this.emit('REGIME_CHANGED', {
          oldState,
          newState: output.state,
          regimeName: output.regimeName,
          output
        });
      } else {
        this.stateDuration++;
        output.duration = this.stateDuration;
      }

      this.currentOutput = output;

      if (output.confidence > 0.80) {
        this.emit('HIGH_CONFIDENCE_REGIME', output);
      }

      return output;
    } catch (error) {
      console.error('Error during regime detection inference:', error);
      return null;
    }
  }

  private runInference(): RegimeOutput | null {
    const N = this.ticksBuffer.length;
    
    for (let i = 0; i < N; i++) {
      this.pricesBuf[i] = this.ticksBuffer[i].price;
      this.timesBuf[i] = this.ticksBuffer[i].time;
    }

    this.tickDirectionsBuf[0] = 0;
    this.dtsBuf[0] = 1;
    this.dtCleansBuf[0] = 1;
    this.velocitiesBuf[0] = 0;
    this.accelerationsBuf[0] = 0;
    this.streakLengthsBuf[0] = 1;
    this.directionChangesBuf[0] = 0;
    this.return1Buf[0] = 0;

    for (let i = 1; i < N; i++) {
      const delta = this.pricesBuf[i] - this.pricesBuf[i - 1];
      this.tickDirectionsBuf[i] = delta > 0 ? 1 : (delta < 0 ? -1 : 0);
      this.dtsBuf[i] = (this.timesBuf[i] - this.timesBuf[i - 1]) / 1000.0;
      this.dtCleansBuf[i] = this.dtsBuf[i] <= 0 ? 0.001 : this.dtsBuf[i];
      this.velocitiesBuf[i] = delta / this.dtCleansBuf[i];
      this.directionChangesBuf[i] = this.tickDirectionsBuf[i] !== this.tickDirectionsBuf[i - 1] ? 1 : 0;
      this.streakLengthsBuf[i] = this.tickDirectionsBuf[i] === this.tickDirectionsBuf[i - 1] ? this.streakLengthsBuf[i - 1] + 1 : 1;
      this.return1Buf[i] = Math.log(this.pricesBuf[i] / this.pricesBuf[i - 1]);
    }

    this.accelerationsBuf[1] = 0;
    for (let i = 2; i < N; i++) {
      this.accelerationsBuf[i] = (this.velocitiesBuf[i] - this.velocitiesBuf[i - 1]) / this.dtCleansBuf[i];
    }

    const getMean = (arr: ArrayLike<number>, endIdx: number, window: number): number => {
      let sum = 0;
      for (let j = endIdx - window + 1; j <= endIdx; j++) {
        sum += arr[j];
      }
      return sum / window;
    };

    const getStd = (arr: ArrayLike<number>, endIdx: number, window: number, meanVal: number): number => {
      let sumSqDiff = 0;
      for (let j = endIdx - window + 1; j <= endIdx; j++) {
        const diff = arr[j] - meanVal;
        sumSqDiff += diff * diff;
      }
      return Math.sqrt(sumSqDiff / (window - 1));
    };

    const hmmSteps = N - 50;
    if (hmmSteps <= 0) return null;

    const n_states = this.model.n_states;
    const n_features = this.model.features.length;
    const { mean: scalerMean, scale: scalerScale } = this.model.scaler;
    const { startprob, transmat, means, covars, regimes, historical_edges } = this.model;

    let forwardProb = new Array(n_states).fill(0);

    for (let t = 0; t < hmmSteps; t++) {
      const idx = 50 + t;

      const ret1 = this.return1Buf[idx];
      const ret5 = Math.log(this.pricesBuf[idx] / this.pricesBuf[idx - 5]);
      const ret10 = Math.log(this.pricesBuf[idx] / this.pricesBuf[idx - 10]);

      const mean10 = getMean(this.return1Buf, idx, 10);
      const mean20 = getMean(this.return1Buf, idx, 20);

      const vol10 = getStd(this.return1Buf, idx, 10, mean10);
      const vol20 = getStd(this.return1Buf, idx, 20, mean20);
      const vol50 = getStd(this.return1Buf, idx, 50, getMean(this.return1Buf, idx, 50));

      const streak = this.streakLengthsBuf[idx];
      const dirChange = this.directionChangesBuf[idx];
      const vel = this.velocitiesBuf[idx];
      const acc = this.accelerationsBuf[idx];

      const rawFeaturesMap: Record<string, number> = {
        'return_1': ret1,
        'return_5': ret5,
        'return_10': ret10,
        'rolling_vol_10': vol10,
        'rolling_vol_20': vol20,
        'rolling_vol_50': vol50,
        'rolling_mean_10': mean10,
        'rolling_mean_20': mean20,
        'streak_length': streak,
        'direction_change': dirChange,
        'velocity': vel,
        'acceleration': acc
      };

      // Scale features dynamically based on feature names to align correctly with scaler parameters
      const scaledFeatures = new Array(n_features);
      for (let f = 0; f < n_features; f++) {
        const featureName = this.model.features[f];
        const rawVal = rawFeaturesMap[featureName] ?? 0;
        scaledFeatures[f] = (rawVal - scalerMean[f]) / scalerScale[f];
      }

      // Compute emission log-probabilities
      const logEmissions = new Array(n_states).fill(0);
      for (let j = 0; j < n_states; j++) {
        let logSum = 0;
        const stateCovar = covars[j];
        const is3D = Array.isArray(stateCovar[0]);
        for (let f = 0; f < n_features; f++) {
          let rawVar = 1e-9;
          if (is3D) {
            const row = stateCovar[f] as number[];
            rawVar = row[f];
          } else {
            rawVar = stateCovar[f] as number;
          }
          // Impose a floor (1e-9) on variance to prevent division by zero or log of zero
          const variance = Math.max(1e-9, rawVar);
          const diff = scaledFeatures[f] - means[j][f];
          const logEmission = -0.5 * Math.log(2 * Math.PI) - 0.5 * Math.log(variance) - (diff * diff) / (2 * variance);
          logSum += logEmission;
        }
        logEmissions[j] = logSum;
      }

      // Scale emissions to avoid underflow
      const maxLogEmission = Math.max(...logEmissions);
      let scaledEmissions = new Array(n_states).fill(0);
      if (Number.isFinite(maxLogEmission) && maxLogEmission !== -Infinity) {
        scaledEmissions = logEmissions.map(val => Math.exp(val - maxLogEmission));
      } else {
        // Fallback for extreme outlier features/underflow to avoid NaN propagation
        scaledEmissions.fill(1.0 / n_states);
      }

      // Run forward step
      const nextForwardProb = new Array(n_states).fill(0);
      if (t === 0) {
        for (let j = 0; j < n_states; j++) {
          nextForwardProb[j] = startprob[j] * scaledEmissions[j];
        }
      } else {
        for (let j = 0; j < n_states; j++) {
          let sum = 0;
          for (let i = 0; i < n_states; i++) {
            sum += forwardProb[i] * transmat[i][j];
          }
          nextForwardProb[j] = sum * scaledEmissions[j];
        }
      }

      // Normalize
      const sumProb = nextForwardProb.reduce((a, b) => a + b, 0);
      if (sumProb > 0) {
        forwardProb = nextForwardProb.map(val => val / sumProb);
      } else {
        forwardProb = new Array(n_states).fill(1 / n_states);
      }
    }

    let bestState = 0;
    let maxConfidence = -1;
    for (let j = 0; j < n_states; j++) {
      if (forwardProb[j] > maxConfidence) {
        maxConfidence = forwardProb[j];
        bestState = j;
      }
    }

    const regimeName = regimes[bestState.toString()] || 'Unknown';
    const persistence = transmat[bestState][bestState];
    const historicalEdge = historical_edges[bestState.toString()] || [];

    return {
      state: bestState,
      confidence: maxConfidence,
      duration: this.stateDuration,
      persistence,
      regimeName,
      historicalEdge
    };
  }

  public getCurrentOutput(): RegimeOutput | null {
    return this.currentOutput;
  }
}
```

---

## 2. Parameter Configurations (`hmm_model.json`)

Here is a summary of the model structure and parameter matrices extracted from the model parameters:

### General Information
* **States Count ($N_{states}$)**: 8
* **Features Count ($N_{features}$)**: 12
* **Priors (`startprob`)**:
  * State 1 starts with probability `1.0`. All other states start with `0.0`.

### Transition Matrix (`transmat`)
Each row corresponds to the transition probability vector from that state:

| From State | S0 | S1 | S2 | S3 | S4 | S5 | S6 | S7 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **S0** (Expansion) | 0.461 | 0.484 | 0.000 | 0.000 | 0.000 | 0.020 | 0.010 | 0.026 |
| **S1** (Reversal)  | 0.210 | 0.433 | 0.001 | 0.000 | 0.000 | 0.205 | 0.011 | 0.139 |
| **S2** (Reversal)  | 0.005 | 0.033 | 0.939 | 0.000 | 0.000 | 0.017 | 0.007 | 0.000 |
| **S3** (Expansion) | 0.000 | 0.000 | 1.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 |
| **S4** (Calm)      | 0.000 | 0.000 | 0.276 | 0.000 | 0.724 | 0.000 | 0.000 | 0.000 |
| **S5** (Trending)  | 0.018 | 0.480 | 0.000 | 0.000 | 0.001 | 0.452 | 0.008 | 0.041 |
| **S6** (Calm)      | 0.027 | 0.090 | 0.001 | 0.000 | 0.000 | 0.028 | 0.063 | 0.791 |
| **S7** (Calm)      | 0.038 | 0.500 | 0.000 | 0.000 | 0.000 | 0.060 | 0.401 | 0.000 |

### Means & Covariances Summary
* The **Means** array contains 8 rows (one per state) of 12 normalized values.
* The **Covariances** array is defined as a $12 \times 12$ matrix for each of the 8 states. However, it is diagonalized (all off-diagonal entries are `0.0`), which means features are modeled as conditionally independent within states. 
* Some variances are extremely small (e.g. $1.62 \times 10^{-7}$ for direction changes or return streaks), which poses potential division-by-zero or numeric overflow risks.

---

## 3. Mathematical Explanation & Scaling

### Gaussian Emission Log-Probability
Since features are modeled as conditionally independent multivariate normals, the emission probability density function for state $j$ given a scaled feature vector $\mathbf{x} = [x_1, x_2, ..., x_F]^T$ is the product of univariate normal densities:
$$P(\mathbf{x} | \text{State } j) = \prod_{f=1}^{F} \frac{1}{\sqrt{2\pi \sigma_{jf}^2}} \exp\left( -\frac{(x_f - \mu_{jf})^2}{2\sigma_{jf}^2} \right)$$

Taking the logarithm to preserve numeric precision:
$$\ln P(\mathbf{x} | \text{State } j) = \sum_{f=1}^{F} \left( -0.5 \ln(2\pi) - 0.5 \ln(\sigma_{jf}^2) - \frac{(x_f - \mu_{jf})^2}{2\sigma_{jf}^2} \right)$$

### Scaling & Underflow Prevention
If we computed raw probabilities directly, multiplying probabilities over a window of 100 ticks would result in values around $\approx 10^{-200}$, causing floating point underflow to $0$.

To solve this, `RegimeDetectionEngine` implements **scaled forward induction**:
1. **Subtracting Max Log-Emission**: We find $M = \max_k \ln P(\mathbf{x} | \text{State } k)$ and scale emission values:
   $$\tilde{P}(\mathbf{x} | \text{State } j) = \exp\left(\ln P(\mathbf{x} | \text{State } j) - M\right)$$
   This guarantees that the highest emission probability is exactly $1.0$ and all others are bounded in $[0, 1]$, preventing underflow.
2. **Forward Induction Step**:
   $$\tilde{\alpha}_t(j) = \tilde{P}(\mathbf{x}_t | \text{State } j) \times \sum_{i=1}^{N_{states}} \alpha_{t-1}(i) A_{ij}$$
3. **Normalization**: The forward variables $\alpha_t(j)$ are normalized to sum to $1$ at each step:
   $$\alpha_t(j) = \frac{\tilde{\alpha}_t(j)}{\sum_{k} \tilde{\alpha}_t(k)}$$
   This prevents numbers from underflowing to $0.0$ or overflowing.

### Instability Identified & Calibrated
During our audit, we identified a critical vulnerability:
* **Log-of-Zero / Division-by-Zero**: Some states have feature variances $\sigma^2 \approx 10^{-7}$. If a feature value deviates even slightly from the mean, the term $(x_f - \mu_{jf})^2 / 2\sigma_{jf}^2$ can explode to $> 500,000$, driving $\ln P \rightarrow -\infty$ and causing $M = -\infty$.
* **NaN Poisoning**: When $M = -\infty$, the scaling subtraction $\ln P - M$ becomes $-\infty - (-\infty)$, producing `NaN`. This `NaN` then propagates through all subsequent forward probabilities, destroying the engine's inference.
* **The Fix**: We introduced an **epsilon floor of $10^{-9}$ on variances** and added a safety fallback check. If $M = -\infty$ (or is non-finite), we intercept it and set uniform emissions rather than letting `NaN` poison the state buffer.

---

## 4. State Definitions & Market Behavior

The states correspond to four primary market regimes, mapped as follows:

1. **Expansion** (States 0, 3):
   * **Behavior**: Large tick-to-tick jumps, wide return range, high speed (high volatility). Typically signals the start of a momentum breakout or high-liquidity event.
2. **Reversal** (States 1, 2):
   * **Behavior**: High direction change probability, alternating green/red ticks, shorter streak lengths. Prices tend to mean-revert around local S/R corridors.
3. **Calm** (States 4, 6, 7):
   * **Behavior**: Low volatility, low tick velocity, small price differences, long periods of flat trading. Matches a range-bound market with low participant interest.
4. **Trending** (State 5):
   * **Behavior**: Long streaks of same-direction ticks, high persistence ($A_{5,5} \approx 0.45$), high velocity, low direction change frequency. Signals sustained direction.

---

## 5. Integration Logic & Safety Fallbacks

### Evaluating RegimeOutput in decisions
The HMM `RegimeOutput` is calculated on every tick and assigned to `this.currentHMMOutput` in [cli.ts](file:///root/OlympBackup/olymp-trade-bot-simulator/src/cli.ts). When a trade decision is evaluated in `processRegimeTradeDecision`, the bot inspects the configuration setting `this.config.regimeAdaptiveMode`. 

If `regimeAdaptiveMode` is not `'OFF'`, the bot maps the HMM output as follows:

* **Trending** (State 5) is treated as a momentum confirmation.
* **Reversal** (States 1, 2) signals short-term mean reversion.
* **Expansion** (States 0, 3) and **Calm** (States 4, 6, 7) act as neutral or non-trending regimes.

### Regime Adaptive Rules
Depending on the configured adaptive mode, the trade signals are dynamically modified or suppressed:
1. **`SKIP_BAD`**: Bypasses entry if the HMM detects a **Reversal** state (treating it as range chop or trend exhaustion), suppressing the signal.
2. **`REVERSE_BAD`**: Reverses the direction of the signal (e.g. `UP` becomes `DOWN`) if in a **Reversal** state, trying to capture the counter-trend bounce. Bypasses the entry if not in a reversal.
3. **`ADAPTIVE_ALL`**: Confirms the trend signal if in a **Trending** state, reverses the signal if in a **Reversal** state, and skips trading entirely in **Expansion** or **Calm** states.

### Safety Fallbacks (Low Confidence filter)
To prevent the bot from acting on noisy classification signals when the model has low certainty:
* **Confidence Guard**: A safety fallback filter has been integrated into `processRegimeTradeDecision`. If the HMM classification confidence is **less than 60%** (`hmmConfidence < 0.60`), the bot skips the adaptive regime modifications and defaults to a neutral filter (`NORMAL_LOW_CONF`), allowing the standard trade signal to execute normally.
