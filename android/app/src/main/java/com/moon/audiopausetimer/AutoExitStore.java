package com.moon.audiopausetimer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 「自动关闭应用」的配置与开关存档。
 *
 * 分两部分，刻意分开保存：
 * 1. 配置（开关、关闭方式、几小时、固定时刻）—— 每次打开应用都恢复显示，随时可改；
 * 2. 下次触发时间 targetAt —— 只是当前排程的缓存，到点执行时会自动滚动到下一次。
 *
 * 开关是常开常关的：开着就一直生效（固定时间每天重复、几小时后每 N 小时重复），
 * 只有用户手动关掉才停，执行本身不会动开关。
 */
public final class AutoExitStore {

    /** 几小时后关闭 */
    public static final int MODE_HOURS = 0;
    /** 固定时间关闭 */
    public static final int MODE_FIXED = 1;

    /** 兜底有效期上限：720 小时 = 30 天 */
    public static final double MAX_HOURS = 720.0;

    private static final String NAME = "auto_exit";
    private static final String KEY_MODE = "mode";
    private static final String KEY_HOURS = "hours";
    private static final String KEY_FIXED_HOUR = "fixed_hour";
    private static final String KEY_FIXED_MINUTE = "fixed_minute";
    private static final String KEY_TARGET_AT = "target_at";
    private static final String KEY_ENABLED = "enabled";
    /** 本次关闭时间是否因为蓝牙任务被延后过：任务结束后要立刻补上 */
    private static final String KEY_DEFERRED = "deferred";

    private AutoExitStore() {
    }

    private static SharedPreferences sp(Context context) {
        return context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // ---------- 配置 ----------

    /** 保存本次的关闭方式设置（不含计划） */
    public static void saveConfig(Context context, int mode, double hours,
                                  int fixedHour, int fixedMinute) {
        sp(context).edit()
                .putInt(KEY_MODE, mode)
                .putFloat(KEY_HOURS, (float) hours)
                .putInt(KEY_FIXED_HOUR, fixedHour)
                .putInt(KEY_FIXED_MINUTE, fixedMinute)
                .apply();
    }

    public static int getMode(Context context) {
        int mode = sp(context).getInt(KEY_MODE, MODE_HOURS);
        return mode == MODE_FIXED ? MODE_FIXED : MODE_HOURS;
    }

    public static double getHours(Context context) {
        return sp(context).getFloat(KEY_HOURS, 1f);
    }

    /** 上次设置的固定时刻（小时），-1 表示从未设置过 */
    public static int getFixedHour(Context context) {
        return sp(context).getInt(KEY_FIXED_HOUR, -1);
    }

    public static int getFixedMinute(Context context) {
        return sp(context).getInt(KEY_FIXED_MINUTE, 0);
    }

    // ---------- 开关与排程 ----------

    /** 打开/关闭自动关闭功能（这是用户手动操作的总开关） */
    public static void setEnabled(Context context, boolean enabled) {
        SharedPreferences.Editor editor = sp(context).edit().putBoolean(KEY_ENABLED, enabled);
        if (!enabled) {
            // 关掉开关时顺便清掉「被蓝牙任务延后」的标记，下次打开重新按配置排
            editor.putBoolean(KEY_DEFERRED, false);
        }
        editor.apply();
    }

    public static boolean isEnabled(Context context) {
        return sp(context).getBoolean(KEY_ENABLED, false);
    }

    /** 标记本次关闭是否被蓝牙任务延后过 */
    public static void setDeferred(Context context, boolean deferred) {
        sp(context).edit().putBoolean(KEY_DEFERRED, deferred).apply();
    }

    public static boolean isDeferred(Context context) {
        return sp(context).getBoolean(KEY_DEFERRED, false);
    }

    /**
     * 记录下次触发时间。用 commit 而不是 apply：
     * 这条路径在「到点退出、马上要结束进程」时也会走到，异步写盘可能来不及落盘。
     *
     * @return 是否成功落盘
     */
    public static boolean setNextTargetAt(Context context, long targetAt) {
        return sp(context).edit().putLong(KEY_TARGET_AT, targetAt).commit();
    }

    public static long getTargetAt(Context context) {
        return sp(context).getLong(KEY_TARGET_AT, 0L);
    }

    // ---------- 固定时刻的计算 ----------

    /**
     * 某个「时:分」的下一次触发时间：今天的该时刻已经过了就顺延到明天。
     * 固定时间模式每天重复，靠的就是这个方法——到点执行时再算一次，自然就落到明天。
     *
     * 判定留 2 秒缓冲：闹钟几乎总是踩点触发，万一被算成「今天的还没到」，
     * 就会把闹钟排到两秒后再触发一次，紧接着又退出、又排程，变成连环退出。
     */
    public static long nextFixedTimeMillis(int hour, int minute) {
        java.util.Calendar target = java.util.Calendar.getInstance();
        target.set(java.util.Calendar.HOUR_OF_DAY, hour);
        target.set(java.util.Calendar.MINUTE, minute);
        target.set(java.util.Calendar.SECOND, 0);
        target.set(java.util.Calendar.MILLISECOND, 0);
        if (target.getTimeInMillis() <= System.currentTimeMillis() + 2000L) {
            target.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        return target.getTimeInMillis();
    }
}
