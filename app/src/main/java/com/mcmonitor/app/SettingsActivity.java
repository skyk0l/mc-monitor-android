package com.mcmonitor.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Dedicated settings screen for app accents and widget appearance. */
public class SettingsActivity extends Activity {
    private static final String PREFERENCES = "app_settings";
    private static final String PENDING_UPDATE_APK = "pending_update_apk";
    private static final String INSTALL_STATUS_ACTION =
            "com.mcmonitor.app.ACTION_UPDATE_INSTALL_STATUS";

    private static final int[] APP_ACCENTS = {
            0xFF7EE0A3, 0xFF7EB9FF, 0xFFBF9DFF, 0xFFFF83A7, 0xFFFFC978
    };
    private static final String[] APP_ACCENT_NAMES = {
            "Мятный", "Голубой", "Лавандовый", "Розовый", "Янтарный"
    };

    private LinearLayout content;
    private View root;
    private BackdropView backdrop;
    private Button updateButton;
    private TextView updateStatus;
    private ProgressBar updateProgress;
    private Button notificationStartButton;
    private Button notificationEndButton;
    private TextView dndStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int appAccent = getSharedPreferences("app_settings", MODE_PRIVATE)
                .getInt("accent_color", APP_ACCENTS[0]);
        getWindow().setStatusBarColor(
                Build.VERSION.SDK_INT >= 35
                        ? Color.TRANSPARENT : BackdropView.topBarColor(appAccent));
        getWindow().setNavigationBarColor(Color.rgb(15, 18, 25));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        FrameLayout frame = new FrameLayout(this);
        root = frame;
        backdrop = new BackdropView(this);
        backdrop.setAccentColor(appAccent);
        frame.addView(backdrop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(22);
        content.setPadding(padding, dp(18), padding, dp(24));
        content.setBackgroundColor(Color.TRANSPARENT);
        scroll.addView(content);
        frame.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(frame);

        TextView back = text("‹   Назад", 15, 0xFFA7C6A4);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setPadding(0, 0, 0, dp(18));
        back.setOnClickListener(v -> finish());
        content.addView(back);

        TextView title = text("Настройки", 28, 0xFFE6E9E5);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        content.addView(title);
        TextView subtitle = text("Оформление приложения и виджетов", 13, 0xFF929A97);
        subtitle.setPadding(0, dp(5), 0, dp(20));
        content.addView(subtitle);

        addSection("ЦВЕТ ПРИЛОЖЕНИЯ");
        addColorChoices(APP_ACCENT_NAMES, APP_ACCENTS,
                getSharedPreferences("app_settings", MODE_PRIVATE)
                        .getInt("accent_color", APP_ACCENTS[0]),
                color -> {
                    getSharedPreferences("app_settings", MODE_PRIVATE).edit()
                            .putInt("accent_color", color).apply();
                    backdrop.setAccentColor(color);
                    getWindow().setStatusBarColor(
                            Build.VERSION.SDK_INT >= 35
                                    ? Color.TRANSPARENT : BackdropView.topBarColor(color));
                });

        addSection("УВЕДОМЛЕНИЯ");
        addHint("Уведомления о приоритетных никах приходят только в выбранное время.");
        notificationStartButton = new Button(this);
        notificationStartButton.setOnClickListener(v -> chooseNotificationTime(true));
        content.addView(notificationStartButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        notificationEndButton = new Button(this);
        notificationEndButton.setOnClickListener(v -> chooseNotificationTime(false));
        content.addView(notificationEndButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        dndStatus = text("", 12, 0xFF929A97);
        dndStatus.setPadding(0, dp(6), 0, dp(6));
        content.addView(dndStatus);
        Button dndButton = new Button(this);
        dndButton.setText("Режим «Не беспокоить»");
        dndButton.setOnClickListener(v -> showDndOptions());
        content.addView(dndButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        updateNotificationLabels();

        addSection("ВИДЖЕТЫ");
        addHint("Цвета и видимая информация сохраняются на устройстве и сразу применяются.");
        addColorChoices(WidgetSettingsStore.ACCENT_NAMES,
                WidgetSettingsStore.ACCENTS,
                WidgetSettingsStore.accent(this),
                color -> {
                    WidgetSettingsStore.setAccent(this, color);
                    WidgetUpdater.renderAll(this);
                });
        addPaletteChoices();
        addSwitch("Показывать название сервера", WidgetSettingsStore.showName(this),
                WidgetSettingsStore::setShowName);
        addSwitch("Показывать список игроков", WidgetSettingsStore.showPlayers(this),
                WidgetSettingsStore::setShowPlayers);
        addSwitch("Показывать версию сервера", WidgetSettingsStore.showVersion(this),
                WidgetSettingsStore::setShowVersion);
        addSwitch("Показывать время обновления", WidgetSettingsStore.showUpdated(this),
                WidgetSettingsStore::setShowUpdated);

        TextView account = text("Аккаунт и синхронизация находятся в меню профиля.", 12, 0xFF929A97);
        account.setPadding(0, dp(18), 0, 0);
        content.addView(account);

        addSection("ОБНОВЛЕНИЯ");
        addHint("APK загружается из официального GitHub-релиза. Установку нужно подтвердить в Android.");
        updateButton = new Button(this);
        updateButton.setText("Проверить обновления");
        updateButton.setOnClickListener(v -> checkForUpdates());
        content.addView(updateButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        updateProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updateProgress.setMax(100);
        updateProgress.setVisibility(View.GONE);
        content.addView(updateProgress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)));
        updateStatus = text("", 12, 0xFF929A97);
        updateStatus.setPadding(0, dp(4), 0, 0);
        content.addView(updateStatus);
        applyWindowInsets();
    }

    private void chooseNotificationTime(boolean start) {
        int minute = start
                ? NotificationPolicy.startMinute(this)
                : NotificationPolicy.endMinute(this);
        int hour = minute == 1440 ? 0 : minute / 60;
        int selectedMinute = minute % 60;
        new TimePickerDialog(
                this,
                (picker, selectedHour, selectedMinuteValue) -> {
                    int selected = selectedHour * 60 + selectedMinuteValue;
                    if (start) {
                        NotificationPolicy.setStartMinute(this, selected);
                    } else {
                        NotificationPolicy.setEndMinute(
                                this,
                                selected == 0
                                        && NotificationPolicy.startMinute(this) == 0
                                        ? 1440 : selected);
                    }
                    updateNotificationLabels();
                },
                hour,
                selectedMinute,
                android.text.format.DateFormat.is24HourFormat(this))
                .show();
    }

    private void showDndOptions() {
        String[] options = {
                "Выключить",
                "На 1 час",
                "На 3 часа",
                "На 8 часов",
                "Пока не отключу"
        };
        int[] durations = {0, 3600, 3 * 3600, 8 * 3600, -1};
        new AlertDialog.Builder(this)
                .setTitle("Не беспокоить")
                .setItems(options, (dialog, which) -> {
                    NotificationPolicy.setDndDuration(this, durations[which]);
                    updateNotificationLabels();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void updateNotificationLabels() {
        if (notificationStartButton == null || notificationEndButton == null) {
            return;
        }
        notificationStartButton.setText(
                "Уведомления с " + formatMinute(NotificationPolicy.startMinute(this)));
        int end = NotificationPolicy.endMinute(this);
        notificationEndButton.setText(
                "Уведомления до " + (end == 1440 ? "24:00" : formatMinute(end)));
        if (dndStatus == null) {
            return;
        }
        long until = NotificationPolicy.dndUntil(this);
        if (until == -1) {
            dndStatus.setText("Не беспокоить активно — до отключения вручную.");
        } else if (until > System.currentTimeMillis()) {
            String time = android.text.format.DateFormat.getTimeFormat(this)
                    .format(new java.util.Date(until));
            dndStatus.setText("Не беспокоить активно до " + time + ".");
        } else {
            dndStatus.setText("Не беспокоить выключен.");
        }
    }

    private String formatMinute(int minute) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d",
                minute / 60, minute % 60);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && getPackageManager().canRequestPackageInstalls()) {
            String pendingPath = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .getString(PENDING_UPDATE_APK, null);
            if (pendingPath != null) {
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                        .remove(PENDING_UPDATE_APK).apply();
                File apk = new File(pendingPath);
                if (apk.isFile()) {
                    installUpdate(apk);
                } else if (updateStatus != null) {
                    updateStatus.setText("Загруженный APK не найден. Проверь обновления ещё раз.");
                }
            }
        }
    }

    private void checkForUpdates() {
        updateButton.setEnabled(false);
        updateProgress.setVisibility(View.GONE);
        updateStatus.setText("Проверяю GitHub Releases…");
        AppUpdateManager.checkForUpdate(this, new AppUpdateManager.CheckCallback() {
            @Override
            public void onLatest() {
                updateButton.setEnabled(true);
                updateStatus.setText("Установлена последняя версия.");
            }

            @Override
            public void onUpdateAvailable(AppUpdateManager.UpdateInfo update) {
                updateButton.setEnabled(true);
                updateStatus.setText("Доступна версия " + update.version + ".");
                String message = "Доступна версия " + update.version + ".";
                if (!update.notes.trim().isEmpty()) {
                    message += "\n\n" + update.notes.trim();
                }
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("Доступно обновление")
                        .setMessage(message)
                        .setNegativeButton("Позже", (dialog, which) -> dialog.dismiss())
                        .setPositiveButton("Скачать и установить",
                                (dialog, which) -> downloadUpdate(update))
                        .show();
            }

            @Override
            public void onError(String message) {
                updateButton.setEnabled(true);
                updateStatus.setText("Не удалось проверить обновления: " + message);
            }
        });
    }

    private void downloadUpdate(AppUpdateManager.UpdateInfo update) {
        updateButton.setEnabled(false);
        updateProgress.setProgress(0);
        updateProgress.setVisibility(View.VISIBLE);
        updateStatus.setText("Скачиваю и проверяю APK…");
        AppUpdateManager.download(this, update, new AppUpdateManager.DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                updateProgress.setProgress(percent);
                updateStatus.setText("Скачиваю APK… " + percent + "%");
            }

            @Override
            public void onDownloaded(File apk) {
                updateButton.setEnabled(true);
                updateProgress.setProgress(100);
                updateStatus.setText("APK проверен. Открываю установщик Android…");
                installUpdate(apk);
            }

            @Override
            public void onError(String message) {
                updateButton.setEnabled(true);
                updateProgress.setVisibility(View.GONE);
                updateStatus.setText("Не удалось скачать обновление: " + message);
            }
        });
    }

    private void installUpdate(File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(PENDING_UPDATE_APK, apk.getAbsolutePath()).apply();
            updateStatus.setText("Разреши установку приложений для MC Monitor и вернись сюда.");
            try {
                startActivity(new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (android.content.ActivityNotFoundException exception) {
                updateStatus.setText(
                        "Разреши установку из этого источника в системных настройках и повтори.");
            }
            return;
        }

        int sessionId = -1;
        PackageInstaller installer = getPackageManager().getPackageInstaller();
        try {
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(getPackageName());
            sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);
            try {
                try (FileInputStream input = new FileInputStream(apk);
                     OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                    session.fsync(output);
                }
                Intent statusIntent = new Intent(this, UpdateInstallReceiver.class)
                        .setAction(INSTALL_STATUS_ACTION);
                int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
                pendingIntentFlags |= Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE;
                PendingIntent statusPendingIntent = PendingIntent.getBroadcast(
                        this,
                        sessionId,
                        statusIntent,
                        pendingIntentFlags);
                session.commit(statusPendingIntent.getIntentSender());
            } finally {
                session.close();
            }
            updateStatus.setText("Ожидай запроса Android и подтверди установку обновления.");
        } catch (IOException | SecurityException | IllegalArgumentException exception) {
            if (sessionId >= 0) {
                installer.abandonSession(sessionId);
            }
            updateStatus.setText("Не удалось открыть установщик: " + exception.getMessage());
        }
    }

    private void applyWindowInsets() {
        final int baseTop = content.getPaddingTop();
        final int baseBottom = content.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            content.setPadding(
                    content.getPaddingLeft(),
                    baseTop + top,
                    content.getPaddingRight(),
                    baseBottom + bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void addColorChoices(
            String[] names, int[] colors, int selected, ColorSelectionListener listener) {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        for (int i = 0; i < names.length; i++) {
            final int color = colors[i];
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setText(names[i]);
            button.setTextColor(0xFFE6E9E5);
            button.setButtonTintList(android.content.res.ColorStateList.valueOf(color));
            button.setChecked(color == selected);
            button.setTag(color);
            group.addView(button);
        }
        group.setOnCheckedChangeListener((checkedGroup, checkedId) -> {
            View checked = checkedGroup.findViewById(checkedId);
            if (checked != null && checked.getTag() instanceof Integer) {
                listener.select((Integer) checked.getTag());
            }
        });
        content.addView(group);
    }

    private void addPaletteChoices() {
        addSection("ФОН ВИДЖЕТА");
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        int selected = WidgetSettingsStore.background(this);
        for (int i = 0; i < WidgetSettingsStore.BACKGROUNDS.length; i++) {
            final int index = i;
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setText(WidgetSettingsStore.BACKGROUND_NAMES[i]);
            button.setTextColor(0xFFE6E9E5);
            button.setButtonTintList(android.content.res.ColorStateList.valueOf(
                    WidgetSettingsStore.BACKGROUNDS[i]));
            button.setChecked(i == selected);
            button.setTag(index);
            group.addView(button);
        }
        group.setOnCheckedChangeListener((checkedGroup, checkedId) -> {
            View checked = checkedGroup.findViewById(checkedId);
            if (checked != null && checked.getTag() instanceof Integer) {
                WidgetSettingsStore.setBackground(this, (Integer) checked.getTag());
                WidgetUpdater.renderAll(this);
            }
        });
        content.addView(group);
    }

    private void addSwitch(
            String label, boolean checked, PreferenceSetter setter) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextColor(0xFFE6E9E5);
        toggle.setTextSize(14);
        toggle.setChecked(checked);
        toggle.setPadding(0, dp(5), 0, dp(5));
        toggle.setOnCheckedChangeListener((button, value) -> {
            setter.set(this, value);
            WidgetUpdater.renderAll(this);
        });
        content.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
    }

    private void addSection(String title) {
        TextView heading = text(title, 11, 0xFFA7C6A4);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, dp(18), 0, dp(4));
        content.addView(heading);
    }

    private void addHint(String value) {
        TextView hint = text(value, 12, 0xFF929A97);
        hint.setPadding(0, dp(3), 0, dp(8));
        content.addView(hint);
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface ColorSelectionListener {
        void select(int color);
    }

    private interface PreferenceSetter {
        void set(android.content.Context context, boolean value);
    }
}
