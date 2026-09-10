/**
 * 鼾声/呼吸暂停检测引擎（规则版 v1）。
 * 参数与 Android 端 Detector.kt、docs/DESIGN.md 第 2 节完全一致，双端行为对齐。
 */
export const SAMPLE_RATE = 16000
export const FRAME = 8000            // 0.5s @16kHz
export const SNORE_MIN_FRAMES = 3    // >=1.5s 连续判定
export const APNEA_SILENCE_MS = 10000
export const SNORE_ALERT_COUNT = 6
export const SNORE_ALERT_WINDOW_MS = 10 * 60000

export class FrameFeat {
  rms: number = 0
  lowRatio: number = 0
  centroid: number = 0
  isSnore: boolean = false
}

export class DetectionResult {
  type: string = ''   // 'SNORE' | 'APNEA' | ''
  score: number = 0
}

export class Detector {
  private sensitivityValue: number = 55
  private thrRms: number = 0
  private thrSilence: number = 0
  private recentSnoreFrames: number = 0
  private silenceMs: number = 0
  private lastSnoreEventMs: number = 0
  private inSnoringEpisode: boolean = false
  private snoreTimestamps: number[] = []

  constructor(sensitivity: number) {
    this.setSensitivity(sensitivity)
  }

  setSensitivity(v: number): void {
    this.sensitivityValue = Math.min(100, Math.max(0, Math.round(v)))
    this.thrRms = 200 + (2000 - 200) * (1 - this.sensitivityValue / 100)
    this.thrSilence = this.thrRms * 0.25
  }

  /** 每 0.5s 帧（8000 采样）计算特征：RMS + Goertzel 8 频带 */
  analyzeFrame(samples: Int16Array): FrameFeat {
    const f = new FrameFeat()
    const n = samples.length
    if (n !== FRAME) {
      return f
    }
    const bands: number[] = [80, 150, 250, 400, 700, 1200, 2400, 4800]
    const bandE: number[] = new Array<number>(bands.length).fill(0)
    for (let b = 0; b < bands.length; b++) {
      const w = 2 * Math.PI * bands[b] / SAMPLE_RATE
      let sPrev = 0, s2Prev = 0, energy = 0
      for (let i = 0; i < n; i += 4) {
        const x = samples[i] / 32768
        const coeff = 2 * Math.cos(w)
        const s = x + coeff * sPrev - s2Prev
        s2Prev = sPrev
        sPrev = s
        energy += x * x
      }
      bandE[b] = sPrev * sPrev + s2Prev * s2Prev - coeff * sPrev * s2Prev + energy
    }
    let sumSq = 0
    for (let i = 0; i < n; i++) {
      const x = samples[i]
      sumSq += x * x
    }
    f.rms = Math.sqrt(sumSq / n)
    let total = 1e-6, low = 0, centroidNum = 0
    for (let b = 0; b < bands.length; b++) {
      total += bandE[b]
      if (bands[b] <= 300) {
        low += bandE[b]
      }
      centroidNum += bandE[b] * bands[b]
    }
    f.lowRatio = low / total
    f.centroid = centroidNum / total
    f.isSnore = f.rms > this.thrRms && f.lowRatio > 0.55 && f.centroid < 900
    return f
  }

  /** 每 0.5s 调一次；返回事件（一帧最多一个） */
  onFrame(f: FrameFeat, nowMs: number): DetectionResult {
    const r = new DetectionResult()
    if (f.isSnore) {
      this.recentSnoreFrames++
    } else {
      this.recentSnoreFrames = Math.max(0, this.recentSnoreFrames - 1)
    }
    if (this.recentSnoreFrames >= SNORE_MIN_FRAMES) {
      if (nowMs - this.lastSnoreEventMs > 3000) {
        this.lastSnoreEventMs = nowMs
        this.inSnoringEpisode = true
        this.snoreTimestamps.push(nowMs)
        if (this.snoreTimestamps.length > 40) {
          this.snoreTimestamps.shift()
        }
        r.type = 'SNORE'
        r.score = Math.min(3, f.rms / this.thrRms)
        return r
      }
      this.inSnoringEpisode = true
      this.silenceMs = 0
      return r
    }
    if (this.inSnoringEpisode) {
      if (f.rms < this.thrSilence) {
        this.silenceMs += 500
        if (this.silenceMs >= APNEA_SILENCE_MS) {
          this.inSnoringEpisode = false
          this.silenceMs = 0
          r.type = 'APNEA'
          r.score = 1
          return r
        }
      } else {
        if (this.silenceMs >= APNEA_SILENCE_MS / 2 && f.rms > this.thrRms * 2) {
          this.inSnoringEpisode = false
          this.silenceMs = 0
          r.type = 'APNEA'
          r.score = 2.2
          return r
        }
        this.silenceMs = 0
      }
    } else if (nowMs - this.lastSnoreEventMs > 3 * 60000) {
      this.inSnoringEpisode = false
    }
    return r
  }

  shouldAlertSnore(): boolean {
    const cut = Date.now() - SNORE_ALERT_WINDOW_MS
    let count = 0
    for (const t of this.snoreTimestamps) {
      if (t >= cut) {
        count++
      }
    }
    return count >= SNORE_ALERT_COUNT
  }

  reset(): void {
    this.recentSnoreFrames = 0
    this.silenceMs = 0
    this.lastSnoreEventMs = 0
    this.inSnoringEpisode = false
    this.snoreTimestamps = []
  }
}
