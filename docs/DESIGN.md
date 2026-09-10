# SleepGuard 共享设计文档（Android / HarmonyOS 双端对齐）

## 1. 音频管线

| 参数 | 值 | 两端 API |
|------|----|----------|
| 采样率 | 16000 Hz | AudioRecord (Android) / AudioCapturer (鸿蒙) |
| 声道 | 单声道 | |
| 位深 | PCM 16-bit | SOURCE_TYPE_MIC / SOURCE_TYPE_MIC |
| 帧块 | 4096 采样 ≈ 256ms | |
| 分析窗 | 1.5s（24000 采样），滑动步长 0.5s | |
| 预缓冲 | 环形缓冲保留最近 5s，命中事件时连同后 8s 一起存 WAV | |

## 2. 检测引擎（规则引擎 v1，双端同一套参数）

### 2.1 特征（每 0.5s 帧计算）
- `rms`：均方根能量
- `spectralCentroid`：谱心（鼾声通常 60~500Hz，偏低）
- `lowRatio`：300Hz 以下能量占比（鼾声 > 0.55）
- `zeroCrossRate`：过零率
- 帧间自相关周期性：鼾声呈 0.4~2.5s 的节律性爆发

### 2.2 鼾声判定
```
frame.isSnore = rms > thrRms(灵敏度映射 200~2000)
             && lowRatio > 0.55
             && spectralCentroid < 900Hz
snoreEvent   = 连续 ≥3 帧 isSnore（≈1.5s）且呈周期性
```

### 2.3 呼吸暂停判定
```
apneaEvent = 处于活跃打鼾期（最近 3min 内有 snoreEvent）
          && 连续 ≥10s 音频能量持续低于 thrSilence（呼吸音消失）
          && 随后 2s 内出现 > 2×thrRms 的恢复性喘息峰值
```
- 达到 10s 中断即发**预警**（不等喘息确认，安全优先）；
- 若 30s 内无恢复峰值且能量回升平缓，降级为「体动/离枕」误报剔除。

### 2.4 灵敏度
UI 滑杆 0~100 → 映射 thrRms ∈ [2000, 200]，thrSilence 同步缩放。默认 55。

## 3. 事件与存储

`SleepEvent { id, type: SNORE|APNEA, startEpochMs, durationMs, wavPath, score }`
- Android：SQLiteOpenHelper（`events.db`），WAV 存 `getExternalFilesDir("clips")`
- 鸿蒙：relationalStore（`sleepguard.db`），WAV 存应用沙箱 `files/clips`

## 4. 手环告警循环（Wear Engine）

```
检测到 APNEA（或 SNORE 连续升级，见下）且告警开关开
  → 立即发第 1 条高优先级模板通知（含「我醒了」按钮文案）
  → 未确认：每 15s 重发，共 12 轮（3 分钟）后休息 2min 再开新周期，防耗电失控
确认通道：
  a) 手机通知栏 action「我醒了」（Android PendingIntent / 鸿蒙 WantAgent）
  b) 打开 App 点击「停止告警」
触发策略：仅 APNEA 立即告警；SNORE 需 10 分钟内 ≥ 6 次事件（重度连续鼾声）才告警
```

鸿蒙端通知接口（wearEngine）：
```ts
const notifyClient = wearEngine.getNotifyClient(context)
notifyClient.notify(deviceId, { title, text, buttons, importance: HIGH })
```
Android 端经 `com.huawei.hms:wearengine`（developer.huawei.com repo）DeviceClient/NotifyClient。
两端均对「设备不在线/权限未开通/运动健康未连接」做降级：手机本地铃声+强震动循环。

## 5. 后台保活

- Android：Foreground Service + `FOREGROUND_SERVICE_MICROPHONE`（API 34）类型，通知常驻。
- 鸿蒙：`backgroundTaskManager` 长时任务，backgroundMode `audioRecording`；录音须前台启动后可退后台。

## 6. Wear Engine 权限申请步骤（鸿蒙端）

1. 华为开发者联盟实名认证（个人开发者即可申请「设备基础信息 + 消息通知」两项）。
2. AppGallery Connect → 开发与服务 → 项目设置 → 开放能力管理 → Wear Engine → 管理 → 申请。
3. 勾选「消息通知」权限，绑定本应用包名（`com.sleepguard.harmony`，≤28 字符）。
4. 手环10（HA 系列）与华为运动健康保持连接，穿戴侧设置开启「通知振动」。

## 7. 模型升级路径

见 `MODEL_TRAINING.md`。规则引擎与模型引擎共用 `Detector` 接口：
`analyze(window: ShortArray): DetectionResult`，替换无需动 UI/服务层。
