# SleepGuard 守眠 — 睡眠监测 + 华为手环10 告警 App

点击「睡觉」开始整晚安录音 → 端侧 AI 实时识别**打鼾 / 呼吸暂停** → 事件截段保存可回放 → 检测到异常时通过
**Wear Engine** 向华为手环10 发送高优先级告警（亮屏+振动），未确认则循环重发，直到用户点「我醒了」才停止。

完全离线，无任何云端 API。

## 目录结构

```
sleepguard/
├── android/          Android 原生工程（Kotlin, minSdk 26, targetSdk 34）
├── harmony/          HarmonyOS NEXT 原生工程（ArkTS, API 12+）
└── docs/
    ├── DESIGN.md          共享架构与检测算法设计
    └── MODEL_TRAINING.md  鼾声/呼吸暂停模型训练方案（可选升级）
```

## 双端功能对齐（两端行为完全一致）

| 功能 | 说明 |
|------|------|
| 一键睡觉 | 前台/长时任务启动录音，锁屏不中断 |
| 事件检测 | 1.5s 音频窗滑动分析：鼾声、呼吸暂停（鼾声中断 ≥10s 后喘息恢复） |
| 事件回放 | 事件截取前 5s + 后 8s 存为 WAV，列表点按播放 |
| 手环告警 | Wear Engine 模板通知 → 手环亮屏+振动；未确认每 15s 重发 |
| 取消告警 | 手机通知栏「我醒了」按钮 / 打开 App 点停止，停止重发循环 |
| 睡眠报告 | 起床后统计时长、鼾声次数、呼吸暂停次数、事件时间轴 |

## 编译运行

### Android
1. Android Studio 打开 `android/`，等待 Gradle Sync。
2. 连接手机（开启 USB 调试）直接 Run。
3. 手环联动需真机装有**华为运动健康 App**；`WearAlert.kt` 对未安装/未授权场景做了优雅降级（App 内铃声+震动兜底）。

### HarmonyOS
1. DevEco Studio 5.0+ 打开 `harmony/`。
2. 注册华为开发者账号并实名认证；在 AppGallery Connect 为应用申请
   **Wear Engine → 消息通知权限**（个人开发者可申请），见 `docs/DESIGN.md` 第 6 节。
3. 连接鸿蒙真机 Run。模拟器无麦克风连续采集，建议真机测试。

## 重要限制（诚实说明）

- 手环侧**没有**第三方可直接启停振动的 API；本项目用「高优先级通知 + 未确认循环重发」逼近持续震动效果，
  每轮之间手环会有数秒间歇。手环须处于佩戴状态、未开勿扰、通知振动开启。
- 开箱即用的是内置**规则引擎**（能量+频谱启发式，见 DESIGN.md）；追求更高准确率按
  `docs/MODEL_TRAINING.md` 训练 TFLite / MindSpore Lite 模型放入 assets 即可无缝替换。
