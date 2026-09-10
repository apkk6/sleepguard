import { wearEngine } from '@kit.WearEngineKit';
import { notificationManager } from '@kit.NotificationKit';
import { vibrator } from '@kit.SensorServiceKit';
import { audio } from '@kit.AudioKit';
import { media } from '@kit.MediaKit';
import { Context, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const TAG = 'WearAlert';
const RESEND_MS = 15000;
const ROUNDS = 12;

/**
 * 手环告警：检测到呼吸暂停/重度鼾声 → Wear Engine 模板通知到华为手环10
 * （亮屏 + 振动，跟随手环系统设置）。未确认则每 15s 重发，12 轮后休息 2min 再开新周期。
 * Wear Engine 不可用时降级：手机本地铃声 + 循环强震动 + 高优先级通知（含「我醒了」按钮）。
 */
export class WearAlert {
  private static alerting: boolean = false
  private static rounds: number = 0
  private static timerId: number = -1
  private static player: media.AVPlayer | null = null
  private static ctx: Context | null = null

  static isAlerting(): boolean {
    return WearAlert.alerting
  }

  static start(ctx: Context, title: string, text: string): void {
    WearAlert.ctx = ctx
    if (WearAlert.alerting) {
      return
    }
    WearAlert.alerting = true
    WearAlert.rounds = 0
    WearAlert.tick(title, text)
  }

  private static tick(title: string, text: string): void {
    if (!WearAlert.alerting) {
      return
    }
    WearAlert.fireOnce(title, text)
    WearAlert.rounds++
    const delay = (WearAlert.rounds % ROUNDS === 0) ? 120000 : RESEND_MS
    WearAlert.timerId = setTimeout(() => WearAlert.tick(title, text), delay)
  }

  /** 用户确认醒来 → 停止循环 */
  static stop(ctx?: Context): void {
    WearAlert.alerting = false
    if (WearAlert.timerId >= 0) {
      clearTimeout(WearAlert.timerId)
      WearAlert.timerId = -1
    }
    if (WearAlert.player) {
      WearAlert.player.stop()
      WearAlert.player.release()
      WearAlert.player = null
    }
    try {
      vibrator.stopVibration()
    } catch (e) {
    }
    if (ctx) {
      try {
        notificationManager.cancel(9001)
      } catch (e) {
      }
    }
  }

  private static async fireOnce(title: string, text: string): Promise<void> {
    let wearOk = false
    try {
      wearOk = await WearAlert.sendWearNotification(title, text)
    } catch (e) {
      hilog.warn(0x0000, TAG, `Wear Engine unavailable: ${JSON.stringify(e)}`)
    }
    if (!wearOk) {
      WearAlert.localAlarm()
    }
    WearAlert.postPhoneNotification(title, text)
  }

  /** Wear Engine 模板通知：手机 App → 手环。设备不在线/权限缺失返回 false。 */
  private static async sendWearNotification(title: string, text: string): Promise<boolean> {
    if (!WearAlert.ctx) {
      return false
    }
    const deviceClient = wearEngine.getDeviceClient(WearAlert.ctx)
    const devices = await deviceClient.getConnectedDevices()
    if (devices.length === 0) {
      return false
    }
    const notifyClient = wearEngine.getNotifyClient(WearAlert.ctx)
    for (const device of devices) {
      const options: wearEngine.NotificationOptions = {
        title: title,
        text: text,
        deviceType: device.deviceType,
        importance: wearEngine.NotifyImportance.IMPORTANCE_HIGH
      }
      await notifyClient.notify(device, options)
    }
    return true
  }

  /** 本地兜底：铃声循环 + 强震动 */
  private static localAlarm(): void {
    if (!WearAlert.player) {
      media.createAVPlayer().then(p => {
        WearAlert.player = p
        p.url = 'resource://rawfile/alarm_fallback.mp3'
        p.loop = true
        p.prepare()
        p.play()
      })
    }
    try {
      vibrator.startVibration({
        type: 'preset',
        count: 3,
        duration: 800
      }, { id: 0, usage: 'alarm' })
    } catch (e) {
    }
  }

  private static postPhoneNotification(title: string, text: string): void {
    const req: notificationManager.NotificationRequest = {
      id: 9001,
      notification: {
        content: {
          notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
          normal: { title: title, text: text }
        },
        deliveryTime: Date.now(),
        isOngoing: false
      }
    }
    notificationManager.publish(req).catch(() => {})
  }
}
