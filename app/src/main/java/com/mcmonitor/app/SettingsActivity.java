package com.mcmonitor.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(15, 18, 25));
        getWindow().setNavigationBarColor(Color.rgb(15, 18, 25));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(22);
        content.setPadding(padding, dp(18), padding, dp(24));
        content.setBackgroundColor(Color.rgb(15, 18, 25));
        scroll.addView(content);
        setContentView(scroll);

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
                color -> getSharedPreferences("app_settings", MODE_PRIVATE).edit()
                        .putInt("accent_color", color).apply());

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
    }

    private void addColorChoices(
            String[] names, int[] colors, int selected, ColorSelectionListener listener) {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        for (int i = 0; i < names.length; i++) {
            final int color = colors[i];
            RadioButton button = new RadioButton(this);
            button.setText(names[i]);
            button.setTextColor(0xFFE6E9E5);
            button.setButtonTintList(android.content.res.ColorStateList.valueOf(color));
            button.setChecked(color == selected);
            button.setOnClickListener(v -> listener.select(color));
            group.addView(button);
        }
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
            button.setText(WidgetSettingsStore.BACKGROUND_NAMES[i]);
            button.setTextColor(0xFFE6E9E5);
            button.setButtonTintList(android.content.res.ColorStateList.valueOf(
                    WidgetSettingsStore.BACKGROUNDS[i]));
            button.setChecked(i == selected);
            button.setOnClickListener(v -> {
                WidgetSettingsStore.setBackground(this, index);
                WidgetUpdater.renderAll(this);
            });
            group.addView(button);
        }
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
