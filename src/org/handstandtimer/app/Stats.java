package org.handstandtimer.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.io.File;

/**
 * The one thing this app tells the rest of the phone: how far today is towards the daily
 * goal, and today's best hold. Asked for on 2026-09-14 so another app (e.g. a home-screen widget)
 * can show the progress bar too.
 *
 *   content://org.handstandtimer.app.stats/today   — one row, always; query() ignores
 *   projection, selection and order, so `cr.query(uri, null, null, null, null)` is enough.
 *
 * Columns: day, holds, total_ms, goal_ms, percent (0-100, capped), best_ms (today),
 * best_month_ms, best_all_ms, last_ms (duration of the most recently recorded hold),
 * running (0/1) and run_from — the wall clock of a hold in progress, 0 if none. A live hold is
 * already counted in total_ms and percent,
 * so a reader that only paints the bar needs nothing else; one that wants a ticking clock
 * takes run_from. "recording" is the other thing a control needs: whether the service
 * is on at all, which is what the Ctl broadcasts switch. "streak_days" is the run of
 * days in a row that reached the goal (today counts once it is in, and a day that is
 * still running never breaks it), "streak_best_days" the longest run ever. "last_at" is the
 * wall clock when the most recently recorded hold started, or 0 if none; it was appended in
 * 2026-09-19 so existing readers that use the earlier column positions keep working.
 *
 * The numbers come out of sessions.csv, the same file the screen draws, so nothing here
 * can disagree with it. Rec notifies this URI when a hold ends.
 */
public class Stats extends ContentProvider {

    public static final String AUTHORITY = "org.handstandtimer.app.stats";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/today");

    private static final String[] COLS = {"day", "holds", "total_ms", "goal_ms", "percent",
            "best_ms", "best_month_ms", "best_all_ms", "last_ms", "running", "run_from", "recording",
            "streak_days", "streak_best_days", "last_at"};

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] proj, String sel, String[] args, String ord) {
        Context c = getContext();
        if (c == null) return null;
        File dir = c.getExternalFilesDir(null);
        String day = Rec.scoreDay(System.currentTimeMillis());
        long[] s = dir == null ? new long[Rec.NSTAT] : Rec.stats(dir, day, day.substring(0, 7));

        long run = c.getSharedPreferences(Rec.PREFS, Context.MODE_PRIVATE).getLong("run_from", 0);
        boolean live = Rec.running && run > 0;
        // the hold happening right now is not in sessions.csv yet, but it is what the bar
        // is about: count it, in the total, in today's best and in the streak
        if (live) Rec.withLive(s, System.currentTimeMillis() - run);
        long total = s[Rec.TOTAL];
        long pct = Math.min(100, total * 100 / Rec.GOAL_MS);

        MatrixCursor m = new MatrixCursor(COLS, 1);
        m.addRow(new Object[]{day, s[Rec.COUNT], total, Rec.GOAL_MS, pct, s[Rec.BEST_DAY],
                s[Rec.BEST_MONTH], s[Rec.BEST_ALL],
                s[Rec.LAST], live ? 1 : 0, live ? run : 0, Rec.running ? 1 : 0,
                s[Rec.STREAK], s[Rec.STREAK_BEST], s[Rec.LAST_AT]});
        m.setNotificationUri(c.getContentResolver(), URI);
        return m;
    }

    @Override public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd." + AUTHORITY + ".today";
    }

    // read only: this is a window onto the logs, not a store
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String sel, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String sel, String[] args) { return 0; }
}
