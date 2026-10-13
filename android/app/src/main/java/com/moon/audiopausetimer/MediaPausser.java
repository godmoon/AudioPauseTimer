package com.moon.audiopausetimer;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.util.Log;

import java.util.List;

/**
 * 媒体暂停工具：通过系统的 MediaSessionManager 拿到所有已注册媒体会话的应用，
 * 把正在播放（含缓冲/快进等活跃状态）的会话全部暂停。
 * 需要调用方拥有「通知使用权」，否则拿不到任何会话。
 */
public final class MediaPausser {

    private static final String TAG = "AudioPauseTimer";

    private MediaPausser() {
    }

    /** 获取系统里所有活跃的媒体会话，失败时返回空列表 */
    private static List<MediaController> getSessions(Context context) {
        try {
            MediaSessionManager manager =
                    (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) {
                return null;
            }
            ComponentName listener = new ComponentName(context, MediaListenerService.class);
            return manager.getActiveSessions(listener);
        } catch (SecurityException e) {
            // 用户还没授予通知使用权
            Log.w(TAG, "没有获取媒体会话的权限，请检查通知使用权", e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "获取媒体会话失败", e);
            return null;
        }
    }

    /** 当前是否处于「活跃播放」状态 */
    public static boolean isActive(int state) {
        return state == PlaybackState.STATE_PLAYING
                || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_FAST_FORWARDING
                || state == PlaybackState.STATE_REWINDING
                || state == PlaybackState.STATE_SKIPPING_TO_NEXT
                || state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS;
    }

    /** 暂停所有正在播放的媒体会话，返回被暂停的数量 */
    public static int pauseAll(Context context) {
        List<MediaController> sessions = getSessions(context);
        if (sessions == null || sessions.isEmpty()) {
            Log.d(TAG, "当前没有活跃的媒体会话");
            return 0;
        }

        int paused = 0;
        for (MediaController controller : sessions) {
            try {
                PlaybackState state = controller.getPlaybackState();
                if (state == null || !isActive(state.getState())) {
                    continue;
                }
                String pkg = safePkg(controller);
                controller.getTransportControls().pause();
                paused++;
                Log.i(TAG, "已暂停: " + pkg + " (state=" + state.getState() + ")");
            } catch (Exception e) {
                Log.e(TAG, "暂停媒体会话失败", e);
            }
        }
        Log.i(TAG, "本次共暂停 " + paused + " 个播放中的音频（会话总数 " + sessions.size() + "）");
        return paused;
    }

    /** 是否还有正在播放的媒体（用于「防止自动续播」的监督阶段） */
    public static boolean anyPlaying(Context context) {
        List<MediaController> sessions = getSessions(context);
        if (sessions == null) {
            return false;
        }
        for (MediaController controller : sessions) {
            try {
                PlaybackState state = controller.getPlaybackState();
                if (state != null && isActive(state.getState())) {
                    Log.d(TAG, "检测到仍在播放: " + safePkg(controller));
                    return true;
                }
            } catch (Exception e) {
                Log.e(TAG, "读取播放状态失败", e);
            }
        }
        return false;
    }

    /** 当前正在播放的媒体会话数量，用于在界面上给用户一个直观反馈 */
    public static int getPlayingCount(Context context) {
        List<MediaController> sessions = getSessions(context);
        if (sessions == null) {
            return 0;
        }
        int count = 0;
        for (MediaController controller : sessions) {
            try {
                PlaybackState state = controller.getPlaybackState();
                if (state != null && isActive(state.getState())) {
                    count++;
                }
            } catch (Exception e) {
                Log.e(TAG, "读取播放状态失败", e);
            }
        }
        return count;
    }

    private static String safePkg(MediaController controller) {
        try {
            android.media.session.MediaSession.Token token = controller.getSessionToken();
            return String.valueOf(token);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
