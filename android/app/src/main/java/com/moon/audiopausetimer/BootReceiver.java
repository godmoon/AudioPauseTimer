package com.moon.audiopausetimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机后重新武装各种闹钟。
 *
 * 设备重启会清空所有闹钟，但存档里的计划还在；
 * 不重新排一次就会出现「界面显示已设置、到点却不动作」的假象。
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

        // 蓝牙任务与自动关闭互相独立，先各自补排
        BluetoothTask.rearm(context);

        if (!AutoExitStore.isEnabled(context)) {
            return;
        }
        if (AutoExitStore.isDeferred(context) && BluetoothTask.isBusy(context)) {
            // 关闭时间本来就被蓝牙任务延后过：重启后继续按任务结束时间排，别退回下一个周期
            Log.i(TAG, "开机完成，自动关闭仍排在蓝牙任务结束后");
            CountdownService.scheduleAutoExitAt(context,
                    BluetoothTask.getEstimatedEndAt(context) + BluetoothTask.AUTO_EXIT_BUFFER_MS);
            return;
        }
        // 开机后强制重算：几小时后模式从现在重新起一轮，固定时间模式算到下一个时刻
        Log.i(TAG, "开机完成，重新排上自动关闭闹钟");
        CountdownService.scheduleNextAutoExit(context);
    }
}
