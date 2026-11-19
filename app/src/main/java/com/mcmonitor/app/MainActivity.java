package com.mcmonitor.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.LruCache;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String TAG = "MCMonitor";
    private static final int REQUEST_NOTIFICATIONS = 7102;

    private static final long REFRESH_INTERVAL = 60_000L;
    private static final long CLOUD_SYNC_INTERVAL = 5 * 60_000L;
    private static final int MAX_CHIPS = 30;

    private static final int[] CHIP_BACKGROUNDS = {
            R.drawable.bg_widget_chip1,
            R.drawable.bg_widget_chip2,
            R.drawable.bg_widget_chip3,
            R.drawable.bg_widget_chip4,
    };
    private static final int[] CHIP_TEXT_COLORS = {
            R.color.chip_text1,
            R.color.chip_text2,
            R.color.chip_text3,
            R.color.chip_text4,
    };

    private LinearLayout serverList;
    private View emptyState;
    private ScrollView scroll;
    private TextView subtitle;
    private TextView serverSummaryText;
    private View accountButton;
    private EditText searchInput;
    private View headerActionsSpacer;

    private final Map<String, View> cardViews = new java.util.HashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object syncLock = new Object();
    private boolean syncRunning;
    private boolean syncPending;
    private AlertDialog profileDialog;
    private EditText profileDisplayName;
    private EditText profileBio;
    private CheckBox profileShareServers;
    private ImageView profileAvatarView;
    private TextView profileStatus;
    private byte[] pendingAvatarJpeg;
    private AlertDialog communityDialog;
    private EditText communitySearch;
    private LinearLayout communityResults;
    private int communityMode;
    private int communityRequestId;
    private final LruCache<String, Bitmap> iconCache = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            refreshAll();
            handler.postDelayed(this, REFRESH_INTERVAL);
        }
    };
    private final Runnable periodicCloudSync = new Runnable() {
        @Override
        public void run() {
            requestCloudSync(true);
            handler.postDelayed(this, CLOUD_SYNC_INTERVAL);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        serverList = findViewById(R.id.serverList);
        emptyState = findViewById(R.id.emptyState);
        scroll = findViewById(R.id.scroll);
        subtitle = findViewById(R.id.subtitle);
        subtitle.setText("JAVA + BEDROCK  ·  МОНИТОРИНГ СЕРВЕРОВ");
        serverSummaryText = findViewById(R.id.serverSummaryText);
        accountButton = findViewById(R.id.accountButton);
        accountButton.setOnClickListener(v -> showAccountMenu());

        searchInput = findViewById(R.id.searchInput);
        headerActionsSpacer = findViewById(R.id.headerActionsSpacer);
        findViewById(R.id.searchToggle).setOnClickListener(this::toggleSearch);
        findViewById(R.id.settingsButton).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                buildCards(false);
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        });
        searchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                openCommunitySearch();
                return true;
            }
            return false;
        });

        applyWindowInsets();
        applySavedAccent();
        WidgetJobService.schedule(this);
        WidgetJobService.requestRefresh(this);

        View refresh = findViewById(R.id.refresh);
        refresh.setOnClickListener(v -> {
            v.animate().rotationBy(360f).setDuration(520).start();
            refreshAll();
        });

        findViewById(R.id.addButton).setOnClickListener(v -> showServerDialog(null));

        buildCards();
        updateAccountButton();
    }

    /** Keeps content below system bars while the themed backdrop continues behind them. */
    private void applyWindowInsets() {
        final View root = findViewById(R.id.root);
        final View content = findViewById(R.id.content);
        final int baseTop = content.getPaddingTop();
        final int baseBottom = content.getPaddingBottom();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

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

    @Override
    protected void onResume() {
        super.onResume();
        applySavedAccent();
        refreshAll();
        requestCloudSync(true);
        handler.removeCallbacks(autoRefresh);
        handler.postDelayed(autoRefresh, REFRESH_INTERVAL);
        handler.removeCallbacks(periodicCloudSync);
        handler.postDelayed(periodicCloudSync, CLOUD_SYNC_INTERVAL);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(autoRefresh);
        handler.removeCallbacks(periodicCloudSync);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        syncExecutor.shutdownNow();
        super.onDestroy();
    }

    private void buildCards() {
        buildCards(true);
    }

    private void buildCards(boolean animateCards) {
        cardViews.clear();
        serverList.removeAllViews();
        List<ServerEntry> entries = ServerStore.load(this);
        String query = searchInput == null
                ? ""
                : searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
        int visibleCount = 0;

        for (int i = 0; i < entries.size(); i++) {
            ServerEntry entry = entries.get(i);
            if (!matchesQuery(entry, query)) {
                continue;
            }

            View card = LayoutInflater.from(this).inflate(R.layout.item_server, serverList, false);
            cardViews.put(entry.id, card);
            bindCard(card, entry);
            serverList.addView(card);
            if (animateCards) {
                card.setAlpha(0f);
                card.setTranslationY(dp(12));
                card.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(220)
                        .setStartDelay(visibleCount * 35L)
                        .start();
            }
            visibleCount++;
        }

        TextView emptyTitle = emptyState.findViewById(R.id.emptyTitle);
        TextView emptyMessage = emptyState.findViewById(R.id.emptyMessage);
        boolean noServers = entries.isEmpty();
        boolean noMatches = !noServers && visibleCount == 0;
        emptyTitle.setText(noMatches ? "Ничего не найдено" : "Пока пусто");
        emptyMessage.setText(noMatches
                ? "Измени запрос — список обновляется по мере ввода"
                : "Добавь сервер Java или Bedrock —\nпокажем онлайн и ники игроков");
        emptyState.setVisibility(noServers || noMatches ? View.VISIBLE : View.GONE);
        updateServerSummary(entries);
    }

    private boolean matchesQuery(ServerEntry entry, String query) {
        if (query.isEmpty()) {
            return true;
        }
        String searchable = (entry.displayName() + " " + entry.address + " "
                + ServerEntry.normalizeProtocol(entry.protocol)).toLowerCase(Locale.ROOT);
        if (searchable.contains(query)) {
            return true;
        }
        ServerStatus status = StatusCache.load(this, entry.address);
        if (status != null) {
            for (String player : status.players) {
                if (player.toLowerCase(Locale.ROOT).contains(query)) {
                    return true;
                }
            }

        }
        return false;
    }

    private void updateServerSummary(List<ServerEntry> entries) {
        int online = 0;
        for (ServerEntry entry : entries) {
            ServerStatus status = StatusCache.load(this, entry.address);
            if (status != null && status.hasData && status.online) {
                online++;
            }
        }
        serverSummaryText.setText(entries.size() + " " + pluralServers(entries.size())
                + " · " + online + " онлайн");
    }

    private void bindCard(View card, ServerEntry entry) {
        TextView name = card.findViewById(R.id.serverName);
        TextView address = card.findViewById(R.id.serverAddress);
        name.setText(entry.displayName());
        address.setText(entry.address);

        View more = card.findViewById(R.id.moreButton);
        more.setOnClickListener(v -> showServerMenu(entry, v));

        FlowLayout flow = card.findViewById(R.id.playersFlow);
        flow.setSpacing(0, 0);

        ServerStatus cached = StatusCache.load(this, entry.address);
        renderCard(card, entry, cached, cached == null);
    }

    private void renderCard(View card, ServerEntry entry, ServerStatus status, boolean loading) {
        TextView name = card.findViewById(R.id.serverName);
        if (name != null) {
            name.setText(entry.displayName());
        }

        ImageView icon = card.findViewById(R.id.serverIcon);
        Bitmap bitmap = decodeIcon(status == null ? "" : status.favicon);
        if (bitmap != null) {
            icon.setImageBitmap(bitmap);
            icon.setVisibility(View.VISIBLE);
        } else {
            icon.setImageDrawable(null);
            icon.setVisibility(View.GONE);
        }

        boolean known = status != null && status.hasData;
        boolean online = known && status.online;
        int accent = getColor(!known ? R.color.amber : online ? R.color.mint : R.color.coral);

        TextView dot = card.findViewById(R.id.statusDot);
        dot.setTextColor(accent);

        TextView count = card.findViewById(R.id.onlineCount);
        count.setText(known ? (online ? String.valueOf(status.onlineCount) : "—") : "—");

        TextView max = card.findViewById(R.id.maxSuffix);
        max.setText(known && online && status.maxCount > 0 ? "/ " + status.maxCount : "");

        TextView statusLabel = card.findViewById(R.id.statusLabel);
        statusLabel.setTextColor(accent);
        if (!known) {
            statusLabel.setText(loading ? "проверяю" : "нет связи");
        } else {
            statusLabel.setText(online ? "онлайн" : "офлайн");
        }

        TextView version = card.findViewById(R.id.versionLine);
        version.setText(versionLine(status, entry, loading));
        version.setTextColor(getColor(known && online ? R.color.soft : R.color.dim));

        TextView hint = card.findViewById(R.id.playersHint);
        if (!known) {
            hint.setText(loading ? "…" : "—");
        } else if (!online) {
            hint.setText("—");
        } else if (status.players.isEmpty()) {
            hint.setText(status.onlineCount > 0 ? "список скрыт" : "пусто");
        } else {
            hint.setText(status.players.size() + " из " + status.onlineCount);
        }

        FlowLayout flow = card.findViewById(R.id.playersFlow);
        flow.removeAllViews();
        boolean showChips = online && !status.players.isEmpty();
        flow.setVisibility(showChips ? View.VISIBLE : View.GONE);
        if (showChips) {
            int shown = Math.min(status.players.size(), MAX_CHIPS);
            for (int i = 0; i < shown; i++) {
                addPlayerChip(flow, status.players.get(i), i);
            }
            if (status.players.size() > shown) {
                addMoreChip(flow, "+" + (status.players.size() - shown) + " ещё");
            }
        }

        TextView empty = card.findViewById(R.id.playersEmpty);
        if (!known) {
            empty.setText(loading ? "Тяну список игроков…" : "Нет связи с сервером");
            empty.setVisibility(View.VISIBLE);
        } else if (!online) {
            empty.setText("Сервер сейчас офлайн");
            empty.setVisibility(View.VISIBLE);
        } else if (status.players.isEmpty()) {
            empty.setText(status.onlineCount > 0
                    ? "Сервер не показывает список игроков"
                    : "На сервере никого нет");
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
        }
    }

    private String versionLine(ServerStatus status, ServerEntry entry, boolean loading) {
        if (status == null) {
            return loading ? "тяну статус…" : "нет данных";
        }
        if (!status.hasData) {
            return status.error == null || status.error.isEmpty() ? "нет связи" : status.error;
        }
        if (!status.online) {
            return "Сервер не отвечает";
        }

        String version = status.version == null ? "" : status.version.trim();
        String edition = entry.isBedrock() ? "Bedrock · " : "";
        String name = entry.displayName();
        if (!version.isEmpty() && !name.isEmpty()
                && version.regionMatches(true, 0, name, 0, name.length())) {
            version = version.substring(name.length()).trim();
            while (version.startsWith("-") || version.startsWith("|") || version.startsWith("—")) {
                version = version.substring(1).trim();
            }
        }

        String description = description(status, name);
        if (version.isEmpty()) {
            return edition + description;
        }
        if (description.isEmpty() || description.equalsIgnoreCase(version)) {
            return edition + version;
        }
        return edition + version + " · " + description;
    }

    private String description(ServerStatus status, String name) {
        if (status.motd == null || status.motd.isEmpty()) {
            return "";
        }
        String[] lines = status.motd.split("\n");
        String first = "";
        for (String raw : lines) {
            String line = raw.replaceAll("§.", "").trim();
            if (line.isEmpty()) {
                continue;
            }
            if (first.isEmpty()) {
                first = line;
            } else {
                if (!line.equalsIgnoreCase(name) && !line.equalsIgnoreCase(first)) {
                    return shorten(line, 64);
                }
            }
        }
        if (first.equalsIgnoreCase(name)) {
            return "";
        }
        return shorten(first, 64);
    }

    private void addPlayerChip(FlowLayout flow, String player, int index) {
        TextView chip = new TextView(this);
        chip.setText(player);
        chip.setTextSize(11f);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setTextColor(getColor(CHIP_TEXT_COLORS[index % CHIP_TEXT_COLORS.length]));
        chip.setBackgroundResource(CHIP_BACKGROUNDS[index % CHIP_BACKGROUNDS.length]);
        chip.setPadding(dp(9), dp(4), dp(9), dp(4));
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setMaxWidth(dp(170));

        FlowLayout.LayoutParams params = new FlowLayout.LayoutParams(
                FlowLayout.LayoutParams.WRAP_CONTENT,
                FlowLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, dp(6), dp(6));
        flow.addView(chip, params);
    }

    private void addMoreChip(FlowLayout flow, String label) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(11f);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setTextColor(getColor(R.color.soft));
        chip.setBackgroundResource(R.drawable.bg_input);
        chip.setPadding(dp(9), dp(4), dp(9), dp(4));
        chip.setSingleLine(true);

        FlowLayout.LayoutParams params = new FlowLayout.LayoutParams(
                FlowLayout.LayoutParams.WRAP_CONTENT,
                FlowLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, dp(6), dp(6));
        flow.addView(chip, params);
    }

    private Bitmap decodeIcon(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        String key = base64.length() + ":" + base64.hashCode();
        Bitmap cached = iconCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bitmap != null) {
                iconCache.put(key, bitmap);
            }
            return bitmap;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void toggleSearch(View toggle) {
        if (searchInput.getVisibility() != View.VISIBLE) {
            searchInput.setVisibility(View.VISIBLE);
            headerActionsSpacer.setVisibility(View.GONE);
            searchInput.setAlpha(0f);
            searchInput.animate().alpha(1f).setDuration(180).start();
            searchInput.requestFocus();
            toggle.setContentDescription("Закрыть поиск");
            InputMethodManager input = (InputMethodManager) getSystemService(
                    Context.INPUT_METHOD_SERVICE);
            if (input != null) {
                input.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
            }
            return;
        }

        InputMethodManager input = (InputMethodManager) getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (input != null) {
            input.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        }
        searchInput.setText("");
        searchInput.clearFocus();
        searchInput.setVisibility(View.GONE);
        searchInput.setAlpha(1f);
        headerActionsSpacer.setVisibility(View.VISIBLE);
        toggle.setContentDescription("Поиск серверов");
        buildCards(false);
    }

    private void openCommunitySearch() {
        String query = searchInput.getText().toString().trim();
        if (query.length() < 2) {
            Toast.makeText(this, "Для поиска сообщества введи хотя бы 2 символа.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        InputMethodManager input = (InputMethodManager) getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (input != null) {
            input.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        }
        AccountSession session = AccountSessionStore.load(this);
        if (session == null) {
            Toast.makeText(this, "Войди в аккаунт, чтобы искать серверы и игроков сообщества.",
                    Toast.LENGTH_LONG).show();
            showAccountMenu();
            return;
        }
        showCommunityDialog(session, false, query, 1);
    }

    private void updateAccountButton() {
        AccountSession session = AccountSessionStore.load(this);
        accountButton.setContentDescription(session == null
                ? "Профиль: войти или создать аккаунт"
                : "Профиль " + session.username);
        accountButton.setSelected(session != null);
    }

    private void showAccountMenu() {
        AccountSession session = AccountSessionStore.load(this);
        if (session == null) {
            String[] options = {"Войти", "Создать аккаунт"};
            new AlertDialog.Builder(this, R.style.DialogTheme)
                    .setTitle("Аккаунт")
                    .setItems(options, (dialog, which) -> showAccountForm(which == 1))
                    .setNegativeButton("Закрыть", null)
                    .show();
            return;
        }

        String[] options = {
                "Профиль",
                "Поиск игроков и серверов",
                "Мои подписки",
                "Синхронизировать сейчас",
                "Выйти из аккаунта"
        };
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(session.username)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showProfileDialog(session);
                    } else if (which == 1) {
                        showCommunityDialog(session, false);
                    } else if (which == 2) {
                        showCommunityDialog(session, true);
                    } else if (which == 3) {
                        requestCloudSync(false);
                    } else {
                        new AlertDialog.Builder(this, R.style.DialogTheme)
                                .setTitle("Выйти из аккаунта?")
                                .setMessage("Локальный список серверов останется на устройстве.")
                                .setPositiveButton("Выйти", (confirm, action) -> {
                                    AccountSessionStore.clearSession(this);
                                    updateAccountButton();
                                    Toast.makeText(this, "Вы вышли из аккаунта.", Toast.LENGTH_SHORT)
                                            .show();
                                })
                                .setNegativeButton("Отмена", null)
                                .show();
                    }
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void showProfileDialog(AccountSession session) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        form.setPadding(padding, dp(8), padding, 0);

        TextView username = new TextView(this);
        username.setText("@" + session.username + "  ·  "
                + ServerStore.load(this).size() + " серверов в вашем списке");
        username.setTextColor(getColor(R.color.dim));
        form.addView(username);

        profileAvatarView = new ImageView(this);
        profileAvatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        profileAvatarView.setBackground(avatarBackground());
        profileAvatarView.setClipToOutline(true);
        form.addView(profileAvatarView, new LinearLayout.LayoutParams(dp(72), dp(72)));

        profileDisplayName = new EditText(this);
        profileDisplayName.setSingleLine(true);
        profileDisplayName.setHint("Отображаемое имя");
        profileDisplayName.setMaxLines(1);
        form.addView(profileDisplayName);

        profileBio = new EditText(this);
        profileBio.setHint("О себе (до 160 символов)");
        profileBio.setMinLines(2);
        profileBio.setMaxLines(4);
        form.addView(profileBio);

        profileShareServers = new CheckBox(this);
        profileShareServers.setText("Публично показывать адреса моих серверов");
        profileShareServers.setTextColor(getColor(R.color.soft));
        form.addView(profileShareServers);
        TextView privacyNote = new TextView(this);
        privacyNote.setText("Выключено по умолчанию. При включении адреса будут видны "
                + "авторизованным пользователям в поиске.");
        privacyNote.setTextColor(getColor(R.color.dim));
        privacyNote.setTextSize(12f);
        form.addView(privacyNote);

        Button avatarButton = new Button(this);
        avatarButton.setText("Выбрать аватар");
        avatarButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_AVATAR);
        });
        form.addView(avatarButton);

        profileStatus = new TextView(this);
        profileStatus.setText("Загружаю профиль…");
        profileStatus.setTextColor(getColor(R.color.dim));
        form.addView(profileStatus);
        pendingAvatarJpeg = null;

        profileDialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Мой профиль")
                .setView(form)
                .setNegativeButton("Закрыть", null)
                .setPositiveButton("Сохранить", null)
                .create();
        profileDialog.setOnShowListener(ignored -> {
            profileDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.mint));
            profileDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.soft));
            profileDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                    saveProfile(session));
        });
        profileDialog.setOnDismissListener(ignored -> {
            profileDialog = null;
            profileDisplayName = null;
            profileBio = null;
            profileShareServers = null;
            profileAvatarView = null;
            profileStatus = null;
            pendingAvatarJpeg = null;
        });
        profileDialog.show();

        syncExecutor.execute(() -> {
            try {
                CloudSyncClient.PublicProfile profile = CloudSyncClient.fetchProfile(session);
                byte[] avatar = null;
                String avatarWarning = null;
                try {
                    avatar = CloudSyncClient.downloadAvatar(profile);
                } catch (IOException exception) {
                    avatarWarning = "Не удалось загрузить аватар: " + exception.getMessage();
                    Log.w(TAG, "Не удалось загрузить аватар профиля", exception);
                }
                byte[] avatarBytes = avatar;
                String warning = avatarWarning;
                AccountSessionStore.save(getApplicationContext(), session);
                handler.post(() -> {
                    if (profileDialog == null || profileDisplayName == null) {
                        return;
                    }
                    profileDisplayName.setText(profile.displayName);
                    profileBio.setText(profile.bio);
                    profileShareServers.setChecked(profile.shareServers);
                    if (profileAvatarView != null && avatarBytes != null) {
                        Bitmap bitmap = BitmapFactory.decodeByteArray(
                                avatarBytes, 0, avatarBytes.length);
                        if (bitmap != null) {
                            profileAvatarView.setImageBitmap(bitmap);
                        }
                    }
                    profileStatus.setText(warning != null
                            ? warning
                            : profile.avatarPath == null
                            ? "Аватар не установлен."
                            : "Текущий аватар установлен.");
                });
            } catch (Exception exception) {
                handler.post(() -> {
                    if (profileStatus != null) {
                        profileStatus.setText(cloudErrorMessage(exception));
                    }
                });
            }
        });
    }

    private void saveProfile(AccountSession session) {
        if (profileDisplayName == null || profileBio == null || profileShareServers == null) {
            return;
        }
        String displayName = profileDisplayName.getText().toString();
        String bio = profileBio.getText().toString();
        boolean share = profileShareServers.isChecked();
        byte[] avatar = pendingAvatarJpeg;
        if (profileStatus != null) {
            profileStatus.setText("Сохраняю профиль…");
        }
        profileDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        syncExecutor.execute(() -> {
            try {
                CloudSyncClient.updateProfile(session, displayName, bio, share);
                if (avatar != null) {
                    CloudSyncClient.uploadAvatar(session, avatar);
                }
                AccountSessionStore.save(getApplicationContext(), session);
                handler.post(() -> {
                    if (profileDialog != null) {
                        profileDialog.dismiss();
                    }
                    Toast.makeText(this, "Профиль сохранён.", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception exception) {
                handler.post(() -> {
                    if (profileDialog != null && profileStatus != null) {
                        profileStatus.setText(cloudErrorMessage(exception));
                        profileDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    }
                });
            }
        });
    }

    private static final int REQUEST_AVATAR = 7103;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_AVATAR || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            Bitmap selected = decodeAvatar(uri);
            int side = Math.min(selected.getWidth(), selected.getHeight());
            Bitmap square = Bitmap.createBitmap(
                    selected,
                    (selected.getWidth() - side) / 2,
                    (selected.getHeight() - side) / 2,
                    side,
                    side);
            Bitmap resized = Bitmap.createScaledBitmap(square, 512, 512, true);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!resized.compress(Bitmap.CompressFormat.JPEG, 86, output)
                    || output.size() > 2 * 1024 * 1024) {
                throw new IOException("Изображение слишком большое для аватара.");
            }
            pendingAvatarJpeg = output.toByteArray();
            if (profileAvatarView != null) {
                Bitmap preview = BitmapFactory.decodeByteArray(
                        pendingAvatarJpeg, 0, pendingAvatarJpeg.length);
                if (preview != null) {
                    profileAvatarView.setImageBitmap(preview);
                }
            }
            if (profileStatus != null) {
                profileStatus.setText("Аватар готов к загрузке.");
            }
            if (square != selected) {
                square.recycle();
            }
            if (resized != selected && resized != square) {
                resized.recycle();
            }
            selected.recycle();
        } catch (Exception exception) {
            if (profileStatus != null) {
                profileStatus.setText("Не удалось открыть изображение: " + exception.getMessage());
            }
        }
    }

    private Bitmap decodeAvatar(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Не удалось открыть выбранное изображение.");
            }
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Выбранный файл не является изображением.");
        }
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 1024) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
            Bitmap selected = input == null ? null : BitmapFactory.decodeStream(input, null, options);
            if (selected == null) {
                throw new IOException("Не удалось прочитать изображение.");
            }
            return selected;
        }
    }

    private void showCommunityDialog(AccountSession session, boolean followedOnly) {
        showCommunityDialog(session, followedOnly, "", followedOnly ? 2 : 0);
    }

    private void showCommunityDialog(
            AccountSession session, boolean followedOnly, String initialQuery, int initialMode) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(18);
        root.setPadding(padding, dp(6), padding, 0);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        String[] tabNames = {"Игроки", "Серверы", "Подписки"};
        Button[] tabButtons = new Button[tabNames.length];
        for (int i = 0; i < tabNames.length; i++) {
            final int mode = i;
            Button tab = new Button(this);
            tab.setText(tabNames[i]);
            tab.setOnClickListener(v -> setCommunityMode(mode, session));
            tabs.addView(tab, new LinearLayout.LayoutParams(0, dp(44), 1f));
            tabButtons[i] = tab;
        }
        root.addView(tabs);

        communitySearch = new EditText(this);
        communitySearch.setSingleLine(true);
        communitySearch.setHint("Ник, имя или сервер (от 2 символов)");
        root.addView(communitySearch);

        ScrollView resultsScroll = new ScrollView(this);
        communityResults = new LinearLayout(this);
        communityResults.setOrientation(LinearLayout.VERTICAL);
        communityResults.setPadding(0, dp(8), 0, dp(8));
        resultsScroll.addView(communityResults);
        root.addView(resultsScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360)));

        communityDialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Сообщество")
                .setView(root)
                .setNegativeButton("Закрыть", null)
                .create();
        communityDialog.setOnDismissListener(ignored -> {
            communityDialog = null;
            communitySearch = null;
            communityResults = null;
            communityRequestId++;
        });
        communityDialog.show();
        communityMode = followedOnly ? 2 : initialMode;
        communitySearch.setVisibility(followedOnly ? View.GONE : View.VISIBLE);
        communitySearch.setText(initialQuery);
        communitySearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                handler.removeCallbacksAndMessages("community-search");
                handler.postAtTime(
                        () -> loadCommunity(session),
                        "community-search",
                        android.os.SystemClock.uptimeMillis() + 320);
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        });
        for (int i = 0; i < tabButtons.length; i++) {
            tabButtons[i].setEnabled(i != 2 || !followedOnly);
        }
        loadCommunity(session);
    }

    private void setCommunityMode(int mode, AccountSession session) {
        communityMode = mode;
        if (communitySearch != null) {
            communitySearch.setVisibility(mode == 2 ? View.GONE : View.VISIBLE);
        }
        loadCommunity(session);
    }

    private void loadCommunity(AccountSession session) {
        if (communityDialog == null || communityResults == null) {
            return;
        }
        final int requestId = ++communityRequestId;
        final int mode = communityMode;
        final String query = communitySearch == null
                ? "" : communitySearch.getText().toString().trim();
        communityResults.removeAllViews();
        if (mode != 2 && query.length() < 2) {
            addCommunityMessage("Введи не менее двух символов для поиска.");
            return;
        }
        addCommunityMessage("Ищу…");
        syncExecutor.execute(() -> {
            List<CloudSyncClient.PublicProfile> profiles = null;
            List<CloudSyncClient.PublicServer> servers = null;
            Exception failure = null;
            try {
                if (mode == 0) {
                    profiles = CloudSyncClient.searchProfiles(session, query);
                } else if (mode == 1) {
                    servers = CloudSyncClient.searchPublicServers(session, query);
                } else {
                    profiles = CloudSyncClient.listFollowedProfiles(session);
                }
                AccountSessionStore.save(getApplicationContext(), session);
            } catch (Exception exception) {
                failure = exception;
            }
            List<CloudSyncClient.PublicProfile> finalProfiles = profiles;
            List<CloudSyncClient.PublicServer> finalServers = servers;
            Exception finalFailure = failure;
            handler.post(() -> {
                if (communityDialog == null || requestId != communityRequestId) {
                    return;
                }
                communityResults.removeAllViews();
                if (finalFailure != null) {
                    addCommunityMessage(cloudErrorMessage(finalFailure));
                } else if (mode == 1) {
                    renderCommunityServers(finalServers);
                } else {
                    renderCommunityProfiles(
                            finalProfiles,
                            mode == 2,
                            session);
                }
            });
        });
    }

    private void renderCommunityProfiles(
            List<CloudSyncClient.PublicProfile> profiles,
            boolean followed,
            AccountSession session) {
        if (profiles.isEmpty()) {
            addCommunityMessage(followed ? "Ты пока ни на кого не подписан."
                    : "Ничего не найдено.");
            return;
        }
        for (CloudSyncClient.PublicProfile profile : profiles) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            LinearLayout identity = new LinearLayout(this);
            identity.setGravity(android.view.Gravity.CENTER_VERTICAL);
            ImageView avatar = new ImageView(this);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setBackground(avatarBackground());
            avatar.setClipToOutline(true);
            avatar.setTag(profile.userId);
            identity.addView(avatar, new LinearLayout.LayoutParams(dp(40), dp(40)));
            TextView title = new TextView(this);
            title.setText((profile.displayName.isEmpty()
                    ? profile.username : profile.displayName + "  @" + profile.username));
            title.setTextColor(getColor(R.color.soft));
            title.setTextSize(15);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            titleParams.leftMargin = dp(10);
            identity.addView(title, titleParams);
            row.addView(identity);
            loadCommunityAvatar(avatar, profile);
            if (!profile.bio.isEmpty()) {
                TextView bio = new TextView(this);
                bio.setText(profile.bio);
                bio.setTextColor(getColor(R.color.dim));
                row.addView(bio);
            }
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            Button follow = new Button(this);
            follow.setText(profile.isFollowing ? "Отписаться" : "Подписаться");
            follow.setOnClickListener(v -> changeFollow(session, profile));
            actions.addView(follow);
            Button servers = new Button(this);
            servers.setText("Серверы");
            servers.setOnClickListener(v -> loadProfileServers(session, profile));
            actions.addView(servers);
            row.addView(actions);
            communityResults.addView(row);
            View divider = new View(this);
            divider.setBackgroundColor(Color.argb(45, 255, 255, 255));
            communityResults.addView(divider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        }
    }

    private GradientDrawable avatarBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.argb(50, 126, 224, 163));
        background.setStroke(dp(1), Color.argb(100, 126, 224, 163));
        return background;
    }

    private void loadCommunityAvatar(
            ImageView imageView, CloudSyncClient.PublicProfile profile) {
        if (profile.avatarPath == null) {
            return;
        }
        executor.execute(() -> {
            try {
                byte[] data = CloudSyncClient.downloadAvatar(profile);
                if (data == null) {
                    return;
                }
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap == null) {
                    throw new IOException("Изображение аватара повреждено.");
                }
                handler.post(() -> {
                    if (!isFinishing() && profile.userId.equals(imageView.getTag())) {
                        imageView.setImageBitmap(bitmap);
                    }
                });
            } catch (IOException exception) {
                Log.w(TAG, "Не удалось загрузить публичный аватар", exception);
            }
        });
    }

    private void renderCommunityServers(List<CloudSyncClient.PublicServer> servers) {
        if (servers.isEmpty()) {
            addCommunityMessage("Публичных серверов не найдено.");
            return;
        }
        for (CloudSyncClient.PublicServer server : servers) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(8), 0, dp(8));
            TextView details = new TextView(this);
            details.setText(server.name + "\n" + server.address + " · "
                    + server.protocol.toUpperCase(Locale.ROOT) + " · @" + server.username);
            details.setTextColor(getColor(R.color.soft));
            row.addView(details, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            Button add = new Button(this);
            add.setText("Добавить");
            add.setOnClickListener(v -> addPublicServer(server));
            row.addView(add);
            communityResults.addView(row);
        }
    }

    private void addCommunityMessage(String message) {
        if (communityResults == null) {
            return;
        }
        TextView status = new TextView(this);
        status.setText(message);
        status.setTextColor(getColor(R.color.dim));
        status.setPadding(0, dp(12), 0, dp(12));
        communityResults.addView(status);
    }

    private void changeFollow(
            AccountSession session, CloudSyncClient.PublicProfile profile) {
        syncExecutor.execute(() -> {
            try {
                if (profile.isFollowing) {
                    CloudSyncClient.unfollowProfile(session, profile.userId);
                } else {
                    CloudSyncClient.followProfile(session, profile.userId);
                }
                AccountSessionStore.save(getApplicationContext(), session);
                handler.post(() -> loadCommunity(session));
            } catch (Exception exception) {
                handler.post(() -> Toast.makeText(
                        this, cloudErrorMessage(exception), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void loadProfileServers(
            AccountSession session, CloudSyncClient.PublicProfile profile) {
        final int requestId = ++communityRequestId;
        syncExecutor.execute(() -> {
            try {
                List<CloudSyncClient.PublicServer> servers =
                        CloudSyncClient.listPublicServers(session, profile.userId);
                AccountSessionStore.save(getApplicationContext(), session);
                handler.post(() -> {
                    if (communityResults == null || requestId != communityRequestId) {
                        return;
                    }
                    communityResults.removeAllViews();
                    renderCommunityServers(servers);
                });
            } catch (Exception exception) {
                handler.post(() -> Toast.makeText(
                        this, cloudErrorMessage(exception), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void addPublicServer(CloudSyncClient.PublicServer server) {
        List<ServerEntry> servers = ServerStore.load(this);
        for (ServerEntry existing : servers) {
            if (ServerEntry.normalize(existing.address)
                    .equalsIgnoreCase(ServerEntry.normalize(server.address))) {
                Toast.makeText(this, "Этот сервер уже есть в списке.", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        ServerEntry entry = new ServerEntry(server.address, server.name);
        entry.protocol = ServerEntry.normalizeProtocol(server.protocol);
        servers.add(entry);
        ServerStore.save(this, servers);
        buildCards();
        requestCloudSync(true);
        Toast.makeText(this, "Сервер добавлен.", Toast.LENGTH_SHORT).show();
    }

    private void applySavedAccent() {
        int color = getSharedPreferences("app_settings", MODE_PRIVATE)
                .getInt("accent_color", Color.rgb(126, 224, 163));
        applyAccent(color);
    }

    private void applyAccent(int color) {
        getWindow().setStatusBarColor(
                Build.VERSION.SDK_INT >= 35
                        ? Color.TRANSPARENT : BackdropView.topBarColor(color));
        View addButton = findViewById(R.id.addButton);
        int lighter = Color.rgb(
                Color.red(color) + (255 - Color.red(color)) / 5,
                Color.green(color) + (255 - Color.green(color)) / 5,
                Color.blue(color) + (255 - Color.blue(color)) / 5);
        GradientDrawable buttonBackground = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{lighter, color});
        buttonBackground.setCornerRadius(dp(18));
        addButton.setBackground(buttonBackground);
        ((ImageView) findViewById(R.id.searchToggle)).setColorFilter(color);
        ((ImageView) findViewById(R.id.settingsButton)).setColorFilter(color);
        ((ImageView) findViewById(R.id.refresh)).setColorFilter(color);
        ((BackdropView) findViewById(R.id.backdrop)).setAccentColor(color);
    }

        private void showAccountForm(boolean register) {
            LinearLayout form = new LinearLayout(this);
            form.setOrientation(LinearLayout.VERTICAL);
            int padding = dp(20);
            form.setPadding(padding, dp(8), padding, 0);

            TextView recoveryNote = new TextView(this);
            recoveryNote.setText(R.string.account_recovery_note);
            recoveryNote.setTextColor(getColor(R.color.dim));
            recoveryNote.setTextSize(12f);
            form.addView(recoveryNote, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            EditText username = new EditText(this);
            username.setSingleLine(true);
            username.setHint("Никнейм (3–16 символов)");
            username.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
            form.addView(username, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            EditText password = new EditText(this);
            password.setSingleLine(true);
            password.setHint("Пароль (от 8 символов)");
            password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            form.addView(password, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            AlertDialog dialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                    .setTitle(register ? "Создать аккаунт" : "Войти")
                    .setView(form)
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton(register ? "Создать" : "Войти", null)
                    .create();
            dialog.setOnShowListener(ignored -> {
                Button submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                submit.setTextColor(getColor(R.color.mint));
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.soft));
                submit.setOnClickListener(v -> {
                    String nick = username.getText().toString().trim();
                    String pass = password.getText().toString();
                    try {
                        CloudSyncClient.validateCredentials(nick, pass);
                    } catch (IllegalArgumentException exception) {
                        Toast.makeText(this, exception.getMessage(), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dialog.dismiss();
                    authenticate(register, nick, pass);
                });
            });
            dialog.show();
        }

        private void authenticate(boolean register, String username, String password) {
            accountButton.setEnabled(false);
            syncExecutor.execute(() -> {
                try {
                    AccountSession session = register
                            ? CloudSyncClient.signUp(username, password)
                            : CloudSyncClient.signIn(username, password);
                    AccountSessionStore.save(getApplicationContext(), session);
                    int serverCount = synchronizeWithCloud(session);
                    handler.post(() -> {
                        updateAccountButton();
                        accountButton.setEnabled(true);
                        buildCards();
                        Toast.makeText(
                                this,
                                "Синхронизировано серверов: " + serverCount,
                                Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception exception) {
                    Log.e(TAG, "Ошибка входа или синхронизации аккаунта", exception);
                    handler.post(() -> {
                        updateAccountButton();
                        accountButton.setEnabled(true);
                        Toast.makeText(this, cloudErrorMessage(exception), Toast.LENGTH_LONG).show();
                    });
                }
            });
        }

        private void requestCloudSync(boolean silent) {
            AccountSession saved = AccountSessionStore.load(getApplicationContext());
            if (saved == null) {
                return;
            }
            synchronized (syncLock) {
                if (syncRunning) {
                    syncPending = true;
                    return;
                }
                syncRunning = true;
            }
            accountButton.setEnabled(false);
            syncExecutor.execute(() -> {
                boolean showResult = !silent;
                while (true) {
                    AccountSession session = AccountSessionStore.load(getApplicationContext());
                    if (session == null) {
                        finishCloudSync();
                        return;
                    }
                    try {
                        int serverCount = synchronizeWithCloud(session);
                        AccountSessionStore.save(getApplicationContext(), session);
                        boolean showMessage = showResult;
                        handler.post(() -> {
                            updateAccountButton();
                            buildCards();
                            if (showMessage) {
                                Toast.makeText(
                                        this,
                                        "Синхронизировано серверов: " + serverCount,
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Exception exception) {
                        Log.w(TAG, "Не удалось синхронизировать серверы", exception);
                        try {
                            AccountSessionStore.save(getApplicationContext(), session);
                        } catch (Exception saveException) {
                            Log.w(TAG, "Не удалось сохранить обновлённую сессию", saveException);
                        }
                        boolean showMessage = showResult;
                        handler.post(() -> {
                            updateAccountButton();
                            if (showMessage) {
                                Toast.makeText(this, cloudErrorMessage(exception), Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                    synchronized (syncLock) {
                        if (syncPending) {
                            syncPending = false;
                            showResult = false;
                            continue;
                        }
                        syncRunning = false;
                        handler.post(() -> accountButton.setEnabled(true));
                        return;
                    }
                }
            });
        }

        private void finishCloudSync() {
            synchronized (syncLock) {
                syncRunning = false;
                syncPending = false;
            }
            handler.post(() -> accountButton.setEnabled(true));
        }

        private int synchronizeWithCloud(AccountSession session) throws Exception {
            Context context = getApplicationContext();
            String previousOwner = AccountSessionStore.lastOwnerId(context);
            List<ServerEntry> local = ServerStore.load(context);
            List<ServerEntry> remote = CloudSyncClient.fetchServers(session);
            List<ServerEntry> merged;
            if (previousOwner != null && !previousOwner.equals(session.userId)) {
                AccountSessionStore.saveBaseServers(context, new java.util.ArrayList<>());
                merged = remote;
            } else {
                List<ServerEntry> base = previousOwner == null
                        ? null
                        : AccountSessionStore.loadBaseServers(context);
                merged = CloudSyncClient.mergeServers(base, local, remote);
            }
            List<ServerEntry> latestLocal = ServerStore.load(context);
            merged = CloudSyncClient.mergeServers(local, latestLocal, merged);
            ServerStore.save(context, merged);
            AccountSessionStore.saveOwnerId(context, session.userId);
            CloudSyncClient.saveServers(session, merged);
            AccountSessionStore.saveBaseServers(context, merged);
            AccountSessionStore.save(context, session);
            return merged.size();
        }

        private String cloudErrorMessage(Exception exception) {
            String message = exception.getMessage();
            if (message == null || message.trim().isEmpty()) {
                return "Не удалось подключиться к облаку.";
            }
            return message;
        }
    private void refreshAll() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        List<ServerEntry> entries = ServerStore.load(this);
        for (ServerEntry entry : entries) {
            fetch(entry);
        }
    }

    private void fetch(ServerEntry entry) {
        final String entryId = entry.id;
        final String address = entry.address;
        final String protocol = ServerEntry.normalizeProtocol(entry.protocol);

        View card = cardViews.get(entryId);
        if (card != null) {
            ServerStatus cached = StatusCache.load(this, address);
            renderCard(card, entry, cached, cached == null);
        }

        executor.execute(() -> {
            ServerStatus previous = StatusCache.load(getApplicationContext(), address);
            final ServerStatus status = MinecraftApi.fetch(address, protocol);
            PriorityPlayerNotifications.notifyChanges(
                    getApplicationContext(),
                    ServerStore.findByAddress(getApplicationContext(), address),
                    previous,
                    status);
            StatusCache.save(getApplicationContext(), status);
            handler.post(() -> applyStatus(entryId, address, status));
        });
    }

    private void applyStatus(String entryId, String address, ServerStatus status) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        ServerEntry current = ServerStore.findById(this, entryId);
        if (current == null || !current.address.equals(address)) {
            return;
        }

        if (status != null && status.hasData && status.online
                && (current.name == null || current.name.trim().isEmpty())) {
            String auto = deriveName(status);
            if (!auto.isEmpty() && !auto.equals(current.autoName)) {
                current.autoName = auto;
                ServerStore.saveEntry(this, current);
            }
        }

        View card = cardViews.get(entryId);
        if (card != null) {
            renderCard(card, current, status, false);
        }
        updateServerSummary(ServerStore.load(this));
        WidgetUpdater.renderAll(this);
    }

    private String deriveName(ServerStatus status) {
        if (status.motd == null || status.motd.isEmpty()) {
            return "";
        }
        String line = "";
        for (String raw : status.motd.split("\n")) {
            String candidate = raw.replaceAll("§.", "").trim();
            if (!candidate.isEmpty()) {
                line = candidate;
                break;
            }
        }
        if (line.isEmpty()) {
            return "";
        }

        String[] parts = line.split("\\s*[|•·—–]\\s*");
        String candidate = parts.length > 0 ? parts[0].trim() : line;
        candidate = candidate.replaceAll("\\s{2,}", " ").trim();
        candidate = candidate.replaceAll("^[^\\p{L}\\p{N}]+", "");
        candidate = shorten(candidate, 28);
        return candidate.length() < 2 ? "" : candidate;
    }

    private void showServerMenu(ServerEntry entry, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Изменить");
        menu.getMenu().add(0, 2, 1, "Добавить виджет");
        menu.getMenu().add(0, 4, 2, "Приоритеты игроков…");
        menu.getMenu().add(0, 5, 3,
                entry.groupId == null || entry.groupId.isEmpty()
                        ? "Добавить в группу…" : "Группа: " + entry.groupId);
        menu.getMenu().add(0, 3, 4, "Удалить");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                showServerDialog(entry);
                return true;
            }
            if (item.getItemId() == 2) {
                showWidgetPicker(entry);
                return true;
            }
            if (item.getItemId() == 3) {
                confirmDelete(entry);
                return true;
            }
            if (item.getItemId() == 4) {
                showPrioritiesDialog(entry);
                return true;
            }
            if (item.getItemId() == 5) {
                showGroupDialog(entry);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void showPrioritiesDialog(ServerEntry entry) {
        EditText input = new EditText(this);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setMinLines(4);
        input.setMaxLines(8);
        input.setHint("Например:\nNotch\nJeb_\nDinnerbone");
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (entry.priorityPlayers != null) {
            input.setText(TextUtils.join("\n", entry.priorityPlayers));
        }

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Приоритеты · " + entry.displayName())
                .setMessage("Уведомления о входе и выходе будут только для этих ников.")
                .setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setTextColor(getColor(R.color.mint));
            save.setOnClickListener(v -> {
                List<String> names = new java.util.ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                String raw = input.getText().toString().replace(",", "\n");
                for (String line : raw.split("\\R")) {
                    String name = line.trim();
                    if (name.isEmpty()) {
                        continue;
                    }
                    if (name.length() > 32 || name.matches(".*\\s+.*")) {
                        input.setError("Ник до 32 символов, без пробелов");
                        return;
                    }
                    if (names.size() >= 50) {
                        input.setError("Можно отслеживать не больше 50 ников");
                        return;
                    }
                    if (seen.add(name.toLowerCase(Locale.ROOT))) {
                        names.add(name);
                    }
                }
                entry.priorityPlayers = names;
                ServerStore.saveEntry(this, entry);
                requestCloudSync(true);
                if (!names.isEmpty() && Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                            new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                            REQUEST_NOTIFICATIONS);
                }
                dialog.dismiss();
                Toast.makeText(this, names.isEmpty()
                        ? "Отслеживание игроков выключено"
                        : "Приоритеты сохранены и синхронизируются",
                        Toast.LENGTH_SHORT).show();
            });
        });
        dialog.show();
    }

    private void showGroupDialog(ServerEntry entry) {
        List<String> groups = new java.util.ArrayList<>();
        for (ServerEntry server : ServerStore.load(this)) {
            String group = server.groupId == null ? "" : server.groupId.trim();
            if (!group.isEmpty() && !containsIgnoreCase(groups, group)) {
                groups.add(group);
            }
        }
        List<String> options = new java.util.ArrayList<>();
        options.add("Без группы");
        options.addAll(groups);
        options.add("＋ Создать новую группу");
        String[] labels = options.toArray(new String[0]);
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Группа серверов")
                .setItems(labels, (dialog, which) -> {
                    if (which == labels.length - 1) {
                        showCreateGroupDialog(entry, groups);
                    } else {
                        saveGroup(entry, which == 0 ? "" : groups.get(which - 1));
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showCreateGroupDialog(ServerEntry entry, List<String> existingGroups) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Название группы");
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Новая группа")
                .setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Создать", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button create = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            create.setTextColor(getColor(R.color.mint));
            create.setOnClickListener(v -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty() || name.length() > 60) {
                    input.setError("Название должно содержать 1–60 символов");
                    return;
                }
                String selected = name;
                for (String existing : existingGroups) {
                    if (existing.equalsIgnoreCase(name)) {
                        selected = existing;
                        break;
                    }
                }
                saveGroup(entry, selected);
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private boolean containsIgnoreCase(List<String> values, String candidate) {
        for (String value : values) {
            if (value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void saveGroup(ServerEntry entry, String groupId) {
        entry.groupId = groupId;
        ServerStore.saveEntry(this, entry);
        requestCloudSync(true);
        Toast.makeText(this,
                groupId.isEmpty() ? "Сервер убран из группы" : "Сервер добавлен в группу " + groupId,
                Toast.LENGTH_SHORT).show();
    }

    private void showServerDialog(ServerEntry entry) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_server, null);
        EditText nameInput = view.findViewById(R.id.nameInput);
        EditText addressInput = view.findViewById(R.id.addressInput);
        Spinner protocolInput = view.findViewById(R.id.protocolInput);
        TextView addressLabel = view.findViewById(R.id.addressLabel);
        TextView protocolHint = view.findViewById(R.id.protocolHint);
        String[] protocolLabels = {"Java Edition", "Bedrock Edition"};
        ArrayAdapter<String> protocolAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, protocolLabels);
        protocolAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        protocolInput.setAdapter(protocolAdapter);
        protocolInput.setSelection(entry != null && entry.isBedrock() ? 1 : 0);
        protocolInput.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    android.widget.AdapterView<?> parent, View selected, int position, long id) {
                addressInput.setHint(position == 1
                        ? "mc.example.com или host:19132"
                        : "mc.example.com или host:25565");
                addressLabel.setText(position == 1 ? "Адрес сервера Bedrock" : "IP сервера");
                protocolHint.setText(position == 1
                        ? "Bedrock Edition · порт по умолчанию 19132"
                        : "Java Edition · порт по умолчанию 25565");
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        if (entry != null) {
            nameInput.setText(entry.name == null ? "" : entry.name);
            addressInput.setText(entry.address);
        }

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(entry == null ? "Новый сервер" : "Изменить сервер")
                .setView(view)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setTextColor(getColor(R.color.mint));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.soft));

            positive.setOnClickListener(v -> {
                String address = ServerEntry.normalize(addressInput.getText().toString());
                String name = nameInput.getText().toString().trim();

                if (address.isEmpty()) {
                    addressInput.setError("Введи IP или домен сервера");
                    return;
                }
                if (address.length() > 120) {
                    addressInput.setError("Слишком длинный адрес");
                    return;
                }
                if (ServerStore.isAddressTaken(this, address, entry == null ? null : entry.id)) {
                    addressInput.setError("Этот сервер уже есть в списке");
                    return;
                }

                if (entry == null) {
                    ServerEntry created = new ServerEntry(address, name);
                    created.protocol = protocolInput.getSelectedItemPosition() == 1
                            ? ServerEntry.PROTOCOL_BEDROCK : ServerEntry.PROTOCOL_JAVA;
                    List<ServerEntry> entries = ServerStore.load(this);
                    entries.add(created);
                    ServerStore.save(this, entries);
                    requestCloudSync(true);
                    dialog.dismiss();
                    buildCards();
                    fetch(created);
                    scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                } else {
                    String oldAddress = entry.address;
                    entry.address = address;
                    entry.name = name;
                    entry.protocol = protocolInput.getSelectedItemPosition() == 1
                            ? ServerEntry.PROTOCOL_BEDROCK : ServerEntry.PROTOCOL_JAVA;
                    ServerStore.saveEntry(this, entry);
                    requestCloudSync(true);
                    if (!oldAddress.equals(address)) {
                        if (!ServerStore.isAddressTaken(this, oldAddress, entry.id)) {
                            StatusCache.clear(this, oldAddress);
                        }
                        remapWidgets(oldAddress, address);
                    }
                    dialog.dismiss();
                    buildCards();
                    fetch(entry);
                }
            });
        });

        dialog.show();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (entry == null || entry.address.isEmpty()) {
            addressInput.requestFocus();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
    }

    private void confirmDelete(ServerEntry entry) {
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Удалить сервер?")
                .setMessage("«" + entry.displayName() + "» исчезнет из списка.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (ignored, which) -> {
                    ServerStore.delete(this, entry.id);
                    if (!ServerStore.isAddressTaken(this, entry.address, null)) {
                        StatusCache.clear(this, entry.address);
                    }
                    buildCards();
                    WidgetUpdater.renderAll(this);
                    requestCloudSync(true);
                })
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.coral));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.soft));
        });
        dialog.show();
    }

    private void showWidgetPicker(ServerEntry entry) {
        String[] items = {
                "2 × 2 · компактный",
                "2 × 1 · мини-виджет",
                "3 × 3 · со списком игроков",
        };
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Виджет для «" + entry.displayName() + "»")
                .setItems(items, (dialog, which) -> pinWidget(entry, which))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void pinWidget(ServerEntry entry, int kind) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showWidgetFallback();
            return;
        }
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        if (!manager.isRequestPinAppWidgetSupported()) {
            showWidgetFallback();
            return;
        }

        ComponentName provider = new ComponentName(this, WidgetUpdater.providerClass(kind));
        WidgetStore.clearPending(this);

        Intent callback = new Intent(this, PinResultReceiver.class)
                .setAction("com.mcmonitor.app.action.PIN_RESULT")
                .setData(Uri.parse(
                        "mcmonitor://pin-result/" + kind + "/" + Uri.encode(entry.address)))
                .putExtra("kind", kind)
                .putExtra("address", entry.address);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingFlags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pending = PendingIntent.getBroadcast(
                this, 500 + kind, callback, pendingFlags);

        Bundle extras = new Bundle();
        extras.putInt("kind", kind);
        extras.putString("address", entry.address);

        try {
            boolean requested = manager.requestPinAppWidget(provider, extras, pending);
            if (!requested) {
                WidgetStore.clearPending(this);
                showWidgetFallback();
            }
        } catch (RuntimeException exception) {
            Log.e(TAG, "Не удалось запросить закрепление виджета", exception);
            WidgetStore.clearPending(this);
            showWidgetFallback();
        }
    }

    private void showWidgetFallback() {
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Как добавить виджет")
                .setMessage("Зажми пустое место на главном экране → «Виджеты» → найди MC Monitor → выбери 2×1, 2×2 или 3×3.\n\nЧтобы виджет показывал конкретный сервер, удобнее добавлять его из меню ⋮ у сервера.")
                .setPositiveButton("Понятно", null)
                .show();
    }

    private void remapWidgets(String oldAddress, String newAddress) {
        for (Map.Entry<Integer, WidgetStore.Info> item : WidgetStore.all(this).entrySet()) {
            if (item.getValue().address.equals(oldAddress)) {
                WidgetStore.put(this, item.getKey(), newAddress, item.getValue().kind);
            }
        }
        WidgetUpdater.renderAll(this);
    }

    private String pluralServers(int count) {
        int mod10 = count % 10;
        int mod100 = count % 100;
        if (mod10 == 1 && mod100 != 11) {
            return "сервер";
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return "сервера";
        }
        return "серверов";
    }

    private String shorten(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max).trim() + "…";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
