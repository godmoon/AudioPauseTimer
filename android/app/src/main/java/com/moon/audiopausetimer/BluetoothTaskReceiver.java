package com.moon.audiopausetimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 蓝牙任务闹钟的到点入口：
 * - {@link BluetoothTask#ACTION_OFF}：停止播放满 N 分钟，关蓝牙；
 * - {@link BluetoothTask#ACTION_ON}：蓝牙关闭满 X 分钟，重新打开。
 */
public class BluetoothTaskReceiver extends BroadcastReceiver {

    private static final String TAG = "AudioPauseTimer";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "蓝牙任务闹钟到点: " + action);
        if (BluetoothTask.ACTION_OFF.equals(action)) {
            BluetoothTask.onOffAlarm(context);
        } else if (BluetoothTask.ACTION_ON.equals(action)) {
            BluetoothTask.onOnAlarm(context);
        }
    }
}
