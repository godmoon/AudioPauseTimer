package com.moon.audiopausetimer;

import android.service.notification.NotificationListenerService;

/**
 * 媒体会话监听服务。
 * 本身不做任何逻辑，只作为 MediaSessionManager.getActiveSessions() 的凭据：
 * 用户在系统设置里授予本应用「通知使用权」后，系统才会允许我们枚举并控制其它应用的媒体会话。
 */
public class MediaListenerService extends NotificationListenerService {
}
