package com.mcmonitor.app;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Dedicated settings screen for app accents and widget appearance. */
public class SettingsActivity extends LocalizedActivity {
    private static final String PREFERENCES = "app_settings";
    private static final String PENDING_UPDATE_APK = "pending_update_apk";
    private static final String INSTALL_STATUS_ACTION =
            "com.mcmonitor.app.ACTION_UPDATE_INSTALL_STATUS";

    private static final int[] APP_ACCENTS = {
            0xFF7EE0A3, 0xFF7EB9FF, 0xFFBF9DFF, 0xFFFF83A7, 0xFFFFC978
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
    private Spinner widgetTarget;
    private LinearLayout widgetOptions;
    private RadioGroup appAccentChoices;
    private final List<Integer> widgetTargetIds = new ArrayList<>();

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
        android.content.SharedPreferences appearance =
                getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        int backgroundStyle = appearance.getInt("background_style", 0);
        int backgroundPattern = appearance.getInt("background_pattern", 0);
        backdrop.setSceneStyle(backgroundStyle);
        backdrop.setPattern(backgroundPattern);
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

        TextView back = text(getString(R.string.settings_back), 15, 0xFFA7C6A4);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setPadding(0, 0, 0, dp(18));
        back.setOnClickListener(v -> finish());
        content.addView(back);

        TextView title = text(getString(R.string.settings_title), 28, 0xFFE6E9E5);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        content.addView(title);
        TextView subtitle = text(getString(R.string.settings_subtitle), 13, 0xFF929A97);
        subtitle.setPadding(0, dp(5), 0, dp(20));
        content.addView(subtitle);

        addSection(getString(R.string.language_section));
        addHint(getString(R.string.language_restart_note));
        String[] languageValues = {
                AppLanguage.SYSTEM, "ru", "en"
        };
        String[] languageLabels = {
                getString(R.string.language_system),
                getString(R.string.language_russian),
                getString(R.string.language_english)
        };
        Spinner languageSpinner = new Spinner(this);
        ArrayAdapter<String> languageAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, languageLabels);
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(languageAdapter);
        String selectedLanguage = AppLanguage.selected(this);
        int selectedLanguageIndex = 0;
        for (int i = 0; i < languageValues.length; i++) {
            if (languageValues[i].equals(selectedLanguage)) {
                selectedLanguageIndex = i;
                break;
            }
        }
        languageSpinner.setSelection(selectedLanguageIndex);
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String language = languageValues[position];
                if (!language.equals(AppLanguage.selected(SettingsActivity.this))) {
                    AppLanguage.setAndRestart(SettingsActivity.this, language);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        content.addView(languageSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        addSection(getString(R.string.settings_app_color));
        appAccentChoices = addColorChoices(
                getResources().getStringArray(R.array.app_accent_names), APP_ACCENTS,
                getSharedPreferences("app_settings", MODE_PRIVATE)
                        .getInt("accent_color", APP_ACCENTS[0]),
                color -> {
                    getSharedPreferences("app_settings", MODE_PRIVATE).edit()
                            .putInt("accent_color", color).apply();
                    backdrop.setAccentColor(color);
                    styleButtons(content, color);
                    getWindow().setStatusBarColor(
                            Build.VERSION.SDK_INT >= 35
                                    ? Color.TRANSPARENT : BackdropView.topBarColor(color));
                });
        Button customAccent = new Button(this);
        customAccent.setText(R.string.settings_custom_accent);
        customAccent.setOnClickListener(v -> chooseCustomAccent());
        content.addView(customAccent, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        addSection(getString(R.string.settings_app_background));
        addAppearanceSpinner(
                R.array.app_background_scene_names,
                "background_style",
                backgroundStyle,
                backdrop,
                true);
        addAppearanceSpinner(
                R.array.app_background_pattern_names,
                "background_pattern",
                backgroundPattern,
                backdrop,
                false);

        addSection(getString(R.string.settings_notifications));
        addHint(getString(R.string.settings_notification_window));
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
        dndButton.setText(R.string.settings_dnd_mode);
        dndButton.setOnClickListener(v -> showDndOptions());
        content.addView(dndButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        updateNotificationLabels();

        addSection(getString(R.string.settings_servers));
        addHint(getString(R.string.settings_pinned_order_hint));
        Button sortButton = new Button(this);
        updateSortLabel(sortButton);
        sortButton.setOnClickListener(v -> {
            String[] labels = {
                    getString(R.string.settings_sort_manual),
                    getString(R.string.settings_sort_name),
                    getString(R.string.settings_sort_online),
                    getString(R.string.settings_sort_ping),
                    getString(R.string.settings_sort_date)
            };
            String[] values = {"manual", "name", "online", "ping", "date"};
            String selected = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .getString("server_sort_mode", "manual");
            int selectedIndex = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(selected)) {
                    selectedIndex = i;
                    break;
                }
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.settings_sort_title)
                    .setSingleChoiceItems(labels, selectedIndex, (dialog, which) -> {
                        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                                .putString("server_sort_mode", values[which])
                                .apply();
                        updateSortLabel(sortButton);
                        dialog.dismiss();
                    })
                    .setNegativeButton(R.string.settings_cancel, null)
                    .show();
        });
        content.addView(sortButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        addSection(getString(R.string.settings_widgets));
        addHint(getString(R.string.settings_widget_hint));
        widgetTarget = new Spinner(this);
        content.addView(widgetTarget, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        widgetOptions = new LinearLayout(this);
        widgetOptions.setOrientation(LinearLayout.VERTICAL);
        content.addView(widgetOptions);
        setupWidgetTargets();

        TextView account = text(getString(R.string.settings_account_hint), 12, 0xFF929A97);
        account.setPadding(0, dp(18), 0, 0);
        content.addView(account);

        addSection(getString(R.string.settings_updates));
        addHint(getString(R.string.settings_updates_hint));
        updateButton = new Button(this);
        updateButton.setText(R.string.settings_check_updates);
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
        styleButtons(content, appAccent);
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
                getString(R.string.settings_dnd_off),
                getString(R.string.settings_dnd_one_hour),
                getString(R.string.settings_dnd_three_hours),
                getString(R.string.settings_dnd_eight_hours),
                getString(R.string.settings_dnd_until_disabled)
        };
        int[] durations = {0, 3600, 3 * 3600, 8 * 3600, -1};
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_dnd_title)
                .setItems(options, (dialog, which) -> {
                    NotificationPolicy.setDndDuration(this, durations[which]);
                    updateNotificationLabels();
                })
                .setNegativeButton(R.string.settings_cancel, null)
                .show();
    }

    private void updateNotificationLabels() {
        if (notificationStartButton == null || notificationEndButton == null) {
            return;
        }
        notificationStartButton.setText(getString(
                R.string.settings_notification_start,
                formatMinute(NotificationPolicy.startMinute(this))));
        int end = NotificationPolicy.endMinute(this);
        notificationEndButton.setText(getString(
                R.string.settings_notification_end,
                end == 1440 ? "24:00" : formatMinute(end)));
        if (dndStatus == null) {
            return;
        }
        long until = NotificationPolicy.dndUntil(this);
        if (until == -1) {
            dndStatus.setText(R.string.settings_dnd_active_manual);
        } else if (until > System.currentTimeMillis()) {
            String time = android.text.format.DateFormat.getTimeFormat(this)
                    .format(new java.util.Date(until));
            dndStatus.setText(getString(R.string.settings_dnd_active_until, time));
        } else {
            dndStatus.setText(R.string.settings_dnd_disabled);
        }
    }

    private void updateSortLabel(Button button) {
        String mode = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getString("server_sort_mode", "manual");
        String label;
        switch (mode) {
            case "name":
                label = getString(R.string.settings_sort_name);
                break;
            case "online":
                label = getString(R.string.settings_sort_online);
                break;
            case "ping":
                label = getString(R.string.settings_sort_ping);
                break;
            case "date":
                label = getString(R.string.settings_sort_date);
                break;
            default:
                label = getString(R.string.settings_sort_manual);
                break;
        }
        button.setText(getString(R.string.settings_sort_prefix, label));
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
                    updateStatus.setText(R.string.settings_missing_apk);
                }
            }
        }
    }

    private void checkForUpdates() {
        updateButton.setEnabled(false);
        updateProgress.setVisibility(View.GONE);
        updateStatus.setText(R.string.settings_checking_updates);
        AppUpdateManager.checkForUpdate(this, new AppUpdateManager.CheckCallback() {
            @Override
            public void onLatest() {
                updateButton.setEnabled(true);
                updateStatus.setText(R.string.settings_latest_version);
            }

            @Override
            public void onUpdateAvailable(AppUpdateManager.UpdateInfo update) {
                updateButton.setEnabled(true);
                String available = getString(R.string.settings_update_available, update.version);
                updateStatus.setText(available);
                String message = available;
                if (!update.notes.trim().isEmpty()) {
                    message += "\n\n" + update.notes.trim();
                }
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(R.string.settings_update_dialog_title)
                        .setMessage(message)
                        .setNegativeButton(R.string.settings_later, (dialog, which) -> dialog.dismiss())
                        .setPositiveButton(R.string.settings_download_install,
                                (dialog, which) -> downloadUpdate(update))
                        .show();
            }

            @Override
            public void onError(String message) {
                updateButton.setEnabled(true);
                updateStatus.setText(getString(R.string.settings_update_check_failed, message));
            }
        });
    }

    private void downloadUpdate(AppUpdateManager.UpdateInfo update) {
        updateButton.setEnabled(false);
        updateProgress.setProgress(0);
        updateProgress.setVisibility(View.VISIBLE);
        updateStatus.setText(R.string.settings_downloading_verify);
        AppUpdateManager.download(this, update, new AppUpdateManager.DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                updateProgress.setProgress(percent);
                updateStatus.setText(getString(R.string.settings_downloading_progress, percent));
            }

            @Override
            public void onDownloaded(File apk) {
                updateButton.setEnabled(true);
                updateProgress.setProgress(100);
                updateStatus.setText(R.string.settings_apk_verified);
                installUpdate(apk);
            }

            @Override
            public void onError(String message) {
                updateButton.setEnabled(true);
                updateProgress.setVisibility(View.GONE);
                updateStatus.setText(getString(R.string.settings_download_failed, message));
            }
        });
    }

    private void installUpdate(File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(PENDING_UPDATE_APK, apk.getAbsolutePath()).apply();
            updateStatus.setText(R.string.settings_allow_install);
            try {
                startActivity(new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (android.content.ActivityNotFoundException exception) {
                updateStatus.setText(R.string.settings_allow_source_install);
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
            updateStatus.setText(R.string.settings_confirm_install);
        } catch (IOException | SecurityException | IllegalArgumentException exception) {
            if (sessionId >= 0) {
                installer.abandonSession(sessionId);
            }
            updateStatus.setText(getString(
                    R.string.settings_installer_failed, exception.getMessage()));
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

    private RadioGroup addColorChoices(
            String[] names, int[] colors, int selected, ColorSelectionListener listener) {
        return addColorChoices(content, names, colors, selected, listener);
    }

    private RadioGroup addColorChoices(
            LinearLayout parent, String[] names, int[] colors,
            int selected, ColorSelectionListener listener) {
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
        parent.addView(group);
        return group;
    }

    private void addAppearanceSpinner(
            int labelsResource,
            String preference,
            int selected,
            BackdropView target,
            boolean scene) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                getResources().getStringArray(labelsResource));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(adapter.getCount() - 1, selected)));
        spinner.setContentDescription(
                getString(scene ? R.string.settings_app_background : R.string.settings_app_pattern));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    AdapterView<?> parent, View view, int position, long id) {
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                        .putInt(preference, position)
                        .apply();
                if (scene) {
                    target.setSceneStyle(position);
                } else {
                    target.setPattern(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        content.addView(spinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
    }

    private void chooseCustomAccent() {
        EditText colorInput = new EditText(this);
        colorInput.setSingleLine(true);
        colorInput.setInputType(InputType.TYPE_CLASS_TEXT);
        colorInput.setHint(R.string.settings_custom_accent_hint);
        int current = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getInt("accent_color", APP_ACCENTS[0]);
        colorInput.setText(String.format(
                java.util.Locale.ROOT, "#%06X", current & 0xFFFFFF));
        colorInput.setSelection(colorInput.length());
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_custom_accent)
                .setView(colorInput)
                .setNegativeButton(R.string.settings_cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    try {
                        String value = colorInput.getText().toString().trim();
                        if (!value.matches("#[0-9A-Fa-f]{6}")) {
                            throw new IllegalArgumentException("Invalid color format");
                        }
                        int color = Color.parseColor(value);
                        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                                .putInt("accent_color", color)
                                .apply();
                        appAccentChoices.clearCheck();
                        backdrop.setAccentColor(color);
                        getWindow().setStatusBarColor(
                                Build.VERSION.SDK_INT >= 35
                                        ? Color.TRANSPARENT : BackdropView.topBarColor(color));
                        styleButtons(content, color);
                    } catch (IllegalArgumentException exception) {
                        android.widget.Toast.makeText(
                                this, R.string.settings_custom_accent_invalid,
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void styleButtons(View view, int accent) {
        if (view instanceof Button) {
            Button button = (Button) view;
            int lighter = Color.rgb(
                    Color.red(accent) + (255 - Color.red(accent)) / 5,
                    Color.green(accent) + (255 - Color.green(accent)) / 5,
                    Color.blue(accent) + (255 - Color.blue(accent)) / 5);
            GradientDrawable gradient = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT, new int[]{lighter, accent});
            gradient.setCornerRadius(dp(18));
            gradient.setStroke(dp(1), Color.argb(58, 255, 255, 255));
            button.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(Color.argb(54, 255, 255, 255)),
                    gradient,
                    null));
            int luminance = Color.red(accent) * 299
                    + Color.green(accent) * 587
                    + Color.blue(accent) * 114;
            button.setTextColor(luminance > 150_000 ? 0xFF14201A : Color.WHITE);
            button.setAllCaps(false);
            button.setElevation(dp(2));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                styleButtons(group.getChildAt(i), accent);
            }
        }
    }

    private void setupWidgetTargets() {
        List<String> labels = new ArrayList<>();
        widgetTargetIds.clear();
        labels.add(getString(R.string.settings_widget_default));
        widgetTargetIds.add(0);

        for (Map.Entry<Integer, WidgetStore.Info> entry : WidgetStore.all(this).entrySet()) {
            WidgetStore.Info info = entry.getValue();
            String label;
            if (info.kind == WidgetStore.KIND_GROUP
                    && info.groupId != null && !info.groupId.isEmpty()) {
                label = getString(R.string.settings_widget_group, info.groupId);
            } else {
                ServerEntry server = ServerStore.findByAddress(this, info.address);
                label = server == null ? info.address : server.displayName();
            }
            labels.add(label + " · #" + entry.getKey());
            widgetTargetIds.add(entry.getKey());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        widgetTarget.setAdapter(adapter);
        widgetTarget.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    AdapterView<?> parent, View view, int position, long id) {
                buildWidgetOptions(widgetTargetIds.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                widgetOptions.removeAllViews();
            }
        });
    }

    private void buildWidgetOptions(int widgetId) {
        widgetOptions.removeAllViews();
        if (widgetId > 0) {
            EditText name = new EditText(this);
            name.setSingleLine(true);
            name.setHint(R.string.settings_widget_name_hint);
            name.setTextColor(0xFFE6E9E5);
            name.setHintTextColor(0xFF929A97);
            name.setText(WidgetSettingsStore.name(this, widgetId));
            name.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence value, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence value, int start, int before, int count) {
                    WidgetSettingsStore.setName(
                            SettingsActivity.this, widgetId, value.toString());
                    WidgetUpdater.renderAll(SettingsActivity.this);
                }

                @Override
                public void afterTextChanged(Editable value) {
                }
            });
            widgetOptions.addView(name);
        }

        addSection(widgetOptions, getString(R.string.settings_widget_accent));
        addColorChoices(
                widgetOptions,
                getResources().getStringArray(R.array.widget_accent_names),
                WidgetSettingsStore.ACCENTS,
                WidgetSettingsStore.accent(this, widgetId),
                color -> {
                    WidgetSettingsStore.setAccent(this, widgetId, color);
                    WidgetUpdater.renderAll(this);
                });

        addSection(widgetOptions, getString(R.string.settings_widget_background));
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        int selected = WidgetSettingsStore.background(this, widgetId);
        for (int i = 0; i < WidgetSettingsStore.BACKGROUNDS.length; i++) {
            final int index = i;
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setText(getResources().getStringArray(
                    R.array.widget_background_names)[i]);
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
                WidgetSettingsStore.setBackground(
                        this, widgetId, (Integer) checked.getTag());
                WidgetUpdater.renderAll(this);
            }
        });
        widgetOptions.addView(group);

        LinearLayout opacityRow = new LinearLayout(this);
        opacityRow.setOrientation(LinearLayout.HORIZONTAL);
        opacityRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView opacityLabel = text(
                getString(R.string.settings_opacity), 13, 0xFFE6E9E5);
        opacityRow.addView(opacityLabel);
        SeekBar opacity = new SeekBar(this);
        opacity.setMin(40);
        opacity.setMax(100);
        opacity.setProgress(Math.round(
                WidgetSettingsStore.opacity(this, widgetId) * 100f));
        opacity.setContentDescription(getString(R.string.settings_widget_background_opacity));
        opacityRow.addView(opacity, new LinearLayout.LayoutParams(
                0, dp(44), 1f));
        TextView opacityValue = text(
                opacity.getProgress() + "%", 12, 0xFF929A97);
        opacityRow.addView(opacityValue);
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                opacityValue.setText(progress + "%");
                WidgetSettingsStore.setOpacity(
                        SettingsActivity.this, widgetId, progress / 100f);
                WidgetUpdater.renderAll(SettingsActivity.this);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        widgetOptions.addView(opacityRow);

        addWidgetSwitch(
                getString(R.string.settings_widget_show_name),
                WidgetSettingsStore.showName(this, widgetId),
                widgetId,
                WidgetSettingsStore::setShowName);
        addWidgetSwitch(
                getString(R.string.settings_widget_show_players),
                WidgetSettingsStore.showPlayers(this, widgetId),
                widgetId,
                WidgetSettingsStore::setShowPlayers);
        addWidgetSwitch(
                getString(R.string.settings_widget_show_version),
                WidgetSettingsStore.showVersion(this, widgetId),
                widgetId,
                WidgetSettingsStore::setShowVersion);
        addWidgetSwitch(
                getString(R.string.settings_widget_show_updated),
                WidgetSettingsStore.showUpdated(this, widgetId),
                widgetId,
                WidgetSettingsStore::setShowUpdated);
        styleButtons(widgetOptions, getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getInt("accent_color", APP_ACCENTS[0]));
    }

    private void addWidgetSwitch(
            String label, boolean checked, int widgetId, WidgetPreferenceSetter setter) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextColor(0xFFE6E9E5);
        toggle.setTextSize(14);
        toggle.setChecked(checked);
        toggle.setPadding(0, dp(5), 0, dp(5));
        toggle.setOnCheckedChangeListener((button, value) -> {
            setter.set(this, widgetId, value);
            WidgetUpdater.renderAll(this);
        });
        widgetOptions.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
    }

    private void addSection(String title) {
        addSection(content, title);
    }

    private void addSection(LinearLayout parent, String title) {
        TextView heading = text(title, 11, 0xFFA7C6A4);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, dp(18), 0, dp(4));
        parent.addView(heading);
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

    private interface WidgetPreferenceSetter {
        void set(android.content.Context context, int widgetId, boolean value);
    }
}
