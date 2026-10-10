package org.handstandtimer.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Keeps recording across a reboot when it was on. */
public class Boot extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (c.getSharedPreferences(Rec.PREFS, Context.MODE_PRIVATE).getBoolean("on", false))
            Rec.start(c);
    }
}
