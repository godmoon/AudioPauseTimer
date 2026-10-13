package com.moon.audiopausetimer;

import android.Manifest;
import android.app.AlarmManager;
import android.app.TimePickerDialog;
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
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.util.Calendar;

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

    // ---------- 自动关闭应用 ----------
    private RadioGroup rgExitMode;
    private View rowExitHours;
    private View rowExitFixed;
    private EditText etExitHours;
    private TextView btnPickTime;
    private TextView tvAutoExitStatus;
    private SwitchCompat switchAutoExit;
    /** 固定时间模式选择的时刻（小时/分钟），改动即写回存档 */
    private int exitFixedHour = 23;
    private int exitFixedMinute = 0;

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
            // 顺带刷新自动关闭的剩余时间，让状态行实时走字
            refreshAutoExitStatus();
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
            } else if (CountdownService.ACTION_EXITING.equals(action)) {
                // 应用马上要结束进程，先让界面自己关掉，避免被硬杀时画面卡住
                finish();
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
        setupAutoExitCard();
        // 恢复上次的自动关闭配置（含计划状态），要放在控件都绑好事件之后
        restoreAutoExitConfig();

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

    // ---------- 自动关闭应用 ----------

    /** 绑定「自动关闭应用」卡片的交互 */
    private void setupAutoExitCard() {
        rgExitMode = findViewById(R.id.rg_exit_mode);
        rowExitHours = findViewById(R.id.row_exit_hours);
        rowExitFixed = findViewById(R.id.row_exit_fixed);
        etExitHours = findViewById(R.id.et_exit_hours);
        btnPickTime = findViewById(R.id.btn_pick_time);
        tvAutoExitStatus = findViewById(R.id.tv_auto_exit_status);
        switchAutoExit = findViewById(R.id.switch_auto_exit);

        // 快捷时长：点一下直接填入小时数
        int[] ids = {R.id.btn_exit_quick_1, R.id.btn_exit_quick_2,
                R.id.btn_exit_quick_4, R.id.btn_exit_quick_8};
        final double[] values = {1, 2, 4, 8};
        for (int i = 0; i < ids.length; i++) {
            final double hours = values[i];
            findViewById(ids[i]).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    etExitHours.setText(formatExitHours(hours));
                    saveAutoExitConfig();
                    rescheduleAutoExitIfOn();
                }
            });
        }

        rgExitMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                applyExitModeUi();
                saveAutoExitConfig();
                rescheduleAutoExitIfOn();
                refreshAutoExitStatus();
            }
        });

        // 小时数边输边生效：开关开着时每变一次就按新时长重排一次
        etExitHours.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                saveAutoExitConfig();
                rescheduleAutoExitIfOn();
                refreshAutoExitStatus();
            }
        });

        btnPickTime.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new TimePickerDialog(MainActivity.this, new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
                        exitFixedHour = hourOfDay;
                        exitFixedMinute = minute;
                        btnPickTime.setText(formatClock(hourOfDay, minute));
                        saveAutoExitConfig();
                        rescheduleAutoExitIfOn();
                        refreshAutoExitStatus();
                    }
                }, exitFixedHour, exitFixedMinute, true).show();
            }
        });

        // 总开关：打开即按当前配置排程，关掉即撤销，选项区跟着显隐
        switchAutoExit.setOnCheckedChangeListener(exitSwitchListener);
    }

    private final CompoundButton.OnCheckedChangeListener exitSwitchListener =
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (isChecked && !isExitHoursValid()
                            && currentExitMode() == AutoExitStore.MODE_HOURS) {
                        // 小时数不合法就别打开，先摘监听再回弹，避免递归触发一次「已关闭」
                        switchAutoExit.setOnCheckedChangeListener(null);
                        switchAutoExit.setChecked(false);
                        switchAutoExit.setOnCheckedChangeListener(exitSwitchListener);
                        Toast.makeText(MainActivity.this, R.string.invalid_hours,
                                Toast.LENGTH_SHORT).show();
                        refreshAutoExitStatus();
                        return;
                    }
                    AutoExitStore.setEnabled(MainActivity.this, isChecked);
                    if (isChecked) {
                        CountdownService.scheduleNextAutoExit(MainActivity.this);
                        Toast.makeText(MainActivity.this, R.string.toast_auto_exit_on,
                                Toast.LENGTH_SHORT).show();
                    } else {
                        CountdownService.cancelAutoExit(MainActivity.this);
                        Toast.makeText(MainActivity.this, R.string.toast_auto_exit_off,
                                Toast.LENGTH_SHORT).show();
                    }
                    applyExitModeUi();
                    refreshAutoExitStatus();
                }
            };

    /** 开关开着时重新排下一次；关着就什么都不做 */
    private void rescheduleAutoExitIfOn() {
        if (switchAutoExit != null && switchAutoExit.isChecked()) {
            CountdownService.scheduleNextAutoExit(this);
        }
    }

    /** 小时数是否合法（0 与上限 720 之间） */
    private boolean isExitHoursValid() {
        double hours = parseExitHours();
        return hours > 0 && hours <= AutoExitStore.MAX_HOURS;
    }

    /** 每次打开应用都读取上次的配置与计划记录，把它们恢复到界面上 */
    private void restoreAutoExitConfig() {
        int mode = AutoExitStore.getMode(this);
        double hours = AutoExitStore.getHours(this);
        exitFixedHour = AutoExitStore.getFixedHour(this);
        exitFixedMinute = AutoExitStore.getFixedMinute(this);
        if (exitFixedHour < 0 || exitFixedHour > 23 || exitFixedMinute < 0 || exitFixedMinute > 59) {
            // 从未设置过固定时刻：默认给当前时间往后一小时，点一下确认即可
            Calendar calendar = Calendar.getInstance();
            calendar.add(Calendar.HOUR_OF_DAY, 1);
            exitFixedHour = calendar.get(Calendar.HOUR_OF_DAY);
            exitFixedMinute = calendar.get(Calendar.MINUTE);
            AutoExitStore.saveConfig(this, mode, hours, exitFixedHour, exitFixedMinute);
        }

        // 恢复开关状态要先摘监听：挂回后再由 onResume 的自愈重排把闹钟补上
        switchAutoExit.setOnCheckedChangeListener(null);
        switchAutoExit.setChecked(AutoExitStore.isEnabled(this));
        switchAutoExit.setOnCheckedChangeListener(exitSwitchListener);

        // 先填好输入控件再 check：check 会触发监听器，而监听器要读控件内容
        etExitHours.setText(formatExitHours(hours));
        btnPickTime.setText(formatClock(exitFixedHour, exitFixedMinute));
        rgExitMode.check(mode == AutoExitStore.MODE_FIXED ? R.id.rb_exit_fixed : R.id.rb_exit_hours);
        applyExitModeUi();
        refreshAutoExitStatus();
    }

    /** 保存当前的关闭方式配置（不含排程状态），供下次打开时恢复 */
    private void saveAutoExitConfig() {
        double hours = parseExitHours();
        if (hours < 0) {
            // 输入框为空或不是数字时保留上次的有效值；
            // 0、超过上限这类「是数字但不合法」的值照样存进去，
            // 这样状态行和闹钟读同一份数据，不会一个提示无效、另一个还排着
            hours = AutoExitStore.getHours(this);
        }
        AutoExitStore.saveConfig(this, currentExitMode(), hours, exitFixedHour, exitFixedMinute);
    }

    /** 刷新自动关闭状态行，由 3 秒轮询、开关与配置变化、打开应用时调用 */
    private void refreshAutoExitStatus() {
        if (tvAutoExitStatus == null) {
            return;
        }
        if (!AutoExitStore.isEnabled(this)) {
            tvAutoExitStatus.setText(R.string.auto_exit_status_off);
            tvAutoExitStatus.setTextColor(getColor(R.color.text_secondary));
            return;
        }
        long now = System.currentTimeMillis();
        long targetAt = AutoExitStore.getTargetAt(this);
        if (currentExitMode() == AutoExitStore.MODE_FIXED) {
            if (targetAt <= now) {
                // 刚打开开关、排程还没落盘时的兜底显示：直接算下一个时刻
                targetAt = AutoExitStore.nextFixedTimeMillis(exitFixedHour, exitFixedMinute);
            }
            tvAutoExitStatus.setText(getString(R.string.auto_exit_status_daily,
                    formatClock(exitFixedHour, exitFixedMinute),
                    formatExitDayLabel(targetAt),
                    formatClockFromMillis(targetAt),
                    TimerStore.format((targetAt - now + 999) / 1000)));
            tvAutoExitStatus.setTextColor(getColor(R.color.ok));
        } else {
            // 与排程同源：都读存档。输入框短暂为空/非法时存档保留上次有效值，
            // 状态行和闹钟就会一致地按有效值走，而不是一边提示无效一边还排着
            double hours = AutoExitStore.getHours(this);
            if (!(hours > 0) || hours > AutoExitStore.MAX_HOURS) {
                tvAutoExitStatus.setText(R.string.auto_exit_status_invalid_hours);
                tvAutoExitStatus.setTextColor(getColor(R.color.warn));
                return;
            }
            if (targetAt <= now) {
                targetAt = now + (long) (hours * 3600_000L);
            }
            tvAutoExitStatus.setText(getString(R.string.auto_exit_status_interval,
                    formatExitHours(hours),
                    TimerStore.format((targetAt - now + 999) / 1000)));
            tvAutoExitStatus.setTextColor(getColor(R.color.ok));
        }
    }

    /** 选项区显隐：开关关着就只剩开关和说明文字，打开后才出现方式与时间选项 */
    private void applyExitModeUi() {
        boolean on = switchAutoExit != null && switchAutoExit.isChecked();
        boolean fixed = currentExitMode() == AutoExitStore.MODE_FIXED;
        rgExitMode.setVisibility(on ? View.VISIBLE : View.GONE);
        rowExitFixed.setVisibility(on && fixed ? View.VISIBLE : View.GONE);
        rowExitHours.setVisibility(on && !fixed ? View.VISIBLE : View.GONE);
    }

    private int currentExitMode() {
        return rgExitMode.getCheckedRadioButtonId() == R.id.rb_exit_fixed
                ? AutoExitStore.MODE_FIXED : AutoExitStore.MODE_HOURS;
    }

    private double parseExitHours() {
        try {
            return Double.parseDouble(etExitHours.getText().toString().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 1.0 显示成 1，1.5 保持 1.5，避免输入框里出现 1.0 这种别扭的数字 */
    private static String formatExitHours(double hours) {
        return hours == Math.floor(hours) ? String.valueOf((long) hours) : String.valueOf(hours);
    }

    private String formatExitDayLabel(long millis) {
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(millis);
        Calendar now = Calendar.getInstance();
        if (isSameDay(target, now)) {
            return getString(R.string.auto_exit_day_today);
        }
        Calendar tomorrow = (Calendar) now.clone();
        tomorrow.add(Calendar.DAY_OF_YEAR, 1);
        if (isSameDay(target, tomorrow)) {
            return getString(R.string.auto_exit_day_tomorrow);
        }
        return getString(R.string.auto_exit_day_md,
                target.get(Calendar.MONTH) + 1, target.get(Calendar.DAY_OF_MONTH));
    }

    private static boolean isSameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static String formatClock(int hour, int minute) {
        return String.format("%02d:%02d", hour, minute);
    }

    private static String formatClockFromMillis(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        return formatClock(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
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
        filter.addAction(CountdownService.ACTION_EXITING);
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
        rearmAutoExitAlarm();
        refreshAutoExitStatus();
        uiHandler.removeCallbacks(nowPlayingRunnable);
        uiHandler.post(nowPlayingRunnable);
    }

    /**
     * 每次回到界面前做一次自愈重排：闹钟可能因设备重启、系统清理等原因丢失。
     * 用 rearm 而不是强制重算——否则几小时后模式的剩余时间会被一次次往后推。
     */
    private void rearmAutoExitAlarm() {
        CountdownService.rearmAutoExit(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开界面时把当前设置存下来，下次打开可恢复
        saveUiPrefs();
        saveAutoExitConfig();
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
