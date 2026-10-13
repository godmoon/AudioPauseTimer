package com.moon.audiopausetimer;

import android.Manifest;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AudioPauseTimer";
    private static final String PREF_NAME = "ui_prefs";
    private static final String KEY_LAST_MINUTES = "last_minutes";
    private static final String KEY_LAST_REPEAT = "last_repeat";
    private static final int REQ_POST_NOTIFICATIONS = 1001;
    private static final int MIN_MINUTES = 1;
    private static final int MAX_MINUTES = 1440;

    private EditText etMinutes;
    private TextView btnStart;
    /** 主按钮当前是否处于「取消」形态（倒计时/等待中），决定点击走取消还是启动 */
    private boolean mainButtonIsCancel;
    private TextView tvCountdown;
    private TextView tvStatus;
    private SwitchCompat switchRepeat;

    private TextView tvPermListener;
    private TextView btnGrantListener;
    private TextView tvPermNotify;
    private TextView btnGrantNotify;
    private TextView tvPermAlarm;
    private TextView btnGrantAlarm;
    private TextView tvPermBattery;
    private TextView btnGrantBattery;
    private TextView tvNowPlaying;

    /** 收到「已完成」广播后，短暂保持完成文案，避免紧接着的空闲广播把它覆盖掉 */
    private long doneUntil = 0L;
    /** 最近一次暂停的音频数量，用于「等待下次播放」状态的文案 */
    private int lastPausedCount = 0;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** 前台时每 3 秒刷新一次「当前正在播放」的数量，让用户能直观看到本应用是否生效 */
    private final Runnable nowPlayingRunnable = new Runnable() {
        @Override
        public void run() {
            refreshNowPlaying();
            uiHandler.postDelayed(this, 3000L);
        }
    };

    private final BroadcastReceiver timerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (CountdownService.ACTION_TICK.equals(action)) {
                int mode = intent.getIntExtra(CountdownService.EXTRA_MODE, TimerStore.MODE_IDLE);
                long remaining = intent.getLongExtra(CountdownService.EXTRA_REMAINING, 0L);
                if (mode == TimerStore.MODE_COUNTING) {
                    showRunning(remaining);
                } else if (mode == TimerStore.MODE_WAITING) {
                    showWaiting();
                } else if (System.currentTimeMillis() > doneUntil) {
                    showIdle();
                }
            } else if (CountdownService.ACTION_DONE.equals(action)) {
                doneUntil = System.currentTimeMillis() + 6000L;
                lastPausedCount = intent.getIntExtra(CountdownService.EXTRA_PAUSED_COUNT, 0);
                boolean none = intent.getBooleanExtra(CountdownService.EXTRA_NO_SESSION, true);
                showDone(none ? 0 : lastPausedCount, none);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etMinutes = findViewById(R.id.et_minutes);
        btnStart = findViewById(R.id.btn_start);
        tvCountdown = findViewById(R.id.tv_countdown);
        tvStatus = findViewById(R.id.tv_status);
        switchRepeat = findViewById(R.id.switch_repeat);

        tvPermListener = findViewById(R.id.tv_perm_listener);
        btnGrantListener = findViewById(R.id.btn_grant_listener);
        tvPermNotify = findViewById(R.id.tv_perm_notify);
        btnGrantNotify = findViewById(R.id.btn_grant_notify);
        tvPermAlarm = findViewById(R.id.tv_perm_alarm);
        btnGrantAlarm = findViewById(R.id.btn_grant_alarm);
        tvPermBattery = findViewById(R.id.tv_perm_battery);
        btnGrantBattery = findViewById(R.id.btn_grant_battery);
        tvNowPlaying = findViewById(R.id.tv_now_playing);

        setupQuickChips();
        setupPermissionButtons();

        // 同一个按钮双形态：空闲时点它开始，倒计时/等待中点它取消
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mainButtonIsCancel) {
                    cancelCountdown();
                } else {
                    startCountdown();
                }
            }
        });

        // 恢复上次的时长与「连续定时」设置
        SharedPreferences pref = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        etMinutes.setText(String.valueOf(pref.getInt(KEY_LAST_MINUTES, 30)));
        switchRepeat.setChecked(pref.getBoolean(KEY_LAST_REPEAT, true));
        switchRepeat.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                saveUiPrefs();
            }
        });

        registerTimerReceiver();
    }

    private void setupQuickChips() {
        int[] ids = {R.id.btn_quick_5, R.id.btn_quick_10, R.id.btn_quick_15,
                R.id.btn_quick_30, R.id.btn_quick_60, R.id.btn_quick_120};
        final int[] values = {5, 10, 15, 30, 60, 120};
        for (int i = 0; i < ids.length; i++) {
            final int minutes = values[i];
            View chip = findViewById(ids[i]);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    etMinutes.setText(String.valueOf(minutes));
                }
            });
        }
    }

    private void setupPermissionButtons() {
        btnGrantListener.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, R.string.no_permission_toast, Toast.LENGTH_LONG).show();
                }
            }
        });

        btnGrantNotify.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.POST_NOTIFICATIONS},
                            REQ_POST_NOTIFICATIONS);
                } else {
                    openAppNotificationSettings();
                }
            }
        });

        btnGrantAlarm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        openAppNotificationSettings();
                    }
                }
            }
        });

        btnGrantBattery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 系统标准入口：请求忽略电池优化
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    // 部分 ROM（含 MIUI）会拒绝该意图，退回应用详情页，由用户手动设置省电策略
                    openAppNotificationSettings();
                }
            }
        });
    }

    // ---------- 倒计时控制 ----------

    private void startCountdown() {
        int minutes;
        try {
            minutes = Integer.parseInt(etMinutes.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, R.string.invalid_duration, Toast.LENGTH_SHORT).show();
            return;
        }
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
            Toast.makeText(this, R.string.invalid_duration, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!hasListenerPermission()) {
            // 没有通知使用权就无法控制其它应用的媒体会话，必须先授权
            Toast.makeText(this, R.string.no_permission_toast, Toast.LENGTH_LONG).show();
            refreshPermissionUi();
            btnGrantListener.setVisibility(View.VISIBLE);
            return;
        }

        // Android 13+ 申请通知权限，用于显示倒计时通知（不申请也能倒计时，只是看不到通知）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIFICATIONS);
        }

        long totalSecs = minutes * 60L;
        Intent intent = new Intent(this, CountdownService.class)
                .setAction(CountdownService.ACTION_START)
                .putExtra(CountdownService.EXTRA_TOTAL_SECS, totalSecs)
                .putExtra(CountdownService.EXTRA_REPEAT, switchRepeat.isChecked());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        saveUiPrefs();
        doneUntil = 0L;
        showRunning(totalSecs);
    }

    /** 保存时长与「连续定时」设置，供下次打开时恢复 */
    private void saveUiPrefs() {
        SharedPreferences.Editor editor = getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit();
        try {
            int minutes = Integer.parseInt(etMinutes.getText().toString().trim());
            if (minutes >= MIN_MINUTES && minutes <= MAX_MINUTES) {
                editor.putInt(KEY_LAST_MINUTES, minutes);
            }
        } catch (NumberFormatException ignored) {
            // 输入框为空或非法时保留上次的有效值
        }
        editor.putBoolean(KEY_LAST_REPEAT, switchRepeat.isChecked());
        editor.apply();
    }

    private void cancelCountdown() {
        Intent intent = new Intent(this, CountdownService.class)
                .setAction(CountdownService.ACTION_CANCEL);
        startService(intent);
        TimerStore.clear(this);
        showIdle();
    }

    // ---------- 界面状态 ----------

    /**
     * 主按钮双形态切换：同一个按钮在「开始」和「取消」之间变形。
     *
     * @param running true = 取消形态（描边红字，倒计时或等待中），false = 开始形态（实心主色）
     * @param enabled 是否可点击（服务收尾的短暂瞬间两者都不该响应）
     */
    private void setMainButton(boolean running, boolean enabled) {
        mainButtonIsCancel = running;
        if (running) {
            btnStart.setText(R.string.btn_cancel);
            btnStart.setBackgroundResource(R.drawable.bg_button_outline);
            btnStart.setTextColor(getColor(R.color.warn));
        } else {
            btnStart.setText(R.string.btn_start);
            btnStart.setBackgroundResource(R.drawable.bg_button_primary);
            btnStart.setTextColor(Color.WHITE);
        }
        btnStart.setEnabled(enabled);
    }

    private void showIdle() {
        tvCountdown.setText("--:--");
        tvStatus.setText(R.string.status_idle);
        tvStatus.setTextColor(getColor(R.color.text_secondary));
        setMainButton(false, true);
        etMinutes.setEnabled(true);
    }

    private void showRunning(long remainingSecs) {
        tvCountdown.setText(TimerStore.format(remainingSecs));
        tvStatus.setText(R.string.status_running);
        tvStatus.setTextColor(getColor(R.color.accent));
        setMainButton(true, true);
        etMinutes.setEnabled(false);
    }

    private void showDone(int pausedCount, boolean nonePlaying) {
        tvCountdown.setText("00:00");
        if (nonePlaying) {
            tvStatus.setText(R.string.nothing_playing);
        } else {
            tvStatus.setText(getString(R.string.paused_count, pausedCount));
        }
        tvStatus.setTextColor(getColor(R.color.ok));
        setMainButton(false, true);
        etMinutes.setEnabled(true);
    }

    /** 已到点、服务正在收尾的状态 */
    private void showFinishing() {
        tvCountdown.setText("00:00");
        tvStatus.setText(R.string.status_done);
        tvStatus.setTextColor(getColor(R.color.ok));
        setMainButton(false, false);
        etMinutes.setEnabled(false);
    }

    /** 「已暂停、等待下次播放」状态：连续定时模式下，取消操作必须仍然可用 */
    private void showWaiting() {
        tvCountdown.setText("--:--");
        if (lastPausedCount > 0) {
            tvStatus.setText(getString(R.string.status_waiting_count, lastPausedCount));
        } else {
            tvStatus.setText(R.string.status_waiting);
        }
        tvStatus.setTextColor(getColor(R.color.accent));
        setMainButton(true, true);
        etMinutes.setEnabled(false);
    }

    private void refreshTimerState() {
        int mode = TimerStore.getMode(this);
        if (mode == TimerStore.MODE_COUNTING) {
            long remaining = TimerStore.remainingSecs(this);
            if (remaining > 0) {
                showRunning(remaining);
            } else {
                // 已到点但服务仍在收尾，此时不该再显示「取消」按钮
                showFinishing();
            }
        } else if (mode == TimerStore.MODE_WAITING) {
            showWaiting();
        } else {
            showIdle();
        }
    }

    /** 刷新「当前正在播放」的数量；未授权时如实提示，避免误以为没有音频在播 */
    private void refreshNowPlaying() {
        if (!hasListenerPermission()) {
            tvNowPlaying.setText(R.string.perm_listener_missing);
            tvNowPlaying.setTextColor(getColor(R.color.warn));
            return;
        }
        int count = MediaPausser.getPlayingCount(this);
        if (count > 0) {
            tvNowPlaying.setText(getString(R.string.now_playing_count, count));
            tvNowPlaying.setTextColor(getColor(R.color.ok));
        } else {
            tvNowPlaying.setText(R.string.now_playing_none);
            tvNowPlaying.setTextColor(getColor(R.color.text_secondary));
        }
    }

    // ---------- 权限 ----------

    private boolean hasListenerPermission() {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(getPackageName());
    }

    private void refreshPermissionUi() {
        // 通知使用权
        if (hasListenerPermission()) {
            tvPermListener.setText(R.string.perm_listener_ok);
            tvPermListener.setTextColor(getColor(R.color.ok));
            btnGrantListener.setVisibility(View.GONE);
        } else {
            tvPermListener.setText(R.string.perm_listener_missing);
            tvPermListener.setTextColor(getColor(R.color.warn));
            btnGrantListener.setVisibility(View.VISIBLE);
        }

        // 通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean enabled = NotificationManagerCompat.from(this).areNotificationsEnabled();
            if (enabled) {
                tvPermNotify.setText(R.string.perm_notify_ok);
                tvPermNotify.setTextColor(getColor(R.color.ok));
                tvPermNotify.setVisibility(View.VISIBLE);
                btnGrantNotify.setVisibility(View.GONE);
            } else {
                tvPermNotify.setText(R.string.perm_notify_missing);
                tvPermNotify.setTextColor(getColor(R.color.warn));
                tvPermNotify.setVisibility(View.VISIBLE);
                btnGrantNotify.setVisibility(View.VISIBLE);
            }
        } else {
            tvPermNotify.setVisibility(View.GONE);
            btnGrantNotify.setVisibility(View.GONE);
        }

        // 精确定时权限（Android 12+），只在缺失时提示，避免平时打扰
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
            boolean canExact = alarmManager != null && alarmManager.canScheduleExactAlarms();
            if (canExact) {
                tvPermAlarm.setVisibility(View.GONE);
                btnGrantAlarm.setVisibility(View.GONE);
            } else {
                tvPermAlarm.setText(R.string.perm_alarm_missing);
                tvPermAlarm.setTextColor(getColor(R.color.warn));
                tvPermAlarm.setVisibility(View.VISIBLE);
                btnGrantAlarm.setVisibility(View.VISIBLE);
            }
        } else {
            tvPermAlarm.setVisibility(View.GONE);
            btnGrantAlarm.setVisibility(View.GONE);
        }
        // 电池优化白名单：MIUI/HyperOS 的 greeze 会冻结未加白名单的应用，
        // 熄屏后唤醒锁被禁用、闹钟也可能投递不到，导致无法准点暂停
        boolean ignoringBattery;
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        ignoringBattery = powerManager != null
                && powerManager.isIgnoringBatteryOptimizations(getPackageName());
        if (ignoringBattery) {
            tvPermBattery.setText(R.string.perm_battery_ok);
            tvPermBattery.setTextColor(getColor(R.color.ok));
            tvPermBattery.setVisibility(View.VISIBLE);
            btnGrantBattery.setVisibility(View.GONE);
        } else {
            tvPermBattery.setText(R.string.perm_battery_missing);
            tvPermBattery.setTextColor(getColor(R.color.warn));
            tvPermBattery.setVisibility(View.VISIBLE);
            btnGrantBattery.setVisibility(View.VISIBLE);
        }
    }

    private void openAppNotificationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            refreshPermissionUi();
        }
    }

    // ---------- 广播 / 生命周期 ----------

    private void registerTimerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(CountdownService.ACTION_TICK);
        filter.addAction(CountdownService.ACTION_DONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(timerReceiver, filter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionUi();
        refreshTimerState();
        uiHandler.removeCallbacks(nowPlayingRunnable);
        uiHandler.post(nowPlayingRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开界面时把当前设置存下来，下次打开可恢复
        saveUiPrefs();
        uiHandler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(timerReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
