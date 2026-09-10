# 鼾声/呼吸暂停检测模型训练方案（可选升级）

规则引擎开箱即用；要更高的准确率/召回率，按本方案训练轻量模型替换。

## 1. 数据

| 类别 | 来源 |
|------|------|
| 鼾声 | Kaggle "Snoring Dataset"（500+ 片段）、AudioSet Snoring 类、MPSSC 奥地利鼾声库 |
| 呼吸暂停 | 麻省理工 Apnea-ECG 配套音频有限；可用 PhysioNet apnea 相关记录 + 自录夜间数据标注 |
| 负样本 | ESC-50（环境噪声）、MIMIC 环境音、安静房间自录 |

每段切成 1.5s @16kHz 片段，训练集：验证集 = 8:2，按说话人/夜晚切分防止泄漏。

## 2. 模型（PyTorch 训练 → 端侧导出）

```
输入: log-mel 64 mel bins × 96 frames (1.5s)
骨干: MobileNetV3-Small 修改版 或 4 层 CNN + 全局池化
输出: 3 类 softmax {安静, 鼾声, 呼吸暂停事件(中断/喘息)}
参数量: < 1.2M，int8 量化后 < 1.5MB
```

- 损失：交叉熵 + 类别权重（呼吸暂停样本少，weight ×3）
- 数据增强：加性噪声、时间拉伸 ±10%、随机增益 ±6dB
- 目标指标：鼾声 F1 ≥ 0.90；暂停事件召回 ≥ 0.85，误报 ≤ 1 次/晚

## 3. 导出与接入

- Android：`torch.export` / ONNX → TFLite int8，放 `android/app/src/main/assets/snore_v1.tflite`，
  `Detector` 已预留 TFLite 加载钩子（检测到文件自动启用模型，否则走规则引擎）。
- 鸿蒙：转 MindSpore Lite `.ms`，放 `harmony/entry/src/main/resources/rawfile/snore_v1.ms`，
  同名钩子逻辑一致。
- 两侧推理逻辑等价：特征提取、窗口、后处理完全复用 DESIGN.md 第 2 节参数。

## 4. 迭代闭环

App 内每次事件 WAV 即天然标注样本（用户可纠正误报），导出打包后回流训练集，
每两周重训一版。呼吸暂停判定建议后期融合手环血氧（Health Kit 读取，实时性受限，仅作起床后复核）。
