package com.moon.audiopausetimer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 「停止播放后自动关蓝牙」的配置与任务状态存档。
 *
 * 配置（开关、N 分钟、X 分钟）每次打开应用都恢复显示；
 * 任务状态描述当前走到哪一步，进程被杀、设备重启后靠它把闹钟补回来。
 *
 * 状态机：IDLE（空闲，监听播放中）→ WAITING（已停止播放，等满 N 分钟）→ OFF（蓝牙已关，等 X 分钟）→ IDLE
 * 中途恢复播放会把 WAITING 直接打回 IDLE，已关的蓝牙则不会因为恢复播放而立刻打开。
 */
public final class BluetoothStore {

    /** 空闲：没有待执行的蓝牙任务 */
    public static final int STATE_IDLE = 0;
    /** 已停止播放，等待满 N 分钟后关蓝牙 */
    public static final int STATE_WAITING = 1;
    /** 蓝牙已关闭，等待 X 分钟后自动开启 */
    public static final int STATE_OFF = 2;

    public static final int MIN_MINUTES = 1;
    public static final int MAX_MINUTES = 1440;

    private static final String NAME = "bt_task";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_STOP_MINUTES = "stop_minutes";
    private static final String KEY_OFF_MINUTES = "off_minutes";
    private static final String KEY_STATE = "state";
    private static final String KEY_TARGET_AT = "target_at";
    private static final String KEY_ANCHOR_AT = "anchor_at";
    private static final String KEY_TURNED_OFF_BY_US = "turned_off_by_us";

    private BluetoothStore() {
    }

    private static SharedPreferences sp(Context context) {
        return context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // ---------- 配置 ----------

    public static void setEnabled(Context context, boolean enabled) {
        sp(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static boolean isEnabled(Context context) {
        return sp(context).getBoolean(KEY_ENABLED, false);
    }

    public static void saveConfig(Context context, int stopMinutes, int offMinutes) {
        sp(context).edit()
                .putInt(KEY_STOP_MINUTES, stopMinutes)
                .putInt(KEY_OFF_MINUTES, offMinutes)
                .apply();
    }

    /** 停止播放后等待多少分钟关蓝牙（N） */
    public static int getStopMinutes(Context context) {
        int value = sp(context).getInt(KEY_STOP_MINUTES, 10);
        return value >= MIN_MINUTES && value <= MAX_MINUTES ? value : 10;
    }

    /** 关闭蓝牙后多少分钟自动开启（X） */
    public static int getOffMinutes(Context context) {
        int value = sp(context).getInt(KEY_OFF_MINUTES, 5);
        return value >= MIN_MINUTES && value <= MAX_MINUTES ? value : 5;
    }

    // ---------- 任务状态 ----------

    public static void setState(Context context, int state) {
        sp(context).edit().putInt(KEY_STATE, state).apply();
    }

    public static int getState(Context context) {
        return sp(context).getInt(KEY_STATE, STATE_IDLE);
    }

    /**
     * 记录当前阶段的到点时间：WAITING 阶段是「关蓝牙时刻」，OFF 阶段是「开蓝牙时刻」。
     * 用 commit：到点执行后马上要重新排程，异步写盘可能赶不上进程被回收。
     */
    public static boolean setTargetAt(Context context, long targetAt) {
        return sp(context).edit().putLong(KEY_TARGET_AT, targetAt).commit();
    }

    public static long getTargetAt(Context context) {
        return sp(context).getLong(KEY_TARGET_AT, 0L);
    }

    /** 当前阶段的起点（停止播放 / 关蓝牙的时刻），改配置时按它重算，避免把时间越推越晚 */
    public static void setAnchorAt(Context context, long anchorAt) {
        sp(context).edit().putLong(KEY_ANCHOR_AT, anchorAt).apply();
    }

    public static long getAnchorAt(Context context) {
        return sp(context).getLong(KEY_ANCHOR_AT, 0L);
    }

    /** 蓝牙是不是本应用关掉的：是才需要在任务结束时替用户打开，避免动用户自己关的蓝牙 */
    public static void setTurnedOffByUs(Context context, boolean byUs) {
        sp(context).edit().putBoolean(KEY_TURNED_OFF_BY_US, byUs).apply();
    }

    public static boolean isTurnedOffByUs(Context context) {
        return sp(context).getBoolean(KEY_TURNED_OFF_BY_US, false);
    }

    /** 清空任务状态（不动开关与 N/X 配置） */
    public static void clearTask(Context context) {
        sp(context).edit()
                .putInt(KEY_STATE, STATE_IDLE)
                .putLong(KEY_TARGET_AT, 0L)
                .putLong(KEY_ANCHOR_AT, 0L)
                .putBoolean(KEY_TURNED_OFF_BY_US, false)
                .apply();
    }
}
