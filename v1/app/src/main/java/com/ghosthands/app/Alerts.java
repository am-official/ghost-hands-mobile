package com.ghosthands.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.List;
import java.util.TimeZone;

/**
 * v3.0 (items 2 + 5): the app's two notification jobs and the
 * alarm wiring behind them, in one Android-side helper used by
 * both MainActivity and CheckReceiver.
 *
 * - Scheduled checks (item 2): an AlarmManager alarm per saved
 *   goal with a daily time. The alarm wakes CheckReceiver, which
 *   posts the "time for your check" notification and re-arms for
 *   the next day. setAndAllowWhileIdle is used deliberately: it
 *   needs no exact-alarm permission (v3.0 adds exactly one new
 *   permission, RECORD_AUDIO), at the price of doze-era drift —
 *   a check may land minutes late on a sleeping phone, which a
 *   daily reminder tolerates. Alarms do not survive a reboot
 *   (no boot receiver in v3.0); the Activity re-arms every
 *   scheduled goal each time it opens.
 * - Finish notifications (item 5): posted when a run ends with
 *   the app in the background; the tap reopens the app on that
 *   run's answer card (the trail path rides along as an extra).
 * - Watch verdicts (v3.2): the Activity applies the watch
 *   matrix (see BUILD_NOTES) — a watched run that changed is
 *   the one run that notifies even in the foreground, via
 *   postWatchChanged below, carrying its first diff line; a
 *   watched run that did not change stays silent entirely.
 *
 * Android 13+ note (disclosed in the release notes): this app
 * targets API 34 but deliberately does NOT declare
 * POST_NOTIFICATIONS, so on Android 13+ these notifications
 * appear only when the user has allowed notifications for the
 * app in system settings; the machinery posts regardless and
 * the OS decides.
 */
public final class Alerts {

    private Alerts() { }

    public static final String CHANNEL_CHECKS = "ghost_hands_checks";
    public static final String CHANNEL_RUNS = "ghost_hands_runs";

    public static final String EXTRA_GOAL_NAME = "saved_goal_name";
    public static final String EXTRA_FROM_CHECK = "from_check";
    public static final String EXTRA_CARD_TRAIL = "card_trail_path";

    /** The preferences key the saved-goals JSON lives under (the
     *  preferences file is MainActivity's "ghosthands"). */
    public static final String PREF_SAVED_GOALS = "saved_goals";

    private static final String ACTION_CHECK =
            "com.ghosthands.app.action.CHECK";
    private static final int RUN_NOTIFICATION_REQUEST = 4242;

    // ---------------- channels ----------------

    /** Create both notification channels (idempotent; a no-op
     *  before API 26, where channels do not exist). */
    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_CHECKS) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_CHECKS, "Daily checks",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        if (nm.getNotificationChannel(CHANNEL_RUNS) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_RUNS, "Run finished",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    // ---------------- notifications ----------------

    /** Item 2: "Time for your check" — the tap opens the app
     *  with that goal loaded, ready to start under readonly. */
    public static void postCheck(Context ctx, SavedGoals.SavedGoal g) {
        ensureChannels(ctx);
        Intent open = new Intent(ctx, MainActivity.class);
        open.putExtra(EXTRA_GOAL_NAME, g.name);
        open.putExtra(EXTRA_FROM_CHECK, true);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx,
                ("check:" + g.name).hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        Notification n = build(ctx, CHANNEL_CHECKS,
                "Ghost Hands — time for your check",
                "Time for your check: " + g.name + " — tap to run",
                pi);
        notify(ctx, ("check:" + g.name).hashCode(), n);
    }

    /** v3.2: a watched check found a change — the tap opens
     *  the app on that run's answer card (banner and all). The
     *  text is the verdict's first diff line, the one number
     *  the user most likely wants at a glance. Posted per-goal
     *  (its id derives from the goal name) so two watched goals
     *  changing on the same morning do not clobber each other. */
    public static void postWatchChanged(Context ctx, String goalName,
                                        String firstDiffLine,
                                        String trailPath) {
        ensureChannels(ctx);
        Intent open = new Intent(ctx, MainActivity.class);
        if (trailPath != null) {
            open.putExtra(EXTRA_CARD_TRAIL, trailPath);
        }
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int id = ("watch:" + goalName).hashCode();
        PendingIntent pi = PendingIntent.getActivity(ctx, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        String text = (firstDiffLine == null
                || firstDiffLine.length() == 0)
                ? "the answer changed" : firstDiffLine;
        if (text.length() > 120) {
            text = text.substring(0, 119) + "…";
        }
        Notification n = build(ctx, CHANNEL_RUNS,
                "Ghost Hands — watch: " + goalName, text, pi);
        notify(ctx, id, n);
    }

    /** Item 5: a run ended off-screen — the tap opens the app
     *  on that run's answer card. */
    public static void postRunFinished(Context ctx, AnswerCard card,
                                       String trailPath) {
        ensureChannels(ctx);
        Intent open = new Intent(ctx, MainActivity.class);
        if (trailPath != null) {
            open.putExtra(EXTRA_CARD_TRAIL, trailPath);
        }
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx,
                RUN_NOTIFICATION_REQUEST, open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        Notification n = build(ctx, CHANNEL_RUNS,
                "Ghost Hands — run finished",
                card.notificationLine(), pi);
        notify(ctx, RUN_NOTIFICATION_REQUEST, n);
    }

    private static Notification build(Context ctx, String channel,
                                      String title, String text,
                                      PendingIntent pi) {
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(ctx, channel)
                : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.ic_dialog_info);
        b.setContentTitle(title);
        b.setContentText(text);
        b.setAutoCancel(true);
        b.setContentIntent(pi);
        return b.build();
    }

    private static void notify(Context ctx, int id, Notification n) {
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(id, n);
    }

    // ---------------- alarms ----------------

    private static PendingIntent alarmIntent(Context ctx, String name) {
        Intent i = new Intent(ctx, CheckReceiver.class);
        i.setAction(ACTION_CHECK);
        i.putExtra(EXTRA_GOAL_NAME, name);
        return PendingIntent.getBroadcast(ctx, name.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Arm (or re-arm) one goal's daily check at its next
     *  occurrence. A goal without a schedule disarms instead. */
    public static void armCheck(Context ctx, SavedGoals.SavedGoal g) {
        if (g == null) return;
        if (!g.hasSchedule()) {
            cancelCheck(ctx, g.name);
            return;
        }
        AlarmManager am = (AlarmManager)
                ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long when = SavedGoals.nextFireMillis(
                System.currentTimeMillis(), g.schedMinutes,
                TimeZone.getDefault());
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when,
                alarmIntent(ctx, g.name));
    }

    /** Disarm one goal's daily check. */
    public static void cancelCheck(Context ctx, String name) {
        if (name == null) return;
        AlarmManager am = (AlarmManager)
                ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(alarmIntent(ctx, name));
    }

    /** Arm every scheduled goal in the store (the Activity calls
     *  this at launch: alarms are the app's memory of its checks,
     *  and a reboot forgets them). */
    public static void armAllChecks(Context ctx, SavedGoals store) {
        if (store == null) return;
        List<SavedGoals.SavedGoal> all = store.all();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).hasSchedule()) armCheck(ctx, all.get(i));
        }
    }
}
