# 如何拿到 SleepGuard 安装包（APK）

你不需要懂代码。下面两条路，**任选一条**就能拿到 APK，然后用 MT 管理器装到手机。

---

## 路径 A（推荐，我替你跑完，你只给我一个令牌）

如果觉得下面路径 B 的 GitHub 步骤麻烦，可以让助手直接替你做完：

1. 你注册 GitHub（https://github.com ，免费）→ 右上角头像 → **Settings** → **Developer settings** → **Personal access tokens** → **Tokens (classic)** → **Generate new token (classic)**。
2. 勾选 `repo`（全选 repo 那一组）→ 最下面 **Generate token**。
3. **复制那串 `ghp_xxx` 令牌，发给我**（发完随时可在 GitHub 删掉它，很安全）。
4. 我帮你：建仓库 → 传代码 → 触发编译 → 把编好的 `app-debug.apk` 直接发给你。
5. 你拿到 APK → 手机上用 **MT 管理器** 安装即可。

> 令牌只用来这次传代码和取 APK，用完你就去上面那个页面把它删掉，没有任何风险。

---

## 路径 B（不开令牌，自己 5 步搞定，免费）

GitHub 的服务器有完整外网，会自己下载 SDK 帮你编，你本机不需要翻墙、不需要装软件。

### 第 1 步：注册 GitHub
浏览器打开 https://github.com → Sign up → 邮箱注册（免费）。

### 第 2 步：新建仓库
右上角 **+** → **New repository** → Name 填 `sleepguard` → 选 **Public** → 勾 **Add a README file** → **Create repository**。

### 第 3 步：上传两个文件夹
进仓库后点 **Add file** → **Upload files**：
- 把本项目的 **`sleepguard/android` 文件夹整体**拖进去（里面是 app 的全部源码）。
- 再把本项目的 **`sleepguard/.github` 文件夹整体**拖进去（云端编译脚本）。
- 拉到底点 **Commit changes**。
> 拖文件夹时保持原样，仓库里应出现 `android/` 和 `.github/` 两个目录。

### 第 4 步：一键编译
- 点顶部 **Actions** 标签 → 左侧 **Build SleepGuard APK** → **Run workflow** → 再点一次 **Run workflow**。
- 等 3~8 分钟，任务变绿色 ✓ 即成功（红色就点进去看日志，多半是文件夹传漏了）。

### 第 5 步：下载 APK
- 点进那个绿色任务 → 底部 **Artifacts** → 点 `sleepguard-debug-apk` 下载。
- 得到 `app-debug.apk` → 传到手机 → **MT 管理器** 安装。

---

## 装好之后怎么用
1. 打开 App → 允许「麦克风」「通知」「忽略电池优化」权限。
2. 点 **开始睡觉** → 手机放床头，整夜录音。
3. 识别到打鼾 / 呼吸暂停 → 「事件」列表记录并可回放。
4. 开启「手环告警」后命中事件会向华为手环10 发高优先级通知（亮屏+震动），未确认循环重发，直到你点「我醒了」。

## 常见问题
- **安装提示"未知来源"**：手机设置里允许 MT 管理器「安装未知应用」即可。
- **手环不震动**：Wear Engine 默认没开，先走手机闹钟兜底；要手环震动按 README 申请权限。
- **编译失败**：看 Actions 里红色日志；最常见是把 `android/` 或 `.github/` 漏传了。
