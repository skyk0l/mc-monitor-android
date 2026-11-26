package com.mcmonitor.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Notifications for joins and leaves of explicitly watched players only. */
public final class PriorityPlayerNotifications {
    static final String CHANNEL_ID = "priority_player_events";

    private PriorityPlayerNotifications() {
    }

    public static void notifyChanges(
            Context context,
            ServerEntry server,
            ServerStatus previous,
            ServerStatus current) {
        context = AppLanguage.wrap(context);
        if (server == null
                || previous == null
                || current == null
                || !previous.hasData
                || !previous.online
                || !current.hasData
                || !current.online
                || !NotificationPolicy.notificationsAllowed(
                        context, System.currentTimeMillis())
                || server.priorityPlayers == null
                || server.priorityPlayers.isEmpty()
                || (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED)) {
            return;
        }

        Set<String> watched = new HashSet<>();
        for (String player : server.priorityPlayers) {
            if (player != null && !player.trim().isEmpty()) {
                watched.add(player.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (watched.isEmpty()) {
            return;
        }

        Map<String, String> before = byName(previous.players);
        Map<String, String> after = byName(current.players);
        List<String> joined = changedNames(watched, before, after);
        List<String> left = changedNames(watched, after, before);
        if (joined.isEmpty() && left.isEmpty()) {
            return;
        }

        List<String> parts = new ArrayList<>();
        if (!joined.isEmpty()) {
            parts.add(context.getString(
                    R.string.notification_player_joined, String.join(", ", joined)));
        }
        if (!left.isEmpty()) {
            parts.add(context.getString(
                    R.string.notification_player_left, String.join(", ", left)));
        }
        String message = context.getString(
                R.string.notification_players_changed,
                String.join("; ", parts),
                current.onlineCount);

        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_priority_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
        }

        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                context,
                server.address.hashCode(),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_profile)
                .setContentTitle(server.displayName())
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        manager.notify(server.address.hashCode(), notification);
    }

    private static Map<String, String> byName(List<String> names) {
        Map<String, String> result = new HashMap<>();
        if (names != null) {
            for (String name : names) {
                if (name != null && !name.trim().isEmpty()) {
                    result.put(name.trim().toLowerCase(Locale.ROOT), name.trim());
                }
            }
        }
        return result;
    }

    private static List<String> changedNames(
            Set<String> watched,
            Map<String, String> source,
            Map<String, String> reference) {
        List<String> result = new ArrayList<>();
        for (String name : watched) {
            if (source.containsKey(name) && !reference.containsKey(name)) {
                result.add(source.get(name));
            }
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }
}
