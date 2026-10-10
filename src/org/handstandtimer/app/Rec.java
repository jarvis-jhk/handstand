package org.handstandtimer.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;

/**
 * The whole app: a foreground service that logs every sensor we might ever want
 * for handstand detection into one gzipped CSV per day, and times handstands
 * live from the gravity vector.
 *
 * Log files land in the app's external files dir (/sdcard/Android/data/<pkg>/files),
 * so they can be pulled off the phone without root and without a share sheet.
 */
public class Rec extends Service implements SensorEventListener {

    static final String PREFS = "hs";
    static final String CH = "rec";
    static final String ACTION_STOP = "org.handstandtimer.app.STOP";

    /** gravity along -Y (phone top pointing at the floor) for this long = a handstand */
    static final float G_INVERTED = -6.0f;     // m/s^2 on the phone's Y axis
    /** and along +Y = standing the usual way up, which is where a handstand starts from */
    static final float G_UPRIGHT = 6.0f;
    /**
     * …unless the phone rides top down in the pocket, as some people carry theirs: then both
     * signs are the other way round, the app times the gaps between the handstands instead of
     * the handstands, and every start beeps as an end. Pref "upside_down", read here because
     * detect() runs 25x a second (Main sets it too, same process, like {@link #running}).
     */
    static volatile boolean upsideDown;
    static final long UPRIGHT_MS = 500;        // this long upright counts as "was standing"
    static final long UPRIGHT_MAX_AGO = 5000;  // and it has to be this recent when going over
    static final long ENTER_MS = 1200;         // hold before the clock starts
    static final long LEAVE_MS = 800;          // out of position before it stops
    static final long MIN_MS = 2000;           // shorter than this is not a handstand
    static final int KEEP_DAYS = 21;
    /** the daily target the progress bar and the content provider report against */
    static final long GOAL_MS = 5 * 60 * 1000;
    /** how often the gz log is flushed; Main waits this long for a fresh hold to land */
    static final long FLUSH_MS = 5000;

    /**
     * Beeps (pref "beep", off by default): a short tone when a hold starts, a double one when it
     * ends, and an ascending run of DTMF tones when it was a record — two for the day, three for
     * the month, four for all time. Synthesised by ToneGenerator, so this still costs no resource
     * file. On the alarm stream on purpose: the phone is upside down in a trouser pocket, and a
     * notification-stream beep would be swallowed by silent mode exactly when it is wanted.
     */
    static final int[] FANFARE = {ToneGenerator.TONE_DTMF_1, ToneGenerator.TONE_DTMF_5,
                                  ToneGenerator.TONE_DTMF_9, ToneGenerator.TONE_DTMF_D};
    static final int TONE_MS = 160, TONE_GAP = 200, TONE_VOL = 70;
    /** a tick every TICK_MS while a hold runs; 30 s gets two and 60 s gets three tones */
    static final long TICK_MS = 10000;
    static final int TONE_TICK = ToneGenerator.TONE_PROP_PROMPT;
    /** played once a hold's end pushes today's total over GOAL_MS for the first time that day,
     *  after any record fanfare — unused DTMF tones, so it cannot be mistaken for one */
    static final int[] GOAL_TUNE = {ToneGenerator.TONE_DTMF_S, ToneGenerator.TONE_DTMF_0,
                                    ToneGenerator.TONE_DTMF_P};

    /** the service is really recording (Main runs in the same process); pref "on" is only the wish */
    static volatile boolean running;

    /** sensor type -> sampling period in us; 0 values mean "on change" (SENSOR_DELAY_NORMAL) */
    static final int[][] SENSORS = {
        {Sensor.TYPE_ACCELEROMETER, 40000},
        {Sensor.TYPE_GYROSCOPE, 40000},
        {Sensor.TYPE_GRAVITY, 40000},
        {Sensor.TYPE_ROTATION_VECTOR, 40000},
        {Sensor.TYPE_LINEAR_ACCELERATION, 40000},
        {Sensor.TYPE_MAGNETIC_FIELD, 200000},
        {Sensor.TYPE_PRESSURE, 200000},
        {Sensor.TYPE_LIGHT, 0},
        {Sensor.TYPE_PROXIMITY, 0},
        {Sensor.TYPE_STEP_COUNTER, 0},
        {Sensor.TYPE_STEP_DETECTOR, 0},
    };

    private SensorManager sm;
    private PowerManager.WakeLock lock;
    private HandlerThread thread;
    private Handler bg;
    private Writer out;
    private ToneGenerator tone;
    private String day = "";
    private long bootWall;                     // wall clock of elapsedRealtime 0
    private long lastFlush, lastDayCheck;

    // detection state, all on the sensor thread
    private long candFrom, outFrom, runFrom;
    private long uprightFrom, lastUpright;
    private long tickedMs;                      // how much of the running hold has ticked already
    private boolean inverted;
    /** Serialises appends and UI deletions of sessions.csv. */
    private static final Object SESSION_LOCK = new Object();

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && ACTION_STOP.equals(i.getAction())) {
            prefs().edit().putBoolean("on", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (sm != null) return START_STICKY;

        // a hold that was live when the process died is gone
        prefs().edit().putBoolean("on", true).putLong("run_from", 0).apply();
        upsideDown = prefs().getBoolean("upside_down", true);
        try {
            startForeground(1, note("Recording"));
        } catch (Exception ex) {   // health FGS needs ACTIVITY_RECOGNITION granted
            Log.w("Handstand", "no foreground service", ex);
            prefs().edit().putBoolean("on", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }

        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "handstand:rec");
        lock.acquire();

        thread = new HandlerThread("sensors");
        thread.start();
        bg = new Handler(thread.getLooper());

        bootWall = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        for (int[] s : SENSORS) {
            Sensor sensor = sm.getDefaultSensor(s[0]);
            if (sensor == null) continue;
            int period = s[1] == 0 ? SensorManager.SENSOR_DELAY_NORMAL : s[1];
            // Gravity drives the live timer, so it must not sit in a batch.
            int latency = s[0] == Sensor.TYPE_GRAVITY ? 0 : 2000000;
            sm.registerListener(this, sensor, period, latency, bg);
        }
        bg.post(new Runnable() { public void run() { sweep(); } });
        running = true;
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (sm != null) sm.unregisterListener(this);
        if (bg != null) bg.post(new Runnable() { public void run() { close(); silence(); } });
        if (thread != null) thread.quitSafely();
        if (lock != null && lock.isHeld()) lock.release();
        sm = null;
        running = false;
    }

    // ---- sensors ------------------------------------------------------------

    @Override public void onAccuracyChanged(Sensor s, int a) { }


    @Override public void onSensorChanged(SensorEvent e) {
        long t = e.timestamp / 1000000L;                     // ms since boot
        StringBuilder b = new StringBuilder(48);
        b.append(e.sensor.getType()).append(',').append(t);
        int n = Math.min(e.values.length, 4);
        for (int i = 0; i < n; i++) b.append(',').append(fmt(e.values[i]));
        b.append('\n');
        write(b.toString());
        if (e.sensor.getType() == Sensor.TYPE_GRAVITY) detect(e.values[1], t);
    }

    /** four decimals without the cost and noise of String.format */
    private static String fmt(float v) {
        long r = Math.round(Math.abs(v) * 10000.0);   // sign kept apart: -0.5 must not become 0.5
        return (v < 0 ? "-" : "") + (r / 10000) + "."
                + Long.toString(r % 10000 + 10000).substring(1);
    }

    // ---- handstand timer ----------------------------------------------------

    private void detect(float gy, long t) {
        // one sign flip and the whole detector works for a phone carried top down
        if (upsideDown) gy = -gy;
        // Standing upright is what a handstand starts from. Without that precondition the
        // very act of sliding the phone into a pocket top first reads as a 4 s handstand,
        // which is exactly what happened in the log of 2026-09-13 19:01.
        if (gy > G_UPRIGHT) {
            if (uprightFrom == 0) uprightFrom = t;
            if (t - uprightFrom >= UPRIGHT_MS) lastUpright = t;
        } else uprightFrom = 0;

        boolean now = gy < G_INVERTED;
        if (now) {
            outFrom = 0;
            if (candFrom == 0) candFrom = t;
            if (runFrom == 0 && t - candFrom >= ENTER_MS
                    && lastUpright > 0 && candFrom - lastUpright <= UPRIGHT_MAX_AGO) {
                runFrom = candFrom;
                inverted = true;
                tickedMs = 0;
                prefs().edit().putLong("run_from", bootWall + runFrom).apply();
                beep(ToneGenerator.TONE_PROP_BEEP, 0);
                notify("Handstand…");
            }
        } else {
            candFrom = 0;
            if (outFrom == 0) outFrom = t;
            if (inverted && t - outFrom >= LEAVE_MS) {
                long ms = outFrom - runFrom;
                long from = bootWall + runFrom;
                inverted = false;
                runFrom = 0;
                prefs().edit().putLong("run_from", 0).apply();
                beep(ToneGenerator.TONE_PROP_BEEP2, 0);
                if (ms >= MIN_MS) session(from, ms);
                else notify("Recording");
            }
        }
        // a brief drop below LEAVE_MS does not end the hold, so this checks "inverted", not "now"
        if (inverted && t - runFrom - tickedMs >= TICK_MS) {
            tickedMs += TICK_MS;
            tick(tickedMs);
        }
    }

    private void session(long from, long ms) {
        SharedPreferences p = prefs();
        String today = scoreDay(from), month = today.substring(0, 7);
        // everything the screen shows comes out of sessions.csv, the one place holds are kept
        // for good: the fanfare can then never disagree with it, and emptying that file really
        // does start the day and the records over
        long[] s = stats(dir(), today, month);
        int tier = ms > s[BEST_ALL] ? 3 : ms > s[BEST_MONTH] ? 2 : ms > s[BEST_DAY] ? 1 : 0;
        boolean goalJustReached = s[TOTAL] < GOAL_MS && s[TOTAL] + ms >= GOAL_MS;
        p.edit().putString("day", today).putInt("count", (int) s[COUNT] + 1)
                .putLong("total", s[TOTAL] + ms).putLong("best", Math.max(s[BEST_ALL], ms))
                .putLong("last", ms).apply();
        // to the millisecond: truncated to the second the chart drew every hold's curves up
        // to a second beside the green bar that marks it (reported 2026-09-14 10:25)
        append("sessions.csv", stampMs(from) + "," + (ms / 1000.0) + "\n");
        // the hold's own samples are still in the deflater otherwise, and the chart that
        // sessions.csv is about to wake up would draw this hold as "no log"
        flush();
        if (tier > 0) fanfare(tier + 1);
        // the day's own tune, once, queued after the record fanfare if this hold was both
        if (goalJustReached) goalFanfare(tier > 0 ? tier + 1 : 0);
        notify("Recording");
        getContentResolver().notifyChange(Stats.URI, null);   // whoever shows the daily goal
        Log.i("Handstand", "session " + (ms / 1000.0) + " s" + (tier > 0 ? ", record " + tier : ""));
    }

    /** what {@link #stats} returns */
    static final int COUNT = 0, TOTAL = 1, BEST_DAY = 2, BEST_MONTH = 3, BEST_ALL = 4,
            STREAK = 5, STREAK_BEST = 6, LAST = 7, LAST_AT = 8, NSTAT = 9;

    /**
     * The day's holds and their total, the longest hold of that day, of that month and ever,
     * and the streak — straight out of sessions.csv, which is the only record kept for good.
     * Read by the detector for the fanfare and by {@link Stats} for whoever shows the daily goal.
     */
    static long[] stats(File dir, String day, String month) {
        long[] b = new long[NSTAT];
        Map<String, Long> perDay = new HashMap<>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(new File(dir, "sessions.csv")));
            for (String l = r.readLine(); l != null; l = r.readLine()) {
                long[] h = holdOf(l);
                if (h == null) continue;
                long ms = h[1];
                String d = scoreDay(h[0]);
                Long had = perDay.get(d);
                perDay.put(d, (had == null ? 0 : had) + ms);
                if (d.equals(day)) {
                    b[COUNT]++;
                    b[TOTAL] += ms;
                    b[BEST_DAY] = Math.max(b[BEST_DAY], ms);
                }
                if (d.startsWith(month)) b[BEST_MONTH] = Math.max(b[BEST_MONTH], ms);
                b[BEST_ALL] = Math.max(b[BEST_ALL], ms);
                b[LAST] = ms;
                b[LAST_AT] = h[0];
            }
        } catch (Exception ignored) {          // no file yet: no records yet
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) { }
        }
        long[] st = streakOf(perDay, day);
        b[STREAK] = st[0];
        b[STREAK_BEST] = st[1];
        return b;
    }

    /**
     * The streak (asked for on 2026-09-14): how many days in an unbroken run ending today
     * reached {@link #GOAL_MS}, and the longest such run there has ever been — from the same
     * per-day totals as everything else, so emptying sessions.csv resets it too.
     *
     * A day counts only once its total is in, but a day that is not over yet cannot break the
     * run either: with nothing held today the count is still yesterday's, and it falls to 0
     * only when a whole day has gone by without the goal. A hold in progress is not in
     * sessions.csv yet — {@link #withLive} folds it in.
     */
    static long[] streakOf(Map<String, Long> perDay, String today) {
        TreeSet<LocalDate> hit = new TreeSet<>();
        for (Map.Entry<String, Long> e : perDay.entrySet())
            if (e.getValue() >= GOAL_MS)
                try { hit.add(LocalDate.parse(e.getKey())); } catch (Exception ignored) { }
        long best = 0, run = 0;
        LocalDate prev = null;
        for (LocalDate d : hit) {                       // a TreeSet walks in date order
            run = prev != null && prev.plusDays(1).equals(d) ? run + 1 : 1;
            prev = d;
            best = Math.max(best, run);
        }
        long cur = 0;
        try {
            LocalDate t = LocalDate.parse(today);
            for (LocalDate c = hit.contains(t) ? t : t.minusDays(1);
                 hit.contains(c); c = c.minusDays(1)) cur++;
        } catch (Exception ignored) { }
        return new long[]{cur, best};
    }

    /**
     * The same numbers with a hold that is still running folded in: it is what the bar is
     * about, and it can be the very thing that puts today over the goal — and so extends the
     * streak while the user is still upside down.
     */
    static void withLive(long[] b, long liveMs) {
        if (liveMs <= 0) return;
        boolean had = b[TOTAL] >= GOAL_MS;
        b[TOTAL] += liveMs;
        b[BEST_DAY] = Math.max(b[BEST_DAY], liveMs);
        b[BEST_MONTH] = Math.max(b[BEST_MONTH], b[BEST_DAY]);
        b[BEST_ALL] = Math.max(b[BEST_ALL], b[BEST_DAY]);
        if (!had && b[TOTAL] >= GOAL_MS) {              // today has just joined the run
            b[STREAK]++;
            b[STREAK_BEST] = Math.max(b[STREAK_BEST], b[STREAK]);
        }
    }

    // ---- beeps ----------------------------------------------------------------

    /** one tone, delay ms from now; the generator lives on the sensor thread */
    private void beep(final int type, long delay) {
        if (bg == null || !prefs().getBoolean("beep", false)) return;
        bg.postDelayed(new Runnable() { public void run() {
            if (sm == null) return;      // stopped meanwhile: do not open a generator to leak it
            try {
                if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOL);
                tone.startTone(type, TONE_MS);
            } catch (Exception ex) {     // a tone generator is a scarce global resource
                Log.w("Handstand", "tone", ex);
                tone = null;
            }
        } }, delay);
    }

    /** n ascending tones, starting after the end beep has had its turn */
    private void fanfare(int n) {
        for (int i = 0; i < n && i < FANFARE.length; i++)
            beep(FANFARE[i], TONE_GAP * (i + 2L));
    }

    /** one tone every 10 s, two on each half minute and three on each full minute */
    private void tick(long elapsed) {
        int n = elapsed % 60000 == 0 ? 3 : elapsed % 30000 == 0 ? 2 : 1;
        for (int i = 0; i < n; i++) beep(TONE_TICK, TONE_GAP * i);
    }

    /** GOAL_TUNE, starting once the afterTones of a record fanfare (0 if none) have played */
    private void goalFanfare(int afterTones) {
        long start = TONE_GAP * (afterTones + 2L);
        for (int i = 0; i < GOAL_TUNE.length; i++)
            beep(GOAL_TUNE[i], start + TONE_GAP * i);
    }

    private void silence() {
        if (tone != null) tone.release();
        tone = null;
    }

    // ---- files ---------------------------------------------------------------

    private File dir() {
        File d = getExternalFilesDir(null);
        return d != null ? d : getFilesDir();
    }

    private void write(String line) {
        try {
            long now = SystemClock.elapsedRealtime();
            // dayOf() builds a formatter, so ask for the date about once a minute, not 120x/s
            if (out != null && now - lastDayCheck < 60000) {
                out.write(line);
                if (now - lastFlush > FLUSH_MS) { lastFlush = now; out.flush(); }
                return;
            }
            lastDayCheck = now;
            String d = dayOf(System.currentTimeMillis());
            if (out == null || !d.equals(day)) {
                close();
                day = d;
                // A new file per start and per day, never an append to an old one: a killed
                // process leaves a gzip member without its trailer, and anything appended
                // after that is unreadable for gunzip.
                long wall = System.currentTimeMillis();
                File f = new File(dir(), "sensors-" + stamp(wall).replace(":", "").replace(' ', '-') + ".csv.gz");
                out = new OutputStreamWriter(
                        new GZIPOutputStream(new FileOutputStream(f, true), 16384, true), "UTF-8");
                out.write("#base," + bootWall + "," + stamp(wall) + "\n");
            }
            out.write(line);
            if (now - lastFlush > FLUSH_MS) { lastFlush = now; out.flush(); }
        } catch (Exception ex) {
            Log.w("Handstand", "write", ex);
        }
    }

    private void flush() {
        try { if (out != null) { out.flush(); lastFlush = SystemClock.elapsedRealtime(); } }
        catch (Exception ex) { Log.w("Handstand", "flush", ex); }
    }

    private void close() {
        try { if (out != null) out.close(); } catch (Exception ignored) { }
        out = null;
    }

    private void append(String name, String line) {
        synchronized (SESSION_LOCK) {
            try {
                FileOutputStream f = new FileOutputStream(new File(dir(), name), true);
                f.write(line.getBytes("UTF-8"));
                f.close();
            } catch (Exception ex) {
                Log.w("Handstand", "append", ex);
            }
        }
    }

    /**
     * Remove exactly one detected hold from sessions.csv. Sensor logs are deliberately untouched:
     * deleting a false detection must not delete the measurements needed for later analysis.
     */
    static boolean deleteSession(File dir, String target) {
        synchronized (SESSION_LOCK) {
            File src = new File(dir, "sessions.csv");
            File tmp = new File(dir, "sessions.csv.tmp");
            BufferedReader r = null;
            FileOutputStream out = null;
            boolean removed = false;
            try {
                r = new BufferedReader(new FileReader(src));
                out = new FileOutputStream(tmp);
                for (String l = r.readLine(); l != null; l = r.readLine()) {
                    if (!removed && l.equals(target)) { removed = true; continue; }
                    out.write((l + "\n").getBytes("UTF-8"));
                }
                r.close(); r = null;
                out.getFD().sync();
                out.close(); out = null;
                if (!removed) { tmp.delete(); return false; }
                try {
                    java.nio.file.Files.move(tmp.toPath(), src.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                    java.nio.file.Files.move(tmp.toPath(), src.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return true;
            } catch (Exception ex) {
                Log.w("Handstand", "delete session", ex);
                tmp.delete();
                return false;
            } finally {
                try { if (r != null) r.close(); } catch (Exception ignored) { }
                try { if (out != null) out.close(); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * Drop logs older than KEEP_DAYS so this can be left on for good — except the ones holding
     * the samples of a hold the screen draws (asked for on 2026-09-15). The all-time record can
     * be any age, and sweeping its log away left the chart drawing it as a bare "no log" bar,
     * with no way to ever look at its curves again. {@link #windows} says which holds those are;
     * everything else still goes after three weeks.
     */
    private void sweep() { sweep(dir(), System.currentTimeMillis()); }

    /** the same with the directory and "now" handed in, so it can be tried on a desktop JVM */
    static void sweep(File dir, long now) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        List<File> logs = new ArrayList<>();
        for (File f : fs) if (f.getName().startsWith("sensors-")) logs.add(f);
        Collections.sort(logs);                 // File compares by path, and the names sort by time
        long cut = now - KEEP_DAYS * 86400000L;
        long[][] win = windows(dir, now);
        for (int i = 0; i < logs.size(); i++) {
            File f = logs.get(i);
            if (f.lastModified() >= cut) continue;
            // a log covers from the wall clock in its name to the name of the next one
            long a = nameTime(f.getName());
            long b = i + 1 < logs.size() ? nameTime(logs.get(i + 1).getName()) : Long.MAX_VALUE;
            boolean keep = false;
            for (long[] w : win) if (a <= w[1] && b >= w[0]) { keep = true; break; }
            if (!keep) f.delete();
        }
    }

    /**
     * The spans of raw data the chart in {@link Main} needs: the last {@link Main#SHOWN} holds
     * and the best of today, of this month and of all time, each with {@link Main#PAD} ms of
     * context on either side. Out of sessions.csv, the same file the chart reads, so the two
     * can never disagree about which log has to stay.
     */
    static long[][] windows(File dir, long now) {
        List<long[]> all = new ArrayList<>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(new File(dir, "sessions.csv")));
            for (String l = r.readLine(); l != null; l = r.readLine()) {
                long[] h = holdOf(l);
                if (h != null) all.add(h);
            }
        } catch (Exception ignored) {          // no file yet: nothing to protect
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) { }
        }
        List<long[]> keep = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && i >= all.size() - Main.SHOWN; i--) keep.add(all.get(i));
        String today = scoreDay(now), month = today.substring(0, 7);
        long[] bestDay = null, bestMonth = null, bestAll = null;
        for (long[] h : all) {                 // the newer one on a tie, as in Main.better
            String d = scoreDay(h[0]);
            if (d.equals(today) && (bestDay == null || h[1] >= bestDay[1])) bestDay = h;
            if (d.startsWith(month) && (bestMonth == null || h[1] >= bestMonth[1])) bestMonth = h;
            if (bestAll == null || h[1] >= bestAll[1]) bestAll = h;
        }
        // arrays compare by identity, so a hold that is already among the last five stays once
        for (long[] h : new long[][]{bestDay, bestMonth, bestAll})
            if (h != null && !keep.contains(h)) keep.add(h);
        long[][] w = new long[keep.size()][];
        for (int i = 0; i < w.length; i++) {
            long[] h = keep.get(i);
            w[i] = new long[]{h[0] - Main.PAD, h[0] + h[1] + Main.PAD};
        }
        return w;
    }

    /** one line of sessions.csv as {start wall clock, duration ms}, null if it is not one */
    static long[] holdOf(String l) {
        int c = l.indexOf(',');
        if (c < 19) return null;
        try {
            String t = l.substring(0, c);
            int dot = t.indexOf('.');
            // ".SSS" only since 2026-09-14; parse() stops at the dot, so add it by hand
            long start = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .parse(dot > 0 ? t.substring(0, dot) : t).getTime();
            if (dot > 0) start += Math.round(Double.parseDouble(t.substring(dot)) * 1000);
            return new long[]{start, (long) (Double.parseDouble(l.substring(c + 1)) * 1000)};
        } catch (Exception ex) { return null; }
    }

    /**
     * When a log starts, from its name. Its mtime is no use for that: while the file is still
     * open the media provider does not refresh it, so the newest log looks older than it is.
     */
    static long nameTime(String n) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)
                    .parse(n.substring(8, 25)).getTime();
        } catch (Exception ex) { return 0; }
    }

    static String dayOf(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms));
    }

    /** Score/date bucket whose midnight is 04:00 local time. */
    static String scoreDay(long ms) {
        java.time.ZonedDateTime z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault());
        LocalDate d = z.toLocalDate();
        return (z.getHour() < 4 ? d.minusDays(1) : d).toString();
    }

    static String stamp(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
    }

    /** the same, to the millisecond — what a hold in sessions.csv is stamped with */
    static String stampMs(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(ms));
    }

    // ---- notification --------------------------------------------------------

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private Notification note(String title) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(
                    new NotificationChannel(CH, "Recording", NotificationManager.IMPORTANCE_LOW));
        SharedPreferences p = prefs();
        String sub = "today " + p.getInt("count", 0) + " · best "
                + Main.mmss(p.getLong("best", 0));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, Main.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(title).setContentText(sub)
                .setContentIntent(open).setOngoing(true).build();
    }

    private void notify(String title) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1, note(title));
    }

    static void start(Context c) {
        // Without it the health service cannot go foreground, and a service started with
        // startForegroundService that never does crashes the app even after stopSelf.
        if (c.checkSelfPermission("android.permission.ACTIVITY_RECOGNITION") != 0) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", false).apply();
            return;
        }
        c.startForegroundService(new Intent(c, Rec.class));
    }

    static void stop(Context c) {
        c.startService(new Intent(c, Rec.class).setAction(ACTION_STOP));
    }
}
