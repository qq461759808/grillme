package com.grillme.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 记录与使用时长。表结构刻意和电脑端 JSONL 的字段一一对应，同步时直接按 id 求并集。 */
public class Store extends SQLiteOpenHelper {

    private static final String DB = "grill.db";
    private static Store inst;

    public static synchronized Store get(Context c) {
        if (inst == null) {
            inst = new Store(c.getApplicationContext());
        }
        return inst;
    }

    private Store(Context c) {
        super(c, DB, null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE records ("
                + "id TEXT PRIMARY KEY,"
                + "ts REAL,"
                + "day TEXT,"
                + "time TEXT,"
                + "answer TEXT,"
                + "kind TEXT,"
                + "active INTEGER,"
                + "span INTEGER,"
                + "n INTEGER,"
                + "delay INTEGER,"
                + "shot TEXT,"
                + "src TEXT)");
        db.execSQL("CREATE INDEX idx_rec_ts ON records(ts)");
        db.execSQL("CREATE INDEX idx_rec_day ON records(day)");
        db.execSQL("CREATE TABLE usage ("
                + "day TEXT,"
                + "app TEXT,"
                + "title TEXT,"
                + "sec INTEGER,"
                + "PRIMARY KEY(day, app, title))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // 第一版，没有历史数据要迁移
    }

    // ------------------------------------------------------------ 记录

    public static class Rec {
        public String id = "";
        public double ts;
        public String day = "";
        public String time = "";
        public String answer = "";
        public String kind = "normal";
        public int active;
        public int span;
        public int n;
        public int delay;
        public String shot = "";
        public String src = "android";
    }

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    public static String hhmm(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(millis));
    }

    public void addRecord(Rec r) {
        ContentValues v = new ContentValues();
        v.put("id", r.id);
        v.put("ts", r.ts);
        v.put("day", r.day);
        v.put("time", r.time);
        v.put("answer", r.answer);
        v.put("kind", r.kind);
        v.put("active", r.active);
        v.put("span", r.span);
        v.put("n", r.n);
        v.put("delay", r.delay);
        v.put("shot", r.shot);
        v.put("src", r.src);
        getWritableDatabase().insertWithOnConflict("records", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 同步合并用：同 id 不覆盖，返回是否真的新增。 */
    public boolean mergeRecord(Rec r) {
        ContentValues v = new ContentValues();
        v.put("id", r.id);
        v.put("ts", r.ts);
        v.put("day", r.day);
        v.put("time", r.time);
        v.put("answer", r.answer);
        v.put("kind", r.kind);
        v.put("active", r.active);
        v.put("span", r.span);
        v.put("n", r.n);
        v.put("delay", r.delay);
        v.put("shot", r.shot);
        v.put("src", r.src);
        long row = getWritableDatabase().insertWithOnConflict("records", null, v,
                SQLiteDatabase.CONFLICT_IGNORE);
        return row != -1;
    }

    public List<Rec> records(int limit) {
        return query("SELECT * FROM records ORDER BY ts DESC"
                + (limit > 0 ? " LIMIT " + limit : ""), null);
    }

    public List<Rec> recordsOfDay(String day) {
        return query("SELECT * FROM records WHERE day = ? ORDER BY ts DESC", new String[]{day});
    }

    public int countToday() {
        return (int) count("SELECT COUNT(*) FROM records WHERE day = ?", new String[]{today()});
    }

    public int countAll() {
        return (int) count("SELECT COUNT(*) FROM records", null);
    }

    public String lastAnswer() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT answer FROM records ORDER BY ts DESC LIMIT 1", null);
        try {
            if (c.moveToFirst()) {
                return c.getString(0);
            }
        } finally {
            c.close();
        }
        return "";
    }

    /** 最近 n 条回答，用来判断"又在说同一句话"。 */
    public List<String> recentAnswers(int n) {
        List<String> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT answer FROM records ORDER BY ts DESC LIMIT " + Math.max(1, n), null);
        try {
            while (c.moveToNext()) {
                out.add(c.getString(0));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public int maxN() {
        return (int) count("SELECT IFNULL(MAX(n), 0) FROM records", null);
    }

    public void clearRecords() {
        getWritableDatabase().delete("records", null, null);
        getWritableDatabase().delete("usage", null, null);
    }

    private List<Rec> query(String sql, String[] args) {
        List<Rec> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        try {
            while (c.moveToNext()) {
                Rec r = new Rec();
                r.id = str(c, "id");
                r.ts = c.getDouble(c.getColumnIndexOrThrow("ts"));
                r.day = str(c, "day");
                r.time = str(c, "time");
                r.answer = str(c, "answer");
                r.kind = str(c, "kind");
                r.active = (int) c.getLong(c.getColumnIndexOrThrow("active"));
                r.span = (int) c.getLong(c.getColumnIndexOrThrow("span"));
                r.n = (int) c.getLong(c.getColumnIndexOrThrow("n"));
                r.delay = (int) c.getLong(c.getColumnIndexOrThrow("delay"));
                r.shot = str(c, "shot");
                r.src = str(c, "src");
                out.add(r);
            }
        } finally {
            c.close();
        }
        return out;
    }

    private static String str(Cursor c, String name) {
        int i = c.getColumnIndexOrThrow(name);
        String v = c.getString(i);
        return v == null ? "" : v;
    }

    private long count(String sql, String[] args) {
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        try {
            if (c.moveToFirst()) {
                return c.getLong(0);
            }
        } finally {
            c.close();
        }
        return 0;
    }

    // ------------------------------------------------------------ 使用时长

    public void addUsage(String day, String app, String title, int sec) {
        if (sec <= 0) {
            return;
        }
        // 不用 UPSERT 语法：Android 11 以下自带的 SQLite 不支持，这里用两条语句代替
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("INSERT OR IGNORE INTO usage(day, app, title, sec) VALUES(?,?,?,0)",
                new Object[]{day, app, title});
        db.execSQL("UPDATE usage SET sec = sec + ? WHERE day = ? AND app = ? AND title = ?",
                new Object[]{sec, day, app, title});
    }

    public static class Use {
        public String app = "";
        public String title = "";
        public int sec;
    }

    /** 按应用汇总某天。 */
    public List<Use> usageByApp(String day) {
        List<Use> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT app, SUM(sec) AS s FROM usage WHERE day = ? GROUP BY app ORDER BY s DESC",
                new String[]{day});
        try {
            while (c.moveToNext()) {
                Use u = new Use();
                u.app = c.getString(0);
                u.sec = (int) c.getLong(1);
                out.add(u);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** 按窗口标题汇总某天。 */
    public List<Use> usageByTitle(String day) {
        List<Use> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT app, title, SUM(sec) AS s FROM usage WHERE day = ? "
                        + "GROUP BY app, title ORDER BY s DESC", new String[]{day});
        try {
            while (c.moveToNext()) {
                Use u = new Use();
                u.app = c.getString(0);
                u.title = c.getString(1);
                u.sec = (int) c.getLong(2);
                out.add(u);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public int activeSeconds(String day) {
        return (int) count("SELECT IFNULL(SUM(sec),0) FROM usage WHERE day = ?", new String[]{day});
    }

    // ------------------------------------------------------------ 导出 / 同步

    public static JSONObject toJson(Rec r) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", r.id);
            o.put("ts", r.ts);
            o.put("time", r.time);
            o.put("day", r.day);
            o.put("answer", r.answer);
            o.put("kind", r.kind);
            o.put("active", r.active);
            o.put("span", r.span);
            o.put("n", r.n);
            o.put("delay", r.delay);
            o.put("shot", r.shot);
            o.put("src", r.src);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Rec fromJson(JSONObject o) {
        Rec r = new Rec();
        r.id = o.optString("id", "");
        r.ts = o.optDouble("ts", 0);
        r.day = o.optString("day", "");
        r.time = o.optString("time", "");
        r.answer = o.optString("answer", "");
        r.kind = o.optString("kind", "normal");
        r.active = o.optInt("active", 0);
        r.span = o.optInt("span", 0);
        r.n = o.optInt("n", 0);
        r.delay = o.optInt("delay", 0);
        r.shot = o.optString("shot", "");
        r.src = o.optString("src", "android");
        if (r.day.length() == 0 && r.ts > 0) {
            r.day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date((long) (r.ts * 1000)));
        }
        if (r.time.length() == 0 && r.ts > 0) {
            r.time = hhmm((long) (r.ts * 1000));
        }
        return r;
    }

    /** 某天的全部记录，按时间正序，供上传。 */
    public List<Rec> recordsOfDayAsc(String day) {
        List<Rec> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM records WHERE day = ? ORDER BY ts ASC", new String[]{day});
        try {
            while (c.moveToNext()) {
                Rec r = new Rec();
                r.id = str(c, "id");
                r.ts = c.getDouble(c.getColumnIndexOrThrow("ts"));
                r.day = str(c, "day");
                r.time = str(c, "time");
                r.answer = str(c, "answer");
                r.kind = str(c, "kind");
                r.active = (int) c.getLong(c.getColumnIndexOrThrow("active"));
                r.span = (int) c.getLong(c.getColumnIndexOrThrow("span"));
                r.n = (int) c.getLong(c.getColumnIndexOrThrow("n"));
                r.delay = (int) c.getLong(c.getColumnIndexOrThrow("delay"));
                r.shot = str(c, "shot");
                r.src = str(c, "src");
                out.add(r);
            }
        } finally {
            c.close();
        }
        return out;
    }
}
