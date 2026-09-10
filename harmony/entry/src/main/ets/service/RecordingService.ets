import { audio } from '@kit.AudioKit';
import { backgroundTaskManager } from '@kit.BackgroundTaskKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { Context } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { Detector, FRAME, DetectionResult } from './Detector';
import { WearAlert } from './WearAlert';
import { EventStore } from '../data/EventStore';

const TAG = 'RecService';
const PRE_BLOCKS = 10;   // 5s 预缓冲（0.5s/块）
const POST_BLOCKS = 16;  // 8s 后补录

export interface RecState {
  recording: boolean
  snoreCount: number
  apneaCount: number
}

/**
 * 整夜录音服务：AudioCapturer 16kHz 单声道 PCM + 长时任务（audioRecording）。
 * 命中事件 → 截前 5s + 后 8s 存 WAV → 入库 → 触发手环告警。
 */
export class RecordingController {
  private capturer: audio.AudioCapturer | null = null
  private detector: Detector | null = null
  private store: EventStore | null = null
  private ctx: Context | null = null
  private continuousId: number = -1

  private preBuffer: Int16Array[] = []
  private pendingBlocks: Int16Array[] = []
  private pendingType: string = ''
  private pendingStartMs: number = 0
  private pendingScore: number = 0
  private capturingPost: boolean = false
  private postLeft: number = 0

  wearAlertOn: boolean = true
  snoreCount: number = 0
  apneaCount: number = 0
  recording: boolean = false

  async start(ctx: Context, sensitivity: number, wearAlertOn: boolean, store: EventStore): Promise<void> {
    if (this.recording) {
      return
    }
    this.ctx = ctx
    this.wearAlertOn = wearAlertOn
    this.store = store
    this.snoreCount = 0
    this.apneaCount = 0
    this.detector = new Detector(sensitivity)
    this.detector.reset()
    this.preBuffer = []
    this.capturingPost = false

    // 长时任务：audioRecording 模式，锁屏不中断
    const req: backgroundTaskManager.ContinuousTaskRequest = {
      bgMode: backgroundTaskManager.BackgroundMode.AUDIO_RECORDING,
      abilityName: 'EntryAbility',
      wantAgent: {
        abilityName: 'EntryAbility'
      } as Want
    }
    try {
      this.continuousId = await backgroundTaskManager.startContinuousTask(req)
    } catch (e) {
      hilog.warn(0x0000, TAG, `continuous task failed: ${JSON.stringify(e)}`)
    }

    const streamInfo: audio.AudioStreamInfo = {
      samplingRate: audio.AudioSamplingRate.SAMPLE_RATE_16000,
      channels: audio.AudioChannel.CHANNEL_1,
      sampleFormat: audio.AudioSampleFormat.SAMPLE_FORMAT_S16LE,
      encodingType: audio.AudioEncodingType.ENCODING_TYPE_RAW
    }
    const capturerInfo: audio.AudioCapturerInfo = {
      source: audio.SourceType.SOURCE_TYPE_MIC,
      capturerFlags: 0
    }
    const options: audio.AudioCapturerOptions = { streamInfo, capturerInfo }
    this.capturer = await audio.createAudioCapturer(options)
    this.capturer.on('readData', (buf: ArrayBuffer) => this.onData(new Int16Array(buf)))
    await this.capturer.start()
    this.recording = true
    hilog.info(0x0000, TAG, 'recording started')
  }

  async stop(): Promise<void> {
    this.recording = false
    if (this.capturer) {
      try {
        await this.capturer.stop()
        await this.capturer.release()
      } catch (e) {
      }
      this.capturer = null
    }
    if (this.continuousId >= 0) {
      try {
        await backgroundTaskManager.stopContinuousTask(this.continuousId)
      } catch (e) {
      }
      this.continuousId = -1
    }
    WearAlert.stop(this.ctx ?? undefined)
  }

  getState(): RecState {
    return { recording: this.recording, snoreCount: this.snoreCount, apneaCount: this.apneaCount }
  }

  private onData(pcm: Int16Array): void {
    if (!this.recording || !this.detector) {
      return
    }
    // readData 回调长度不定，累计满 0.5s（8000 采样）再分析
    if (pcm.length < FRAME) {
      return
    }
    for (let off = 0; off + FRAME <= pcm.length; off += FRAME) {
      this.processFrame(pcm.subarray(off, off + FRAME))
    }
  }

  private processFrame(block: Int16Array): void {
    const d = this.detector!
    const now = Date.now()
    const feat = d.analyzeFrame(block)
    const res: DetectionResult = d.onFrame(feat, now)
    this.preBuffer.push(block)
    while (this.preBuffer.length > PRE_BLOCKS) {
      this.preBuffer.shift()
    }

    if (this.capturingPost) {
      this.pendingBlocks.push(block)
      this.postLeft--
      if (this.postLeft <= 0) {
        this.finalizeEvent()
      }
      return
    }

    const type = (res.type === 'APNEA') ? 'APNEA'
      : (res.type === 'SNORE' && res.score > 1.6) ? 'SNORE' : ''
    if (type !== '') {
      this.pendingType = type
      this.pendingStartMs = now
      this.pendingScore = res.score
      this.pendingBlocks = [...this.preBuffer]
      this.capturingPost = true
      this.postLeft = POST_BLOCKS
      if (type === 'APNEA') {
        this.apneaCount++
      } else {
        this.snoreCount++
      }
      this.maybeAlert(type)
    }
  }

  private maybeAlert(type: string): void {
    if (!this.wearAlertOn || !this.ctx) {
      return
    }
    const d = this.detector!
    if (type === 'APNEA' || (type === 'SNORE' && d.shouldAlertSnore())) {
      const text = type === 'APNEA' ? '检测到呼吸暂停，手环正在提醒' : '检测到重度连续鼾声'
      WearAlert.start(this.ctx, '检测到呼吸暂停/重度鼾声！', text)
    }
  }

  private async finalizeEvent(): Promise<void> {
    const type = this.pendingType
    const start = this.pendingStartMs
    const score = this.pendingScore
    const blocks = this.pendingBlocks
    this.capturingPost = false
    this.pendingBlocks = []
    const ctx = this.ctx
    const store = this.store
    if (!ctx || !store || blocks.length === 0) {
      return
    }
    try {
      const dir = `${ctx.filesDir}/clips`
      if (!fs.accessSync(dir)) {
        fs.mkdirSync(dir, true)
      }
      const path = `${dir}/${type}_${start}.wav`
      fs.writeFileSync(path, this.buildWav(blocks))
      await store.insert(type, start, blocks.length * 500, path, score)
    } catch (e) {
      hilog.error(0x0000, TAG, `save clip failed: ${JSON.stringify(e)}`)
    }
  }

  /** PCM16 单声道 → WAV 字节 */
  private buildWav(blocks: Int16Array[]): Uint8Array {
    let total = 0
    for (const b of blocks) {
      total += b.length
    }
    const pcm = new Uint8Array(total * 2)
    let p = 0
    for (const b of blocks) {
      for (let i = 0; i < b.length; i++) {
        pcm[p++] = b[i] & 0xFF
        pcm[p++] = (b[i] >> 8) & 0xFF
      }
    }
    const wav = new Uint8Array(44 + pcm.length)
    const dv = new DataView(wav.buffer)
    const ascii = (off: number, s: string) => {
      for (let i = 0; i < s.length; i++) {
        dv.setUint8(off + i, s.charCodeAt(i))
      }
    }
    ascii(0, 'RIFF'); dv.setUint32(4, 36 + pcm.length, true)
    ascii(8, 'WAVE'); ascii(12, 'fmt ')
    dv.setUint32(16, 16, true); dv.setUint16(20, 1, true); dv.setUint16(22, 1, true)
    dv.setUint32(24, 16000, true); dv.setUint32(28, 32000, true)
    dv.setUint16(32, 2, true); dv.setUint16(34, 16, true)
    ascii(36, 'data'); dv.setUint32(40, pcm.length, true)
    wav.set(pcm, 44)
    return wav
  }
}
