package org.handstandtimer.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The other half of what this app tells the rest of the phone: {@link Stats} reads today's
 * numbers out, this switches the recording on and off. Asked for on 2026-09-14, by a home-screen
 * widget that draws the progress bar — a widget button can fire one of these straight
 * from a PendingIntent.getBroadcast, no service binding and no code on our side of it.
 *
 *   am broadcast -a org.handstandtimer.app.action.TOGGLE -n org.handstandtimer.app/.Ctl
 *
 * Actions: …action.START, …action.STOP, …action.TOGGLE. Exported and without a permission,
 * like the provider: nothing here is worth more than the convenience of any app being able
 * to ask. The broadcast is ordered, so the result data comes back as "on" or "off" — the
 * wish, not a promise: START without ACTIVITY_RECOGNITION granted cannot go foreground and
 * {@link Rec#start} drops it (only Main can ask the user for that permission).
 */
public class Ctl extends BroadcastReceiver {

    static final String START = "org.handstandtimer.app.action.START";
    static final String STOP = "org.handstandtimer.app.action.STOP";
    static final String TOGGLE = "org.handstandtimer.app.action.TOGGLE";

    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (!START.equals(a) && !STOP.equals(a) && !TOGGLE.equals(a)) return;
        boolean on = TOGGLE.equals(a) ? !Rec.running : START.equals(a);
        if (on) Rec.start(c); else Rec.stop(c);
        if (isOrderedBroadcast()) setResultData(on ? "on" : "off");
        // whoever draws the bar also draws the on/off state
        c.getContentResolver().notifyChange(Stats.URI, null);
    }
}
