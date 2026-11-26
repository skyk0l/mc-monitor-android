package com.mcmonitor.app;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
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
import android.view.Gravity;
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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends LocalizedActivity {

    private static final String TAG = "MCMonitor";
    private static final int REQUEST_NOTIFICATIONS = 7102;
    private static final String APP_SETTINGS = "app_settings";
    private static final String NOTIFICATION_PERMISSION_ASKED =
            "priority_notification_permission_asked";
    private static final String NOTIFICATION_SETTINGS_PROMPTED =
            "priority_notification_settings_prompted";

    private static final long REFRESH_INTERVAL = 10_000L;
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
    private final ExecutorService catalogExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, Long> catalogStatusReportAt = new java.util.HashMap<>();
    private final java.util.Set<String> inFlightAddresses =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> refreshAfterInFlight =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Object refreshLock = new Object();
    private final java.util.Set<String> accountAvatarRequests =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
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
    private AlertDialog catalogDialog;
    private EditText catalogSearch;
    private LinearLayout catalogResults;
    private TextView catalogStatus;
    private Spinner catalogSort;
    private int catalogRequestId;
    private Runnable catalogSearchRunnable;
    private final LruCache<String, Bitmap> iconCache = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            refreshAll(false);
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
        subtitle.setText(R.string.main_subtitle);
        serverSummaryText = findViewById(R.id.serverSummaryText);
        accountButton = findViewById(R.id.accountButton);
        accountButton.setOnClickListener(v -> showAccountMenu());

        searchInput = findViewById(R.id.searchInput);
        headerActionsSpacer = findViewById(R.id.headerActionsSpacer);
        findViewById(R.id.searchToggle).setOnClickListener(this::toggleSearch);
        findViewById(R.id.settingsButton).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.topServersButton).setOnClickListener(v -> showCatalogDialog());
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
            refreshAll(true);
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
        updateAccountButton();
        ensurePriorityNotificationPermission();
        WidgetJobService.schedule(this);
        refreshAll(true);
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
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS) {
            return;
        }
        if (grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (arePriorityNotificationsEnabled()) {
                Toast.makeText(
                        this,
                        R.string.main_notifications_enabled,
                        Toast.LENGTH_SHORT).show();
                WidgetJobService.schedule(this);
                WidgetJobService.requestRefresh(this);
                return;
            }
        }
        showNotificationSettingsPrompt();
    }

    private void showNotificationSettingsPrompt() {
        getSharedPreferences(APP_SETTINGS, MODE_PRIVATE)
                .edit().putBoolean(NOTIFICATION_SETTINGS_PROMPTED, true).apply();
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.main_notifications_disabled)
                .setMessage(R.string.main_enable_notifications_message)
                .setNegativeButton(R.string.main_later, null)
                .setPositiveButton(R.string.main_open_settings, (dialog, which) -> {
                    Intent settings = new Intent(
                            android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(
                                    android.provider.Settings.EXTRA_APP_PACKAGE,
                                    getPackageName());
                    startActivity(settings);
                })
                .show();
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
        sortServers(entries);
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
        emptyTitle.setText(noMatches
                ? R.string.main_search_no_results : R.string.main_empty_title);
        emptyMessage.setText(noMatches
                ? R.string.main_search_hint_empty : R.string.main_empty_hint);
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

    private void sortServers(List<ServerEntry> entries) {
        String mode = getSharedPreferences(APP_SETTINGS, MODE_PRIVATE)
                .getString("server_sort_mode", "manual");
        Comparator<ServerEntry> comparator = (first, second) -> {
            int pinnedOrder = Boolean.compare(second.pinned, first.pinned);
            if (pinnedOrder != 0) {
                return pinnedOrder;
            }
            if ("name".equals(mode)) {
                return first.displayName().compareToIgnoreCase(second.displayName());
            }
            if ("date".equals(mode)) {
                return Long.compare(second.addedAt, first.addedAt);
            }
            if ("online".equals(mode)) {
                ServerStatus firstStatus = StatusCache.load(this, first.address);
                ServerStatus secondStatus = StatusCache.load(this, second.address);
                boolean firstOnline = firstStatus != null && firstStatus.hasData
                        && firstStatus.online;
                boolean secondOnline = secondStatus != null && secondStatus.hasData
                        && secondStatus.online;
                int onlineOrder = Boolean.compare(secondOnline, firstOnline);
                if (onlineOrder != 0) {
                    return onlineOrder;
                }
                int countOrder = Integer.compare(
                        secondOnline ? secondStatus.onlineCount : 0,
                        firstOnline ? firstStatus.onlineCount : 0);
                if (countOrder != 0) {
                    return countOrder;
                }
            }
            if ("ping".equals(mode)) {
                ServerStatus firstStatus = StatusCache.load(MainActivity.this, first.address);
                ServerStatus secondStatus = StatusCache.load(MainActivity.this, second.address);
                boolean firstOnline = firstStatus != null && firstStatus.hasData
                        && firstStatus.online;
                boolean secondOnline = secondStatus != null && secondStatus.hasData
                        && secondStatus.online;
                int onlineOrder = Boolean.compare(secondOnline, firstOnline);
                if (onlineOrder != 0) {
                    return onlineOrder;
                }
                long firstPing = firstOnline && firstStatus.pingMs >= 0L
                        ? firstStatus.pingMs : Long.MAX_VALUE;
                long secondPing = secondOnline && secondStatus.pingMs >= 0L
                        ? secondStatus.pingMs : Long.MAX_VALUE;
                int pingOrder = Long.compare(firstPing, secondPing);
                if (pingOrder != 0) {
                    return pingOrder;
                }
            }
            return 0;
        };
        entries.sort(comparator);
    }

    private void updateServerSummary(List<ServerEntry> entries) {
        int online = 0;
        for (ServerEntry entry : entries) {
            ServerStatus status = StatusCache.load(this, entry.address);
            if (status != null && status.hasData && status.online) {
                online++;
            }
        }
        String serverCount = getResources().getQuantityString(
                R.plurals.main_server_count, entries.size(), entries.size());
        serverSummaryText.setText(getString(
                R.string.main_server_summary, serverCount, online));
    }

    private void showCatalogDialog() {
        if (catalogDialog != null) {
            catalogDialog.show();
            catalogDialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.94),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.82));
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(8));

        catalogSearch = new EditText(this);
        catalogSearch.setSingleLine(true);
        catalogSearch.setHint(R.string.catalog_search_hint);
        catalogSearch.setTextColor(getColor(R.color.text));
        catalogSearch.setHintTextColor(getColor(R.color.dim));
        root.addView(catalogSearch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        catalogSort = new Spinner(this);
        catalogSort.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{
                        getString(R.string.catalog_sort_votes),
                        getString(R.string.catalog_sort_subscribers),
                        getString(R.string.catalog_sort_online),
                        getString(R.string.catalog_sort_newest)}));
        root.addView(catalogSort, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        catalogStatus = new TextView(this);
        catalogStatus.setText(R.string.catalog_loading);
        catalogStatus.setTextColor(getColor(R.color.dim));
        root.addView(catalogStatus);

        catalogResults = new LinearLayout(this);
        catalogResults.setOrientation(LinearLayout.VERTICAL);
        ScrollView resultScroll = new ScrollView(this);
        resultScroll.setFillViewport(true);
        resultScroll.addView(catalogResults);
        root.addView(resultScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

        catalogDialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.catalog_title)
                .setView(root)
                .setPositiveButton(R.string.catalog_add_server, null)
                .setNegativeButton(R.string.catalog_close, null)
                .create();
        catalogDialog.setOnShowListener(ignored -> {
            catalogDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
                    getColor(R.color.mint));
            catalogDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(
                    getColor(R.color.soft));
            catalogDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                    showAddCatalogServerDialog());
        });
        catalogDialog.setOnDismissListener(ignored -> {
            if (catalogSearchRunnable != null) {
                handler.removeCallbacks(catalogSearchRunnable);
                catalogSearchRunnable = null;
            }
            catalogRequestId++;
            catalogDialog = null;
            catalogSearch = null;
            catalogResults = null;
            catalogStatus = null;
            catalogSort = null;
        });
        catalogSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                scheduleCatalogSearch();
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        });
        catalogSort.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    android.widget.AdapterView<?> parent, View view, int position, long id) {
                scheduleCatalogSearch();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        catalogDialog.show();
        catalogDialog.getWindow().setLayout(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.94),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.82));
        scheduleCatalogSearch();
    }

    private void scheduleCatalogSearch() {
        if (catalogSearch == null || catalogDialog == null) {
            return;
        }
        if (catalogSearchRunnable != null) {
            handler.removeCallbacks(catalogSearchRunnable);
        }
        int requestId = ++catalogRequestId;
        String query = catalogSearch.getText().toString().trim();
        int selectedSort = catalogSort.getSelectedItemPosition();
        String sort = selectedSort == 1 ? "subscribers"
                : selectedSort == 2 ? "online"
                : selectedSort == 3 ? "newest" : "votes";
        catalogStatus.setText(R.string.catalog_loading);
        catalogSearchRunnable = () -> loadCatalog(query, sort, requestId);
        handler.postDelayed(catalogSearchRunnable, 250);
    }

    private void loadCatalog(String query, String sort, int requestId) {
        AccountSession session = AccountSessionStore.load(this);
        executor.execute(() -> {
            try {
                List<CloudSyncClient.CatalogServer> servers =
                        CloudSyncClient.listServerCatalog(query, sort, 50, session);
                handler.post(() -> {
                    if (catalogDialog == null || requestId != catalogRequestId) {
                        return;
                    }
                    renderCatalogServers(servers, session);
                });
            } catch (Exception exception) {
                handler.post(() -> {
                    if (catalogDialog != null && requestId == catalogRequestId) {
                        catalogStatus.setText(catalogErrorMessage(exception));
                    }
                });
            }
        });
    }

    private void renderCatalogServers(
            List<CloudSyncClient.CatalogServer> servers, AccountSession session) {
        if (catalogResults == null) {
            return;
        }
        catalogResults.removeAllViews();
        catalogStatus.setText(servers.isEmpty()
                ? getString(R.string.catalog_empty)
                : getString(R.string.catalog_count, servers.size()));
        for (CloudSyncClient.CatalogServer server : servers) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable background = new GradientDrawable();
            background.setColor(getColor(R.color.bg_bottom));
            background.setCornerRadius(dp(14));
            int accent = getSharedPreferences(APP_SETTINGS, MODE_PRIVATE)
                    .getInt("accent_color", getColor(R.color.mint));
            background.setStroke(dp(1), accent);
            row.setBackground(background);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(8);
            catalogResults.addView(row, rowParams);

            LinearLayout header = new LinearLayout(this);
            header.setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon = new ImageView(this);
            Bitmap favicon = decodeIcon(server.favicon);
            icon.setImageBitmap(favicon);
            if (favicon == null) {
                icon.setImageResource(R.drawable.ic_server_fallback);
            }
            header.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
            TextView title = new TextView(this);
            title.setText(server.name.isEmpty() ? server.address : server.name);
            title.setTextColor(getColor(R.color.text));
            title.setTextSize(15);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            titleParams.leftMargin = dp(8);
            header.addView(title, titleParams);
            row.addView(header);

            TextView detail = new TextView(this);
            detail.setText(getString(
                    R.string.catalog_server_detail,
                    server.address,
                    server.protocol.toUpperCase(Locale.ROOT),
                    server.onlineCount < 0 ? "—" : String.valueOf(server.onlineCount),
                    server.votes,
                    server.subscribers));
            detail.setTextColor(getColor(R.color.soft));
            detail.setTextSize(12);
            row.addView(detail);
            if (!server.version.isEmpty()) {
                TextView version = new TextView(this);
                version.setText(getString(R.string.catalog_server_version, server.version));
                version.setTextColor(getColor(R.color.dim));
                version.setTextSize(11);
                row.addView(version);
            }
            if (!server.motd.isEmpty()) {
                TextView motd = new TextView(this);
                motd.setText(server.motd);
                motd.setTextColor(getColor(R.color.soft));
                motd.setTextSize(12);
                motd.setMaxLines(2);
                row.addView(motd);
            }

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            Button addToMine = new Button(this);
            addToMine.setText(R.string.catalog_add_to_mine);
            addToMine.setOnClickListener(v -> addCatalogServerToMine(server));
            addToMine.setTextSize(11);
            addToMine.setMinWidth(0);
            actions.addView(addToMine, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button details = new Button(this);
            details.setText(R.string.catalog_server_about);
            details.setOnClickListener(v -> showCatalogServerDetails(server));
            details.setTextSize(11);
            details.setMinWidth(0);
            actions.addView(details, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button vote = new Button(this);
            vote.setText(server.hasVotedToday
                    ? getString(R.string.catalog_vote_counted)
                    : getString(R.string.catalog_vote));
            vote.setEnabled(!server.hasVotedToday);
            vote.setOnClickListener(v -> voteForCatalogServer(server, session));
            vote.setTextSize(11);
            vote.setMinWidth(0);
            actions.addView(vote, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(actions);
        }
    }

    private void showCatalogServerDetails(CloudSyncClient.CatalogServer server) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(12), dp(18), dp(8));

        TextView summary = new TextView(this);
        summary.setText(getString(
                R.string.catalog_history_summary,
                server.address,
                server.protocol.toUpperCase(Locale.ROOT),
                server.votes,
                server.subscribers));
        summary.setTextColor(getColor(R.color.soft));
        content.addView(summary);
        if (!server.version.isEmpty()) {
            TextView version = new TextView(this);
            version.setText(getString(R.string.catalog_server_version, server.version));
            version.setTextColor(getColor(R.color.dim));
            content.addView(version);
        }
        if (!server.motd.isEmpty()) {
            TextView motd = new TextView(this);
            motd.setText(server.motd);
            motd.setTextColor(getColor(R.color.soft));
            motd.setMaxLines(3);
            content.addView(motd);
        }

        TextView status = new TextView(this);
        status.setText(R.string.catalog_history_loading);
        status.setTextColor(getColor(R.color.dim));
        content.addView(status);

        int accent = getSharedPreferences(APP_SETTINGS, MODE_PRIVATE)
                .getInt("accent_color", getColor(R.color.mint));
        CatalogHistoryChartView chart = new CatalogHistoryChartView(this, accent);
        content.addView(chart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));

        TextView selectedSample = new TextView(this);
        selectedSample.setTextColor(getColor(R.color.text));
        selectedSample.setTextSize(13);
        selectedSample.setPadding(0, dp(8), 0, dp(4));
        content.addView(selectedSample);
        chart.setOnSampleSelectedListener(sample -> {
            String players = sample.players.isEmpty()
                    ? getString(R.string.catalog_history_no_players)
                    : android.text.TextUtils.join(", ", sample.players);
            selectedSample.setText(getString(
                    R.string.catalog_history_sample,
                    sample.sampledAt,
                    sample.onlineCount,
                    sample.maxCount,
                    players));
        });

        AlertDialog detailDialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(server.name.isEmpty() ? server.address : server.name)
                .setView(content)
                .setPositiveButton(R.string.catalog_close, null)
                .create();
        detailDialog.show();
        detailDialog.getWindow().setLayout(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.94),
                ViewGroup.LayoutParams.WRAP_CONTENT);

        AccountSession session = AccountSessionStore.load(this);
        executor.execute(() -> {
            try {
                List<CloudSyncClient.CatalogHistorySample> samples =
                        CloudSyncClient.listServerCatalogHistory(server.id, 300, session);
                handler.post(() -> {
                    if (!detailDialog.isShowing()) {
                        return;
                    }
                    status.setText(samples.isEmpty()
                            ? R.string.catalog_history_empty
                            : R.string.catalog_history_range);
                    chart.setSamples(samples);
                });
            } catch (Exception exception) {
                handler.post(() -> {
                    if (detailDialog.isShowing()) {
                        status.setText(catalogErrorMessage(exception));
                    }
                });
            }
        });
    }

    private void addCatalogServerToMine(CloudSyncClient.CatalogServer server) {
        List<ServerEntry> entries = ServerStore.load(this);
        for (ServerEntry entry : entries) {
            if (ServerEntry.normalize(entry.address)
                    .equalsIgnoreCase(ServerEntry.normalize(server.address))) {
                Toast.makeText(this, R.string.catalog_server_exists, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        ServerEntry entry = new ServerEntry(server.address, server.name);
        entry.protocol = ServerEntry.normalizeProtocol(server.protocol);
        entries.add(entry);
        ServerStore.save(this, entries);
        buildCards();
        requestCloudSync(true);
        publishServerToCatalog(entry);
        Toast.makeText(this, R.string.catalog_server_added, Toast.LENGTH_SHORT).show();
    }

    private void publishServerToCatalog(ServerEntry entry) {
        executor.execute(() -> {
            try {
                CloudSyncClient.addServerToCatalog(
                        entry.address, entry.displayName(), entry.protocol);
            } catch (Exception exception) {
                Log.w(TAG, "Не удалось добавить сервер в общий каталог", exception);
                handler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this,
                                getString(R.string.catalog_publish_failed,
                                        catalogErrorMessage(exception)),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void voteForCatalogServer(
            CloudSyncClient.CatalogServer server, AccountSession session) {
        if (session == null) {
            Toast.makeText(this, R.string.catalog_need_login, Toast.LENGTH_LONG).show();
            showAccountMenu();
            return;
        }
        if (catalogStatus != null) {
            catalogStatus.setText(R.string.catalog_vote_sent);
        }
        executor.execute(() -> {
            try {
                boolean inserted = CloudSyncClient.voteForCatalogServer(session, server.id);
                handler.post(() -> {
                    if (catalogDialog == null) {
                        return;
                    }
                    Toast.makeText(this, inserted
                                    ? R.string.catalog_vote_success
                                    : R.string.catalog_vote_duplicate,
                            Toast.LENGTH_SHORT).show();
                    scheduleCatalogSearch();
                });
            } catch (Exception exception) {
                handler.post(() -> {
                    if (catalogStatus != null) {
                        catalogStatus.setText(catalogErrorMessage(exception));
                    }
                });
            }
        });
    }

    private void showAddCatalogServerDialog() {
        if (catalogDialog == null) {
            return;
        }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(R.string.catalog_name_optional);
        form.addView(name);
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setHint(R.string.catalog_address_hint);
        address.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(address);
        Spinner protocol = new Spinner(this);
        protocol.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{
                        getString(R.string.catalog_java),
                        getString(R.string.catalog_bedrock)}));
        form.addView(protocol);
        AlertDialog formDialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.catalog_add_title)
                .setView(form)
                .setPositiveButton(R.string.catalog_add, null)
                .setNegativeButton(R.string.catalog_cancel, null)
                .create();
        formDialog.setOnShowListener(ignored -> formDialog
                .getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String serverAddress = address.getText().toString().trim();
                    if (serverAddress.isEmpty()) {
                        address.setError(getString(R.string.catalog_address_required));
                        return;
                    }
                    formDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    executor.execute(() -> {
                        try {
                            CloudSyncClient.addServerToCatalog(
                                    serverAddress,
                                    name.getText().toString(),
                                    protocol.getSelectedItemPosition() == 1 ? "bedrock" : "java");
                            handler.post(() -> {
                                formDialog.dismiss();
                                Toast.makeText(this, R.string.catalog_server_added_public,
                                        Toast.LENGTH_SHORT).show();
                                scheduleCatalogSearch();
                            });
                        } catch (Exception exception) {
                            handler.post(() -> {
                                formDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                        .setEnabled(true);
                                address.setError(catalogErrorMessage(exception));
                            });
                        }
                    });
                }));
        formDialog.show();
    }

    private void bindCard(View card, ServerEntry entry) {
        TextView name = card.findViewById(R.id.serverName);
        TextView address = card.findViewById(R.id.serverAddress);
        name.setText((entry.pinned ? "📌 " : "") + entry.displayName());
        address.setText(entry.address);
        name.setOnLongClickListener(v -> {
            ClipData dragData = ClipData.newPlainText("server-id", entry.id);
            return v.startDragAndDrop(
                    dragData, new View.DragShadowBuilder(v), entry.id, 0);
        });
        card.setOnDragListener((view, event) -> {
            if (event.getAction() == android.view.DragEvent.ACTION_DRAG_STARTED) {
                return event.getClipDescription() != null
                        && event.getClipDescription().hasMimeType(
                                ClipDescription.MIMETYPE_TEXT_PLAIN);
            }
            if (event.getAction() == android.view.DragEvent.ACTION_DRAG_ENTERED) {
                view.setAlpha(0.72f);
                return true;
            }
            if (event.getAction() == android.view.DragEvent.ACTION_DRAG_EXITED
                    || event.getAction() == android.view.DragEvent.ACTION_DRAG_ENDED) {
                view.setAlpha(1f);
                return true;
            }
            if (event.getAction() == android.view.DragEvent.ACTION_DROP) {
                CharSequence draggedId = event.getClipData().getItemAt(0).getText();
                if (draggedId != null) {
                    reorderServers(draggedId.toString(), entry.id);
                }
                view.setAlpha(1f);
                return true;
            }
            return true;
        });

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
            name.setText((entry.pinned ? "📌 " : "") + entry.displayName());
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
            statusLabel.setText(loading
                    ? R.string.main_checking : R.string.main_no_connection);
        } else {
            statusLabel.setText(online ? R.string.main_online : R.string.main_offline);
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
            hint.setText(status.onlineCount > 0
                    ? R.string.main_hidden_player_list : R.string.main_empty_players);
        } else {
            hint.setText(getString(
                    R.string.main_players_count, status.players.size(), status.onlineCount));
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
                addMoreChip(flow, getString(
                        R.string.main_more_players, status.players.size() - shown));
            }
        }

        TextView empty = card.findViewById(R.id.playersEmpty);
        if (!known) {
            empty.setText(loading
                    ? R.string.main_loading_players : R.string.main_server_unreachable);
            empty.setVisibility(View.VISIBLE);
        } else if (!online) {
            empty.setText(R.string.main_server_offline);
            empty.setVisibility(View.VISIBLE);
        } else if (status.players.isEmpty()) {
            empty.setText(status.onlineCount > 0
                    ? R.string.main_server_hides_players : R.string.main_no_players_online);
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
        }
    }

    private String versionLine(ServerStatus status, ServerEntry entry, boolean loading) {
        if (status == null) {
            return getString(loading
                    ? R.string.main_loading_status : R.string.main_no_data);
        }
        if (!status.hasData) {
            return status.error == null || status.error.isEmpty()
                    ? getString(R.string.main_no_connection) : status.error;
        }
        if (!status.online) {
            return getString(R.string.main_server_not_responding);
        }

        String version = status.version == null ? "" : status.version.trim();
        String edition = entry.isBedrock() ? getString(R.string.main_bedrock_prefix) : "";
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
            toggle.setContentDescription(getString(R.string.main_close_search));
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
        toggle.setContentDescription(getString(R.string.main_search_servers));
        buildCards(false);
    }

    private void openCommunitySearch() {
        String query = searchInput.getText().toString().trim();
        if (query.length() < 2) {
            Toast.makeText(this, R.string.main_community_search_min,
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
            Toast.makeText(this, R.string.main_community_search_signin,
                    Toast.LENGTH_LONG).show();
            showAccountMenu();
            return;
        }
        showCommunityDialog(session, false, query, 1);
    }

    private void updateAccountButton() {
        AccountSession session = AccountSessionStore.load(this);
        ImageButton button = (ImageButton) accountButton;
        button.setClipToOutline(true);
        accountButton.setContentDescription(session == null
                ? getString(R.string.main_profile_signin_description)
                : getString(R.string.main_profile_description, session.username));
        accountButton.setSelected(session != null);
        if (session == null) {
            button.setImageResource(R.drawable.ic_profile);
            button.setColorFilter(getColor(R.color.soft));
            button.setPadding(dp(10), dp(10), dp(10), dp(10));
            return;
        }

        File cachedAvatar = accountAvatarFile(session.userId);
        Bitmap cached = BitmapFactory.decodeFile(cachedAvatar.getAbsolutePath());
        if (cached != null) {
            button.setImageBitmap(cached);
            button.clearColorFilter();
            button.setPadding(0, 0, 0, 0);
        } else {
            button.setImageResource(R.drawable.ic_profile);
            button.setColorFilter(getColor(R.color.soft));
            button.setPadding(dp(10), dp(10), dp(10), dp(10));
        }
        loadAccountAvatar(session);
    }

    private File accountAvatarFile(String userId) {
        return new File(new File(getCacheDir(), "avatars"), userId + ".jpg");
    }

    private void loadAccountAvatar(AccountSession session) {
        String userId = session.userId;
        if (!accountAvatarRequests.add(userId)) {
            return;
        }
        executor.execute(() -> {
            Bitmap avatar = null;
            try {
                CloudSyncClient.PublicProfile profile = CloudSyncClient.fetchProfile(session);
                byte[] data = CloudSyncClient.downloadAvatar(profile);
                File file = accountAvatarFile(userId);
                if (data == null) {
                    if (file.exists() && !file.delete()) {
                        Log.w(TAG, "Не удалось удалить устаревший кэш аватара");
                    }
                } else {
                    File parent = file.getParentFile();
                    if (parent == null) {
                        throw new IOException("Не удалось определить папку кэша аватара.");
                    }
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Не удалось создать кэш аватара.");
                    }
                    try (FileOutputStream output = new FileOutputStream(file)) {
                        output.write(data);
                    }
                    avatar = BitmapFactory.decodeByteArray(data, 0, data.length);
                }
            } catch (Exception exception) {
                Log.w(TAG, "Не удалось обновить аватар в шапке", exception);
            } finally {
                accountAvatarRequests.remove(userId);
            }

            Bitmap result = avatar;
            handler.post(() -> {
                AccountSession current = AccountSessionStore.load(this);
                if (isFinishing() || isDestroyed() || current == null
                        || !userId.equals(current.userId)) {
                    return;
                }
                ImageButton button = (ImageButton) accountButton;
                if (result != null) {
                    button.setImageBitmap(result);
                    button.clearColorFilter();
                    button.setPadding(0, 0, 0, 0);
                } else if (!accountAvatarFile(userId).isFile()) {
                    button.setImageResource(R.drawable.ic_profile);
                    button.setColorFilter(getColor(R.color.soft));
                    button.setPadding(dp(10), dp(10), dp(10), dp(10));
                }
            });
        });
    }

    private void showAccountMenu() {
        AccountSession session = AccountSessionStore.load(this);
        if (session == null) {
            String[] options = {
                    getString(R.string.main_login),
                    getString(R.string.main_create_account)
            };
            new AlertDialog.Builder(this, R.style.DialogTheme)
                    .setTitle(R.string.main_account)
                    .setItems(options, (dialog, which) -> showAccountForm(which == 1))
                    .setNegativeButton(R.string.main_close, null)
                    .show();
            return;
        }

        String[] options = getResources().getStringArray(R.array.main_account_actions);
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(session.username)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        startActivity(new Intent(this, ProfileActivity.class));
                    } else if (which == 1) {
                        showCommunityDialog(session, false);
                    } else if (which == 2) {
                        showCommunityDialog(session, true);
                    } else if (which == 3) {
                        requestCloudSync(false);
                    } else {
                        new AlertDialog.Builder(this, R.style.DialogTheme)
                                .setTitle(R.string.main_logout_title)
                                .setMessage(R.string.main_logout_message)
                                .setPositiveButton(R.string.main_logout_button, (confirm, action) -> {
                                    AccountSessionStore.clearSession(this);
                                    WidgetJobService.schedule(this);
                                    WidgetJobService.requestRefresh(this);
                                    updateAccountButton();
                                    Toast.makeText(this, R.string.main_logged_out, Toast.LENGTH_SHORT)
                                            .show();
                                })
                                .setNegativeButton(R.string.account_cancel, null)
                                .show();
                    }
                })
                .setNegativeButton(R.string.main_close, null)
                .show();
    }

    private void showProfileDialog(AccountSession session) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        form.setPadding(padding, dp(8), padding, 0);

        TextView username = new TextView(this);
        int serverCount = ServerStore.load(this).size();
        username.setText("@" + session.username + "  ·  "
                + getResources().getQuantityString(
                        R.plurals.main_profile_server_count, serverCount, serverCount));
        username.setTextColor(getColor(R.color.dim));
        form.addView(username);

        profileAvatarView = new ImageView(this);
        profileAvatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        profileAvatarView.setBackground(avatarBackground());
        profileAvatarView.setClipToOutline(true);
        form.addView(profileAvatarView, new LinearLayout.LayoutParams(dp(72), dp(72)));

        profileDisplayName = new EditText(this);
        profileDisplayName.setSingleLine(true);
        profileDisplayName.setHint(R.string.main_display_name_hint);
        profileDisplayName.setMaxLines(1);
        form.addView(profileDisplayName);

        profileBio = new EditText(this);
        profileBio.setHint(R.string.main_bio_hint);
        profileBio.setMinLines(2);
        profileBio.setMaxLines(4);
        form.addView(profileBio);

        profileShareServers = new CheckBox(this);
        profileShareServers.setText(R.string.main_share_server_addresses);
        profileShareServers.setTextColor(getColor(R.color.soft));
        form.addView(profileShareServers);
        TextView privacyNote = new TextView(this);
        privacyNote.setText(R.string.main_private_by_default);
        privacyNote.setTextColor(getColor(R.color.dim));
        privacyNote.setTextSize(12f);
        form.addView(privacyNote);

        Button avatarButton = new Button(this);
        avatarButton.setText(R.string.main_choose_avatar);
        avatarButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_AVATAR);
        });
        form.addView(avatarButton);

        profileStatus = new TextView(this);
        profileStatus.setText(R.string.main_loading_profile);
        profileStatus.setTextColor(getColor(R.color.dim));
        form.addView(profileStatus);
        pendingAvatarJpeg = null;

        profileDialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.main_my_profile)
                .setView(form)
                .setNegativeButton(R.string.main_close, null)
                .setPositiveButton(R.string.main_save, null)
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
                    avatarWarning = getString(
                            R.string.main_avatar_load_failed,
                            CloudSyncClient.safeUserErrorMessage(this, exception));
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
                            ? getString(R.string.main_avatar_not_set)
                            : getString(R.string.main_avatar_set));
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
            profileStatus.setText(R.string.main_saving_profile);
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
                    accountAvatarRequests.remove(session.userId);
                    updateAccountButton();
                    Toast.makeText(this, R.string.main_profile_saved, Toast.LENGTH_SHORT).show();
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
                throw new IOException(getString(R.string.main_avatar_too_large));
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
                profileStatus.setText(R.string.main_avatar_ready);
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
                profileStatus.setText(getString(
                        R.string.main_image_open_failed,
                        getString(R.string.main_image_read_failed)));
            }
        }
    }

    private Bitmap decodeAvatar(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException(getString(R.string.profile_image_open_failed));
            }
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException(getString(R.string.main_selected_file_not_image));
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
                throw new IOException(getString(R.string.main_image_read_failed));
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
        String[] tabNames = getResources().getStringArray(R.array.main_community_tabs);
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
        communitySearch.setHint(R.string.main_community_search_hint);
        root.addView(communitySearch);

        ScrollView resultsScroll = new ScrollView(this);
        communityResults = new LinearLayout(this);
        communityResults.setOrientation(LinearLayout.VERTICAL);
        communityResults.setPadding(0, dp(8), 0, dp(8));
        resultsScroll.addView(communityResults);
        root.addView(resultsScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360)));

        communityDialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.main_community_title)
                .setView(root)
                .setNegativeButton(R.string.main_close, null)
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
            addCommunityMessage(getString(R.string.main_enter_two_characters));
            return;
        }
        addCommunityMessage(getString(R.string.main_searching));
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
            addCommunityMessage(followed
                    ? getString(R.string.main_not_following_anyone)
                    : getString(R.string.main_nothing_found));
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
            avatar.setImageResource(R.drawable.ic_profile);
            avatar.setTag(profile.userId);
            identity.addView(avatar, new LinearLayout.LayoutParams(dp(40), dp(40)));
            TextView title = new TextView(this);
            title.setText(profile.displayName.isEmpty()
                    ? profile.username
                    : getString(R.string.profile_identity,
                            profile.displayName, profile.username));
            title.setTextColor(getColor(R.color.soft));
            title.setTextSize(15);
            title.setOnClickListener(v -> {
                Intent intent = new Intent(this, ProfileActivity.class);
                intent.putExtra(ProfileActivity.EXTRA_PROFILE_ID, profile.userId);
                startActivity(intent);
            });
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
            follow.setText(profile.isFollowing
                    ? R.string.profile_unfollow : R.string.profile_follow);
            follow.setOnClickListener(v -> changeFollow(session, profile));
            actions.addView(follow);
            Button servers = new Button(this);
            servers.setText(R.string.main_community_servers);
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
                    throw new IOException(getString(R.string.profile_broken_avatar));
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
            addCommunityMessage(getString(R.string.main_public_servers_not_found));
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
            add.setText(R.string.main_add);
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
                        CloudSyncClient.listPublicServers(session, profile);
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
                Toast.makeText(this, R.string.main_server_already_added, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        ServerEntry entry = new ServerEntry(server.address, server.name);
        entry.protocol = ServerEntry.normalizeProtocol(server.protocol);
        servers.add(entry);
        ServerStore.save(this, servers);
        buildCards();
        requestCloudSync(true);
        publishServerToCatalog(entry);
        Toast.makeText(this, R.string.main_server_added, Toast.LENGTH_SHORT).show();
    }

    private void applySavedAccent() {
        int color = getSharedPreferences(APP_SETTINGS, MODE_PRIVATE)
                .getInt("accent_color", Color.rgb(126, 224, 163));
        applyAccent(color);
    }

    private void applyAccent(int color) {
        getWindow().setStatusBarColor(
                Build.VERSION.SDK_INT >= 35
                        ? Color.TRANSPARENT : BackdropView.topBarColor(color));
        styleButtons(findViewById(android.R.id.content), color);
        ((ImageView) findViewById(R.id.searchToggle)).setColorFilter(color);
        ((ImageView) findViewById(R.id.settingsButton)).setColorFilter(color);
        ((ImageView) findViewById(R.id.refresh)).setColorFilter(color);
        BackdropView backdrop = findViewById(R.id.backdrop);
        backdrop.setAccentColor(color);
        android.content.SharedPreferences appearance =
                getSharedPreferences(APP_SETTINGS, MODE_PRIVATE);
        backdrop.setSceneStyle(appearance.getInt("background_style", 0));
        backdrop.setPattern(appearance.getInt("background_pattern", 0));
    }

    private void styleButtons(View view, int accent) {
        if (view instanceof Button) {
            Button button = (Button) view;
            int lighter = Color.rgb(
                    Color.red(accent) + (255 - Color.red(accent)) / 5,
                    Color.green(accent) + (255 - Color.green(accent)) / 5,
                    Color.blue(accent) + (255 - Color.blue(accent)) / 5);
            GradientDrawable gradient = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{lighter, accent});
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

        private void showAccountForm(boolean register) {
            if (register) {
                showEmailSignupEmailForm();
                return;
            }
            LinearLayout form = new LinearLayout(this);
            form.setOrientation(LinearLayout.VERTICAL);
            int padding = dp(20);
            form.setPadding(padding, dp(8), padding, 0);

            TextView recoveryNote = new TextView(this);
            recoveryNote.setText(R.string.account_login_note);
            recoveryNote.setTextColor(getColor(R.color.dim));
            recoveryNote.setTextSize(12f);
            form.addView(recoveryNote, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            EditText username = new EditText(this);
            username.setSingleLine(true);
            username.setHint(R.string.account_identifier_hint);
            username.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
            form.addView(username, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            EditText password = new EditText(this);
            password.setSingleLine(true);
            password.setHint(R.string.account_password_hint);
            password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            form.addView(password, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            AlertDialog dialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                    .setTitle(R.string.account_login_title)
                    .setView(form)
                    .setNegativeButton(R.string.account_cancel, null)
                    .setPositiveButton(R.string.account_sign_in, null)
                    .create();
            dialog.setOnShowListener(ignored -> {
                Button submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                submit.setTextColor(getColor(R.color.mint));
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.soft));
                submit.setOnClickListener(v -> {
                    String nick = username.getText().toString().trim();
                    String pass = password.getText().toString();
                    try {
                        CloudSyncClient.validateLoginIdentifier(nick, pass);
                    } catch (IllegalArgumentException exception) {
                        Toast.makeText(this, exception.getMessage(), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dialog.dismiss();
                    authenticate(false, nick, pass);
                });
            });
            dialog.show();
        }

        private void showEmailSignupEmailForm() {
            EditText email = new EditText(this);
            email.setSingleLine(true);
            email.setHint(R.string.account_email_hint);
            email.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            LinearLayout content = new LinearLayout(this);
            content.setPadding(dp(20), dp(8), dp(20), 0);
            content.addView(email, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            AlertDialog dialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                    .setTitle(R.string.account_signup_title)
                    .setMessage(R.string.account_signup_email_step)
                    .setView(content)
                    .setNegativeButton(R.string.account_cancel, null)
                    .setPositiveButton(R.string.account_send_code, null)
                    .create();
            dialog.setOnShowListener(ignored -> {
                Button submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                submit.setTextColor(getColor(R.color.mint));
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.soft));
                submit.setOnClickListener(v -> {
                    final String cleanEmail;
                    try {
                        cleanEmail = CloudSyncClient.validateEmail(
                                email.getText().toString());
                    } catch (IllegalArgumentException exception) {
                        Toast.makeText(this, exception.getMessage(), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dialog.dismiss();
                    requestEmailSignupCode(cleanEmail);
                });
            });
            dialog.show();
        }

        private void requestEmailSignupCode(String email) {
            accountButton.setEnabled(false);
            syncExecutor.execute(() -> {
                try {
                    CloudSyncClient.requestEmailSignupCode(email);
                    handler.post(() -> {
                        accountButton.setEnabled(true);
                        showEmailSignupCompletionForm(email);
                    });
                } catch (Exception exception) {
                    Log.w(TAG, "Не удалось отправить код регистрации", exception);
                    handler.post(() -> {
                        accountButton.setEnabled(true);
                        Toast.makeText(this, cloudErrorMessage(exception), Toast.LENGTH_LONG).show();
                    });
                }
            });
        }

        private void showEmailSignupCompletionForm(String email) {
            LinearLayout form = new LinearLayout(this);
            form.setOrientation(LinearLayout.VERTICAL);
            form.setPadding(dp(20), dp(8), dp(20), 0);
            EditText code = new EditText(this);
            code.setSingleLine(true);
            code.setHint(R.string.account_code_hint);
            code.setInputType(InputType.TYPE_CLASS_NUMBER);
            form.addView(code);
            EditText username = new EditText(this);
            username.setSingleLine(true);
            username.setHint(R.string.account_nickname_hint);
            username.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
            form.addView(username);
            EditText password = new EditText(this);
            password.setSingleLine(true);
            password.setHint(R.string.account_password_hint);
            password.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            form.addView(password);
            EditText confirmPassword = new EditText(this);
            confirmPassword.setSingleLine(true);
            confirmPassword.setHint(R.string.account_confirm_password_hint);
            confirmPassword.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            form.addView(confirmPassword);
            AlertDialog dialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                    .setTitle(R.string.account_signup_title)
                    .setMessage(getString(R.string.account_code_sent, email))
                    .setView(form)
                    .setNegativeButton(R.string.account_cancel, null)
                    .setPositiveButton(R.string.account_create, null)
                    .create();
            dialog.setOnShowListener(ignored -> {
                Button submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                submit.setTextColor(getColor(R.color.mint));
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.soft));
                submit.setOnClickListener(v -> {
                    String nick = username.getText().toString().trim();
                    String pass = password.getText().toString();
                    if (!pass.equals(confirmPassword.getText().toString())) {
                        Toast.makeText(this, R.string.account_password_mismatch,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        CloudSyncClient.validateCredentials(nick, pass);
                    } catch (IllegalArgumentException exception) {
                        Toast.makeText(this, exception.getMessage(), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dialog.dismiss();
                    completeEmailSignup(
                            email, code.getText().toString().trim(), nick, pass);
                });
            });
            dialog.show();
        }

        private void completeEmailSignup(
                String email, String code, String username, String password) {
            accountButton.setEnabled(false);
            syncExecutor.execute(() -> {
                try {
                    AccountSession session = CloudSyncClient.completeEmailSignup(
                            email, code, username, password);
                    AccountSessionStore.save(getApplicationContext(), session);
                    int serverCount = synchronizeWithCloud(session);
                    handler.post(() -> {
                        updateAccountButton();
                        accountButton.setEnabled(true);
                        buildCards();
                        ensurePriorityNotificationPermission();
                        Toast.makeText(this,
                                getString(R.string.account_sync_result, serverCount),
                                Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception exception) {
                    Log.e(TAG, "Ошибка регистрации аккаунта", exception);
                    handler.post(() -> {
                        updateAccountButton();
                        accountButton.setEnabled(true);
                        Toast.makeText(this, cloudErrorMessage(exception), Toast.LENGTH_LONG)
                                .show();
                    });
                }
            });
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
                        ensurePriorityNotificationPermission();
                        Toast.makeText(
                                this,
                                getString(R.string.account_sync_result, serverCount),
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
                            ensurePriorityNotificationPermission();
                            if (showMessage) {
                                Toast.makeText(
                                        this,
                                        getString(R.string.account_sync_result, serverCount),
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
            int serverCount = CloudSyncClient.synchronizeServers(context, session);
            WidgetJobService.schedule(context);
            WidgetJobService.requestRefresh(context);
            return serverCount;
        }

        private String cloudErrorMessage(Exception exception) {
            return CloudSyncClient.safeUserErrorMessage(this, exception);
        }

        private String catalogErrorMessage(Exception exception) {
            String message = cloudErrorMessage(exception);
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("could not find")
                    && normalized.contains("function")
                    && normalized.contains("server_catalog")) {
                return getString(R.string.catalog_server_not_configured);
            }
            return message;
        }
    private void refreshAll(boolean force) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        List<ServerEntry> entries = ServerStore.load(this);
        for (ServerEntry entry : entries) {
            fetch(entry, force);
        }
    }

    private void fetch(ServerEntry entry, boolean force) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        final String entryId = entry.id;
        final String address = entry.address;
        final String protocol = ServerEntry.normalizeProtocol(entry.protocol);
        synchronized (refreshLock) {
            if (!inFlightAddresses.add(address)) {
                if (force) {
                    refreshAfterInFlight.add(address);
                }
                return;
            }
        }

        View card = cardViews.get(entryId);
        if (card != null) {
            ServerStatus cached = StatusCache.load(this, address);
            renderCard(card, entry, cached, cached == null);
        }

        executor.execute(() -> {
            try {
                ServerStatus previous = StatusCache.load(getApplicationContext(), address);
                final ServerStatus status = MinecraftApi.fetch(address, protocol);
                PriorityPlayerNotifications.notifyChanges(
                        getApplicationContext(),
                        ServerStore.findByAddress(getApplicationContext(), address),
                        previous,
                        status);
                StatusCache.save(getApplicationContext(), status);
                handler.post(() -> applyStatus(entryId, address, status));
                queueCatalogStatus(address, status);
            } finally {
                boolean shouldRefresh;
                synchronized (refreshLock) {
                    inFlightAddresses.remove(address);
                    shouldRefresh = refreshAfterInFlight.remove(address);
                }
                if (shouldRefresh) {
                    handler.post(() -> {
                        ServerEntry current = ServerStore.findByAddress(
                                getApplicationContext(), address);
                        if (current != null) {
                            fetch(current, false);
                        }
                    });
                }
            }
        });
    }

    private void queueCatalogStatus(String address, ServerStatus status) {
        if (status == null || !status.hasData) {
            return;
        }
        catalogExecutor.execute(() -> {
            long now = System.currentTimeMillis();
            Long previous = catalogStatusReportAt.get(address);
            if (previous != null && now - previous < 60_000L) {
                return;
            }
            catalogStatusReportAt.put(address, now);
            try {
                AccountSession session = AccountSessionStore.load(getApplicationContext());
                if (session == null) {
                    return;
                }
                CloudSyncClient.reportServerCatalogStatus(session, address, status);
                AccountSessionStore.save(getApplicationContext(), session);
            } catch (Exception exception) {
                Log.w(TAG, "Не удалось отправить статус сервера в общий каталог", exception);
            }
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
        menu.getMenu().add(0, 1, 0, R.string.server_action_edit);
        menu.getMenu().add(0, 2, 1, R.string.server_action_add_widget);
        menu.getMenu().add(0, 4, 2, R.string.server_action_priority_players);
        menu.getMenu().add(0, 5, 3,
                entry.groupId == null || entry.groupId.isEmpty()
                        ? getString(R.string.server_action_add_group)
                        : getString(R.string.server_action_group, entry.groupId));
        menu.getMenu().add(0, 6, 4,
                entry.pinned ? R.string.server_action_unpin : R.string.server_action_pin);
        boolean groupPinned = false;
        if (entry.groupId != null && !entry.groupId.isEmpty()) {
            List<ServerEntry> entries = ServerStore.load(this);
            groupPinned = true;
            boolean found = false;
            for (ServerEntry candidate : entries) {
                if (entry.groupId.equals(candidate.groupId)) {
                    found = true;
                    groupPinned &= candidate.pinned;
                }
            }
            groupPinned &= found;
            menu.getMenu().add(0, 7, 5, groupPinned
                    ? R.string.server_action_unpin_group : R.string.server_action_pin_group);
        }
        final boolean groupPinState = groupPinned;
        menu.getMenu().add(0, 3, 6, R.string.server_action_delete);
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
            if (item.getItemId() == 6) {
                setPinned(entry, !entry.pinned, false);
                return true;
            }
            if (item.getItemId() == 7) {
                setPinned(entry, !groupPinState, true);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void setPinned(ServerEntry selected, boolean pinned, boolean group) {
        List<ServerEntry> entries = ServerStore.load(this);
        for (ServerEntry entry : entries) {
            boolean matches = entry.id.equals(selected.id);
            if (group && selected.groupId != null && !selected.groupId.isEmpty()) {
                matches = selected.groupId.equals(entry.groupId);
            }
            if (matches) {
                entry.pinned = pinned;
            }
        }
        ServerStore.save(this, entries);
        buildCards(false);
        requestCloudSync(true);
    }

    private void reorderServers(String draggedId, String targetId) {
        List<ServerEntry> entries = ServerStore.load(this);
        int sourceIndex = -1;
        int targetIndex = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id.equals(draggedId)) {
                sourceIndex = i;
            }
            if (entries.get(i).id.equals(targetId)) {
                targetIndex = i;
            }
        }
        if (sourceIndex < 0 || targetIndex < 0 || sourceIndex == targetIndex) {
            return;
        }
        ServerEntry target = entries.get(targetIndex);
        ServerEntry moving = entries.remove(sourceIndex);
        targetIndex = entries.indexOf(target);
        if (targetIndex < 0) {
            return;
        }
        entries.add(targetIndex, moving);
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).sortOrder = i;
        }
        getSharedPreferences(APP_SETTINGS, MODE_PRIVATE).edit()
                .putString("server_sort_mode", "manual")
                .apply();
        ServerStore.save(this, entries);
        buildCards(false);
        requestCloudSync(true);
    }

    private void showPrioritiesDialog(ServerEntry entry) {
        EditText input = new EditText(this);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setMinLines(4);
        input.setMaxLines(8);
        input.setHint(R.string.priority_examples);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (entry.priorityPlayers != null) {
            input.setText(TextUtils.join("\n", entry.priorityPlayers));
        }

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(getString(R.string.priority_dialog_title, entry.displayName()))
                .setMessage(R.string.priority_dialog_message)
                .setView(input)
                .setNegativeButton(R.string.account_cancel, null)
                .setPositiveButton(R.string.main_save, null)
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
                        input.setError(getString(R.string.priority_invalid_nickname));
                        return;
                    }
                    if (names.size() >= 50) {
                        input.setError(getString(R.string.priority_limit_reached));
                        return;
                    }
                    if (seen.add(name.toLowerCase(Locale.ROOT))) {
                        names.add(name);
                    }
                }
                entry.priorityPlayers = names;
                ServerStore.saveEntry(this, entry);
                WidgetJobService.schedule(this);
                WidgetJobService.requestRefresh(this);
                requestCloudSync(true);
                if (!names.isEmpty()) {
                    requestPriorityNotificationPermission();
                }
                dialog.dismiss();
                Toast.makeText(this, names.isEmpty()
                        ? R.string.priority_disabled
                        : R.string.priority_saved,
                        Toast.LENGTH_SHORT).show();
            });
        });
        dialog.show();
    }

    private void requestPriorityNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            android.content.SharedPreferences preferences =
                    getSharedPreferences(APP_SETTINGS, MODE_PRIVATE);
            if (preferences.getBoolean(NOTIFICATION_PERMISSION_ASKED, false)) {
                showNotificationSettingsPrompt();
                return;
            }
            preferences.edit().putBoolean(NOTIFICATION_PERMISSION_ASKED, true).apply();
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
            return;
        }
        if (!arePriorityNotificationsEnabled()) {
            showNotificationSettingsPrompt();
        }
    }

    private void ensurePriorityNotificationPermission() {
        boolean hasPriorities = false;
        for (ServerEntry server : ServerStore.load(this)) {
            if (server.priorityPlayers != null && !server.priorityPlayers.isEmpty()) {
                hasPriorities = true;
                break;
            }
        }
        if (!hasPriorities) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            android.content.SharedPreferences preferences =
                    getSharedPreferences(APP_SETTINGS, MODE_PRIVATE);
            if (!preferences.getBoolean(NOTIFICATION_PERMISSION_ASKED, false)) {
                requestPriorityNotificationPermission();
            }
            return;
        }
        if (!arePriorityNotificationsEnabled()) {
            android.content.SharedPreferences preferences =
                    getSharedPreferences(APP_SETTINGS, MODE_PRIVATE);
            if (!preferences.getBoolean(NOTIFICATION_SETTINGS_PROMPTED, false)) {
                preferences.edit().putBoolean(NOTIFICATION_SETTINGS_PROMPTED, true).apply();
                showNotificationSettingsPrompt();
            }
        }
    }

    private boolean arePriorityNotificationsEnabled() {
        android.app.NotificationManager manager =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel =
                    manager.getNotificationChannel(PriorityPlayerNotifications.CHANNEL_ID);
            return channel == null
                    || channel.getImportance() != android.app.NotificationManager.IMPORTANCE_NONE;
        }
        return true;
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
        options.add(getString(R.string.group_none));
        options.addAll(groups);
        options.add(getString(R.string.group_create_option));
        String[] labels = options.toArray(new String[0]);
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.group_dialog_title)
                .setItems(labels, (dialog, which) -> {
                    if (which == labels.length - 1) {
                        showCreateGroupDialog(entry, groups);
                    } else {
                        saveGroup(entry, which == 0 ? "" : groups.get(which - 1));
                    }
                })
                .setNegativeButton(R.string.account_cancel, null)
                .show();
    }

    private void showCreateGroupDialog(ServerEntry entry, List<String> existingGroups) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.group_name_hint);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.group_new_title)
                .setView(input)
                .setNegativeButton(R.string.account_cancel, null)
                .setPositiveButton(R.string.group_create, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button create = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            create.setTextColor(getColor(R.color.mint));
            create.setOnClickListener(v -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty() || name.length() > 60) {
                    input.setError(getString(R.string.group_name_error));
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
        Toast.makeText(this, groupId.isEmpty()
                        ? getString(R.string.group_server_removed)
                        : getString(R.string.group_server_added, groupId),
                Toast.LENGTH_SHORT).show();
    }

    private void showServerDialog(ServerEntry entry) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_server, null);
        EditText nameInput = view.findViewById(R.id.nameInput);
        EditText addressInput = view.findViewById(R.id.addressInput);
        Spinner protocolInput = view.findViewById(R.id.protocolInput);
        TextView addressLabel = view.findViewById(R.id.addressLabel);
        TextView protocolHint = view.findViewById(R.id.protocolHint);
        String[] protocolLabels = getResources().getStringArray(R.array.server_protocol_names);
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
                        ? R.string.server_address_hint_bedrock
                        : R.string.server_address_hint_java);
                addressLabel.setText(position == 1
                        ? R.string.server_address_label_bedrock
                        : R.string.server_address_label_java);
                protocolHint.setText(position == 1
                        ? R.string.server_protocol_hint_bedrock
                        : R.string.server_protocol_hint_java);
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
                .setTitle(entry == null
                        ? R.string.server_new_title : R.string.server_edit_title)
                .setView(view)
                .setNegativeButton(R.string.account_cancel, null)
                .setPositiveButton(R.string.main_save, null)
                .create();

        dialog.setOnShowListener(ignored -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setTextColor(getColor(R.color.mint));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.soft));

            positive.setOnClickListener(v -> {
                String address = ServerEntry.normalize(addressInput.getText().toString());
                String name = nameInput.getText().toString().trim();

                if (address.isEmpty()) {
                    addressInput.setError(getString(R.string.server_enter_address));
                    return;
                }
                if (address.length() > 120) {
                    addressInput.setError(getString(R.string.server_address_too_long));
                    return;
                }
                if (ServerStore.isAddressTaken(this, address, entry == null ? null : entry.id)) {
                    addressInput.setError(getString(R.string.server_duplicate));
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
                    publishServerToCatalog(created);
                    dialog.dismiss();
                    buildCards();
                    fetch(created, true);
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
                        publishServerToCatalog(entry);
                        if (!ServerStore.isAddressTaken(this, oldAddress, entry.id)) {
                            StatusCache.clear(this, oldAddress);
                        }
                        remapWidgets(oldAddress, address);
                    }
                    dialog.dismiss();
                    buildCards();
                    fetch(entry, true);
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
                .setTitle(R.string.server_delete_title)
                .setMessage(getString(R.string.server_delete_message, entry.displayName()))
                .setNegativeButton(R.string.account_cancel, null)
                .setPositiveButton(R.string.server_action_delete, (ignored, which) -> {
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
        List<String> options = new java.util.ArrayList<>();
        options.addAll(java.util.Arrays.asList(
                getResources().getStringArray(R.array.widget_sizes)));
        boolean hasGroup = entry.groupId != null && !entry.groupId.trim().isEmpty();
        if (hasGroup) {
            options.add(getString(R.string.widget_group_add, entry.groupId));
        }
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(getString(R.string.widget_server_title, entry.displayName()))
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    if (hasGroup && which == options.size() - 1) {
                        pinWidget(entry, WidgetStore.KIND_GROUP, entry.groupId);
                    } else {
                        pinWidget(entry, which);
                    }
                })
                .setNegativeButton(R.string.account_cancel, null)
                .show();
    }

    private void pinWidget(ServerEntry entry, int kind) {
        pinWidget(entry, kind, "");
    }

    private void pinWidget(ServerEntry entry, int kind, String groupId) {
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
                .putExtra("address", entry.address)
                .putExtra("group_id", groupId);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingFlags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pending = PendingIntent.getBroadcast(
                this, 500 + kind, callback, pendingFlags);

        Bundle extras = new Bundle();
        extras.putInt("kind", kind);
        extras.putString("address", entry.address);
        extras.putString("group_id", groupId);

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
                .setTitle(R.string.widget_how_to_add_title)
                .setMessage(R.string.widget_how_to_add_message)
                .setPositiveButton(R.string.widget_understood, null)
                .show();
    }

    private void remapWidgets(String oldAddress, String newAddress) {
        for (Map.Entry<Integer, WidgetStore.Info> item : WidgetStore.all(this).entrySet()) {
            if (item.getValue().address.equals(oldAddress)) {
                if (item.getValue().kind == WidgetStore.KIND_GROUP) {
                    WidgetStore.putGroup(
                            this, item.getKey(), item.getValue().groupId, newAddress);
                } else {
                    WidgetStore.put(this, item.getKey(), newAddress, item.getValue().kind);
                }
            }
        }
        WidgetUpdater.renderAll(this);
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
