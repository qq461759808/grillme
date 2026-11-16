package com.grillme.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 首页：一眼看到状态、还剩多久被问、今天被问了几次，以及缺哪些权限。 */
public class MainActivity extends Activity {

    private Cfg cfg;
    private Store store;
    private TextView statusText;
    private TextView nextValue;
    private TextView askedValue;
    private TextView activeValue;
    private TextView lastValue;
    private TextView startBtn;
    private TextView pauseBtn;
    private LinearLayout permBox;
    private LinearLayout root;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final int REQ_PROJ = 1001;
    private String permSig = "";
    private String builtLang = "";
    private boolean lastOn = false;
    private boolean dotInited = false;

    // 刷新用的缓存。首页一秒刷一次，但真正会变的只有倒计时；
    // 数据库、权限这些一律降频，且画面没变化时一次都不碰视图，
    // 否则每秒重建背景/重设文本会让它一直重绘，白耗电。
    private View dot;
    private android.graphics.drawable.Drawable bgOn;
    private android.graphics.drawable.Drawable bgOff;
    private String shownSig = "";
    private int askedCache = -1;
    private String activeCache = "";
    private String lastCache = "";
    private int lastNextColor = 0;
    private int tickN = 0;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            update();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = Cfg.get(this);
        store = Store.get(this);
        Lang.init(this);
        Lang.setLang(this, cfg.lang());
        builtLang = Lang.lang();
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Lang.setLang(this, cfg.lang());
        // 语言在设置里被改过：整页重建，否则只有动态文字会变
        if (!builtLang.equals(Lang.lang())) {
            recreate();
            return;
        }
        ui.removeCallbacks(refresh);
        // 从系统设置授权完返回时，立刻重算一次权限，别等下一个五秒窗口
        tickN = 0;
        ui.post(refresh);
        if (cfg.enabled()) {
            GrillService.start(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresh);
    }

    // ------------------------------------------------------------ 界面

    private void build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);

        root = Ui.col(this);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 28), pad, Ui.dp(this, 28));

        LinearLayout topRow = Ui.row(this);
        TextView brand = Ui.tv(this, Lang.t("app_name"), 12, Ui.DIM, true);
        brand.setLetterSpacing(0.14f);
        topRow.addView(brand, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView gear = Ui.tv(this, "⚙  " + Lang.t("main_settings"), 12, Ui.MUTED, false);
        gear.setPadding(Ui.dp(this, 10), Ui.dp(this, 2), 0, Ui.dp(this, 2));
        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        topRow.addView(gear);
        root.addView(topRow);

        TextView tagline = Ui.tv(this, Lang.t("tagline"), 13, Ui.MUTED, false);
        LinearLayout.LayoutParams tglp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tglp.topMargin = Ui.dp(this, 6);
        root.addView(tagline, tglp);

        // ---- 状态卡
        LinearLayout hero = Ui.card(this);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Ui.dp(this, 20);
        root.addView(hero, hlp);

        LinearLayout statusRow = Ui.row(this);
        dot = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(this, 9), Ui.dp(this, 9));
        dot.setLayoutParams(dlp);
        statusRow.addView(dot);
        statusText = Ui.tv(this, "", 17, Ui.TEXT, true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Ui.dp(this, 10);
        statusRow.addView(statusText, slp);
        hero.addView(statusRow);

        LinearLayout nextBox = Ui.row(this);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = Ui.dp(this, 22);
        hero.addView(nextBox, nlp);

        LinearLayout nextLeft = Ui.col(this);
        TextView nextLabel = Ui.tv(this, Lang.t("main_next"), 12, Ui.DIM, false);
        nextLeft.addView(nextLabel);
        nextValue = Ui.tv(this, "--:--", 40, Ui.AMBER, true);
        nextValue.setLetterSpacing(-0.02f);
        nextLeft.addView(nextValue);
        nextBox.addView(nextLeft, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Ui.addDivider(hero, 18);

        LinearLayout statsRow = Ui.row(this);
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srp.topMargin = Ui.dp(this, 14);
        hero.addView(statsRow, srp);

        LinearLayout c1 = Ui.col(this);
        c1.addView(Ui.tv(this, Lang.t("main_today_popups"), 12, Ui.DIM, false));
        askedValue = Ui.tv(this, "0", 20, Ui.TEXT, true);
        c1.addView(askedValue);
        statsRow.addView(c1, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout c2 = Ui.col(this);
        c2.addView(Ui.tv(this, Lang.t("main_today_active"), 12, Ui.DIM, false));
        activeValue = Ui.tv(this, "0", 20, Ui.TEXT, true);
        c2.addView(activeValue);
        statsRow.addView(c2, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        lastValue = Ui.tv(this, "", 13, Ui.MUTED, false);
        lastValue.setLineSpacing(Ui.dp(this, 3), 1f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = Ui.dp(this, 16);
        hero.addView(lastValue, llp);

        // ---- 主按钮
        startBtn = Ui.primary(this, Lang.t("main_start"));
        startBtn.setTextSize(17);
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 18);
        root.addView(startBtn, blp);

        // ---- 三个入口
        LinearLayout nav = Ui.row(this);
        LinearLayout.LayoutParams navlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navlp.topMargin = Ui.dp(this, 10);
        root.addView(nav, navlp);

        nav.addView(navBtn(Lang.t("main_history"), HistoryActivity.class),
                weight(0, 1f, 0, 0, 0, 0));
        nav.addView(navBtn(Lang.t("main_stats"), StatsActivity.class),
                weight(8, 1f, 0, 0, 0, 0));
        nav.addView(navBtn(Lang.t("main_settings"), SettingsActivity.class),
                weight(8, 1f, 0, 0, 0, 0));

        // ---- 暂停
        pauseBtn = Ui.tv(this, "⏸  " + Lang.t("pause_menu"), 13, Ui.MUTED, false);
        pauseBtn.setGravity(Gravity.CENTER);
        pauseBtn.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 4));
        pauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPauseDialog();
            }
        });
        root.addView(pauseBtn);

        // ---- 权限
        permBox = Ui.col(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = Ui.dp(this, 22);
        root.addView(permBox, plp);

        sv.addView(root);
        setContentView(sv);
        Ui.padForSystemBars(sv);
    }

    private LinearLayout.LayoutParams margins(Activity a, int top, int left, int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, top);
        lp.leftMargin = Ui.dp(a, left);
        lp.rightMargin = Ui.dp(a, right);
        lp.bottomMargin = Ui.dp(a, bottom);
        return lp;
    }

    private LinearLayout.LayoutParams weight(int top, float w, int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, w);
        lp.topMargin = Ui.dp(this, top);
        lp.leftMargin = Ui.dp(this, l);
        lp.rightMargin = Ui.dp(this, r);
        return lp;
    }

    private TextView navBtn(String text, final Class<?> target) {
        TextView b = Ui.ghost(this, text);
        b.setTextSize(14);
        b.setSingleLine(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, target));
            }
        });
        return b;
    }

    // ------------------------------------------------------------ 刷新

    private void update() {
        boolean on = cfg.enabled();
        int paused = GrillService.pausedLeftMinutes(this);
        int left = (on && paused == 0) ? GrillService.secondsLeft(this) : -1;

        // 数据库、SharedPreferences、权限探测都不需要每秒做，五秒一次足够
        boolean heavy = (tickN++ % 5) == 0;
        if (heavy) {
            askedCache = store.countToday();
            activeCache = GrillService.todayActive(this);
            lastCache = GrillService.lastAnswer(this);
            renderPerms();
        }

        String sig = on + "|" + paused + "|" + left + "|" + askedCache
                + "|" + activeCache + "|" + lastCache;
        if (sig.equals(shownSig)) {
            return;             // 什么都没变：一个 setText 都不做
        }
        shownSig = sig;

        if (paused > 0) {
            statusText.setText(Lang.t("main_paused", paused));
        } else {
            statusText.setText(on ? Lang.t("main_status_on") : Lang.t("main_status_off"));
        }

        if (!dotInited || on != lastOn) {
            dotInited = true;
            lastOn = on;
            dot.setBackground(Ui.shape(on ? Ui.GREEN : Ui.DIM, 99, 0, 0f, this));
        }

        if (left >= 0) {
            nextValue.setText(GrillService.clock(left));
            if (lastNextColor != Ui.AMBER) {
                lastNextColor = Ui.AMBER;
                nextValue.setTextColor(Ui.AMBER);
            }
        } else {
            nextValue.setText("--:--");
            if (lastNextColor != Ui.DIM) {
                lastNextColor = Ui.DIM;
                nextValue.setTextColor(Ui.DIM);
            }
        }

        askedValue.setText(String.valueOf(Math.max(0, askedCache)));
        activeValue.setText(activeCache);

        if (lastCache.length() == 0) {
            lastValue.setText(Lang.t("main_last") + " · " + Lang.t("never"));
            lastValue.setTextColor(Ui.DIM);
        } else {
            lastValue.setText(Lang.t("main_last") + " · " + lastCache);
            lastValue.setTextColor(Ui.MUTED);
        }

        // 背景是 RippleDrawable，每次 new 一个等于每秒重建一份 drawable，
        // 会连带整棵树重绘。只在开关态真的翻转时才换。
        startBtn.setText(on ? Lang.t("main_stop") : Lang.t("main_start"));
        if (bgOn == null) {
            bgOn = Ui.pressable(this, Ui.CARD2, 12, 0x33FFFFFF);
            bgOff = Ui.pressable(this, Ui.AMBER, 12, 0x55FFFFFF);
        }
        startBtn.setBackground(on ? bgOn : bgOff);
        startBtn.setTextColor(on ? Ui.MUTED : Ui.INK);
    }

    private void renderPerms() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean usage = hasUsageAccess();
        boolean notif = notificationsOn();
        boolean proj = Shots.get().ready();

        // 一秒一次的刷新不能重建视图，否则会闪。只有状态真的变了才重画
        String sig = overlay + "|" + usage + "|" + notif + "|" + proj;
        if (sig.equals(permSig)) {
            return;
        }
        permSig = sig;
        permBox.removeAllViews();

        int missing = 0;
        if (!overlay) {
            missing++;
        }
        if (!usage) {
            missing++;
        }
        if (!notif) {
            missing++;
        }

        if (missing == 0) {
            TextView ok = Ui.tv(this, "✓  " + Lang.t("perm_granted"), 12, Ui.GREEN, true);
            permBox.addView(ok);
        } else {
            permBox.addView(Ui.tv(this, Lang.t("main_perms", missing), 13, Ui.RED, true));
        }

        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(this, 10);
        permBox.addView(card, clp);

        if (!overlay) {
            card.addView(permRow(Lang.t("perm_overlay"), false, 0));
        }
        if (!usage) {
            card.addView(permRow(Lang.t("perm_usage"), false, 1));
        }
        if (!notif) {
            card.addView(permRow(Lang.t("perm_notif"), false, 2));
        }
        card.addView(permRow(Lang.t("perm_battery"), true, 3));

        // 截屏授权：可选，但电脑版有截图，这里也要给
        Ui.addDivider(card);
        card.addView(shotRow(proj));
    }

    private View shotRow(boolean ready) {
        TextView state = Ui.tv(this, ready ? Lang.t("perm_granted") : Lang.t("perm_denied"),
                12, ready ? Ui.GREEN : Ui.RED, false);
        LinearLayout r = Ui.line(this, Lang.t("shot_auth"),
                ready ? null : Lang.t("shot_auth_hint"), state);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askProjection();
            }
        });
        return r;
    }

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
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshNow();
                }
            }, 600);
        } else {
            Ui.toast(this, Lang.t("shot_denied"));
        }
    }

    /** 无视缓存强制重画一遍，用在权限/开关刚被改过之后。 */
    private void refreshNow() {
        shownSig = "";
        permSig = "";
        tickN = 0;
        update();
    }

    private View permRow(String title, boolean alwaysShow, final int which) {
        TextView go = Ui.tv(this, alwaysShow ? "›" : Lang.t("perm_denied"), 12,
                alwaysShow ? Ui.DIM : Ui.RED, false);
        LinearLayout r = Ui.line(this, title, null, go);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPerm(which);
            }
        });
        return r;
    }

    private void openPerm(int which) {
        try {
            if (which == 0) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } else if (which == 1) {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } else if (which == 2) {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    startActivity(i);
                } else {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            } else {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        } catch (Exception e) {
            Ui.toast(this, String.valueOf(e.getMessage()));
        }
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) {
                return false;
            }
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean notificationsOn() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            return nm != null && nm.areNotificationsEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    private void showPauseDialog() {
        final String[] items = {
                Lang.t("pause_30"), Lang.t("pause_60"), Lang.t("pause_today"), Lang.t("pause_off")
        };
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(Lang.t("pause_menu"))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        long until = 0;
                        if (which == 0) {
                            until = System.currentTimeMillis() + 30 * 60000L;
                        } else if (which == 1) {
                            until = System.currentTimeMillis() + 60 * 60000L;
                        } else if (which == 2) {
                            java.util.Calendar c = java.util.Calendar.getInstance();
                            c.add(java.util.Calendar.DAY_OF_MONTH, 1);
                            c.set(java.util.Calendar.HOUR_OF_DAY, 0);
                            c.set(java.util.Calendar.MINUTE, 0);
                            c.set(java.util.Calendar.SECOND, 0);
                            c.set(java.util.Calendar.MILLISECOND, 0);
                            until = c.getTimeInMillis();
                        }
                        cfg.setL(Cfg.PAUSED_UNTIL, until);
                        Ui.toast(MainActivity.this, until > 0
                                ? Lang.t("paused_toast") : Lang.t("done"));
                        refreshNow();
                    }
                })
                .show();
    }

    private void toggle() {
        if (cfg.enabled()) {
            GrillService.stop(this);
            cfg.setEnabled(false);
            Ui.toast(this, Lang.t("msg_stopped"));
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Ui.toast(this, Lang.t("msg_need_overlay"));
                openPerm(0);
                return;
            }
            cfg.setEnabled(true);
            GrillService.startFresh(this);
            Ui.toast(this, Lang.t("msg_started"));
        }
        refreshNow();
    }
}
