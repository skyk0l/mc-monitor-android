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
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.InputType;
import android.util.Base64;
import android.util.LruCache;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String TAG = "MCMonitor";

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
    private TextView accountButton;

    private final Map<String, View> cardViews = new java.util.HashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object syncLock = new Object();
    private boolean syncRunning;
    private boolean syncPending;
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
        accountButton = findViewById(R.id.accountButton);
        accountButton.setOnClickListener(v -> showAccountMenu());

        applyWindowInsets();
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

    /**
     * На Android 15 приложение рисуется под шторкой — добавляем отступ и мягкую
     * серую полоску сверху, чтобы часы и заряд не наезжали на контент.
     * На старых версиях система сама отдаёт место, поэтому полоска выходит нулевой.
     */
    private void applyWindowInsets() {
        final View root = findViewById(R.id.root);
        final View scrim = findViewById(R.id.statusScrim);
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

            ViewGroup.LayoutParams params = scrim.getLayoutParams();
            if (params.height != top) {
                params.height = top;
                scrim.setLayoutParams(params);
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
        cardViews.clear();
        serverList.removeAllViews();
        List<ServerEntry> entries = ServerStore.load(this);

        emptyState.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        if (entries.isEmpty()) {
            subtitle.setText("серверы в кармане");
        } else {
            boolean hasJava = false;
            boolean hasBedrock = false;
            for (ServerEntry entry : entries) {
                if (entry.isBedrock()) {
                    hasBedrock = true;
                } else {
                    hasJava = true;
                }
            }
            String editions = hasJava && hasBedrock
                    ? "Java + Bedrock"
                    : hasBedrock ? "Bedrock Edition" : "Java Edition";
            subtitle.setText(entries.size() + " " + pluralServers(entries.size())
                    + " · " + editions);
        }

        for (int i = 0; i < entries.size(); i++) {
            ServerEntry entry = entries.get(i);
            View card = LayoutInflater.from(this).inflate(R.layout.item_server, serverList, false);
            cardViews.put(entry.id, card);
            bindCard(card, entry);
            serverList.addView(card);

            card.setAlpha(0f);
            card.setTranslationY(dp(16));
            card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(280)
                    .setStartDelay(i * 45L)
                    .start();
        }
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

    private void updateAccountButton() {
            AccountSession session = AccountSessionStore.load(this);
            accountButton.setText(session == null ? "Аккаунт" : session.username);
            accountButton.setContentDescription(session == null
                    ? "Войти и синхронизировать серверы"
                    : "Аккаунт " + session.username + ", синхронизация серверов");
        }

        private void showAccountMenu() {
            AccountSession session = AccountSessionStore.load(this);
            if (session == null) {
                String[] options = {"Войти", "Создать аккаунт"};
                new AlertDialog.Builder(this, R.style.DialogTheme)
                        .setTitle("Синхронизация серверов")
                        .setItems(options, (dialog, which) -> showAccountForm(which == 1))
                        .setNegativeButton("Отмена", null)
                        .show();
                return;
            }

            String[] options = {"Синхронизировать сейчас", "Выйти из аккаунта"};
            new AlertDialog.Builder(this, R.style.DialogTheme)
                    .setTitle(session.username)
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) {
                            requestCloudSync(false);
                        } else {
                            AccountSessionStore.clearSession(this);
                            updateAccountButton();
                            Toast.makeText(this, "Вы вышли. Локальный список сохранён.", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Закрыть", null)
                    .show();
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
                    synchronizeWithCloud(session);
                    handler.post(() -> {
                        updateAccountButton();
                        accountButton.setEnabled(true);
                        buildCards();
                        Toast.makeText(this, "Серверы синхронизированы.", Toast.LENGTH_SHORT).show();
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
                        synchronizeWithCloud(session);
                        AccountSessionStore.save(getApplicationContext(), session);
                        boolean showMessage = showResult;
                        handler.post(() -> {
                            updateAccountButton();
                            buildCards();
                            if (showMessage) {
                                Toast.makeText(this, "Серверы синхронизированы.", Toast.LENGTH_SHORT).show();
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

        private void synchronizeWithCloud(AccountSession session) throws Exception {
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
            final ServerStatus status = MinecraftApi.fetch(address, protocol);
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
        menu.getMenu().add(0, 3, 2, "Удалить");
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
            return false;
        });
        menu.show();
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
