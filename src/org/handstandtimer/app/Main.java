package org.handstandtimer.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * One screen, built in code: a switch, the live clock, today's numbers, the records of today,
 * this month and all time, and a chart of the last five holds plus each of those records.
 */
public class Main extends Activity {

    private TextView big, info;
    private Button toggle;
    private CheckBox beep, down;
    private Bar bar;
    private Graph graph;
    private History history;
    private ScrollView sv;
    private final Handler h = new Handler();
    private final Runnable tick = new Runnable() {
        public void run() { update(); h.postDelayed(this, 500); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(48, 48, 48, 48);

        big = new TextView(this);
        big.setTextSize(64);
        big.setTextColor(Color.WHITE);
        big.setGravity(Gravity.CENTER);

        info = new TextView(this);
        info.setTextSize(16);
        info.setTextColor(0xFF9AA0A6);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, 24, 0, 16);

        bar = new Bar(this);

        toggle = new Button(this);
        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (on()) Rec.stop(Main.this);
                else if (checkSelfPermission("android.permission.ACTIVITY_RECOGNITION") != 0)
                    requestPermissions(new String[]{"android.permission.ACTIVITY_RECOGNITION"}, START);
                else Rec.start(Main.this);
                h.postDelayed(tick, 300);
            }
        });

        // beeps are off until asked for: the tones come out of the alarm stream
        beep = box("Beep on start, end and records", "beep", false);
        beep.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs().edit().putBoolean("beep", beep.isChecked()).apply();
            }
        });

        // which way up the phone rides in the pocket: the detector is the same, mirrored
        down = box("Phone carried upside down", "upside_down", true);
        down.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs().edit().putBoolean("upside_down", down.isChecked()).apply();
                Rec.upsideDown = down.isChecked();     // the service reads it 25x a second
            }
        });
        Rec.upsideDown = down.isChecked();

        history = new History(this);
        graph = new Graph(this, history);

        root.addView(big);
        root.addView(info);
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(toggle);
        root.addView(beep);
        root.addView(down);
        root.addView(graph, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(history, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // nothing inside may take focus: a focused button makes the ScrollView scroll
        // to it on every layout pass and the clock disappears off the top
        root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF101014);
        // targetSdk 35 draws edge to edge: without this the clock sits behind the action bar
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true);
        final int bar = TypedValue.complexToDimensionPixelSize(tv.data, getResources().getDisplayMetrics());
        sv.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                Insets s = in.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(s.left, s.top + bar, s.right, s.bottom);
                return in;
            }
        });
        sv.addView(root);
        setContentView(sv);

        requestPermissions(new String[]{
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.ACTIVITY_RECOGNITION"}, 1);
    }

    /** the permission was asked for by pressing start, so start once it is granted */
    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == START && res.length > 0 && res[0] == 0) Rec.start(this);
        h.postDelayed(tick, 300);
    }

    @Override protected void onResume() {
        super.onResume();
        // recording that should be on but died with its process (a crash, a kill) comes back
        if (prefs().getBoolean("on", false) && !Rec.running) Rec.start(this);
        tick.run();
    }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }

    private static final int START = 2;

    private boolean on() { return Rec.running; }

    /** a settings check box, its state in the prefs under key */
    private CheckBox box(String label, String key, boolean def) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(0xFF9AA0A6);
        c.setChecked(prefs().getBoolean(key, def));
        return c;
    }

    private SharedPreferences prefs() { return getSharedPreferences(Rec.PREFS, MODE_PRIVATE); }

    private void update() {
        SharedPreferences p = prefs();
        long run = p.getLong("run_from", 0);
        boolean live = on() && run > 0;
        // cheap while nothing has changed (one lastModified), so a hold shows up in the
        // chart and in the bests the moment it ends, without reopening the screen
        graph.reload(getExternalFilesDir(null));
        // today's numbers come from sessions.csv like everything else on this screen: the
        // prefs are only reset by the next hold, so yesterday's would show as today's
        long[] s = graph.stat;
        // a live hold already counts towards the day, or the bar would stand still while
        // the one thing that fills it is happening — and it can be what puts today over
        // the goal, which is what the streak is counted from
        if (live) { s = s.clone(); Rec.withLive(s, System.currentTimeMillis() - run); }
        long total = s[Rec.TOTAL];
        big.setText(live ? mmss(System.currentTimeMillis() - run) : mmss(s[Rec.LAST]));
        big.setTextColor(live ? 0xFF7CFF9B : Color.WHITE);
        info.setText((live ? "upside down" : "last hold") + "\n"
                + "today " + s[Rec.COUNT] + " holds · "
                + mmss(total) + " of " + mmss(Rec.GOAL_MS)
                + "\nbest " + secs(s[Rec.BEST_DAY]) + " today · " + secs(s[Rec.BEST_MONTH])
                + " this month · " + secs(s[Rec.BEST_ALL]) + " all time"
                + "\nstreak " + days(s[Rec.STREAK]) + " · best " + days(s[Rec.STREAK_BEST])
                + "\n" + (on() ? "recording" : "off") + " · " + logs());
        bar.set(total / (float) Rec.GOAL_MS);
        toggle.setText(on() ? "Stop recording" : "Start recording");
    }

    private String logs() {
        File d = getExternalFilesDir(null);
        File[] fs = d == null ? null : d.listFiles();
        long n = 0;
        int c = 0;
        if (fs != null) for (File f : fs) { n += f.length(); c++; }
        return c + " files, " + (n / 1024) + " kB";
    }

    /** days in a row that reached the goal */
    static String days(long n) { return n <= 0 ? "–" : n + (n == 1 ? " day" : " days"); }

    /** a hold lasts seconds, not minutes: "3.9 s" reads better than "0:03" */
    static String secs(long ms) { return ms <= 0 ? "–" : (Math.round(ms / 100.0) / 10.0) + " s"; }

    static String mmss(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + (s % 60 + 100 + "").substring(1);
    }

    // ---- the daily goal ---------------------------------------------------------

    /**
     * How much of the daily {@link Rec#GOAL_MS} is done: a rounded track that fills green and
     * turns gold once the day is in. Drawn rather than a ProgressBar so it matches the chart
     * below it and still needs no resource.
     */
    static class Bar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        private float frac;

        Bar(Activity a) {
            super(a);
            d = a.getResources().getDisplayMetrics().density;
        }

        void set(float f) {
            f = f < 0 ? 0 : f > 1 ? 1 : f;
            if (f == frac) return;                 // 2x a second, and it hardly ever moves
            frac = f;
            invalidate();
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(getDefaultSize(0, wSpec), (int) (22 * d));
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), r = 5 * d, top = 6 * d, bot = 16 * d;
            p.setColor(0xFF2A2A31);
            c.drawRoundRect(0, top, w, bot, r, r, p);
            if (frac <= 0) return;
            p.setColor(frac >= 1 ? 0xFFFFD479 : 0xFF7CFF9B);
            // never narrower than it is tall, or the rounding eats the first few seconds
            c.drawRoundRect(0, top, Math.max(frac * w, bot - top), bot, r, r, p);
        }
    }

    // ---- the last holds and the records, drawn from the raw log -----------------

    /**
     * One series of one hold. It grows: a fixed 400 (16 s at 25 Hz) cut every curve off in the
     * middle of the row for any hold past 20 s. The cap is only a guard against a broken log.
     */
    static class Line {
        float[] x = new float[256], y = new float[256];
        int n;
        void add(float a, float b) {
            if (n == x.length) {
                if (n >= 8192) return;                     // ~5 min at 25 Hz: something is wrong
                x = Arrays.copyOf(x, n * 2);
                y = Arrays.copyOf(y, n * 2);
            }
            x[n] = a; y[n] = b; n++;
        }
    }

    /** a detected hold plus the sensors around it */
    static class Hold {
        long start, ms, from, to;              // wall clock; from/to are the plotted window
        String source;                         // exact sessions.csv line, for deleting only this hold
        String tag = "";                       // "best today" and friends; "" for one of the recent holds
        final Line gy = new Line(), gyro = new Line(), lin = new Line();
    }

    static final int PAD = 3000;               // ms of context before and after a hold
    static final int SHOWN = 5;
    static final float ROW = 112;              // dp per hold, label included

    /** Duration of each of the last 200 holds, with the app's 04:00 day boundaries. */
    static class History extends View {
        static final int LIMIT = 200;
        static final int SHORT_COLOR = 0xFF7CFF9B;
        static final int OVER_30_COLOR = 0xFFFFD479;
        static final int OVER_60_COLOR = 0xFFFF8A94;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        private List<Hold> holds = new ArrayList<>();

        History(Activity a) {
            super(a);
            d = a.getResources().getDisplayMetrics().density;
            p.setTextSize(11 * d);
        }

        void set(List<Hold> next) {
            holds = next;
            invalidate();
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(getDefaultSize(0, wSpec), (int) (132 * d));
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), top = 36 * d, bottom = 112 * d;
            int shortCount = 0, over30Count = 0, over60Count = 0;
            for (Hold h : holds) {
                if (h.ms > 60000) over60Count++;
                else if (h.ms > 30000) over30Count++;
                else shortCount++;
            }
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(0xFF9AA0A6);
            c.drawText("Last 200 handstands", 0, 16 * d, p);
            p.setTextSize(9 * d);
            float legendX = 0;
            String label = "≤30s (" + shortCount + ")";
            p.setColor(SHORT_COLOR);
            c.drawText(label, legendX, 29 * d, p);
            legendX += p.measureText(label) + 12 * d;
            label = ">30s (" + over30Count + ")";
            p.setColor(OVER_30_COLOR);
            c.drawText(label, legendX, 29 * d, p);
            legendX += p.measureText(label) + 12 * d;
            p.setColor(OVER_60_COLOR);
            c.drawText(">60s (" + over60Count + ")", legendX, 29 * d, p);
            p.setTextSize(11 * d);
            if (holds.isEmpty()) {
                c.drawText("no holds yet", 0, 60 * d, p);
                return;
            }

            long max = 0;
            for (Hold h : holds) max = Math.max(max, h.ms);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText("max " + secs(max), w, 16 * d, p);
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(0xFF2A2A31);
            c.drawLine(0, bottom, w, bottom, p);

            float slot = w / LIMIT;
            float barWidth = Math.max(1, slot * 0.72f);
            int offset = LIMIT - holds.size();
            String previousDay = null;
            for (int i = 0; i < holds.size(); i++) {
                Hold h = holds.get(i);
                float x = (offset + i + 0.5f) * slot;
                String day = Rec.scoreDay(h.start);
                if (previousDay != null && !day.equals(previousDay)) {
                    p.setColor(0xFF56565E);
                    float boundary = (offset + i) * slot;
                    c.drawLine(boundary, top, boundary, bottom + 10 * d, p);
                }
                previousDay = day;
                float height = Math.max(2 * d, h.ms / (float) max * (bottom - top));
                p.setColor(h.ms > 60000 ? OVER_60_COLOR
                        : h.ms > 30000 ? OVER_30_COLOR : SHORT_COLOR);
                c.drawRect(x - barWidth / 2, bottom - height, x + barWidth / 2, bottom, p);
            }
        }
    }

    /**
     * The chart under the numbers: for each of the last five holds, and for the best hold of
     * today, of this calendar month and of all time, the gravity component that detection runs
     * on, plus the rotation rate and the linear acceleration that show the kick up and the
     * landing. The data is the raw log, read back and cut to the hold's window.
     */
    static class Graph extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private List<Hold> holds = new ArrayList<>();
        private String note = "";
        private long stamp;                    // sessions.csv mtime the current data is from
        private long retryAt;                  // re-read although sessions.csv has not changed
        private boolean loaded, busy;
        private final float d;                 // dp
        private final Activity activity;
        private int pressedDelete = -1;
        /** today's holds, their total, the three records and the streak; read by Main for the numbers */
        volatile long[] stat = new long[Rec.NSTAT];

        private final History history;

        Graph(Activity a, History history) {
            super(a);
            activity = a;
            this.history = history;
            d = a.getResources().getDisplayMetrics().density;
            p.setTextSize(11 * d);
            setClickable(true);
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int rows = Math.max(holds.size(), 1);
            setMeasuredDimension(getDefaultSize(0, wSpec), (int) (26 * d + rows * ROW * d));
        }

        /**
         * (re)read the logs on a background thread when sessions.csv has changed — and again
         * for a few seconds afterwards, because the hold that just woke us up is not on disk
         * in full yet: the log is flushed when the hold ends, so the PAD seconds of context
         * after it are still in the deflater and the curves broke off mid-row.
         */
        void reload(final File dir) {
            if (dir == null || busy) return;
            long now = System.currentTimeMillis();
            if (m(dir) == stamp && loaded && (retryAt == 0 || now < retryAt)) return;
            stamp = m(dir);
            loaded = true;
            busy = true;
            new Thread(new Runnable() { public void run() {
                final List<Hold> hs = new ArrayList<>();
                final List<Hold> recent = new ArrayList<>();
                final long[] bs = new long[Rec.NSTAT];
                String n;
                try { n = read(dir, hs, recent, bs); } catch (Exception ex) { n = "log unreadable: " + ex; }
                final String msg = n;
                post(new Runnable() { public void run() {
                    holds = hs; stat = bs; note = msg; busy = false;
                    history.set(recent);
                    // the newest window still reaches into what has not been flushed yet
                    // (the log is flushed every FLUSH_MS): look again in a moment
                    long end = 0;
                    for (Hold x : hs) end = Math.max(end, x.to);
                    long t = System.currentTimeMillis();
                    retryAt = t < end + Rec.FLUSH_MS + 1000 ? t + 2000 : 0;
                    requestLayout(); invalidate();
                } });
            } }).start();
        }

        private static long m(File dir) { return new File(dir, "sessions.csv").lastModified(); }

        /**
         * The last five sessions, then the best hold of today, of this calendar month and of
         * all time, and from the gz logs the sensors around each of them. The logs of exactly
         * these holds survive {@link Rec#sweep} (see {@link Rec#windows}), so a record keeps its
         * curves however old it gets; a bare bar is left for a record recorded before that, or
         * one whose log was pruned by hand.
         */
        private static String read(File dir, List<Hold> hs, List<Hold> recent, long[] stat) throws Exception {
            File s = new File(dir, "sessions.csv");
            if (!s.exists()) return "no holds yet";
            List<Hold> all = new ArrayList<>();
            BufferedReader r = new BufferedReader(new FileReader(s));
            for (String l = r.readLine(); l != null; l = r.readLine()) {
                // Rec.holdOf does the parsing, so the sweep that keeps a record's log and the
                // chart that draws it always pick the same holds out of the same lines
                long[] c = Rec.holdOf(l);
                if (c == null) continue;
                Hold h = new Hold();
                h.start = c[0];
                h.ms = c[1];
                h.source = l;
                all.add(h);
            }
            r.close();
            if (all.isEmpty()) return "no holds yet";
            recent.addAll(all.subList(Math.max(0, all.size() - History.LIMIT), all.size()));

            // newest hold in the top row: the records appended below it get older as they go
            // (today, this month, all time), so the whole chart reads one way down
            for (int i = all.size() - 1; i >= Math.max(0, all.size() - SHOWN); i--) hs.add(all.get(i));
            // the same hold can hold all three records at once; then it gets one row with one label
            String today = Rec.scoreDay(System.currentTimeMillis()), month = today.substring(0, 7);
            Hold[] rec = new Hold[3];
            Map<String, Long> perDay = new HashMap<>();       // for the streak
            for (Hold h : all) {
                String day = Rec.scoreDay(h.start);
                Long had = perDay.get(day);
                perDay.put(day, (had == null ? 0 : had) + h.ms);
                stat[Rec.LAST] = h.ms;
                stat[Rec.LAST_AT] = h.start;
                if (day.equals(today)) {
                    rec[0] = better(rec[0], h);
                    stat[Rec.COUNT]++;
                    stat[Rec.TOTAL] += h.ms;
                }
                if (day.startsWith(month)) rec[1] = better(rec[1], h);
                rec[2] = better(rec[2], h);
            }
            long[] st = Rec.streakOf(perDay, today);
            stat[Rec.STREAK] = st[0];
            stat[Rec.STREAK_BEST] = st[1];
            String[] tags = {"today", "this month", "all time"};
            for (int i = 0; i < 3; i++) {
                Hold h = rec[i];
                if (h == null) continue;
                stat[Rec.BEST_DAY + i] = h.ms;
                h.tag = h.tag.isEmpty() ? tags[i] : h.tag + " · " + tags[i];
                if (!hs.contains(h)) hs.add(h);                   // == : one of the recent rows
            }
            for (Hold h : hs) { h.from = h.start - PAD; h.to = h.start + h.ms + PAD; }

            File[] fs = dir.listFiles();
            if (fs == null) return "";
            Arrays.sort(fs);                                    // the names sort by time
            List<File> logs = new ArrayList<>();
            for (File g : fs) if (g.getName().startsWith("sensors-")) logs.add(g);

            // an all-time record can be weeks old, so every log in between would be in the
            // span: test each one against the single windows, not against their hull
            long first = Long.MAX_VALUE;
            for (Hold h : hs) first = Math.min(first, h.from);
            for (int i = logs.size() - 1; i >= 0; i--) {        // newest first
                // A log runs from the wall clock in its name to the start of the next one —
                // the one still being written runs up to now. Its mtime is no use: the
                // media provider only refreshes it when the file is closed, so the hold
                // that was just recorded looked as if no log covered it ("no log").
                long a = Rec.nameTime(logs.get(i).getName());
                long b = i + 1 < logs.size()
                        ? Rec.nameTime(logs.get(i + 1).getName()) : System.currentTimeMillis();
                if (b < first) break;                           // and so is everything older
                for (Hold h : hs)
                    if (a <= h.to && b >= h.from) { scan(logs.get(i), hs); break; }
            }
            return "";
        }

        /** the longer of the two, the newer one on a tie */
        private static Hold better(Hold a, Hold b) { return a == null || b.ms >= a.ms ? b : a; }

        /** pull gravity, gyroscope and linear acceleration inside a hold's window out of one log */
        private static void scan(File g, List<Hold> hs) {
            BufferedReader r = null;
            long base = 0;
            try {
                r = new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(new FileInputStream(g), 16384), "UTF-8"), 65536);
                for (String l = r.readLine(); l != null; l = r.readLine()) {
                    if (l.startsWith("#base,")) {
                        base = Long.parseLong(l.substring(6, l.indexOf(',', 6)));
                        continue;
                    }
                    int c1 = l.indexOf(',');
                    if (c1 < 1 || base == 0) continue;
                    int type = Integer.parseInt(l.substring(0, c1));
                    if (type != 9 && type != 4 && type != 10) continue;
                    int c2 = l.indexOf(',', c1 + 1);
                    if (c2 < 0) continue;
                    long w = base + Long.parseLong(l.substring(c1 + 1, c2));
                    float x = 0, y = 0, zz = 0;
                    boolean got = false;
                    // holds less than 2 * PAD apart share samples, so every one of them gets a copy
                    for (Hold h : hs) {
                        if (w < h.from || w > h.to) continue;
                        if (!got) {
                            String[] v = l.substring(c2 + 1).split(",");
                            if (v.length < 3) break;
                            x = Float.parseFloat(v[0]);
                            y = Float.parseFloat(v[1]);
                            zz = Float.parseFloat(v[2]);
                            got = true;
                        }
                        float t = (w - h.start) / 1000f;
                        if (type == 9) h.gy.add(t, y);
                        else if (type == 4) h.gyro.add(t, mag(x, y, zz));
                        else h.lin.add(t, mag(x, y, zz));
                    }
                }
            } catch (Exception ignored) {
                // a log that is still open, or was cut off by a kill, has no gzip trailer:
                // whatever was read before the exception is good data
            } finally {
                try { if (r != null) r.close(); } catch (Exception ignored) { }
            }
        }

        private static float mag(float x, float y, float z) {
            return (float) Math.sqrt(x * x + y * y + z * z);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), row = ROW * d;
            p.setColor(0xFF9AA0A6);
            if (holds.isEmpty()) {
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText(note.isEmpty() ? "no holds yet" : note, w / 2, 20 * d, p);
                return;
            }
            p.setTextAlign(Paint.Align.LEFT);
            float x = 0;
            x = legend(c, 0xFF7CFF9B, x, "gravity Y  \u00b111 m/s\u00b2");
            x = legend(c, 0xFFFFB86B, x, "|gyro|  0-12 rad/s");
            legend(c, 0xFF6BC5FF, x, "|lin.acc|  0-24 m/s\u00b2");

            SimpleDateFormat f = new SimpleDateFormat("EEE HH:mm:ss", Locale.US);
            SimpleDateFormat fd = new SimpleDateFormat("EEE d MMM HH:mm", Locale.US);
            for (int i = 0; i < holds.size(); i++) {
                Hold h = holds.get(i);
                float top = 26 * d + i * row, bot = top + row - 22 * d;
                float t0 = -PAD / 1000f, t1 = (h.ms + PAD) / 1000f;

                // the hold itself
                p.setColor(0xFF1B2A22);
                c.drawRect(xx(0, t0, t1, w), top, xx(h.ms / 1000f, t0, t1, w), bot, p);
                p.setColor(0xFF2A2A31);                      // gravity Y = 0
                c.drawLine(0, (top + bot) / 2, w, (top + bot) / 2, p);
                p.setStyle(Paint.Style.STROKE);
                c.drawRect(0.5f, top, w - 0.5f, bot, p);
                p.setStyle(Paint.Style.FILL);

                line(c, h.gyro, t0, t1, top, bot, w, 0, 12, 0xFFFFB86B);
                line(c, h.lin, t0, t1, top, bot, w, 0, 24, 0xFF6BC5FF);
                line(c, h.gy, t0, t1, top, bot, w, -11, 11, 0xFF7CFF9B);

                // a record can be weeks old, so it gets the date; a recent hold only the time
                p.setColor(h.tag.isEmpty() ? 0xFFE8EAED : 0xFFFFD479);
                c.drawText((h.tag.isEmpty() ? "" : "best " + h.tag + "  ·  ")
                        + (h.tag.isEmpty() ? f : fd).format(new Date(h.start))
                        + "  ·  " + (h.ms / 1000.0) + " s"
                        + (h.gy.n == 0 ? "  ·  no log" : ""), 0, bot + 11 * d, p);
                float bx = w - 49 * d, by = bot - 2 * d;
                p.setColor(0xFF3A2428);
                c.drawRoundRect(bx, by, w, by + 20 * d, 5 * d, 5 * d, p);
                p.setColor(0xFFFF8A94);
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText("delete", (bx + w) / 2, by + 14 * d, p);
                p.setTextAlign(Paint.Align.LEFT);
            }
        }

        private int deleteAt(float x, float y) {
            if (x < getWidth() - 64 * d || y < 26 * d) return -1;
            int i = (int) ((y - 26 * d) / (ROW * d));
            if (i < 0 || i >= holds.size()) return -1;
            float top = 26 * d + i * ROW * d, bot = top + ROW * d - 22 * d;
            return y >= bot - 8 * d && y <= bot + 28 * d ? i : -1;
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            int i = deleteAt(e.getX(), e.getY());
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                pressedDelete = i;
                return i >= 0;
            }
            if (e.getAction() == android.view.MotionEvent.ACTION_CANCEL) pressedDelete = -1;
            if (e.getAction() == android.view.MotionEvent.ACTION_UP
                    && (i < 0 || i != pressedDelete)) {
                pressedDelete = -1;
                return true;
            }
            if (e.getAction() != android.view.MotionEvent.ACTION_UP)
                return pressedDelete >= 0;
            pressedDelete = -1;
            performClick();
            confirmDelete(holds.get(i));
            return true;
        }

        @Override public boolean performClick() { super.performClick(); return true; }

        private void confirmDelete(final Hold hold) {
            String when = new SimpleDateFormat("EEE d MMM HH:mm:ss", Locale.US)
                    .format(new Date(hold.start));
            new AlertDialog.Builder(activity)
                    .setTitle("Delete detected handstand?")
                    .setMessage(when + "  ·  " + (hold.ms / 1000.0)
                            + " s\n\nThe raw sensor log will be kept.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete", (dialog, which) -> delete(hold.source))
                    .show();
        }

        private void delete(final String source) {
            final File dir = activity.getExternalFilesDir(null);
            if (dir == null) return;
            new Thread(() -> {
                final boolean ok = Rec.deleteSession(dir, source);
                post(() -> {
                    if (ok) {
                        stamp = -1; loaded = false; retryAt = 0;
                        activity.getContentResolver().notifyChange(Stats.URI, null);
                        reload(dir);
                    } else Toast.makeText(activity, "Could not delete hold", Toast.LENGTH_SHORT).show();
                });
            }).start();
        }

        /** one swatch plus its label, left to right; returns where the next one starts */
        private float legend(Canvas c, int col, float x, String s) {
            p.setColor(col);
            c.drawRect(x, 6 * d, x + 9 * d, 8.5f * d, p);
            p.setColor(0xFF9AA0A6);
            c.drawText(s, x + 13 * d, 11 * d, p);
            return x + 13 * d + p.measureText(s) + 11 * d;
        }

        private static float xx(float t, float t0, float t1, float w) {
            return (t - t0) / (t1 - t0) * w;
        }

        /** one series, scaled from [lo,hi] onto the row */
        private void line(Canvas c, Line l, float t0, float t1, float top, float bot,
                          float w, float lo, float hi, int col) {
            if (l.n < 2) return;
            float[] seg = new float[(l.n - 1) * 4];
            for (int i = 0; i < l.n - 1; i++) {
                seg[i * 4] = xx(l.x[i], t0, t1, w);
                seg[i * 4 + 1] = bot - (l.y[i] - lo) / (hi - lo) * (bot - top);
                seg[i * 4 + 2] = xx(l.x[i + 1], t0, t1, w);
                seg[i * 4 + 3] = bot - (l.y[i + 1] - lo) / (hi - lo) * (bot - top);
            }
            p.setColor(col);
            p.setStrokeWidth(1.6f * d);
            c.drawLines(seg, p);
            p.setStrokeWidth(0);
        }
    }
}
