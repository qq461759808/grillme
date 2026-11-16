package com.grillme.app;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * 四语言取词。词表是仓库根目录的 lang/strings.json，和电脑端共用同一份文件，
 * 由 build.gradle 的 assets.srcDirs 直接打进 APK。
 */
public final class Lang {

    public static final String[] LANGS = {"zh", "en", "ja", "ru"};

    private static JSONObject table;
    private static String lang = "zh";

    private Lang() {
    }

    public static void init(Context c) {
        if (table != null) {
            return;
        }
        try {
            AssetManager am = c.getAssets();
            InputStream in = am.open("strings.json");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            JSONObject root = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            table = root.getJSONObject("s");
        } catch (Exception e) {
            table = new JSONObject();
        }
        lang = resolve(c, Cfg.get(c).lang());
    }

    /** 把设置里的语言（可能是 system）解析成实际语言码。 */
    public static String resolve(Context c, String setting) {
        if (setting == null || setting.length() == 0 || "system".equals(setting)) {
            String sys = Locale.getDefault().getLanguage();
            for (String l : LANGS) {
                if (l.equals(sys)) {
                    return l;
                }
            }
            return "en";
        }
        for (String l : LANGS) {
            if (l.equals(setting)) {
                return setting;
            }
        }
        return "en";
    }

    public static String lang() {
        return lang;
    }

    public static void setLang(Context c, String setting) {
        lang = resolve(c, setting);
    }

    public static String t(String key) {
        return t(key, new Object[0]);
    }

    /** 取词并把 {0} {1} 换成参数。 */
    public static String t(String key, Object... args) {
        String s = null;
        if (table != null) {
            JSONObject row = table.optJSONObject(key);
            if (row != null) {
                s = row.optString(lang, null);
                if (s == null || s.length() == 0) {
                    s = row.optString("en", null);
                }
                if (s == null || s.length() == 0) {
                    s = row.optString("zh", null);
                }
            }
        }
        if (s == null || s.length() == 0) {
            s = key;
        }
        for (int i = 0; i < args.length; i++) {
            s = s.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return s;
    }

    /** 冷静期这种要一位小数的数字，统一用这个格式化。 */
    public static String one(float v) {
        return String.format(Locale.US, "%.1f", v);
    }
}
