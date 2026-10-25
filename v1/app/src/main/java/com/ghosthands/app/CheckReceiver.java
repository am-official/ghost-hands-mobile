package com.ghosthands.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * v3.0 (item 2): the scheduled-check alarm's landing spot. An
 * alarm is only as good as the store it fires for: the receiver
 * re-reads the saved goals, and a goal that was deleted or
 * unscheduled since the alarm was armed fires nothing. A live
 * goal posts its notification and re-arms for the next day, so
 * the daily cadence survives without the app ever opening.
 *
 * This is notify-and-launch, by design: the receiver never
 * browses. The check itself starts when the user taps the
 * notification and presses Start in the app.
 */
public class CheckReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String name = intent.getStringExtra(Alerts.EXTRA_GOAL_NAME);
        if (name == null) return;
        SharedPreferences prefs = context.getSharedPreferences(
                "ghosthands", Context.MODE_PRIVATE);
        SavedGoals store = SavedGoals.parse(
                prefs.getString(Alerts.PREF_SAVED_GOALS, null));
        SavedGoals.SavedGoal g = store.find(name);
        if (g == null || !g.hasSchedule()) return;
        Alerts.postCheck(context, g);
        Alerts.armCheck(context, g);
    }
}
