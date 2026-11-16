package com.grillme.app;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebDAV 同步。设计要点：
 * 1) 按设备分片存放（<设备名>/YYYY-MM/DD.jsonl），各设备只写自己那一片，永远不会互相覆盖；
 * 2) 合并时按记录 id 求并集 —— 记录只追加、不改不删，所以不需要任何冲突解决；
 * 3) 只同步最近 14 天，避免一次拉几百个文件撞上网盘请求频率限制。
 */
public final class Sync {

    private static final int DAYS = 14;
    private static final int TIMEOUT = 20000;
    private static final Pattern HREF = Pattern.compile("(?i)<(?:d:)?href>(.*?)</(?:d:)?href>");
    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
                    + "<d:resourcetype/><d:getcontentlength/>"
                    + "</d:prop></d:propfind>";

    public static class Result {
        public boolean ok;
        public int merged;
        public String err = "";

        static Result fail(String e) {
            Result r = new Result();
            r.ok = false;
            r.err = e;
            return r;
        }
    }

    private Sync() {
    }

    private static String norm(String url) {
        String u = url == null ? "" : url.trim();
        if (u.length() == 0) {
            return "";
        }
        if (!u.endsWith("/")) {
            u = u + "/";
        }
        return u;
    }

    private static Map<String, String> hdr(String k, String v) {
        Map<String, String> m = new HashMap<>();
        m.put(k, v);
        return m;
    }

    /** 只测连通性和认证，不传数据。 */
    public static Result test(Cfg cfg) {
        String base = norm(cfg.syncUrl());
        if (base.length() == 0) {
            return Result.fail("nourl");
        }
        try {
            Http.Resp r = Http.req(base, "PROPFIND", hdr("Depth", "0"),
                    cfg.syncUser(), cfg.syncPass(), PROPFIND_BODY, TIMEOUT);
            if (r.code == 207 || r.code == 200) {
                Result ok = new Result();
                ok.ok = true;
                return ok;
            }
            if (r.code == 401 || r.code == 403) {
                return Result.fail("auth");
            }
            if (r.code == 404) {
                return Result.fail("notfound");
            }
            return Result.fail("HTTP " + r.code);
        } catch (Exception e) {
            return Result.fail(String.valueOf(e.getMessage()));
        }
    }

    public static Result run(Context c, Cfg cfg) {
        String base = norm(cfg.syncUrl());
        if (!cfg.syncEnabled() || base.length() == 0) {
            return Result.fail("off");
        }
        Store store = Store.get(c);
        String user = cfg.syncUser();
        String pass = cfg.syncPass();
        String own = safeName(cfg.syncDevice());
        int merged = 0;

        try {
            Http.Resp probe = Http.req(base, "PROPFIND", hdr("Depth", "0"),
                    user, pass, PROPFIND_BODY, TIMEOUT);
            if (probe.code == 401 || probe.code == 403) {
                return Result.fail("auth");
            }
            if (probe.code == 404) {
                return Result.fail("notfound");
            }
            if (probe.code != 207 && probe.code != 200) {
                return Result.fail("HTTP " + probe.code);
            }

            // 1. 找出所有设备目录
            Set<String> devices = new LinkedHashSet<>();
            devices.add(own);
            try {
                Http.Resp ls = Http.req(base, "PROPFIND", hdr("Depth", "1"),
                        user, pass, PROPFIND_BODY, TIMEOUT);
                if (ls.code == 207 || ls.code == 200) {
                    for (String href : hrefs(ls.text())) {
                        String name = leaf(href);
                        if (name.length() > 0 && href.endsWith("/") && devices.size() < 8) {
                            devices.add(safeName(name));
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            // 2. 拉取最近 14 天，按 id 合并
            List<String> days = recentDays();
            for (String day : days) {
                String ym = day.substring(0, 7);
                String dd = day.substring(8);
                for (String dev : devices) {
                    String url = base + dev + "/" + ym + "/" + dd + ".jsonl";
                    try {
                        Http.Resp r = Http.req(url, "GET", user, pass, null, TIMEOUT);
                        if (r.code != 200 || r.body.length == 0) {
                            continue;
                        }
                        String text = r.text();
                        for (String line : text.split("\n")) {
                            String s = line.trim();
                            if (s.length() < 2 || s.charAt(0) != '{') {
                                continue;
                            }
                            try {
                                Store.Rec rec = Store.fromJson(new JSONObject(s));
                                if (rec.id.length() == 0) {
                                    continue;
                                }
                                if (store.mergeRecord(rec)) {
                                    merged++;
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            // 3. 把自己这边有记录的日期推上去
            for (String day : days) {
                List<Store.Rec> mine = store.recordsOfDayAsc(day);
                if (mine.isEmpty()) {
                    continue;
                }
                String ym = day.substring(0, 7);
                String dd = day.substring(8);
                String dir = base + own + "/" + ym + "/";
                mkcol(base + own + "/", user, pass);
                mkcol(dir, user, pass);
                StringBuilder sb = new StringBuilder();
                for (Store.Rec r : mine) {
                    sb.append(Store.toJson(r).toString()).append('\n');
                }
                Http.Resp put = Http.req(dir + dd + ".jsonl", "PUT", user, pass,
                        sb.toString(), TIMEOUT);
                if (put.code == 401 || put.code == 403) {
                    return Result.fail("auth");
                }
            }

            Result ok = new Result();
            ok.ok = true;
            ok.merged = merged;
            return ok;
        } catch (Exception e) {
            return Result.fail(String.valueOf(e.getMessage()));
        }
    }

    private static void mkcol(String url, String user, String pass) {
        try {
            Http.Resp r = Http.req(url, "MKCOL", user, pass, null, TIMEOUT);
            // 405 = 已存在，409 = 父目录还没建好（下次再试）
            if (r.code >= 400 && r.code != 405 && r.code != 409) {
                return;
            }
        } catch (Exception ignored) {
        }
    }

    private static List<String> hrefs(String xml) {
        List<String> out = new ArrayList<>();
        Matcher m = HREF.matcher(xml);
        while (m.find()) {
            out.add(Uri.decode(m.group(1).trim()));
        }
        return out;
    }

    /** 取路径最后一段（去掉结尾斜杠）。 */
    private static String leaf(String href) {
        String h = href;
        while (h.endsWith("/")) {
            h = h.substring(0, h.length() - 1);
        }
        int i = h.lastIndexOf('/');
        return i >= 0 ? h.substring(i + 1) : h;
    }

    private static String safeName(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_';
            sb.append(ok ? ch : '-');
        }
        String out = sb.toString();
        return out.length() == 0 ? "device" : out;
    }

    private static List<String> recentDays() {
        List<String> out = new ArrayList<>();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar cal = Calendar.getInstance();
        for (int i = 0; i < DAYS; i++) {
            out.add(f.format(cal.getTime()));
            cal.add(Calendar.DAY_OF_MONTH, -1);
        }
        return out;
    }
}
