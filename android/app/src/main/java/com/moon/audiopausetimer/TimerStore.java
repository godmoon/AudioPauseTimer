package com.moon.audiopausetimer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 倒计时状态存档：Activity 和 Service 共用，保证进程重建后仍能恢复倒计时。
 *
 * 状态机：IDLE（空闲）→ COUNTING（倒计时中）→ PAUSED_WAITING（已暂停、等待下次播放）→ COUNTING …
 * PAUSED_WAITING 只在「连续定时」开启时出现。
 */
public final class TimerStore {

    public static final int MODE_IDLE = 0;
    public static final int MODE_COUNTING = 1;
    public static final int MODE_WAITING = 2;

    private static final String NAME = "pause_timer";
    private static final String KEY_MODE = "mode";
    private static final String KEY_TARGET_AT = "target_at";
    private static final String KEY_TOTAL_SECS = "total_secs";
    private static final String KEY_REPEAT = "repeat";

    private TimerStore() {
    }

    private static SharedPreferences sp(Context context) {
        return context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** 进入「倒计时中」状态 */
    public static void startCounting(Context context, long totalSecs, long targetAt, boolean repeat) {
        sp(context).edit()
                .putInt(KEY_MODE, MODE_COUNTING)
                .putLong(KEY_TOTAL_SECS, totalSecs)
                .putLong(KEY_TARGET_AT, targetAt)
                .putBoolean(KEY_REPEAT, repeat)
                .apply();
    }

    /** 进入「已暂停、等待下次播放」状态，保留时长设置以便下次重新计时 */
    public static void markWaiting(Context context) {
        sp(context).edit().putInt(KEY_MODE, MODE_WAITING).apply();
    }

    public static void clear(Context context) {
        sp(context).edit().putInt(KEY_MODE, MODE_IDLE).apply();
    }

    public static int getMode(Context context) {
        return sp(context).getInt(KEY_MODE, MODE_IDLE);
    }

    public static boolean isRunning(Context context) {
        return getMode(context) != MODE_IDLE;
    }

    public static long getTargetAt(Context context) {
        return sp(context).getLong(KEY_TARGET_AT, 0L);
    }

    public static long getTotalSecs(Context context) {
        return sp(context).getLong(KEY_TOTAL_SECS, 0L);
    }

    public static boolean getRepeat(Context context) {
        return sp(context).getBoolean(KEY_REPEAT, false);
    }

    /** 剩余秒数（已过期返回 0） */
    public static long remainingSecs(Context context) {
        if (getMode(context) != MODE_COUNTING) {
            return 0L;
        }
        long left = getTargetAt(context) - System.currentTimeMillis();
        return left > 0 ? (left + 999) / 1000 : 0L;
    }

    /** 把秒数格式化成 1:02:03 / 05:30 这样的显示 */
    public static String format(long totalSecs) {
        long h = totalSecs / 3600;
        long m = (totalSecs % 3600) / 60;
        long s = totalSecs % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }
}
