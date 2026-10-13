package com.moon.audiopausetimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机后重新武装「自动关闭应用」的闹钟。
 *
 * 设备重启会清空所有闹钟，但存档里的计划还在；
 * 不重新排一次就会出现「界面显示已设置、到点却不关闭」的假象。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "AudioPauseTimer";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        // 存档放在凭据加密存储里，只有解锁后才读得到，所以只关心解锁完成后的 BOOT_COMPLETED
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return;
        }
        if (!AutoExitStore.isEnabled(context)) {
            return;
        }
        // 开机后强制重算：几小时后模式从现在重新起一轮，固定时间模式算到下一个时刻
        Log.i(TAG, "开机完成，重新排上自动关闭闹钟");
        CountdownService.scheduleNextAutoExit(context);
    }
}
