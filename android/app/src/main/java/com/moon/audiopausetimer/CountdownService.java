package com.moon.audiopausetimer;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * 倒计时前台服务。
 *
 * 普通模式：倒计时结束暂停所有正在播放的音频，然后服务收尾退出。
 * 连续定时模式：暂停后服务不退出，转入「等待重新播放」状态轮询；
 *              一旦检测到又有音频在播放（比如用户按耳机线控续播），就重新起一个同样时长的倒计时，如此循环，
 *              直到用户手动取消。
 *
 * 可靠性：倒计时期间用 AlarmManager 精确闹钟兜底（进程被杀也能到点执行）；
 *         等待期间用周期闹钟兜底，进程被杀后仍会被定期唤醒检查是否有播放。
 */
public class CountdownService extends Service {

    private static final String TAG = "AudioPauseTimer";

    public static final String ACTION_START = "com.moon.audiopausetimer.action.START";
    public static final String ACTION_CANCEL = "com.moon.audiopausetimer.action.CANCEL";
    public static final String ACTION_ALARM = "com.moon.audiopausetimer.action.ALARM";
    /** 通知栏「退出程序」按钮：停止服务、清掉所有状态并结束进程 */
    public static final String ACTION_EXIT = "com.moon.audiopausetimer.action.EXIT";
    /** 自动关闭闹钟到点：由 AutoExitReceiver 转发，同样执行彻底退出 */
    public static final String ACTION_AUTO_EXIT = "com.moon.audiopausetimer.action.AUTO_EXIT";
    /** 即将结束进程的广播：让前台界面先自行关闭，避免进程被杀时界面突兀消失 */
    public static final String ACTION_EXITING = "com.moon.audiopausetimer.action.EXITING";

    public static final String EXTRA_TOTAL_SECS = "total_secs";
    public static final String EXTRA_REPEAT = "repeat";

    /** 向 Activity 广播当前状态 */
    public static final String ACTION_TICK = "com.moon.audiopausetimer.action.TICK";
    /** 向 Activity 广播「已执行暂停」 */
    public static final String ACTION_DONE = "com.moon.audiopausetimer.action.DONE";
    public static final String EXTRA_REMAINING = "remaining";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_PAUSED_COUNT = "paused_count";
    public static final String EXTRA_NO_SESSION = "no_session";

    private static final String CHANNEL_ID = "pause_countdown";
    private static final int NOTIF_ID = 2001;
    private static final int ALARM_REQ_CODE = 8801;
    private static final int EXIT_REQ_CODE = 8802;
    /** 自动关闭闹钟的请求码：必须独立，否则会和通知栏「退出程序」按钮的 PendingIntent 串在一起 */
    private static final int AUTO_EXIT_REQ_CODE = 8803;

    /** 「等待重新播放」状态下的轮询间隔 */
    private static final long WAIT_CHECK_INTERVAL_MS = 5_000L;
    /** 「等待重新播放」状态下的兜底周期闹钟间隔（进程被杀时仍会唤醒检查） */
    private static final long WAIT_BACKUP_INTERVAL_MS = 5 * 60 * 1000L;
    /** 倒计时全程持锁时的缓冲时间，防止临近到点时锁先过期 */
    private static final long WAKELOCK_BUFFER_MS = 120_000L;
    /** 发出「即将退出」广播后，再等多久结束进程（留给界面 finish 的时间） */
    private static final long EXIT_DELAY_MS = 300L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    /** 等待状态下的轮询次数，仅用于控制日志频率 */
    private int waitPollCount = 0;

    /** 当前正在监听的媒体会话，用于接收播放状态变化回调 */
    private final java.util.List<MediaController> monitoredControllers = new java.util.ArrayList<>();
    private MediaSessionManager sessionManager;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;

    /**
     * 播放状态变化回调：比轮询可靠得多。
     * 躺着按耳机续播时屏幕是关的，CPU 可能已经休眠，Handler 的轮询收不到；
     * 而通过 MediaController 回调，系统会在状态变化时唤醒进程，从而立即重新计时。
     */
    private final MediaController.Callback playbackCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (state == null) {
                return;
            }
            if (TimerStore.getMode(CountdownService.this) != TimerStore.MODE_WAITING) {
                return;
            }
            if (!MediaPausser.isActive(state.getState())) {
                return;
            }
            Log.i(TAG, "监听到媒体恢复播放，立即重新开始一轮倒计时 (state=" + state.getState() + ")");
            restartCountdown();
        }
    };

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            long remaining = TimerStore.remainingSecs(CountdownService.this);
            if (remaining <= 0) {
                executePause();
                return;
            }
            updateCountdownNotification(remaining);
            sendTick(remaining, TimerStore.MODE_COUNTING);
            handler.postDelayed(this, 1000L);
        }
    };

    /** 「等待重新播放」状态下的轮询：一旦发现又有音频在播放，就重新起一轮倒计时 */
    private final Runnable waitCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (TimerStore.getMode(CountdownService.this) != TimerStore.MODE_WAITING) {
                Log.i(TAG, "状态已不是「等待播放」，停止轮询");
                return;
            }
            boolean playing = MediaPausser.anyPlaying(CountdownService.this);
            waitPollCount++;
            // 检测到播放时立刻记录；否则每 6 次（约 30 秒）记录一次，避免刷屏
            if (playing || waitPollCount % 6 == 0) {
                Log.i(TAG, "等待播放轮询第 " + waitPollCount + " 次，检测到正在播放=" + playing);
            }
            if (playing) {
                Log.i(TAG, "检测到重新播放，重新开始一轮倒计时");
                restartCountdown();
                return;
            }
            handler.postDelayed(this, WAIT_CHECK_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "CountdownService 创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "服务收到指令: " + action);

        if (ACTION_CANCEL.equals(action)) {
            cancelAll();
            return START_NOT_STICKY;
        }

        if (ACTION_EXIT.equals(action)) {
            // 通知栏「退出程序」：清干净所有后台活动后结束进程，彻底释放资源
            exitCompletely();
            return START_NOT_STICKY;
        }

        if (ACTION_AUTO_EXIT.equals(action)) {
            // 自动关闭到点：同样执行彻底退出，用户无需再去通知里长按退出
            Log.i(TAG, "自动关闭到点，执行彻底退出");
            exitCompletely();
            return START_NOT_STICKY;
        }

        // 注意：这里不能先检查 isRunning —— 首次启动时状态尚未写入，
        // 若在此拦截，ACTION_START 会永远进不来，导致服务不调用 startForeground 而崩溃。
        if (ACTION_START.equals(action)) {
            long totalSecs = intent.getLongExtra(EXTRA_TOTAL_SECS, 0L);
            boolean repeat = intent.getBooleanExtra(EXTRA_REPEAT, false);
            // 先落盘倒计时目标，再进前台状态（Android 12+ 要求 startForegroundService 后立刻调用）
            TimerStore.startCounting(this, totalSecs, System.currentTimeMillis() + totalSecs * 1000L, repeat);
            startCounting(totalSecs, repeat);
        } else if (ACTION_ALARM.equals(action)) {
            // 到点闹钟。倒计时期间它保证「到点必执行」（熄屏后 CPU 休眠、唤醒锁被 MIUI 冻结时，
            // 全靠它准时唤醒）；等待期间则作为进程被杀后的唤醒入口。
            resumeByMode();
        } else {
            // 系统重新下发前台服务指令
            resumeByMode();
        }

        return START_NOT_STICKY;
    }

    /** 按当前存档的状态恢复服务（闹钟唤醒、系统重启服务时走这里） */
    private void resumeByMode() {
        int mode = TimerStore.getMode(this);
        if (mode == TimerStore.MODE_COUNTING) {
            if (TimerStore.remainingSecs(this) <= 0) {
                Log.i(TAG, "到点闹钟触发，执行暂停");
                startForeground(NOTIF_ID, buildCountdownNotification(0));
                executePause();
            } else {
                // 只恢复计时，不能重设闹钟或重置目标时间，否则会把倒计时越推越晚
                Log.i(TAG, "闹钟唤醒服务，倒计时尚未到点，恢复计时");
                resumeCounting();
            }
        } else if (mode == TimerStore.MODE_WAITING) {
            Log.i(TAG, "闹钟唤醒服务，恢复「等待重新播放」监听");
            startForeground(NOTIF_ID, buildWaitingNotification());
            enterWaitLoop(false);
        } else {
            Log.d(TAG, "倒计时未在运行，停止服务");
            stopSelf();
        }
    }

    /** 开始一轮倒计时 */
    private void startCounting(long totalSecs, boolean repeat) {
        handler.removeCallbacks(waitCheckRunnable);
        handler.removeCallbacks(tickRunnable);
        startForeground(NOTIF_ID, buildCountdownNotification(TimerStore.remainingSecs(this)));
        scheduleTimeoutAlarm(System.currentTimeMillis() + totalSecs * 1000L);
        // 倒计时期间必须持有唤醒锁：熄屏后设备会进入 Doze，CPU 休眠会让 Handler 的每秒计时停摆，
        // 只能等亮屏才恢复，表现为「锁屏后不暂停、点亮屏幕才暂停」。
        // 有了唤醒锁，计时就能一路走到点；AlarmManager 仅作为进程被杀时的兜底。
        acquireWakeLock(totalSecs * 1000L + WAKELOCK_BUFFER_MS);
        sendTick(TimerStore.remainingSecs(this), TimerStore.MODE_COUNTING);
        handler.post(tickRunnable);
        Log.i(TAG, "开始倒计时 " + (totalSecs / 60) + " 分钟，连续定时=" + repeat);
    }

    /** 从存档恢复倒计时（闹钟唤醒、系统重启服务时走这里），不重置目标时间、不重设闹钟 */
    private void resumeCounting() {
        handler.removeCallbacks(waitCheckRunnable);
        handler.removeCallbacks(tickRunnable);
        long remaining = TimerStore.remainingSecs(this);
        startForeground(NOTIF_ID, buildCountdownNotification(remaining));
        acquireWakeLock(remaining * 1000L + WAKELOCK_BUFFER_MS);
        sendTick(remaining, TimerStore.MODE_COUNTING);
        handler.post(tickRunnable);
    }

    /** 「等待重新播放」状态的入口 */
    private void enterWaitLoop(boolean fromPause) {
        TimerStore.markWaiting(this);
        waitPollCount = 0;
        // 等待期间不需要 CPU 常驻：续播时系统会通过 MediaController 回调唤醒进程
        releaseWakeLock();
        registerPlaybackMonitor();
        if (fromPause) {
            // 暂停后先让通知显示「已暂停」，再转入等待态的常驻通知
            updateWaitingNotification();
            sendTick(0L, TimerStore.MODE_WAITING);
        }
        scheduleWaitingBackupAlarm();
        handler.removeCallbacks(waitCheckRunnable);
        handler.postDelayed(waitCheckRunnable, WAIT_CHECK_INTERVAL_MS);
        Log.i(TAG, "进入「等待重新播放」状态，轮询间隔 " + (WAIT_CHECK_INTERVAL_MS / 1000) + " 秒");
    }

    /** 重新起一轮倒计时（复用上次的时长设置） */
    private void restartCountdown() {
        unregisterPlaybackMonitor();
        long totalSecs = TimerStore.getTotalSecs(this);
        if (totalSecs <= 0) {
            Log.w(TAG, "缺少时长设置，无法重新计时，取消");
            cancelAll();
            return;
        }
        boolean repeat = TimerStore.getRepeat(this);
        TimerStore.startCounting(this, totalSecs,
                System.currentTimeMillis() + totalSecs * 1000L, repeat);
        startCounting(totalSecs, repeat);
    }

    /** 执行暂停，然后按「连续定时」设置决定收尾还是转入等待 */
    private void executePause() {
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(waitCheckRunnable);
        cancelAlarm();
        // 此时唤醒锁仍在手上（倒计时期间持有），保证暂停过程中 CPU 不会休眠

        int paused = MediaPausser.pauseAll(this);
        boolean nonePlaying = paused == 0 && !MediaPausser.anyPlaying(this);
        boolean repeat = TimerStore.getRepeat(this);
        Log.i(TAG, "倒计时结束，暂停数量=" + paused + "，无播放会话=" + nonePlaying
                + "，连续定时=" + repeat);

        sendToActivity(new Intent(ACTION_DONE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_PAUSED_COUNT, paused)
                .putExtra(EXTRA_NO_SESSION, nonePlaying));

        if (repeat) {
            // 连续定时：无论此刻有没有在播放，都不退出服务，转入监听；
            // 之后任何时候开始播放都会重新计时，直到用户手动取消。
            Log.i(TAG, "连续定时开启，转入监听状态（不退出服务），直到手动取消");
            enterWaitLoop(true);
        } else {
            Log.i(TAG, "未开启连续定时，倒计时结束");
            finishWithDoneNotice(paused);
        }
    }

    /** 结束并留下一条「已暂停」的通知，让用户离开 app 也能看到结果 */
    private void finishWithDoneNotice(int paused) {
        handler.removeCallbacksAndMessages(null);
        unregisterPlaybackMonitor();
        cancelAlarm();
        releaseWakeLock();
        TimerStore.clear(this);
        sendTick(0L, TimerStore.MODE_IDLE);
        updateDoneNotification(paused);
        // 传 false 以保留上面这条完成通知，只解除前台状态
        stopForeground(false);
        stopSelf();
    }

    private void cancelAll() {
        handler.removeCallbacksAndMessages(null);
        unregisterPlaybackMonitor();
        cancelAlarm();
        releaseWakeLock();
        TimerStore.clear(this);
        sendTick(0L, TimerStore.MODE_IDLE);
        stopForeground(true);
        stopSelf();
        Log.i(TAG, "倒计时已取消");
    }

    /** 彻底退出：停服务、撤监听、取消闹钟、移除通知，最后结束进程，把资源全部释放 */
    private void exitCompletely() {
        handler.removeCallbacksAndMessages(null);
        unregisterPlaybackMonitor();
        cancelAlarm();
        releaseWakeLock();
        TimerStore.clear(this);
        // 关掉自己之前先把下一次排上：固定时间落到明天同一刻、几小时后重新起一轮。
        // 开关是常开的，执行本身不动它，除非用户手动关掉。
        AutoExitStore.setDeferred(this, false);
        scheduleNextAutoExit(this);
        sendTick(0L, TimerStore.MODE_IDLE);
        // 先告诉界面「要退出了」，让它在进程结束前先 finish，避免界面被硬杀
        sendToActivity(new Intent(ACTION_EXITING).setPackage(getPackageName()));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIF_ID);
        }
        stopForeground(true);
        stopSelf();
        Log.i(TAG, "收到退出指令，清理完成，即将结束进程");
        // 稍等一拍再结束进程，确保上面的清理、日志和广播都已送出。
        // 必须用独立 Handler：stopSelf() 随后触发 onDestroy，那里会执行
        // handler.removeCallbacksAndMessages(null)，若挂在这个 handler 上，
        // 延迟任务会被一并清掉，导致进程永远杀不掉。
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "进程结束");
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, EXIT_DELAY_MS);
    }

    /**
     * 服务没在运行（或被后台限制拒绝启动）时的兜底退出：
     * 取消闹钟、清状态、撤通知，然后直接结束进程。
     * 唤醒锁与媒体监听都挂在进程上，进程一结束系统会自动回收，这里不需要也不该去碰服务实例。
     *
     * @param onFinish 进程结束前的收尾回调（用于归还广播占位），可传 null
     */
    public static void exitWithoutService(Context context, final Runnable onFinish) {
        Context app = context.getApplicationContext();
        cancelAlarmOf(app);
        TimerStore.clear(app);
        // 同样要在结束进程前把下一次排好，否则循环到这就断了
        AutoExitStore.setDeferred(app, false);
        scheduleNextAutoExit(app);
        NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIF_ID);
        }
        app.sendBroadcast(new Intent(ACTION_EXITING).setPackage(app.getPackageName()));
        Log.i(TAG, "兜底退出：清理完成，即将结束进程");
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (onFinish != null) {
                    onFinish.run();
                }
                Log.i(TAG, "进程结束");
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, EXIT_DELAY_MS);
    }

    // ---------- 通知 ----------

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notif_channel_desc));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private PendingIntent contentIntent() {
        return PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 通知栏「退出程序」按钮的意图 */
    private PendingIntent exitIntent() {
        Intent intent = new Intent(this, CountdownService.class).setAction(ACTION_EXIT);
        return PendingIntent.getService(
                this, EXIT_REQ_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildCountdownNotification(long remaining) {
        ensureChannel();
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_content, TimerStore.format(remaining)))
                .setSubText(getString(R.string.app_name))
                .setContentIntent(contentIntent())
                .addAction(R.drawable.ic_exit, getString(R.string.notif_btn_exit), exitIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    /** 「等待重新播放」状态的常驻通知 */
    private Notification buildWaitingNotification() {
        ensureChannel();
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_waiting_title))
                .setContentText(getString(R.string.notif_waiting_content))
                .setSubText(getString(R.string.app_name))
                .setContentIntent(contentIntent())
                .addAction(R.drawable.ic_exit, getString(R.string.notif_btn_exit), exitIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void updateCountdownNotification(long remaining) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIF_ID, buildCountdownNotification(remaining));
        }
    }

    private void updateWaitingNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIF_ID, buildWaitingNotification());
        }
    }

    private void updateDoneNotification(int paused) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        ensureChannel();
        String content = paused > 0
                ? getString(R.string.paused_count, paused)
                : getString(R.string.nothing_playing);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_done_title))
                .setContentText(content)
                .setSubText(getString(R.string.app_name))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build();
        manager.notify(NOTIF_ID, notification);
    }

    // ---------- 播放状态监听 ----------

    /** 注册媒体会话监听（进入「等待重新播放」状态时调用） */
    private void registerPlaybackMonitor() {
        try {
            if (sessionManager == null) {
                sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            }
            if (sessionManager == null) {
                return;
            }
            final ComponentName listener = new ComponentName(this, MediaListenerService.class);
            if (sessionsListener == null) {
                // 会话列表变化（播放器新注册会话）时重新挂监听
                sessionsListener = new MediaSessionManager.OnActiveSessionsChangedListener() {
                    @Override
                    public void onActiveSessionsChanged(java.util.List<MediaController> controllers) {
                        refreshMonitoredSessions(controllers, listener);
                    }
                };
                sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listener);
            }
            refreshMonitoredSessions(sessionManager.getActiveSessions(listener), listener);
        } catch (SecurityException e) {
            Log.w(TAG, "注册播放监听失败：没有通知使用权");
        } catch (Exception e) {
            Log.e(TAG, "注册播放监听失败", e);
        }
    }

    private void refreshMonitoredSessions(java.util.List<MediaController> controllers,
                                          ComponentName listener) {
        for (MediaController controller : monitoredControllers) {
            try {
                controller.unregisterCallback(playbackCallback);
            } catch (Exception ignored) {
            }
        }
        monitoredControllers.clear();
        if (controllers == null || controllers.isEmpty()) {
            Log.i(TAG, "当前没有活跃的媒体会话，等待播放器重新注册");
            return;
        }
        for (MediaController controller : controllers) {
            try {
                controller.registerCallback(playbackCallback, handler);
                monitoredControllers.add(controller);
            } catch (Exception e) {
                Log.e(TAG, "挂载会话回调失败", e);
            }
        }
        Log.i(TAG, "已挂载 " + monitoredControllers.size() + " 个媒体会话的播放状态监听");
    }

    /** 注销监听（离开等待状态、服务结束时调用） */
    private void unregisterPlaybackMonitor() {
        for (MediaController controller : monitoredControllers) {
            try {
                controller.unregisterCallback(playbackCallback);
            } catch (Exception ignored) {
            }
        }
        monitoredControllers.clear();
        if (sessionManager != null && sessionsListener != null) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (Exception ignored) {
            }
            sessionsListener = null;
        }
    }

    // ---------- AlarmManager 兜底 ----------

    /** 到点闹钟：到点必执行，即使进程被系统回收也能唤醒回来。这是熄屏后准时暂停的主力路径 */
    private void scheduleTimeoutAlarm(long targetAt) {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        setExactAlarm(alarmManager, targetAt, backupPendingIntent(this));
    }

    /**
     * 统一的闹钟设置：有精确定时权限就用精确闹钟，否则（或被系统拒绝时）退化为非精确闹钟。
     * 自动关闭与倒计时到点共用同一套判定，行为保持一致。
     */
    private static void setExactAlarm(AlarmManager alarmManager, long targetAt,
                                      PendingIntent pendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // 没有精确定时权限时退化为非精确闹钟（可能延迟几分钟）
                Log.w(TAG, "无精确定时权限，改用非精确闹钟");
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetAt, pendingIntent);
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetAt, pendingIntent);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "设置精确定时失败，退化为非精确闹钟", e);
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetAt, pendingIntent);
        }
    }

    // ---------- 自动关闭应用的闹钟 ----------

    /**
     * 设置「到点自动关闭应用」的精确闹钟。静态方法，供 Activity、开机广播等处调用；
     * 重复设置同一个 PendingIntent 会覆盖旧的，因此重新设置无需先取消。
     */
    public static void scheduleAutoExit(Context context, long targetAt) {
        Context app = context.getApplicationContext();
        AlarmManager alarmManager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        setExactAlarm(alarmManager, targetAt, autoExitPendingIntent(app));
        Log.i(TAG, "已设置自动关闭闹钟，到点时间戳=" + targetAt);
    }

    /** 取消「到点自动关闭应用」的闹钟（没有设置过时调用也是安全的空操作） */
    public static void cancelAutoExit(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager alarmManager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        alarmManager.cancel(autoExitPendingIntent(app));
    }

    /**
     * 直接排到指定时刻（用于「被蓝牙任务延后」的场景）：不改配置，只把这一次推迟。
     * 到点后照常执行，执行完再按配置滚动到下一次。
     */
    public static void scheduleAutoExitAt(Context context, long targetAt) {
        Context app = context.getApplicationContext();
        if (!AutoExitStore.isEnabled(app)) {
            cancelAutoExit(app);
            return;
        }
        AutoExitStore.setNextTargetAt(app, targetAt);
        scheduleAutoExit(app, targetAt);
        Log.i(TAG, "自动关闭已延后到时间戳=" + targetAt);
    }

    /**
     * 按当前配置强制排下一次自动关闭（重新算，不看已排的目标时间）。
     * 用于打开开关、修改配置、到点退出前、开机重启后这几种「本来就要重算」的场合。
     */
    public static void scheduleNextAutoExit(Context context) {
        Context app = context.getApplicationContext();
        if (!AutoExitStore.isEnabled(app)) {
            // 开关关着就只负责把闹钟撤掉
            cancelAutoExit(app);
            return;
        }
        long next = computeNextAutoExitAt(app, false);
        if (next <= 0) {
            Log.w(TAG, "自动关闭配置无效，取消排程");
            cancelAutoExit(app);
            return;
        }
        AutoExitStore.setNextTargetAt(app, next);
        scheduleAutoExit(app, next);
    }

    /**
     * 自愈式重排：只在闹钟丢失时补上，不动已经排好的未来时刻。
     * 用于界面回到前台——若每次回来都强制重算，几小时后的模式会一直被推迟，越拖越晚。
     */
    public static void rearmAutoExit(Context context) {
        Context app = context.getApplicationContext();
        if (!AutoExitStore.isEnabled(app)) {
            return;
        }
        long next = computeNextAutoExitAt(app, true);
        if (next <= 0) {
            cancelAutoExit(app);
            return;
        }
        AutoExitStore.setNextTargetAt(app, next);
        scheduleAutoExit(app, next);
    }

    /**
     * 计算下一次触发时间。
     *
     * @param keepFuture true 时，若已排的时刻仍在未来就原样保留（自愈场景）
     * @return 下一次触发时间戳；配置无效返回 -1
     */
    private static long computeNextAutoExitAt(Context app, boolean keepFuture) {
        if (AutoExitStore.getMode(app) == AutoExitStore.MODE_FIXED) {
            int hour = AutoExitStore.getFixedHour(app);
            int minute = AutoExitStore.getFixedMinute(app);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return -1L;
            }
            // 固定时刻：已过则顺延到明天，「每天重复」就是这么来的
            return AutoExitStore.nextFixedTimeMillis(hour, minute);
        }
        double hours = AutoExitStore.getHours(app);
        if (!(hours > 0) || hours > AutoExitStore.MAX_HOURS) {
            return -1L;
        }
        long targetAt = AutoExitStore.getTargetAt(app);
        if (keepFuture && targetAt > System.currentTimeMillis()) {
            return targetAt;
        }
        // 几小时后模式：从现在重新起一轮，形成「每 N 小时重复」
        return System.currentTimeMillis() + (long) (hours * 3600_000L);
    }

    /** 自动关闭闹钟：指向广播接收器，而不是直接 startService，以便在后台限制下还能兜底处理 */
    private static PendingIntent autoExitPendingIntent(Context context) {
        Intent intent = new Intent(context, AutoExitReceiver.class);
        return PendingIntent.getBroadcast(
                context, AUTO_EXIT_REQ_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 等待状态下的周期兜底闹钟：不需要精确定时权限，进程被杀后仍会定期唤醒 */
    private void scheduleWaitingBackupAlarm() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + WAIT_BACKUP_INTERVAL_MS,
                WAIT_BACKUP_INTERVAL_MS,
                backupPendingIntent(this));
    }

    private static PendingIntent backupPendingIntent(Context context) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, CountdownService.class).setAction(ACTION_ALARM);
        return PendingIntent.getService(
                app, ALARM_REQ_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void cancelAlarm() {
        cancelAlarmOf(this);
    }

    /** 取消倒计时到点/唤醒闹钟（静态版，供不持有服务实例的退出路径调用） */
    private static void cancelAlarmOf(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager alarmManager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        alarmManager.cancel(backupPendingIntent(app));
    }

    // ---------- 唤醒锁 ----------

    /** 短暂持有唤醒锁，确保指定时间内 CPU 不休眠 */
    private void acquireWakeLock(long timeoutMs) {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return;
        }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioPauseTimer:pause");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(timeoutMs);
        Log.d(TAG, "持有唤醒锁 " + (timeoutMs / 1000) + " 秒，保证熄屏期间计时不停摆");
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "唤醒锁已释放");
        }
        wakeLock = null;
    }

    // ---------- 广播 / 生命周期 ----------

    private void sendTick(long remaining, int mode) {
        sendToActivity(new Intent(ACTION_TICK)
                .setPackage(getPackageName())
                .putExtra(EXTRA_REMAINING, remaining)
                .putExtra(EXTRA_MODE, mode));
    }

    private void sendToActivity(Intent intent) {
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterPlaybackMonitor();
        releaseWakeLock();
        super.onDestroy();
        Log.i(TAG, "CountdownService 销毁");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
