# 音频暂停定时器（AudioPauseTimer）

设定一个倒计时，时间到了自动把设备上**所有正在播放的音频暂停**。适合听书、听播客、睡前听歌时使用。

## 功能

- 倒计时结束暂停所有正在播放的音频（网易云音乐、QQ 音乐、Spotify、播客等主流播放器）
- 快捷时长：5 / 10 / 15 / 30 / 60 / 120 分钟，也可自定义输入（1–1440 分钟）
- **连续定时**：到点暂停后，若之后又开始播放（比如按耳机线控续播），会自动再起一轮同样时长的倒计时，如此循环，直到手动取消。适合睡前听书、听歌
- 记住上次的时长与「连续定时」设置，下次打开自动恢复
- 实时显示剩余时间，同时常驻一条前台服务通知
- 实时显示「当前正在播放 N 个音频」，可直观验证是否生效
- 到点后只是暂停，不会退出或关闭对方应用

## 工作原理

1. 应用内置一个空的 `NotificationListenerService`（`MediaListenerService`），用户在系统设置里授权「通知使用权」后，系统才允许我们调用 `MediaSessionManager.getActiveSessions()` 枚举设备上所有已注册媒体会话的应用。
2. 倒计时由前台服务 `CountdownService` 承担。准时性有三层保障，从主到次：
   - **精确闹钟**（`setExactAndAllowWhileIdle`，用 `USE_EXACT_ALARM` 权限自动授予，无需用户手动开启）——熄屏后 CPU 休眠时，靠它在到点那一刻唤醒系统执行暂停。实测可精确到毫秒级。
   - **`PARTIAL_WAKE_LOCK`**——屏幕亮着时保持 CPU 运行，让每秒计时照常走、通知里的剩余时间实时刷新。
   - **`AlarmManager` 进程被回收时的恢复入口**——闹钟唤醒后按存档状态自动恢复计时或监听。
   
   注意 MIUI/HyperOS 的 greeze 会冻结第三方应用并禁用其唤醒锁（日志可见 `disabled: true, reason: greeze`），所以务必把应用加入电池白名单、省电策略设为「无限制」，否则唤醒锁那条路径会失效。
3. 到点后遍历所有处于播放状态的 `MediaController`，逐个调用 `getTransportControls().pause()`。
4. **连续定时**开启时，暂停后服务不退出，而是转入「等待重新播放」状态：
   - 监听每个媒体会话的播放状态回调（`MediaController.Callback`）——这条是主路径。躺着按耳机续播时屏幕是关的、CPU 可能已休眠，Handler 轮询收不到，但系统会在状态变化时唤醒进程并回调，从而立即重新计时；
   - 另外每 5 秒做一次轮询兜底，并配一个 5 分钟的周期闹钟，保证进程被杀后仍能被唤醒恢复监听。

> 注意：只能控制注册了媒体会话的应用。游戏音效、未接入媒体会话的音频不在控制范围内。

## 使用步骤

1. 安装 APK 后打开应用
2. 点「去开启通知使用权」，在系统设置里找到「音频暂停定时器」并打开开关（这是本应用唯一必需的权限）
3. 回到应用，填写或点选时长，点「开始倒计时」
4. Android 13+ 首次使用会顺带申请通知权限，用于显示倒计时通知；不授权也能正常倒计时，只是看不到通知
5. **建议点「去加入电池白名单」**，并在系统设置里把省电策略设为「无限制」、允许自启动。这一步在 MIUI/HyperOS 上尤其关键：系统的 greeze 会冻结未加白名单的应用并禁用其唤醒锁，表现为熄屏后无法准点暂停

## 构建

```bash
cd android
./build-local.sh
```

产物：`android/audioPauseTimer-debug.apk`

## 安装与调试

```bash
# 安装
adb install -r ~/github/AudioPauseTimer/android/audioPauseTimer-debug.apk

# 启动应用
adb shell am start -n com.moon.audiopausetimer/.MainActivity

# 查看日志（本应用所有日志都带 AudioPauseTimer 标签）
adb logcat -s AudioPauseTimer

# 手动打开「通知使用权」设置页
adb shell am start -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS

# 确认通知使用权是否已授予
adb shell settings get secure enabled_notification_listeners
```

### 日志里能看到什么

```bash
adb logcat -s AudioPauseTimer
```

- `开始倒计时 1 分钟，连续定时=true` — 开始一轮，注意「连续定时」是否为 true
- `已暂停: xxx (state=3)` — 暂停了哪个应用
- `到点闹钟触发，执行暂停` — 熄屏后由精确闹钟唤醒执行（主力路径）
- `倒计时结束，暂停数量=1，无播放会话=false，连续定时=true` — 到点时的判定
- `未开启连续定时，倒计时结束` — 说明**没有勾选连续定时**，服务直接退出了（这是最容易被忽略的一点）
- `进入「等待重新播放」状态，轮询间隔 5 秒` / `已挂载 N 个媒体会话的播放状态监听` — 等待态已就绪
- `等待播放轮询第 N 次，检测到正在播放=true|false` — 兜底轮询的情况（每 30 秒记一次）
- `监听到媒体恢复播放，立即重新开始一轮倒计时` — 用户续播后被成功捕获，这是连续定时生效的标志

排查顺序：先看有没有「未开启连续定时」这句 → 再看有没有「进入等待状态」→ 最后看续播后有没有「监听到媒体恢复播放」。

## 目录结构

```
android/app/src/main/java/com/moon/audiopausetimer/
├── MainActivity.java          # 设置时长、权限引导、倒计时显示
├── CountdownService.java      # 前台服务：每秒计时、到点暂停、防续播监督
├── MediaPausser.java          # 通过 MediaSessionManager 暂停所有播放中的会话
├── MediaListenerService.java  # 空的 NotificationListenerService，仅作授权凭据
└── TimerStore.java            # 倒计时状态存档（Activity 与 Service 共用）
```
