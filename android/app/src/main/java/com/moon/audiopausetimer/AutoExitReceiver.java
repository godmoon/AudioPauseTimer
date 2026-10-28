package com.moon.audiopausetimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 「自动关闭应用」闹钟的到点入口。
 *
 * 闹钟刻意指向广播接收器而不是直接 startService：应用在后台时 startService 可能被系统拒绝，
 * 这里能捕获异常并降级，保证到点后进程一定被结束；交给服务则可以做更干净的清理
 * （注销媒体监听、释放唤醒锁、撤前台通知）。
 */
public class AutoExitReceiver extends BroadcastReceiver {

    private static final String TAG = "AudioPauseTimer";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "自动关闭闹钟到点，准备彻底退出应用");
        // 开关本身不动：退出流程里会自动把下一次排好（固定时间排明天、几小时后重新起一轮）

        // 蓝牙任务（等关蓝牙 / 等开蓝牙）进行中就先不退出：
        // 关蓝牙后还要自动开回来，退早了任务就断了，所以把关闭时间延后到任务完全结束。
        if (BluetoothTask.isBusy(context)) {
            long endAt = BluetoothTask.getEstimatedEndAt(context);
            long deferTo = Math.max(endAt + BluetoothTask.AUTO_EXIT_BUFFER_MS,
                    System.currentTimeMillis() + 30_000L);
            AutoExitStore.setDeferred(context, true);
            AutoExitStore.setNextTargetAt(context, deferTo);
            CountdownService.scheduleAutoExit(context, deferTo);
            Log.i(TAG, "蓝牙任务进行中，自动关闭延后到时间戳=" + deferTo);
            return;
        }

        Intent exit = new Intent(context, CountdownService.class)
                .setAction(CountdownService.ACTION_AUTO_EXIT);
        try {
            context.startService(exit);
        } catch (Throwable t) {
            // 后台启动服务被系统拒绝：进程结束后唤醒锁与媒体回调会被系统自动回收，
            // 这里只需撤掉通知、清掉状态再结束进程即可
            Log.w(TAG, "无法启动服务执行退出，改为兜底清理并直接结束进程", t);
            // goAsync 让广播一直保持在处理中，避免 onReceive 一返回进程就被系统回收，
            // 由兜底退出流程在杀进程之前归还
            final PendingResult pendingResult = goAsync();
            CountdownService.exitWithoutService(context, new Runnable() {
                @Override
                public void run() {
                    pendingResult.finish();
                }
            });
        }
    }
}
