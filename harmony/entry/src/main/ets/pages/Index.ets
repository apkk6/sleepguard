import { media } from '@kit.MediaKit';
import { WearAlert } from '../service/WearAlert';
import { EventStore, SleepEvent } from '../data/EventStore';
import { RecordingController } from '../service/RecordingService';
import { AbilityShare } from '../entryability/EntryAbility';

interface DayCounts {
  snore: number
  apnea: number
}

@Entry
@Component
struct Index {
  @State recording: boolean = false
  @State snoreCount: number = 0
  @State apneaCount: number = 0
  @State sensitivity: number = 55
  @State wearAlertOn: boolean = true
  @State events: SleepEvent[] = []
  @State alerting: boolean = false
  private rec: RecordingController = new RecordingController()
  private store: EventStore | null = null
  private player: media.AVPlayer | null = null

  async aboutToAppear(): Promise<void> {
    this.store = AbilityShare.store
    this.refreshList()
  }

  private dayStart(): number {
    const d = new Date()
    d.setHours(18, 0, 0, 0)
    if (new Date().getHours() < 18) {
      d.setDate(d.getDate() - 1)
    }
    return d.getTime()
  }

  private async refreshList(): Promise<void> {
    if (!this.store) {
      return
    }
    const list = await this.store.querySince(this.dayStart())
    this.events = list
    let sn = 0, ap = 0
    for (const e of list) {
      if (e.type === 'SNORE') {
        sn++
      } else {
        ap++
      }
    }
    this.snoreCount = sn
    this.apneaCount = ap
    this.alerting = WearAlert.isAlerting()
  }

  private async toggleRecording(): Promise<void> {
    const ctx = AbilityShare.ctx
    if (!ctx) {
      return
    }
    if (!this.recording) {
      if (!this.store) {
        const s = new EventStore()
        await s.init(ctx)
        this.store = s
        AbilityShare.store = s
      }
      await this.rec.start(ctx, this.sensitivity, this.wearAlertOn, this.store)
      this.recording = true
    } else {
      await this.rec.stop()
      this.recording = false
      this.refreshList()
    }
  }

  private async playEvent(ev: SleepEvent): Promise<void> {
    try {
      if (this.player) {
        await this.player.release()
        this.player = null
      }
      const p = await media.createAVPlayer()
      this.player = p
      p.url = `file://${ev.wavPath}`
      p.prepare()
      p.play()
    } catch (e) {
    }
  }

  private fmtTime(ms: number): string {
    const d = new Date(ms)
    const pad = (x: number): string => x < 10 ? `0${x}` : `${x}`
    return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  }

  build() {
    Column({ space: 14 }) {
      Text(this.recording ? '录音监测中…' : '准备就绪')
        .fontSize(20)
        .fontWeight(FontWeight.Medium)
        .fontColor('#0C447C')
        .margin({ top: 20 })

      Text(`鼾声 ${this.snoreCount} 次 · 呼吸暂停 ${this.apneaCount} 次`)
        .fontSize(14)
        .fontColor('#5F5E5A')

      Button(this.recording ? '起床' : '开始睡觉')
        .width('100%')
        .height(56)
        .fontSize(18)
        .backgroundColor(this.recording ? '#E24B4A' : '#185FA5')
        .onClick(() => this.toggleRecording())

      if (this.alerting) {
        Button('停止告警（我醒了）')
          .width('100%')
          .height(48)
          .backgroundColor('#FCEBEB')
          .fontColor('#A32D2D')
          .onClick(() => {
            WearAlert.stop(AbilityShare.ctx ?? undefined)
            this.alerting = false
          })
      }

      Row() {
        Text('灵敏度').fontSize(14)
        Slider({
          value: this.sensitivity,
          min: 0,
          max: 100
        })
          .layoutWeight(1)
          .margin({ left: 10, right: 10 })
          .onChange((v: number) => {
            this.sensitivity = Math.round(v)
          })
        Text(`${this.sensitivity}`).fontSize(13).fontColor('#888780')
      }
      .width('100%')

      Row() {
        Text('手环告警（Wear Engine）').fontSize(14)
        Blank()
        Toggle({ type: ToggleType.Switch, isOn: this.wearAlertOn })
          .onChange((on: boolean) => {
            this.wearAlertOn = on
          })
      }
      .width('100%')

      if (this.events.length === 0) {
        Text('今晚暂无事件')
          .fontSize(14)
          .fontColor('#888780')
          .margin({ top: 24 })
      } else {
        List({ space: 6 }) {
          ForEach(this.events, (ev: SleepEvent) => {
            ListItem() {
              Row({ space: 10 }) {
                Circle({ width: 10, height: 10 })
                  .fill(ev.type === 'APNEA' ? '#E24B4A' : '#EF9F27')
                Column({ space: 2 }) {
                  Text(ev.type === 'APNEA' ? '呼吸暂停' : '打鼾')
                    .fontSize(15)
                    .fontColor('#222222')
                  Text(this.fmtTime(ev.startEpochMs))
                    .fontSize(12)
                    .fontColor('#888780')
                }
                .alignItems(HorizontalAlign.Start)
                .layoutWeight(1)
                Button('播放')
                  .height(32)
                  .fontSize(13)
                  .backgroundColor('#E6F1FB')
                  .fontColor('#185FA5')
                  .onClick(() => this.playEvent(ev))
              }
              .width('100%')
              .padding(10)
              .borderRadius(12)
              .backgroundColor('#FFFFFF')
            }
          }, (ev: SleepEvent) => `${ev.id}`)
        }
        .layoutWeight(1)
        .width('100%')
      }
    }
    .width('100%')
    .height('100%')
    .padding(20)
    .backgroundColor('#F7FAFD')
  }
}
