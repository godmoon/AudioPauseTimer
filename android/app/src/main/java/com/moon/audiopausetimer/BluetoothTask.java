package com.moon.audiopausetimer;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

/**
 * 「停止播放后自动关蓝牙，X 分钟后再自动开」的执行逻辑。
 *
 * 触发链：
 * 1. 媒体播放状态变化（{@link MediaListenerService} 里的会话回调）→ 停止播放就从现在起算 N 分钟；
 * 2. N 分钟闹钟到点（{@link BluetoothTaskReceiver}）→ 再确认一遍确实没在播放 → 关蓝牙 → 排 X 分钟后的开蓝牙闹钟；
 * 3. X 分钟闹钟到点 → 打开蓝牙 → 任务结束。
 *
 * 和「自动关闭应用」的配合：任务进行中若到自动关闭时间，{@link AutoExitReceiver} 会把关闭时间
 * 延后到本任务结束（见 {@link #getEstimatedEndAt(Context)}）；任务提前结束（恢复了播放）时，
 * 这里会立刻把延后的关闭补上，不会一直拖到下一个周期。
 */
public final class BluetoothTask {

    private static final String TAG = "AudioPauseTimer";

    /** 到点关蓝牙 */
    public static final String ACTION_OFF = "com.moon.audiopausetimer.action.BT_OFF";
    /** 到点开蓝牙 */
    public static final String ACTION_ON = "com.moon.audiopausetimer.action.BT_ON";

    private static final int REQ_OFF = 8901;
    private static final int REQ_ON = 8902;

    /** 被延后的自动关闭，在蓝牙任务结束后再等这么久才执行（留出蓝牙状态切换的时间） */
    public static final long AUTO_EXIT_BUFFER_MS = 10_000L;

    private BluetoothTask() {
    }

    // ---------- 播放状态驱动 ----------

    /** 媒体播放状态变化时调用：在播就取消等待，没在播就从现在起算 N 分钟 */
    public static void onPlaybackMaybeChanged(Context context) {
        Context app = context.getApplicationContext();
        if (!BluetoothStore.isEnabled(app)) {
            return;
        }
        if (!hasListenerPermission(app)) {
            Log.w(TAG, "没有通知使用权，无法判断播放状态，蓝牙任务暂停推进");
            return;
        }
        boolean playing = MediaPausser.anyPlaying(app);
        if (playing) {
            cancelWaiting(app);
        } else {
            ensureWaiting(app);
        }
    }

    /** 开始等待 N 分钟；已经在等待或已进入关蓝牙阶段就什么都不做 */
    private static void ensureWaiting(Context app) {
        if (BluetoothStore.getState(app) != BluetoothStore.STATE_IDLE) {
            return;
        }
        int minutes = BluetoothStore.getStopMinutes(app);
        long now = System.currentTimeMillis();
        long targetAt = now + minutes * 60_000L;
        BluetoothStore.setAnchorAt(app, now);
        BluetoothStore.setTargetAt(app, targetAt);
        BluetoothStore.setState(app, BluetoothStore.STATE_WAITING);
        arm(app, targetAt, ACTION_OFF, REQ_OFF);
        Log.i(TAG, "已停止播放，开始等待 " + minutes + " 分钟，之后关闭蓝牙");
    }

    /** 等待期间又播起来了：取消本次关蓝牙，把被延后的自动关闭补上 */
    private static void cancelWaiting(Context app) {
        if (BluetoothStore.getState(app) != BluetoothStore.STATE_WAITING) {
            return;
        }
        cancelAlarm(app, ACTION_OFF, REQ_OFF);
        BluetoothStore.clearTask(app);
        Log.i(TAG, "恢复播放，取消关闭蓝牙的等待");
        rearmDeferredAutoExit(app);
    }

    // ---------- 闹钟到点 ----------

    /** N 分钟到点：确认确实没在播放后再关蓝牙 */
    public static void onOffAlarm(Context context) {
        Context app = context.getApplicationContext();
        cancelAlarm(app, ACTION_OFF, REQ_OFF);
        if (!BluetoothStore.isEnabled(app)
                || BluetoothStore.getState(app) != BluetoothStore.STATE_WAITING) {
            Log.i(TAG, "关蓝牙到点，但任务已取消，忽略");
            return;
        }
        if (hasListenerPermission(app) && MediaPausser.anyPlaying(app)) {
            // 回调可能被系统吞掉，到点时再做一次兜底判断
            Log.i(TAG, "到点检查发现仍在播放，取消关闭蓝牙");
            BluetoothStore.clearTask(app);
            rearmDeferredAutoExit(app);
            return;
        }

        int offMinutes = BluetoothStore.getOffMinutes(app);
        boolean turnedOff = false;
        if (isBluetoothOn(app)) {
            turnedOff = setBluetooth(app, false);
        } else {
            Log.i(TAG, "蓝牙本来就是关的，任务结束时不会替用户打开");
        }
        BluetoothStore.setTurnedOffByUs(app, turnedOff);

        long now = System.currentTimeMillis();
        long targetAt = now + offMinutes * 60_000L;
        BluetoothStore.setAnchorAt(app, now);
        BluetoothStore.setTargetAt(app, targetAt);
        BluetoothStore.setState(app, BluetoothStore.STATE_OFF);
        arm(app, targetAt, ACTION_ON, REQ_ON);
        Log.i(TAG, (turnedOff ? "蓝牙已关闭" : "蓝牙关闭失败（系统拒绝）")
                + "，" + offMinutes + " 分钟后重新开启");
    }

    /** X 分钟到点：把本应用关掉的蓝牙重新打开，结束任务 */
    public static void onOnAlarm(Context context) {
        Context app = context.getApplicationContext();
        cancelAlarm(app, ACTION_ON, REQ_ON);
        boolean turnedOffByUs = BluetoothStore.isTurnedOffByUs(app);
        if (turnedOffByUs) {
            boolean ok = setBluetooth(app, true);
            Log.i(TAG, ok ? "已重新打开蓝牙，蓝牙任务结束" : "重新打开蓝牙失败，蓝牙任务结束");
        } else {
            Log.i(TAG, "蓝牙不是本应用关的，跳过打开操作，蓝牙任务结束");
        }
        BluetoothStore.clearTask(app);
        rearmDeferredAutoExit(app);
    }

    // ---------- 开关 / 恢复 ----------

    /** 用户关掉功能：撤销闹钟；若蓝牙是被本应用关的，顺手恢复 */
    public static void onFeatureDisabled(Context context) {
        Context app = context.getApplicationContext();
        boolean inOffState = BluetoothStore.getState(app) == BluetoothStore.STATE_OFF;
        if (inOffState && BluetoothStore.isTurnedOffByUs(app)) {
            setBluetooth(app, true);
            Log.i(TAG, "功能已关闭，把之前关掉的蓝牙重新打开");
        }
        cancelAllAlarms(app);
        BluetoothStore.clearTask(app);
        rearmDeferredAutoExit(app);
        Log.i(TAG, "蓝牙自动任务已停止");
    }

    /**
     * 补排闹钟：回到界面、开机重启后调用。
     * 闹钟可能已经丢失，但存档里的目标时间还在，重新设一次是幂等的；
     * 如果目标时间已经过去，就立刻补执行一次，避免「显示已开启却永远不动作」。
     */
    public static void rearm(Context context) {
        Context app = context.getApplicationContext();
        cancelAllAlarms(app);
        if (!BluetoothStore.isEnabled(app)) {
            BluetoothStore.clearTask(app);
            return;
        }
        int state = BluetoothStore.getState(app);
        if (state == BluetoothStore.STATE_IDLE) {
            // 空闲时由播放状态变化驱动，这里不动
            return;
        }
        long targetAt = BluetoothStore.getTargetAt(app);
        if (targetAt <= System.currentTimeMillis()) {
            Log.i(TAG, "发现已过点的蓝牙任务，立即补执行");
            if (state == BluetoothStore.STATE_WAITING) {
                onOffAlarm(app);
            } else {
                onOnAlarm(app);
            }
            return;
        }
        if (state == BluetoothStore.STATE_WAITING) {
            arm(app, targetAt, ACTION_OFF, REQ_OFF);
        } else {
            arm(app, targetAt, ACTION_ON, REQ_ON);
        }
    }

    /** 改了 N/X：等待中或蓝牙已关时，按新的分钟数从本阶段起点重算，避免把时间越推越晚 */
    public static void onConfigChanged(Context context) {
        Context app = context.getApplicationContext();
        if (!BluetoothStore.isEnabled(app)) {
            return;
        }
        int state = BluetoothStore.getState(app);
        if (state == BluetoothStore.STATE_IDLE) {
            return;
        }
        boolean waiting = state == BluetoothStore.STATE_WAITING;
        int minutes = waiting
                ? BluetoothStore.getStopMinutes(app)
                : BluetoothStore.getOffMinutes(app);
        long anchor = BluetoothStore.getAnchorAt(app);
        if (anchor <= 0) {
            anchor = System.currentTimeMillis();
        }
        long targetAt = Math.max(anchor + minutes * 60_000L, System.currentTimeMillis() + 1000L);
        BluetoothStore.setTargetAt(app, targetAt);
        if (waiting) {
            arm(app, targetAt, ACTION_OFF, REQ_OFF);
        } else {
            arm(app, targetAt, ACTION_ON, REQ_ON);
        }
        Log.i(TAG, "蓝牙任务时长已改为 " + minutes + " 分钟，重排到点时间=" + targetAt);
    }

    // ---------- 供自动关闭使用 ----------

    /** 蓝牙任务是否进行中（等待关蓝牙 / 蓝牙已关等待开启） */
    public static boolean isBusy(Context context) {
        return BluetoothStore.isEnabled(context)
                && BluetoothStore.getState(context) != BluetoothStore.STATE_IDLE;
    }

    /**
     * 任务预计完全结束的时间：等待中则是「关蓝牙时刻 + X 分钟」，已关则是「开蓝牙时刻」。
     * 自动关闭应用靠它把关闭时间往后排。
     */
    public static long getEstimatedEndAt(Context context) {
        int state = BluetoothStore.getState(context);
        if (state == BluetoothStore.STATE_WAITING) {
            return BluetoothStore.getTargetAt(context)
                    + BluetoothStore.getOffMinutes(context) * 60_000L;
        }
        if (state == BluetoothStore.STATE_OFF) {
            return BluetoothStore.getTargetAt(context);
        }
        return System.currentTimeMillis();
    }

    /** 任务因提前结束（恢复播放、关闭功能）而释放了自动关闭：把它补排到缓冲时间之后 */
    private static void rearmDeferredAutoExit(Context app) {
        if (!AutoExitStore.isEnabled(app) || !AutoExitStore.isDeferred(app)) {
            return;
        }
        long targetAt = System.currentTimeMillis() + AUTO_EXIT_BUFFER_MS;
        CountdownService.scheduleAutoExitAt(app, targetAt);
        Log.i(TAG, "蓝牙任务已结束，被延后的自动关闭排到 " + AUTO_EXIT_BUFFER_MS / 1000 + " 秒后");
    }

    // ---------- 权限与蓝牙接口 ----------

    /** 是否已授予「附近设备」权限（Android 12+ 动态权限） */
    public static boolean hasBluetoothPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean hasListenerPermission(Context context) {
        return NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.getPackageName());
    }

    private static BluetoothAdapter adapter(Context context) {
        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager != null ? manager.getAdapter() : null;
    }

    private static boolean isBluetoothOn(Context context) {
        try {
            BluetoothAdapter adapter = adapter(context);
            return adapter != null && adapter.isEnabled();
        } catch (SecurityException e) {
            Log.w(TAG, "没有蓝牙权限，读不到蓝牙状态", e);
            return false;
        }
    }

    /**
     * 打开/关闭系统蓝牙。
     * 依赖 targetSdk ≤ 32：Android 13 起 targetSdk ≥ 33 的应用调用这两个接口会一律返回 false。
     */
    private static boolean setBluetooth(Context context, boolean on) {
        try {
            BluetoothAdapter adapter = adapter(context);
            if (adapter == null) {
                Log.w(TAG, "设备不支持蓝牙");
                return false;
            }
            if (on == adapter.isEnabled()) {
                return true;
            }
            boolean ok = on ? adapter.enable() : adapter.disable();
            Log.i(TAG, (on ? "请求打开蓝牙" : "请求关闭蓝牙") + "，结果=" + ok);
            return ok;
        } catch (SecurityException e) {
            Log.w(TAG, "没有蓝牙权限，操作被拒绝", e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "蓝牙操作失败", e);
            return false;
        }
    }

    // ---------- 闹钟 ----------

    private static void arm(Context context, long targetAt, String action, int requestCode) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        PendingIntent pendingIntent = pendingIntent(context, action, requestCode);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && !alarmManager.canScheduleExactAlarms()) {
                Log.w(TAG, "无精确定时权限，蓝牙任务改用非精确闹钟");
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetAt, pendingIntent);
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetAt, pendingIntent);
            }
            Log.i(TAG, "已设置蓝牙任务闹钟 " + action + "，到点时间戳=" + targetAt);
        } catch (SecurityException e) {
            Log.w(TAG, "设置精确定时失败，退化为非精确闹钟", e);
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetAt, pendingIntent);
        }
    }

    private static void cancelAlarm(Context context, String action, int requestCode) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        alarmManager.cancel(pendingIntent(context, action, requestCode));
    }

    private static void cancelAllAlarms(Context context) {
        cancelAlarm(context, ACTION_OFF, REQ_OFF);
        cancelAlarm(context, ACTION_ON, REQ_ON);
    }

    private static PendingIntent pendingIntent(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, BluetoothTaskReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
