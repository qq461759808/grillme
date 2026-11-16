package com.grillme.app;

import android.app.KeyguardManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 核心循环。每秒一次，逻辑和电脑端 grill.py 的 _tick_once 保持一致：
 * 屏幕亮着就算在用；离屏后看开关与宽限期；到点弹窗，答完才放行。
 */
public class GrillService extends Service {

    public static final String ACTION_START = "com.grillme.app.START";
    public static final String ACTION_STOP = "com.grillme.app.STOP";
    public static final String ACTION_SHOT = "com.grillme.app.SHOT";
    private static final String EXTRA_CODE = "code";
    private static final String EXTRA_DATA = "data";
    private static final String EXTRA_RESET = "reset";

    private static final String K_ACTIVE = "active_today";
    private static final String K_COUNT = "day_count";
    private static final String K_DAY = "day";
    private static final String K_LAST_ANSWER = "last_answer";

    private Cfg cfg;
    private Store store;
    private Handler ui;
    private Random rnd = new Random();

    private GrillOverlay overlay;
    private long sessionStart;
    private boolean longAlerted;
    private long offScreenSince;
    private boolean pending;
    private long lastRingAt;
    private long lastSyncAt;
    private boolean projectionType;
    private String pendingShot = "";

    private String day;
    private int active;
    private int sinceLast;
    private int dayCount;
    private int nextDue;

    private int tickCount;
    private int sampleTick;
    private String curApp = "";
    private String curLabel = "";
    private final Map<String, Integer> buffer = new HashMap<>();
    private long lastBufferFlush;
    private long lastStateSave;
    private long lastNotifAt;

    private PowerManager pm;
    private KeyguardManager km;
    private PowerManager.WakeLock wake;

    private final BroadcastReceiver screenRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(a) || Intent.ACTION_USER_PRESENT.equals(a)) {
                ui.removeCallbacksAndMessages(null);
                ui.post(tick);
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                tickOnce();
            } catch (Exception ignored) {
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        cfg = Cfg.get(this);
        store = Store.get(this);
        Lang.init(this);
        Lang.setLang(this, cfg.lang());
        ui = new Handler(Looper.getMainLooper());
        pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

        day = cfg.s(K_DAY, Store.today());
        active = cfg.i(K_ACTIVE, 0);
        dayCount = cfg.i(K_COUNT, 0);
        sinceLast = cfg.i(Cfg.SINCE_LAST, 0);
        nextDue = cfg.i(Cfg.NEXT_DUE, cfg.firstMinutes() * 60);
        android.util.Log.i("grillme", "onCreate day=" + day + " active=" + active
                + " since=" + sinceLast + " nextDue=" + nextDue
                + " first=" + cfg.firstMinutes() + " enabled=" + cfg.enabled());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenRx, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenRx, f);
        }

        Notifier.ensureChannel(this);
        startForegroundSafely(Lang.t("notif_text", cfg.minMinutes()));

        try {
            Shots.cleanup(this, cfg.shotKeepDays());
        } catch (Exception ignored) {
        }

        ui.post(tick);
        ui.postDelayed(syncTick, 20000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_SHOT.equals(intent.getAction())) {
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data = null;
            try {
                data = (Intent) intent.getParcelableExtra(EXTRA_DATA);
            } catch (Exception ignored) {
            }
            projectionType = true;
            startForegroundTyped(Lang.t("notif_text", cfg.minMinutes()), true);
            boolean ok = Shots.get().start(this, code, data);
            if (!ok) {
                projectionType = false;
                startForegroundTyped(Lang.t("notif_text", cfg.minMinutes()), false);
            }
            return START_STICKY;
        }
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            // 只有"从关闭切到开启"才重新计时。MainActivity 每次 onResume 都会来一次
            // ACTION_START，如果这里无条件重置，用户每开一次 App 倒计时就归零，
            // 频繁看首页的人永远等不到质问。
            boolean reset = intent.getBooleanExtra(EXTRA_RESET, false) || !cfg.enabled();
            cfg.setEnabled(true);
            if (reset) {
                sinceLast = 0;
                nextDue = cfg.firstMinutes() * 60;
                pending = false;
                saveState();
            }
        }
        return START_STICKY;
    }

    private void startForegroundSafely(String text) {
        startForegroundTyped(text, projectionType);
    }

    /** 带 mediaProjection 类型启动前台服务：Android 14 要求先有这一步，才能拿投屏令牌。 */
    private void startForegroundTyped(String text, boolean withProjection) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                int types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                if (withProjection) {
                    types |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                }
                startForeground(Notifier.ID_FOREGROUND, Notifier.build(this, text), types);
            } else if (Build.VERSION.SDK_INT >= 29 && withProjection) {
                startForeground(Notifier.ID_FOREGROUND, Notifier.build(this, text),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(Notifier.ID_FOREGROUND, Notifier.build(this, text));
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(screenRx);
        } catch (Exception ignored) {
        }
        if (overlay != null) {
            overlay.dismiss();
        }
        flushUsage();
        saveState();
        releaseWake();
        try {
            Shots.get().stop();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------ 每秒

    /**
     * 抓帧管道只在"快要弹窗"时才接上，其余时间断开。
     * 一直连着的话，屏幕每变一下系统都要多合成一份副本，带点动画的 App 就能把
     * SurfaceFlinger 吃到 80%；断开后合成端直接停，实测降到 0。
     */
    private void syncShotPipeline() {
        Shots s = Shots.get();
        if (!s.ready()) {
            return;
        }
        boolean nearDue = pending || (nextDue - sinceLast) <= 20;
        boolean keep = cfg.enabled() && cfg.shotEnabled() && overlay == null
                && cfg.l(Cfg.PAUSED_UNTIL, 0) <= System.currentTimeMillis()
                && nearDue;
        if (keep) {
            s.resume();
        } else {
            s.pause();
        }
    }

    private void tickOnce() {
        syncShotPipeline();
        long now = System.currentTimeMillis();
        String today = Store.today();
        if (!today.equals(day)) {
            day = today;
            active = 0;
            sinceLast = 0;
            dayCount = 0;
            longAlerted = false;
            nextDue = cfg.firstMinutes() * 60;
            flushUsage();
            saveState();
        }

        long pausedUntil = cfg.l(Cfg.PAUSED_UNTIL, 0);
        if (pausedUntil > 0) {
            if (now < pausedUntil) {
                releaseWake();
                if (overlay != null) {
                    overlay.dismiss();
                    overlay = null;
                }
                return;
            }
            cfg.setL(Cfg.PAUSED_UNTIL, 0);
        }

        if (!cfg.enabled()) {
            releaseWake();
            if (overlay != null) {
                overlay.dismiss();
                overlay = null;
            }
            return;
        }

        boolean screenOn = pm != null && pm.isInteractive();
        boolean locked = km != null && km.isKeyguardLocked();

        if (!screenOn) {
            handleOffScreen(now);
            return;
        }
        offScreenSince = 0;
        if (locked && !cfg.watchOffScreen()) {
            sessionStart = 0;
            longAlerted = false;
            releaseWake();
            return;
        }

        // ---- 在用
        if (sessionStart == 0) {
            sessionStart = now;
            longAlerted = false;
        }
        active++;
        sinceLast++;

        if (cfg.trackApp()) {
            if (sampleTick % 2 == 0) {
                sampleForeground(now);
            }
            sampleTick++;
            addUsage(curLabel, 1);
        }

        if (overlay != null && overlay.isShowing()) {
            maybeUpdateNotif(now);
            return;
        }
        if (!canPopup()) {
            maybeUpdateNotif(now);
            return;
        }

        if (pending || sinceLast >= nextDue) {
            boolean wasPending = pending;
            pending = false;
            ask(wasPending ? "back" : "normal");
        } else if (cfg.longAlert() && !longAlerted
                && (now - sessionStart) / 60000 >= cfg.longMinutes()) {
            longAlerted = true;
            ask("rest");
        }
        maybeUpdateNotif(now);
        saveEvery(now);
    }

    private void handleOffScreen(long now) {
        if (!cfg.watchOffScreen()) {
            offScreenSince = 0;
            sessionStart = 0;
            longAlerted = false;
            releaseWake();
            return;
        }
        if (offScreenSince == 0) {
            offScreenSince = now;
        }
        long graceMs = cfg.offScreenGrace() * 60000L;
        if (now - offScreenSince >= graceMs) {
            sessionStart = 0;
            longAlerted = false;
            releaseWake();
            return;
        }
        // 宽限期内仍然算在用：短暂黑屏不算你走了
        holdWake();
        active++;
        sinceLast++;
        if (sinceLast >= nextDue) {
            pending = true;
            if (cfg.alertWhenOffScreen() && now - lastRingAt > 60000) {
                lastRingAt = now;
                Notifier.alert(this, cfg);
            }
        }
        saveEvery(now);
    }

    private boolean canPopup() {
        if (!Settings.canDrawOverlays(this)) {
            return false;
        }
        if (cfg.dndEnabled() && inDnd()) {
            return false;
        }
        return true;
    }

    private boolean inDnd() {
        int[] s = parseHm(cfg.dndStart(), 22, 0);
        int[] e = parseHm(cfg.dndEnd(), 8, 0);
        Calendar c = Calendar.getInstance();
        int cur = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int a = s[0] * 60 + s[1];
        int b = e[0] * 60 + e[1];
        if (a == b) {
            return false;
        }
        if (a < b) {
            return cur >= a && cur < b;
        }
        return cur >= a || cur < b;
    }

    private static int[] parseHm(String s, int dh, int dm) {
        try {
            String t = s.replace("：", ":").trim();
            String[] parts = t.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            if (h >= 0 && h < 24 && m >= 0 && m < 60) {
                return new int[]{h, m};
            }
        } catch (Exception ignored) {
        }
        return new int[]{dh, dm};
    }

    // ------------------------------------------------------------ 弹窗

    private void ask(String kind) {
        if (!Settings.canDrawOverlays(this)) {
            return;
        }
        GrillOverlay.Ask a = new GrillOverlay.Ask();
        a.kind = kind;
        a.appLabel = curLabel;
        if ("long".equals(kind)) {
            a.longMinutes = (int) ((System.currentTimeMillis() - sessionStart) / 60000);
        }
        if ("rest".equals(kind)) {
            a.restTip = pickTip();
        }
        pendingShot = "";
        if (cfg.shotEnabled() && Shots.get().ready()) {
            try {
                Shots.get().resume();   // 管道平时是断开的，抓之前先接上
                android.graphics.Bitmap b = Shots.get().grab();
                if (b != null) {
                    pendingShot = Shots.save(this, b, "shot-" + Long.toHexString(
                            System.currentTimeMillis()), cfg.shotWidth(), cfg.shotQuality());
                }
            } catch (Exception ignored) {
            }
        }
        if (overlay != null) {
            overlay.dismiss();
        }
        overlay = new GrillOverlay(this, cfg, store, a, new GrillOverlay.Listener() {
            @Override
            public void onAnswer(String text, String kind, boolean slip) {
                record(text, kind);
            }
        });
        overlay.show();
        if (overlay.isShowing()) {
            Notifier.alert(this, cfg);
            flushUsage();
        }
    }

    private int lastTip = -1;

    private String pickTip() {
        int i = rnd.nextInt(14) + 1;
        if (i == lastTip) {
            i = i % 14 + 1;
        }
        lastTip = i;
        return Lang.t("rest." + i);
    }

    private void record(String text, String kind) {
        long now = System.currentTimeMillis();
        dayCount++;
        Store.Rec r = new Store.Rec();
        r.id = "an-" + Long.toHexString(now) + "-" + Integer.toHexString(rnd.nextInt(0xFFFF));
        r.ts = now / 1000.0;
        r.day = Store.today();
        r.time = Store.hhmm(now);
        r.answer = text;
        r.kind = kind;
        r.active = active;
        r.span = sinceLast;
        r.n = dayCount;
        r.delay = 0;
        r.shot = pendingShot;
        pendingShot = "";
        r.src = "android";
        store.addRecord(r);

        cfg.setS(K_LAST_ANSWER, text);
        sinceLast = 0;
        rollNext();
        saveState();
        if (cfg.syncEnabled() && now - lastSyncAt > 300000) {
            lastSyncAt = now;
            ui.post(syncTick);
        }
    }

    private void rollNext() {
        int lo = cfg.minMinutes() * 60;
        int hi = cfg.maxMinutes() * 60;
        if (hi <= lo) {
            nextDue = lo;
        } else {
            nextDue = lo + rnd.nextInt(hi - lo + 1);
        }
    }

    // ------------------------------------------------------------ 前台应用

    private void sampleForeground(long now) {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return;
            }
            long begin = now - 60000;
            UsageEvents ev = usm.queryEvents(begin, now);
            String pkg = null;
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    pkg = e.getPackageName();
                }
            }
            if (pkg == null || pkg.equals(getPackageName())) {
                return;
            }
            if (!pkg.equals(curApp)) {
                curApp = pkg;
                curLabel = labelOf(pkg);
            }
        } catch (Exception ignored) {
        }
    }

    private String labelOf(String pkg) {
        try {
            PackageManager pmg = getPackageManager();
            ApplicationInfo ai = pmg.getApplicationInfo(pkg, 0);
            CharSequence cs = pmg.getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) {
                return cs.toString();
            }
        } catch (Exception ignored) {
        }
        int i = pkg.lastIndexOf('.');
        return i > 0 ? pkg.substring(i + 1) : pkg;
    }

    private void addUsage(String label, int sec) {
        if (!cfg.trackApp() || label == null || label.length() == 0) {
            return;
        }
        String key = Store.today() + "\u0001" + label;
        Integer old = buffer.get(key);
        buffer.put(key, (old == null ? 0 : old) + sec);
        long now = System.currentTimeMillis();
        if (now - lastBufferFlush > 30000) {
            flushUsage();
        }
    }

    private void flushUsage() {
        if (buffer.isEmpty()) {
            return;
        }
        Map<String, Integer> copy = new HashMap<>(buffer);
        buffer.clear();
        lastBufferFlush = System.currentTimeMillis();
        for (Map.Entry<String, Integer> e : copy.entrySet()) {
            String[] parts = e.getKey().split("\u0001");
            if (parts.length == 2) {
                try {
                    store.addUsage(parts[0], parts[1], "", e.getValue());
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------ 杂项

    private void maybeUpdateNotif(long now) {
        if (now - lastNotifAt < 30000) {
            return;
        }
        lastNotifAt = now;
        int left = Math.max(0, nextDue - sinceLast) / 60;
        Notifier.updateForeground(this, Lang.t("notif_text", left));
    }

    private void saveEvery(long now) {
        if (now - lastStateSave > 5000) {
            saveState();
            flushUsage();
        }
    }

    private void saveState() {
        lastStateSave = System.currentTimeMillis();
        cfg.setI(K_ACTIVE, active);
        cfg.setI(K_COUNT, dayCount);
        cfg.setS(K_DAY, day);
        cfg.setI(Cfg.SINCE_LAST, sinceLast);
        cfg.setI(Cfg.NEXT_DUE, nextDue);
    }

    private void holdWake() {
        try {
            if (wake == null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "grill:offscreen");
                wake.setReferenceCounted(false);
            }
            if (!wake.isHeld()) {
                wake.acquire(10 * 60000L);
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseWake() {
        try {
            if (wake != null && wake.isHeld()) {
                wake.release();
            }
        } catch (Exception ignored) {
        }
    }

    private final Runnable syncTick = new Runnable() {
        @Override
        public void run() {
            if (!cfg.syncEnabled()) {
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Sync.run(GrillService.this, cfg);
                    } catch (Exception ignored) {
                    }
                }
            }, "grill-sync").start();
        }
    };

    // ------------------------------------------------------------ 静态工具

    public static void start(Context c) {
        start(c, false);
    }

    /** 用户主动点「开始监督」时用：把计时清零重新开始。 */
    public static void startFresh(Context c) {
        start(c, true);
    }

    private static void start(Context c, boolean reset) {
        Intent i = new Intent(c, GrillService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_RESET, reset);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception ignored) {
        }
    }

    /** 把系统的投屏授权结果交给服务，由服务去建立抓帧管道。 */
    public static void startWithProjection(Context c, int code, Intent data) {
        Intent i = new Intent(c, GrillService.class);
        i.setAction(ACTION_SHOT);
        i.putExtra(EXTRA_CODE, code);
        i.putExtra(EXTRA_DATA, data);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception ignored) {
        }
    }

    public static void stop(Context c) {
        Intent i = new Intent(c, GrillService.class);
        i.setAction(ACTION_STOP);
        try {
            c.startService(i);
        } catch (Exception ignored) {
        }
        Cfg.get(c).setEnabled(false);
    }

    public static boolean isRunning(Context c) {
        return Cfg.get(c).enabled();
    }

    public static int pausedLeftMinutes(Context c) {
        long until = Cfg.get(c).l(Cfg.PAUSED_UNTIL, 0);
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            return 0;
        }
        return (int) (left / 60000) + 1;
    }

    public static int secondsLeft(Context c) {
        Cfg cfg = Cfg.get(c);
        int next = cfg.i(Cfg.NEXT_DUE, cfg.firstMinutes() * 60);
        int since = cfg.i(Cfg.SINCE_LAST, 0);
        return Math.max(0, next - since);
    }

    public static String todayActive(Context c) {
        int sec = Cfg.get(c).i(K_ACTIVE, 0);
        return fmtDuration(sec);
    }

    public static String fmtDuration(int sec) {
        if (sec < 60) {
            return sec + " " + Lang.t("unit_sec");
        }
        if (sec < 3600) {
            return (sec / 60) + " " + Lang.t("unit_min");
        }
        int h = sec / 3600;
        int m = (sec % 3600) / 60;
        return h + " " + Lang.t("unit_hour") + " " + m + " " + Lang.t("unit_min");
    }

    public static String lastAnswer(Context c) {
        return Cfg.get(c).s(K_LAST_ANSWER, "");
    }

    public static String clock(int sec) {
        return String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
    }
}
