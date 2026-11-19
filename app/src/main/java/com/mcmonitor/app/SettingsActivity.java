package com.mcmonitor.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Dedicated settings screen for app accents and widget appearance. */
public class SettingsActivity extends Activity {

    private static final int[] APP_ACCENTS = {
            0xFF7EE0A3, 0xFF7EB9FF, 0xFFBF9DFF, 0xFFFF83A7, 0xFFFFC978
    };
    private static final String[] APP_ACCENT_NAMES = {
            "Мятный", "Голубой", "Лавандовый", "Розовый", "Янтарный"
    };

    private LinearLayout content;
    private View root;
    private BackdropView backdrop;

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
        applyWindowInsets();
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
