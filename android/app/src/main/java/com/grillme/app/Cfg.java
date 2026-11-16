package com.grillme.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 配置项名称刻意和电脑端 config.json 保持一致，方便以后两端互相同步设置、
 * 也方便用户在两台上理解同一套概念。
 */
public final class Cfg {

    public static final String PREF = "grill";

    // 节奏
    public static final String ENABLED = "enabled";
    public static final String MIN_MINUTES = "min_minutes";
    public static final String MAX_MINUTES = "max_minutes";
    public static final String FIRST_MINUTES = "first_minutes";

    // 锁屏与离屏
    public static final String WATCH_OFF_SCREEN = "watch_off_screen";
    public static final String OFF_SCREEN_GRACE = "off_screen_grace_minutes";
    public static final String ALERT_WHEN_OFF_SCREEN = "alert_when_off_screen";

    // 放行规则
    public static final String MIN_CHARS = "min_chars";
    public static final String ALLOW_REPEAT = "allow_repeat";
    public static final String BLOCK_PASTE = "block_paste";
    public static final String COOLDOWN = "cooldown_seconds";
    public static final String REPEAT_WARN = "repeat_warn";
    public static final String SHOW_SLIP = "show_moyu";

    // 提醒方式
    public static final String SOUND = "sound";
    public static final String VIBRATE = "vibrate";
    public static final String VIBRATE_PATTERN = "vibrate_pattern";

    // 免打扰
    public static final String DND_ENABLED = "dnd_enabled";
    public static final String DND_START = "dnd_start";
    public static final String DND_END = "dnd_end";

    // 长时间使用
    public static final String LONG_ALERT = "long_session_alert";
    public static final String LONG_MINUTES = "long_session_minutes";

    // 统计
    public static final String TRACK_APP = "track_app";
    public static final String TRACK_TITLES = "track_titles";

    // 截图
    public static final String SHOT_ENABLED = "shot_enabled";
    public static final String SHOT_WIDTH = "shot_width";
    public static final String SHOT_QUALITY = "shot_quality";
    public static final String SHOT_KEEP = "shot_keep_days";

    // 语言
    public static final String LANG = "lang";

    // 同步
    public static final String SYNC_ENABLED = "sync_enabled";
    public static final String SYNC_URL = "sync_url";
    public static final String SYNC_USER = "sync_user";
    public static final String SYNC_PASS = "sync_pass";
    public static final String SYNC_DEVICE = "sync_device_id";

    // 运行态
    public static final String PAUSED_UNTIL = "paused_until";
    public static final String NEXT_DUE = "next_due";
    public static final String SINCE_LAST = "since_last";

    private static Cfg inst;
    private final SharedPreferences sp;

    private Cfg(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static synchronized Cfg get(Context c) {
        if (inst == null) {
            inst = new Cfg(c);
        }
        return inst;
    }

    public SharedPreferences raw() {
        return sp;
    }

    public int i(String k, int def) {
        return sp.getInt(k, def);
    }

    public void setI(String k, int v) {
        sp.edit().putInt(k, v).apply();
    }

    public boolean b(String k, int def) {
        return sp.getInt(k, def) != 0;
    }

    public void setB(String k, boolean v) {
        sp.edit().putInt(k, v ? 1 : 0).apply();
    }

    public String s(String k, String def) {
        String v = sp.getString(k, def);
        return v == null ? def : v;
    }

    public void setS(String k, String v) {
        sp.edit().putString(k, v).apply();
    }

    public float f(String k, float def) {
        return sp.getFloat(k, def);
    }

    public void setF(String k, float v) {
        sp.edit().putFloat(k, v).apply();
    }

    public long l(String k, long def) {
        return sp.getLong(k, def);
    }

    public void setL(String k, long v) {
        sp.edit().putLong(k, v).apply();
    }

    // ------------------------------------------------------------ 具名读写

    public boolean enabled() {
        return b(ENABLED, 0);
    }

    public void setEnabled(boolean v) {
        setB(ENABLED, v);
    }

    public int minMinutes() {
        return Math.max(1, i(MIN_MINUTES, 40));
    }

    public int maxMinutes() {
        return Math.max(minMinutes(), i(MAX_MINUTES, 90));
    }

    public int firstMinutes() {
        return Math.max(1, i(FIRST_MINUTES, 25));
    }

    public boolean watchOffScreen() {
        return b(WATCH_OFF_SCREEN, 0);
    }

    public int offScreenGrace() {
        return Math.max(0, i(OFF_SCREEN_GRACE, 10));
    }

    public boolean alertWhenOffScreen() {
        return b(ALERT_WHEN_OFF_SCREEN, 1);
    }

    public int minChars() {
        return Math.max(1, i(MIN_CHARS, 2));
    }

    public boolean allowRepeat() {
        return b(ALLOW_REPEAT, 1);
    }

    public boolean blockPaste() {
        return b(BLOCK_PASTE, 1);
    }

    public float cooldown() {
        float v = f(COOLDOWN, 3.0f);
        if (v < 0f) {
            v = 0f;
        }
        return v;
    }

    public int repeatWarn() {
        return Math.max(0, i(REPEAT_WARN, 2));
    }

    /** 「我承认在摸鱼」这个出口要不要给。键名跟电脑端共用 show_moyu。 */
    public boolean showSlip() {
        return b(SHOW_SLIP, 1);
    }

    public boolean sound() {
        return b(SOUND, 1);
    }

    public boolean vibrate() {
        return b(VIBRATE, 1);
    }

    public String vibratePattern() {
        return s(VIBRATE_PATTERN, "double");
    }

    public boolean dndEnabled() {
        return b(DND_ENABLED, 1);
    }

    public String dndStart() {
        return s(DND_START, "22:00");
    }

    public String dndEnd() {
        return s(DND_END, "08:00");
    }

    public boolean longAlert() {
        return b(LONG_ALERT, 0);
    }

    public int longMinutes() {
        return Math.max(1, i(LONG_MINUTES, 180));
    }

    public boolean trackApp() {
        return b(TRACK_APP, 1);
    }

    public boolean trackTitles() {
        return b(TRACK_TITLES, 1);
    }

    public boolean shotEnabled() {
        return b(SHOT_ENABLED, 1);
    }

    // 下面几个的上下限只是"存进来别把程序搞崩"，不再按推荐值夹断。
    // 推荐区间在设置页里单独提示，用户想填大就让他填——夹断了他还以为自己填错了。
    public int shotWidth() {
        return Math.max(1, Math.min(10000, i(SHOT_WIDTH, 800)));
    }

    public int shotQuality() {
        // JPEG 规范就是 1–100，超出这个范围不是"不合适"，是编码器直接失败
        return Math.max(1, Math.min(100, i(SHOT_QUALITY, 60)));
    }

    public int shotKeepDays() {
        return Math.max(0, i(SHOT_KEEP, 30));
    }

    public String lang() {
        return s(LANG, "system");
    }

    public void setLang(String v) {
        setS(LANG, v);
    }

    public boolean syncEnabled() {
        return b(SYNC_ENABLED, 0);
    }

    public String syncUrl() {
        return s(SYNC_URL, "");
    }

    public String syncUser() {
        return s(SYNC_USER, "");
    }

    public String syncPass() {
        return s(SYNC_PASS, "");
    }

    public String syncDevice() {
        String d = s(SYNC_DEVICE, "android");
        return d.length() == 0 ? "android" : d;
    }
}
