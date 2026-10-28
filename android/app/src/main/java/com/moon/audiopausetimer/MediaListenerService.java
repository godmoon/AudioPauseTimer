package com.moon.audiopausetimer;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 媒体会话监听服务。
 *
 * 1. 作为 MediaSessionManager.getActiveSessions() 的凭据：用户在系统设置里授予本应用「通知使用权」后，
 *    系统才会允许我们枚举并控制其它应用的媒体会话；
 * 2. 常驻监听播放状态：把「停止播放」事件交给 {@link BluetoothTask}，
 *    用于「停止播放 N 分钟后自动关蓝牙」——这条路径不依赖任何界面或倒计时服务。
 */
public class MediaListenerService extends NotificationListenerService {

    private static final String TAG = "AudioPauseTimer";

    private MediaSessionManager sessionManager;
    private final List<MediaController> controllers = new ArrayList<>();
    private boolean monitoring = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 播放状态变化：交给蓝牙任务判断「停播/续播」 */
    private final MediaController.Callback playbackCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            BluetoothTask.onPlaybackMaybeChanged(MediaListenerService.this);
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> list) {
                    refreshSessions(list);
                }
            };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "通知使用权已连接，开始监听媒体播放状态");
        startMonitoring();
        // 只补排已有的蓝牙任务闹钟（进程被杀/重启后闹钟可能丢了）；
        // 空闲时不主动起「等待关蓝牙」，否则白天打开应用也会把蓝牙关掉
        BluetoothTask.rearm(this);
    }

    @Override
    public void onListenerDisconnected() {
        Log.i(TAG, "通知使用权已断开，停止监听媒体播放状态");
        stopMonitoring();
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        stopMonitoring();
        super.onDestroy();
    }

    private void startMonitoring() {
        try {
            if (sessionManager == null) {
                sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            }
            if (sessionManager == null) {
                return;
            }
            ComponentName listener = new ComponentName(this, MediaListenerService.class);
            if (!monitoring) {
                sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listener);
                monitoring = true;
            }
            refreshSessions(sessionManager.getActiveSessions(listener));
        } catch (SecurityException e) {
            Log.w(TAG, "挂载媒体监听失败：没有通知使用权");
        } catch (Exception e) {
            Log.e(TAG, "挂载媒体监听失败", e);
        }
    }

    private void refreshSessions(List<MediaController> list) {
        for (MediaController controller : controllers) {
            try {
                controller.unregisterCallback(playbackCallback);
            } catch (Exception ignored) {
            }
        }
        controllers.clear();
        if (list == null || list.isEmpty()) {
            return;
        }
        for (MediaController controller : list) {
            try {
                controller.registerCallback(playbackCallback, handler);
                controllers.add(controller);
            } catch (Exception e) {
                Log.e(TAG, "挂载媒体会话回调失败", e);
            }
        }
    }

    private void stopMonitoring() {
        for (MediaController controller : controllers) {
            try {
                controller.unregisterCallback(playbackCallback);
            } catch (Exception ignored) {
            }
        }
        controllers.clear();
        if (sessionManager != null && monitoring) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (Exception ignored) {
            }
        }
        monitoring = false;
    }
}
