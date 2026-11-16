package com.grillme.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 设置。参数名和电脑端一致，文案走同一份 lang/strings.json。 */
public class SettingsActivity extends Activity {

    private Cfg cfg;
    private Store store;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = Cfg.get(this);
        store = Store.get(this);
        Lang.init(this);
        Lang.setLang(this, cfg.lang());
        build();
    }

    private void build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);

        root = Ui.col(this);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 32));
        root.addView(Ui.topBar(this, Lang.t("settings_title")));

        // ---------------------------------------------------------- 节奏
        root.addView(Ui.section(this, Lang.t("group_timing")));
        LinearLayout timing = Ui.card(this);
        timing.addView(stepperRow(Lang.t("set_min_minutes"), Lang.t("set_min_minutes_hint"),
                cfg.minMinutes(), 5, 5, 180, Cfg.MIN_MINUTES, "min"));
        Ui.addDivider(timing);
        timing.addView(stepperRow(Lang.t("set_max_minutes"), null,
                cfg.maxMinutes(), 5, 5, 300, Cfg.MAX_MINUTES, "min"));
        Ui.addDivider(timing);
        timing.addView(stepperRow(Lang.t("set_first_minutes"), null,
                cfg.firstMinutes(), 5, 1, 120, Cfg.FIRST_MINUTES, "min"));
        root.addView(timing);

        // ---------------------------------------------------------- 锁屏
        root.addView(Ui.section(this, Lang.t("group_screen")));
        LinearLayout screen = Ui.card(this);
        screen.addView(switchRow(Lang.t("set_watch_off_screen"), Lang.t("set_watch_off_screen_hint"),
                cfg.watchOffScreen(), Cfg.WATCH_OFF_SCREEN));
        Ui.addDivider(screen);
        screen.addView(stepperRow(Lang.t("set_off_screen_grace"), null,
                cfg.offScreenGrace(), 5, 0, 120, Cfg.OFF_SCREEN_GRACE, "min"));
        Ui.addDivider(screen);
        screen.addView(switchRow(Lang.t("set_alert_when_off_screen"), null,
                cfg.alertWhenOffScreen(), Cfg.ALERT_WHEN_OFF_SCREEN));
        root.addView(screen);

        // ---------------------------------------------------------- 放行
        root.addView(Ui.section(this, Lang.t("group_answer")));
        LinearLayout answer = Ui.card(this);
        answer.addView(stepperRow(Lang.t("set_min_chars"), null,
                cfg.minChars(), 1, 1, 40, Cfg.MIN_CHARS, "char"));
        Ui.addDivider(answer);
        answer.addView(switchRow(Lang.t("set_allow_repeat"), null,
                cfg.allowRepeat(), Cfg.ALLOW_REPEAT));
        Ui.addDivider(answer);
        answer.addView(switchRow(Lang.t("set_block_paste"), null,
                cfg.blockPaste(), Cfg.BLOCK_PASTE));
        Ui.addDivider(answer);
        answer.addView(cooldownRow());
        Ui.addDivider(answer);
        answer.addView(stepperRow(Lang.t("set_repeat_warn"), null,
                cfg.repeatWarn(), 1, 0, 10, Cfg.REPEAT_WARN, "times"));
        root.addView(answer);

        // ---------------------------------------------------------- 提醒
        root.addView(Ui.section(this, Lang.t("group_alert")));
        LinearLayout alert = Ui.card(this);
        alert.addView(switchRow(Lang.t("set_sound"), null, cfg.sound(), Cfg.SOUND));
        Ui.addDivider(alert);
        alert.addView(switchRow(Lang.t("set_vibrate"), null, cfg.vibrate(), Cfg.VIBRATE));
        Ui.addDivider(alert);
        alert.addView(vibratePatternRow());
        root.addView(alert);

        // ---------------------------------------------------------- 免打扰
        root.addView(Ui.section(this, Lang.t("group_dnd")));
        LinearLayout dnd = Ui.card(this);
        dnd.addView(switchRow(Lang.t("set_dnd_enabled"), null,
                cfg.dndEnabled(), Cfg.DND_ENABLED));
        Ui.addDivider(dnd);
        dnd.addView(timeRow(Lang.t("set_dnd_start"), cfg.dndStart(), Cfg.DND_START));
        Ui.addDivider(dnd);
        dnd.addView(timeRow(Lang.t("set_dnd_end"), cfg.dndEnd(), Cfg.DND_END));
        root.addView(dnd);

        // ---------------------------------------------------------- 长时间
        root.addView(Ui.section(this, Lang.t("group_long")));
        LinearLayout longer = Ui.card(this);
        longer.addView(switchRow(Lang.t("set_long_alert"), null,
                cfg.longAlert(), Cfg.LONG_ALERT));
        Ui.addDivider(longer);
        longer.addView(stepperRow(Lang.t("set_long_minutes"), null,
                cfg.longMinutes(), 15, 15, 480, Cfg.LONG_MINUTES, "min"));
        root.addView(longer);

        // ---------------------------------------------------------- 截图
        root.addView(Ui.section(this, Lang.t("group_shot")));
        LinearLayout shot = Ui.card(this);
        shot.addView(switchRow(Lang.t("set_shot_enabled"), Lang.t("set_shot_enabled_hint"),
                cfg.shotEnabled(), Cfg.SHOT_ENABLED));
        Ui.addDivider(shot);
        shot.addView(stepperRow(Lang.t("set_shot_width"), null,
                cfg.shotWidth(), 80, 400, 1600, Cfg.SHOT_WIDTH, "px"));
        Ui.addDivider(shot);
        shot.addView(stepperRow(Lang.t("set_shot_quality"), null,
                cfg.shotQuality(), 5, 20, 95, Cfg.SHOT_QUALITY, "num"));
        Ui.addDivider(shot);
        shot.addView(stepperRow(Lang.t("set_shot_keep"), Lang.t("set_shot_keep_hint"),
                cfg.shotKeepDays(), 30, 0, 360, Cfg.SHOT_KEEP, "day"));
        Ui.addDivider(shot);
        shot.addView(shotAuthRow());
        root.addView(shot);

        // ---------------------------------------------------------- 语言
        root.addView(Ui.section(this, Lang.t("group_lang")));
        LinearLayout langCard = Ui.card(this);
        langCard.addView(languageRow());
        root.addView(langCard);

        // ---------------------------------------------------------- 同步
        root.addView(Ui.section(this, Lang.t("group_sync")));
        LinearLayout sync = Ui.card(this);
        sync.addView(switchRow(Lang.t("set_sync_enabled"), null,
                cfg.syncEnabled(), Cfg.SYNC_ENABLED));
        Ui.addDivider(sync);
        sync.addView(textRow(Lang.t("set_sync_url"), cfg.syncUrl(), Cfg.SYNC_URL,
                InputType.TYPE_TEXT_VARIATION_URI, "https://dav.jianguoyun.com/dav/Grill/"));
        Ui.addDivider(sync);
        sync.addView(textRow(Lang.t("set_sync_user"), cfg.syncUser(), Cfg.SYNC_USER,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, ""));
        Ui.addDivider(sync);
        sync.addView(textRow(Lang.t("set_sync_pass"), mask(cfg.syncPass()), Cfg.SYNC_PASS,
                InputType.TYPE_TEXT_VARIATION_PASSWORD, ""));
        Ui.addDivider(sync);
        sync.addView(textRow(Lang.t("set_sync_device"), cfg.syncDevice(), Cfg.SYNC_DEVICE,
                InputType.TYPE_CLASS_TEXT, "android"));
        TextView devHint = Ui.tv(this, Lang.t("set_sync_device_hint"), 12, Ui.DIM, false);
        devHint.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 12));
        sync.addView(devHint);
        sync.addView(actionRow(Lang.t("set_sync_test"), Lang.t("set_sync_test"), new Runnable() {
            @Override
            public void run() {
                testSync();
            }
        }));
        Ui.addDivider(sync);
        sync.addView(actionRow(Lang.t("set_sync_now"), Lang.t("set_sync_now"), new Runnable() {
            @Override
            public void run() {
                runSync();
            }
        }));
        root.addView(sync);

        // ---------------------------------------------------------- 数据
        root.addView(Ui.section(this, Lang.t("group_data")));
        LinearLayout data = Ui.card(this);
        data.addView(actionRow(Lang.t("set_export"), "›", new Runnable() {
            @Override
            public void run() {
                export();
            }
        }));
        Ui.addDivider(data);
        data.addView(actionRow(Lang.t("set_clear"), "›", new Runnable() {
            @Override
            public void run() {
                confirmClear();
            }
        }));
        root.addView(data);

        // ---------------------------------------------------------- 关于
        root.addView(Ui.section(this, Lang.t("about")));
        LinearLayout about = Ui.card(this);
        TextView body = Ui.tv(this, Lang.t("about_body"), 13, Ui.MUTED, false);
        body.setLineSpacing(Ui.dp(this, 4), 1f);
        about.addView(body);
        TextView ver = Ui.tv(this, "Grill Me 1.0.0 · MIT", 12, Ui.DIM, false);
        ver.setPadding(0, Ui.dp(this, 12), 0, 0);
        about.addView(ver);
        root.addView(about);

        sv.addView(root);
        setContentView(sv);
        Ui.padForSystemBars(sv);
    }

    private static String mask(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            sb.append('•');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 行零件

    private View switchRow(String title, String sub, boolean checked, final String key) {
        final android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                cfg.setB(key, v);
                if (Cfg.WATCH_OFF_SCREEN.equals(key) && v) {
                    // 打开全时段模式时顺手提醒一下电量
                    Ui.toast(SettingsActivity.this, Lang.t("set_watch_off_screen_hint"));
                }
            }
        });
        return Ui.line(this, title, sub, sw);
    }

    /** 数值行：− 值 +，点数值可以直接手填 */
    private View stepperRow(String title, String sub, int value, final int step,
                            final int min, final int max, final String key, String unit) {
        final LinearLayout holder = Ui.row(this);
        final int[] cur = {value};
        final TextView val = Ui.tv(this, label(cur[0], unit), 15, Ui.TEXT, true);
        val.setMinWidth(Ui.dp(this, 52));
        val.setGravity(android.view.Gravity.CENTER);
        val.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));

        final String rowTitle = title;
        val.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askInt(rowTitle, cur, min, max, key, val);
            }
        });

        TextView minus = stepBtn("−");
        TextView plus = stepBtn("+");
        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cur[0] = Math.max(min, cur[0] - step);
                cfg.setI(key, cur[0]);
                val.setText(label(cur[0], unitOf(key)));
            }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cur[0] = Math.min(max, cur[0] + step);
                cfg.setI(key, cur[0]);
                val.setText(label(cur[0], unitOf(key)));
            }
        });
        holder.addView(minus);
        holder.addView(val);
        holder.addView(plus);
        return Ui.line(this, title, sub, holder);
    }

    /** 弹个输入框让用户直接填数，省得一下下点 + 号。越界就夹到边界。 */
    private void askInt(String title, final int[] cur, final int min, final int max,
                        final String key, final TextView val) {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(cur[0]));
        input.setSelection(input.getText().length());
        input.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 22), Ui.dp(this, 10));

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setMessage(Lang.t("value_range", min, max))
                .setView(input)
                .setPositiveButton(Lang.t("save"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        int v;
                        try {
                            v = Integer.parseInt(input.getText().toString().trim());
                        } catch (Exception e) {
                            v = cur[0];
                        }
                        cur[0] = Math.max(min, Math.min(max, v));
                        cfg.setI(key, cur[0]);
                        val.setText(label(cur[0], unitOf(key)));
                    }
                })
                .setNegativeButton(Lang.t("cancel"), null)
                .show();
    }

    /** 冷静期这种带一位小数的，单独一个入口。 */
    private void askDecimal(String title, final float[] cur, final float min, final float max,
                            final String key, final TextView val) {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(Lang.one(cur[0]));
        input.setSelection(input.getText().length());
        input.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 22), Ui.dp(this, 10));

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setMessage(Lang.t("value_range", Lang.one(min), Lang.one(max)))
                .setView(input)
                .setPositiveButton(Lang.t("save"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        float v;
                        try {
                            v = Float.parseFloat(input.getText().toString().trim());
                        } catch (Exception e) {
                            v = cur[0];
                        }
                        v = Math.max(min, Math.min(max, v));
                        v = Math.round(v * 10f) / 10f;
                        cur[0] = v;
                        cfg.setF(key, v);
                        val.setText(Lang.one(v) + " " + Lang.t("unit_sec"));
                    }
                })
                .setNegativeButton(Lang.t("cancel"), null)
                .show();
    }

    private String unitOf(String key) {
        if ("char".equals(key) || "times".equals(key)) {
            return "";
        }
        if (Cfg.SHOT_WIDTH.equals(key)) {
            return "px";
        }
        if (Cfg.SHOT_QUALITY.equals(key)) {
            return "";
        }
        if (Cfg.SHOT_KEEP.equals(key)) {
            return "day";
        }
        return "min";
    }

    private String label(int v, String unit) {
        if ("char".equals(unit) || "times".equals(unit) || "num".equals(unit)) {
            return String.valueOf(v);
        }
        if ("px".equals(unit)) {
            return v + " px";
        }
        if ("day".equals(unit)) {
            return v + " " + Lang.t("unit_day");
        }
        return v + " " + Lang.t("unit_min");
    }

    private TextView stepBtn(String text) {
        TextView b = Ui.tv(this, text, 20, Ui.TEXT, true);
        b.setGravity(android.view.Gravity.CENTER);
        b.setBackground(Ui.pressable(this, Ui.CARD2, 10, 0x33FFFFFF));
        b.setWidth(Ui.dp(this, 38));
        b.setHeight(Ui.dp(this, 38));
        b.setClickable(true);
        return b;
    }

    /** 冷静期：0 到 10 秒，每次 0.1 秒。 */
    private View cooldownRow() {
        final LinearLayout holder = Ui.row(this);
        final float[] cur = {cfg.cooldown()};
        final TextView val = Ui.tv(this, Lang.one(cur[0]) + " " + Lang.t("unit_sec"), 15, Ui.TEXT, true);
        val.setMinWidth(Ui.dp(this, 62));
        val.setGravity(android.view.Gravity.CENTER);
        val.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        val.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askDecimal(Lang.t("set_cooldown"), cur, 0f, 10f, Cfg.COOLDOWN, val);
            }
        });
        TextView minus = stepBtn("−");
        TextView plus = stepBtn("+");
        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cur[0] = Math.max(0f, Math.round((cur[0] - 0.1f) * 10f) / 10f);
                cfg.setF(Cfg.COOLDOWN, cur[0]);
                val.setText(Lang.one(cur[0]) + " " + Lang.t("unit_sec"));
            }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cur[0] = Math.min(10f, Math.round((cur[0] + 0.1f) * 10f) / 10f);
                cfg.setF(Cfg.COOLDOWN, cur[0]);
                val.setText(Lang.one(cur[0]) + " " + Lang.t("unit_sec"));
            }
        });
        holder.addView(minus);
        holder.addView(val);
        holder.addView(plus);
        return Ui.line(this, Lang.t("set_cooldown"), Lang.t("set_cooldown_hint"), holder);
    }

    private View vibratePatternRow() {
        final TextView val = Ui.tv(this, vibrateLabel(cfg.vibratePattern()), 14, Ui.MUTED, false);
        View row = Ui.line(this, Lang.t("set_vibrate_pattern"), null, val);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] keys = {"short", "double", "long", "strong"};
                String[] items = new String[keys.length];
                for (int i = 0; i < keys.length; i++) {
                    items[i] = Lang.t("vib_" + keys[i]);
                }
                new AlertDialog.Builder(SettingsActivity.this,
                        android.R.style.Theme_Material_Dialog_Alert)
                        .setItems(items, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                cfg.setS(Cfg.VIBRATE_PATTERN, keys[which]);
                                val.setText(vibrateLabel(keys[which]));
                                Notifier.vibrate(SettingsActivity.this, keys[which]);
                            }
                        }).show();
            }
        });
        return row;
    }

    private String vibrateLabel(String key) {
        return Lang.t("vib_" + (key == null || key.length() == 0 ? "double" : key));
    }

    private View timeRow(final String title, String value, final String key) {
        final TextView val = Ui.tv(this, value, 15, Ui.AMBER, true);
        View row = Ui.line(this, title, null, val);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int[] hm = parse(cfg.s(key, "00:00"));
                new TimePickerDialog(SettingsActivity.this, new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker view, int h, int m) {
                        String s = String.format(Locale.US, "%02d:%02d", h, m);
                        cfg.setS(key, s);
                        val.setText(s);
                    }
                }, hm[0], hm[1], true).show();
            }
        });
        return row;
    }

    private static int[] parse(String s) {
        try {
            String[] p = s.split(":");
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    private View textRow(final String title, final String value, final String key,
                         int inputType, final String hint) {
        final TextView val = Ui.tv(this, value.length() == 0 ? hint : value, 13,
                value.length() == 0 ? Ui.DIM : Ui.MUTED, false);
        val.setMaxWidth(Ui.dp(this, 190));
        val.setGravity(android.view.Gravity.END);
        val.setSingleLine(true);
        View row = Ui.line(this, title, null, val);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final EditText input = new EditText(SettingsActivity.this);
                input.setInputType(InputType.TYPE_CLASS_TEXT | inputType);
                input.setText(key.equals(Cfg.SYNC_PASS) ? cfg.syncPass() : cfg.s(key, ""));
                input.setHint(hint);
                input.setSingleLine(true);
                int p = Ui.dp(SettingsActivity.this, 16);
                input.setPadding(p, p, p, p);
                new AlertDialog.Builder(SettingsActivity.this,
                        android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle(title)
                        .setView(input)
                        .setPositiveButton(Lang.t("save"), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                String v = input.getText().toString().trim();
                                cfg.setS(key, v);
                                boolean empty = v.length() == 0;
                                val.setText(empty ? hint : (key.equals(Cfg.SYNC_PASS) ? mask(v) : v));
                                val.setTextColor(empty ? Ui.DIM : Ui.MUTED);
                            }
                        })
                        .setNegativeButton(Lang.t("cancel"), null)
                        .show();
            }
        });
        return row;
    }

    private View shotAuthRow() {
        boolean ready = Shots.get().ready();
        TextView state = Ui.tv(this, ready ? Lang.t("perm_granted") : Lang.t("perm_denied"),
                12, ready ? Ui.GREEN : Ui.RED, false);
        View row = Ui.line(this, Lang.t("shot_auth"),
                ready ? null : Lang.t("shot_auth_hint"), state);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askProjection();
            }
        });
        return row;
    }

    private static final int REQ_PROJ = 1002;

    private void askProjection() {
        Shots.requestPermission(this, REQ_PROJ);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJ) {
            return;
        }
        if (resultCode == RESULT_OK && data != null) {
            GrillService.startWithProjection(this, resultCode, data);
            recreate();
        } else {
            Ui.toast(this, Lang.t("shot_denied"));
        }
    }

    private View actionRow(String title, String right, final Runnable action) {
        TextView val = Ui.tv(this, right, 13, Ui.AMBER, false);
        View row = Ui.line(this, title, null, val);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return row;
    }

    private View languageRow() {
        final TextView val = Ui.tv(this, langLabel(cfg.lang()), 14, Ui.AMBER, true);
        View row = Ui.line(this, Lang.t("set_language"), null, val);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] keys = {"system", "zh", "en", "ja", "ru"};
                String[] items = new String[keys.length];
                items[0] = Lang.t("lang_system");
                for (int i = 1; i < keys.length; i++) {
                    items[i] = langName(keys[i]);
                }
                new AlertDialog.Builder(SettingsActivity.this,
                        android.R.style.Theme_Material_Dialog_Alert)
                        .setItems(items, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                cfg.setLang(keys[which]);
                                Lang.setLang(SettingsActivity.this, keys[which]);
                                recreate();
                            }
                        }).show();
            }
        });
        return row;
    }

    private String langLabel(String setting) {
        if (setting == null || setting.length() == 0 || "system".equals(setting)) {
            return Lang.t("lang_system") + " · " + langName(Lang.lang());
        }
        return langName(setting);
    }

    private String langName(String code) {
        if ("zh".equals(code)) {
            return "中文";
        }
        if ("en".equals(code)) {
            return "English";
        }
        if ("ja".equals(code)) {
            return "日本語";
        }
        if ("ru".equals(code)) {
            return "Русский";
        }
        return code;
    }

    // ------------------------------------------------------------ 动作

    private void testSync() {
        Ui.toast(this, Lang.t("sync_running"));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Sync.Result r = Sync.test(cfg);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (r.ok) {
                            Ui.toast(SettingsActivity.this, Lang.t("sync_ok_conn"));
                        } else {
                            Ui.toast(SettingsActivity.this, syncError(r.err));
                        }
                    }
                });
            }
        }, "grill-test").start();
    }

    private void runSync() {
        Ui.toast(this, Lang.t("sync_running"));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Sync.Result r = Sync.run(SettingsActivity.this, cfg);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (r.ok) {
                            Ui.toast(SettingsActivity.this, Lang.t("sync_ok", r.merged));
                        } else {
                            Ui.toast(SettingsActivity.this, syncError(r.err));
                        }
                    }
                });
            }
        }, "grill-sync").start();
    }

    private String syncError(String err) {
        if ("auth".equals(err)) {
            return Lang.t("sync_auth_fail");
        }
        if ("notfound".equals(err)) {
            return Lang.t("sync_not_found");
        }
        if ("off".equals(err) || "nourl".equals(err)) {
            return Lang.t("sync_disabled");
        }
        return Lang.t("sync_fail", err == null ? "" : err);
    }

    private void export() {
        try {
            List<Store.Rec> rows = store.records(0);
            StringBuilder sb = new StringBuilder();
            for (Store.Rec r : rows) {
                sb.append(Store.toJson(r).toString()).append('\n');
            }
            File dir = getExternalFilesDir(null);
            if (dir == null) {
                dir = getFilesDir();
            }
            String name = "grill-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                    .format(new Date()) + ".jsonl";
            File f = new File(dir, name);
            FileOutputStream out = new FileOutputStream(f);
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
            Ui.toast(this, Lang.t("export_ok", f.getAbsolutePath()));
        } catch (Exception e) {
            Ui.toast(this, Lang.t("export_fail", String.valueOf(e.getMessage())));
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage(Lang.t("clear_confirm"))
                .setPositiveButton(Lang.t("delete"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        store.clearRecords();
                        Ui.toast(SettingsActivity.this, Lang.t("done"));
                    }
                })
                .setNegativeButton(Lang.t("cancel"), null)
                .show();
    }
}
