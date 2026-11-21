package com.mcmonitor.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProfileActivity extends Activity {
    public static final String EXTRA_PROFILE_ID = "profile_id";
    private static final int REQUEST_AVATAR = 7110;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private AccountSession session;
    private String targetId;
    private boolean ownProfile;
    private CloudSyncClient.PublicProfile profile;
    private CloudSyncClient.ProfileSocialSummary summary;
    private byte[] pendingAvatar;

    private ImageView avatar;
    private TextView identity;
    private TextView biography;
    private TextView counts;
    private TextView status;
    private EditText displayNameInput;
    private EditText bioInput;
    private CheckBox shareServers;
    private CheckBox shareFollowers;
    private CheckBox shareFriends;
    private CheckBox shareSubscriptions;
    private Button followButton;
    private LinearLayout results;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int accent = getSharedPreferences("app_settings", MODE_PRIVATE)
                .getInt("accent_color", 0xFF7EE0A3);
        getWindow().setStatusBarColor(
                Build.VERSION.SDK_INT >= 35
                        ? Color.TRANSPARENT : BackdropView.topBarColor(accent));
        getWindow().setNavigationBarColor(Color.rgb(15, 18, 25));
        session = AccountSessionStore.load(this);
        if (session == null) {
            finish();
            return;
        }
        targetId = getIntent().getStringExtra(EXTRA_PROFILE_ID);
        ownProfile = targetId == null || targetId.isEmpty()
                || targetId.equalsIgnoreCase(session.userId);
        if (ownProfile) {
            targetId = session.userId;
        }
        buildPage();
        loadProfile();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildPage() {
        FrameLayout frame = new FrameLayout(this);
        BackdropView backdrop = new BackdropView(this);
        backdrop.setAccentColor(getSharedPreferences("app_settings", MODE_PRIVATE)
                .getInt("accent_color", 0xFF7EE0A3));
        frame.addView(backdrop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(24));
        page.setBackgroundColor(Color.TRANSPARENT);
        scroll.addView(page);
        frame.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        applyWindowInsets(scroll, page);

        Button back = new Button(this);
        back.setText("‹   Назад");
        back.setOnClickListener(view -> finish());
        page.addView(back, matchWrap());

        TextView heading = new TextView(this);
        heading.setText(ownProfile ? "Профиль" : "Профиль игрока");
        heading.setTextColor(getColor(R.color.text));
        heading.setTextSize(24);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(0, dp(12), 0, dp(12));
        page.addView(heading);

        LinearLayout identityRow = new LinearLayout(this);
        identityRow.setGravity(Gravity.CENTER_VERTICAL);
        avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackground(avatarBackground());
        avatar.setClipToOutline(true);
        avatar.setImageResource(R.drawable.ic_profile);
        identityRow.addView(avatar, new LinearLayout.LayoutParams(dp(72), dp(72)));
        LinearLayout titleColumn = new LinearLayout(this);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        titleColumn.setPadding(dp(14), 0, 0, 0);
        identity = new TextView(this);
        identity.setTextColor(getColor(R.color.text));
        identity.setTextSize(18);
        identity.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleColumn.addView(identity);
        biography = new TextView(this);
        biography.setTextColor(getColor(R.color.soft));
        biography.setTextSize(14);
        titleColumn.addView(biography);
        identityRow.addView(titleColumn, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        page.addView(identityRow);

        counts = sectionText();
        counts.setPadding(0, dp(16), 0, dp(12));
        page.addView(counts);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView tabsScroll = new HorizontalScrollView(this);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        String[] tabNames = {"Подписчики", "Подписки", "Друзья", "Серверы"};
        String[] tabIds = {"followers", "subscriptions", "friends", "servers"};
        for (int i = 0; i < tabNames.length; i++) {
            final String tabId = tabIds[i];
            Button tab = new Button(this);
            tab.setText(tabNames[i]);
            tab.setOnClickListener(view -> loadRelationship(tabId));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
            tab.setMinimumWidth(dp(78));
            params.setMargins(dp(2), 0, dp(2), 0);
            tabs.addView(tab, params);
        }
        tabsScroll.addView(tabs);
        page.addView(tabsScroll, matchWrap());
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(0, dp(6), 0, dp(10));
        page.addView(results);

        followButton = new Button(this);
        followButton.setText("Подписаться");
        followButton.setVisibility(ownProfile ? View.GONE : View.VISIBLE);
        followButton.setOnClickListener(view -> toggleFollow());
        page.addView(followButton, matchWrap());

        if (ownProfile) {
            addDivider(page);
            TextView editHeading = sectionText();
            editHeading.setText("Редактирование и приватность");
            page.addView(editHeading);

            Button chooseAvatar = new Button(this);
            chooseAvatar.setText("Изменить аватар");
            chooseAvatar.setOnClickListener(view -> {
                android.content.Intent intent = new android.content.Intent(
                        android.content.Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("image/*");
                intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, REQUEST_AVATAR);
            });
            page.addView(chooseAvatar, matchWrap());

            displayNameInput = new EditText(this);
            displayNameInput.setSingleLine(true);
            displayNameInput.setHint("Отображаемое имя");
            page.addView(displayNameInput, matchWrap());
            bioInput = new EditText(this);
            bioInput.setHint("О себе");
            bioInput.setMinLines(2);
            bioInput.setMaxLines(4);
            page.addView(bioInput, matchWrap());

            shareServers = privacyCheckBox("Показывать мои серверы");
            shareFollowers = privacyCheckBox("Показывать подписчиков");
            shareFriends = privacyCheckBox("Показывать друзей");
            shareSubscriptions = privacyCheckBox("Показывать подписки на пользователей");
            page.addView(shareServers);
            page.addView(shareFollowers);
            page.addView(shareFriends);
            page.addView(shareSubscriptions);

            Button save = new Button(this);
            save.setText("Сохранить профиль");
            save.setOnClickListener(view -> saveProfile());
            page.addView(save, matchWrap());
        }

        status = sectionText();
        status.setTextColor(getColor(R.color.dim));
        page.addView(status);
    }

    private void applyWindowInsets(View root, LinearLayout content) {
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

    private void loadProfile() {
        status.setText("Загружаю профиль…");
        executor.execute(() -> {
            try {
                CloudSyncClient.PublicProfile loaded = ownProfile
                        ? CloudSyncClient.fetchProfile(session)
                        : CloudSyncClient.fetchPublicProfile(session, targetId);
                CloudSyncClient.ProfileSocialSummary social =
                        CloudSyncClient.getProfileSocialSummary(session, targetId);
                byte[] image = CloudSyncClient.downloadAvatar(loaded);
                AccountSessionStore.save(getApplicationContext(), session);
                runOnUiThread(() -> {
                    profile = loaded;
                    summary = social;
                    renderProfile(image);
                    loadRelationship("followers");
                });
            } catch (Exception exception) {
                runOnUiThread(() -> status.setText(errorMessage(exception)));
            }
        });
    }

    private void renderProfile(byte[] image) {
        String name = profile.displayName.isEmpty()
                ? profile.username : profile.displayName + "  @" + profile.username;
        identity.setText(name);
        biography.setText(profile.bio);
        if (image != null) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(image, 0, image.length);
            if (bitmap != null) {
                avatar.setImageBitmap(bitmap);
            }
        }
        counts.setText(
                (summary.canViewFollowers
                        ? summary.followersCount + " подписчиков" : "подписчики скрыты")
                        + "  ·  "
                        + (summary.canViewSubscriptions
                        ? summary.subscriptionsCount + " подписок" : "подписки скрыты")
                        + "  ·  "
                        + (summary.canViewFriends
                        ? summary.friendsCount + " друзей" : "друзья скрыты")
                        + (summary.isFollowing && summary.isFollowedBy
                        ? "  ·  Вы друзья" : ""));
        followButton.setText(summary.isFollowing ? "Отписаться" : "Подписаться");
        if (ownProfile) {
            displayNameInput.setText(profile.displayName);
            bioInput.setText(profile.bio);
            shareServers.setChecked(profile.shareServers);
            shareFollowers.setChecked(profile.shareFollowers);
            shareFriends.setChecked(profile.shareFriends);
            shareSubscriptions.setChecked(profile.shareSubscriptions);
        }
        status.setText("");
    }

    private void loadRelationship(String relationship) {
        if (results == null) {
            return;
        }
        results.removeAllViews();
        if (summary == null) {
            addMessage("Профиль ещё загружается.");
            return;
        }
        if ("servers".equals(relationship) && !summary.canViewServers) {
            addMessage("Список серверов скрыт владельцем профиля.");
            return;
        }
        boolean visible = "followers".equals(relationship)
                ? summary.canViewFollowers
                : "friends".equals(relationship)
                ? summary.canViewFriends
                : "subscriptions".equals(relationship)
                ? summary.canViewSubscriptions : true;
        if (!visible) {
            addMessage("Этот список скрыт владельцем профиля.");
            return;
        }
        addMessage("Загружаю список…");
        executor.execute(() -> {
            try {
                if ("servers".equals(relationship)) {
                    List<CloudSyncClient.PublicServer> servers =
                            CloudSyncClient.listPublicServers(session, targetId);
                    runOnUiThread(() -> renderServers(servers));
                } else {
                    List<CloudSyncClient.PublicProfile> people =
                            CloudSyncClient.listProfileRelationships(
                                    session, targetId, relationship);
                    runOnUiThread(() -> renderPeople(people, relationship));
                }
                AccountSessionStore.save(getApplicationContext(), session);
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    results.removeAllViews();
                    addMessage(errorMessage(exception));
                });
            }
        });
    }

    private void renderPeople(
            List<CloudSyncClient.PublicProfile> people, String relationship) {
        results.removeAllViews();
        if (people.isEmpty()) {
            addMessage("Пока пусто.");
            return;
        }
        for (CloudSyncClient.PublicProfile person : people) {
            TextView row = sectionText();
            row.setText((person.displayName.isEmpty()
                    ? "@" + person.username
                    : person.displayName + "  @" + person.username)
                    + ("friends".equals(relationship) ? "  ·  Друг" : ""));
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            row.setOnClickListener(view -> {
                android.content.Intent intent = new android.content.Intent(
                        this, ProfileActivity.class);
                intent.putExtra(EXTRA_PROFILE_ID, person.userId);
                startActivity(intent);
            });
            results.addView(row, matchWrap());
        }
    }

    private void renderServers(List<CloudSyncClient.PublicServer> servers) {
        results.removeAllViews();
        if (servers.isEmpty()) {
            addMessage("Публичных серверов нет.");
            return;
        }
        for (CloudSyncClient.PublicServer server : servers) {
            TextView row = sectionText();
            row.setText(server.name + "\n" + server.address + " · "
                    + server.protocol.toUpperCase(java.util.Locale.ROOT));
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            results.addView(row, matchWrap());
        }
    }

    private void saveProfile() {
        if (displayNameInput == null || bioInput == null) {
            return;
        }
        String displayName = displayNameInput.getText().toString();
        String bio = bioInput.getText().toString();
        boolean showServers = shareServers.isChecked();
        boolean showFollowers = shareFollowers.isChecked();
        boolean showFriends = shareFriends.isChecked();
        boolean showSubscriptions = shareSubscriptions.isChecked();
        byte[] avatarBytes = pendingAvatar;
        status.setText("Сохраняю профиль…");
        executor.execute(() -> {
            try {
                CloudSyncClient.updateProfile(
                        session,
                        displayName,
                        bio,
                        showServers,
                        showFollowers,
                        showFriends,
                        showSubscriptions);
                if (avatarBytes != null) {
                    CloudSyncClient.uploadAvatar(session, avatarBytes);
                    cacheAvatar(avatarBytes);
                }
                AccountSessionStore.save(getApplicationContext(), session);
                runOnUiThread(() -> {
                    pendingAvatar = null;
                    loadProfile();
                    Toast.makeText(this, "Профиль сохранён.", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception exception) {
                runOnUiThread(() -> status.setText(errorMessage(exception)));
            }
        });
    }

    private void toggleFollow() {
        if (profile == null || summary == null) {
            return;
        }
        status.setText("Обновляю подписку…");
        executor.execute(() -> {
            try {
                if (summary.isFollowing) {
                    CloudSyncClient.unfollowProfile(session, targetId);
                } else {
                    CloudSyncClient.followProfile(session, targetId);
                }
                AccountSessionStore.save(getApplicationContext(), session);
                CloudSyncClient.ProfileSocialSummary updated =
                        CloudSyncClient.getProfileSocialSummary(session, targetId);
                runOnUiThread(() -> {
                    summary = updated;
                    renderProfile(null);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> status.setText(errorMessage(exception)));
            }
        });
    }

    private void cacheAvatar(byte[] data) {
        java.io.File directory = new java.io.File(getCacheDir(), "avatars");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            android.util.Log.w("MCMonitorProfile", "Unable to create the avatar cache");
            return;
        }
        java.io.File file = new java.io.File(directory, session.userId + ".jpg");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(file)) {
            output.write(data);
        } catch (IOException exception) {
            android.util.Log.w("MCMonitorProfile", "Unable to cache the updated avatar", exception);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
            android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_AVATAR || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        try (java.io.InputStream input = getContentResolver()
                .openInputStream(data.getData())) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(input, null, bounds);
            int sample = 1;
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) {
                sample *= 2;
            }
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = sample;
            Bitmap source;
            try (java.io.InputStream decodeInput = getContentResolver()
                    .openInputStream(data.getData())) {
                source = BitmapFactory.decodeStream(decodeInput, null, decode);
            }
            if (source == null) {
                throw new IOException("Не удалось открыть выбранное изображение.");
            }
            int side = Math.min(source.getWidth(), source.getHeight());
            Bitmap square = Bitmap.createBitmap(source,
                    (source.getWidth() - side) / 2,
                    (source.getHeight() - side) / 2, side, side);
            Bitmap resized = Bitmap.createScaledBitmap(square, 512, 512, true);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!resized.compress(Bitmap.CompressFormat.JPEG, 86, output)
                    || output.size() > 2 * 1024 * 1024) {
                throw new IOException("Изображение слишком большое для аватара.");
            }
            pendingAvatar = output.toByteArray();
            avatar.setImageBitmap(resized);
            status.setText("Аватар готов к сохранению.");
        } catch (Exception exception) {
            status.setText(errorMessage(exception));
        }
    }

    private CheckBox privacyCheckBox(String label) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(label);
        checkBox.setTextColor(getColor(R.color.soft));
        return checkBox;
    }

    private TextView sectionText() {
        TextView text = new TextView(this);
        text.setTextColor(getColor(R.color.soft));
        text.setTextSize(14);
        return text;
    }

    private void addMessage(String message) {
        TextView text = sectionText();
        text.setText(message);
        text.setPadding(dp(8), dp(12), dp(8), dp(12));
        results.addView(text, matchWrap());
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(55, 255, 255, 255));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        params.setMargins(0, dp(10), 0, dp(10));
        parent.addView(divider, params);
    }

    private GradientDrawable avatarBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.argb(50, 126, 224, 163));
        background.setStroke(dp(1), Color.argb(100, 126, 224, 163));
        return background;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String errorMessage(Exception exception) {
        if (exception instanceof IllegalArgumentException) {
            return exception.getMessage();
        }
        return exception.getMessage() == null
                ? "Не удалось загрузить данные профиля."
                : exception.getMessage();
    }
}
