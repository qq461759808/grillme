package com.grillme.app;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 统计：今天时间花在哪些应用上，以及最常拿来搪塞自己的那几句话。 */
public class StatsActivity extends Activity {

    private Store store;
    private LinearLayout content;
    private TextView tabApp;
    private TextView tabAnswer;
    private int tab = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = Store.get(this);
        Lang.init(this);
        Lang.setLang(this, Cfg.get(this).lang());
        build();
    }

    private void build() {
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, Ui.dp(this, 10), pad, 0);
        root.addView(Ui.topBar(this, Lang.t("stats_title")));

        LinearLayout tabs = Ui.row(this);
        tabApp = tabButton(Lang.t("stats_tab_app"));
        tabAnswer = tabButton(Lang.t("stats_tab_answer"));
        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t1.rightMargin = Ui.dp(this, 8);
        tabs.addView(tabApp, t1);
        tabs.addView(tabAnswer, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(tabs);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        content = Ui.col(this);
        content.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 28));
        sv.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Ui.padForSystemBars(root);

        tabApp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tab = 0;
                render();
            }
        });
        tabAnswer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tab = 1;
                render();
            }
        });
        render();
    }

    private TextView tabButton(String text) {
        TextView t = Ui.tv(this, text, 14, Ui.MUTED, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 11), 0, Ui.dp(this, 11));
        t.setBackground(Ui.pressable(this, Ui.CARD, 10, 0x33FFFFFF));
        return t;
    }

    private void render() {
        boolean appTab = tab == 0;
        tabApp.setBackground(appTab
                ? Ui.pressable(this, Ui.AMBER, 10, 0x55FFFFFF)
                : Ui.pressable(this, Ui.CARD, 10, 0x33FFFFFF));
        tabApp.setTextColor(appTab ? Ui.INK : Ui.MUTED);
        tabAnswer.setBackground(!appTab
                ? Ui.pressable(this, Ui.AMBER, 10, 0x55FFFFFF)
                : Ui.pressable(this, Ui.CARD, 10, 0x33FFFFFF));
        tabAnswer.setTextColor(!appTab ? Ui.INK : Ui.MUTED);

        content.removeAllViews();
        if (appTab) {
            renderApps();
        } else {
            renderAnswers();
        }
    }

    // ------------------------------------------------------------ 按应用

    private void renderApps() {
        String day = Store.today();
        int total = store.activeSeconds(day);
        int asked = store.countToday();

        LinearLayout head = Ui.card(this);
        LinearLayout r = Ui.row(this);
        LinearLayout c1 = Ui.col(this);
        c1.addView(Ui.tv(this, Lang.t("stats_active", ""), 12, Ui.DIM, false));
        TextView big = Ui.tv(this, GrillService.fmtDuration(total), 24, Ui.TEXT, true);
        c1.addView(big);
        r.addView(c1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView q = Ui.tv(this, Lang.t("stats_popups", asked), 13, Ui.AMBER, true);
        r.addView(q);
        head.addView(r);
        content.addView(head);

        if (!hasUsageAccess()) {
            LinearLayout warn = Ui.card(this);
            TextView t = Ui.tv(this, Lang.t("stats_no_perm"), 13, Ui.MUTED, false);
            t.setLineSpacing(Ui.dp(this, 3), 1f);
            warn.addView(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 10);
            content.addView(warn, lp);
            return;
        }

        List<Store.Use> list = store.usageByApp(day);
        if (list.isEmpty()) {
            empty();
            return;
        }
        int max = Math.max(1, list.get(0).sec);
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(this, 12);
        content.addView(card, clp);

        int i = 0;
        for (Store.Use u : list) {
            if (i++ > 0) {
                Ui.addDivider(card);
            }
            card.addView(appRow(u, max));
        }
    }

    private View appRow(Store.Use u, int max) {
        LinearLayout box = Ui.col(this);
        box.setPadding(0, Ui.dp(this, 11), 0, Ui.dp(this, 11));
        LinearLayout top = Ui.row(this);
        TextView name = Ui.tv(this, u.app.length() == 0 ? Lang.t("stats_unknown") : u.app,
                14, Ui.TEXT, false);
        TextView dur = Ui.tv(this, GrillService.fmtDuration(u.sec), 12, Ui.MUTED, false);
        top.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(dur);
        box.addView(top);
        View bar = Ui.bar(this, Ui.AMBER, u.sec / (float) max);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 8);
        box.addView(bar, blp);
        return box;
    }

    // ------------------------------------------------------------ 我的回答

    private void renderAnswers() {
        List<Store.Rec> rows = store.records(0);
        if (rows.isEmpty()) {
            empty();
            return;
        }
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (Store.Rec r : rows) {
            String a = r.answer == null ? "" : r.answer.trim();
            if (a.length() == 0) {
                continue;
            }
            Integer old = counter.get(a);
            counter.put(a, old == null ? 1 : old + 1);
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counter.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });

        LinearLayout card = Ui.card(this);
        content.addView(card);
        int max = Math.max(1, list.get(0).getValue());
        int i = 0;
        for (Map.Entry<String, Integer> e : list) {
            if (i++ >= 30) {
                break;
            }
            if (i > 1) {
                Ui.addDivider(card);
            }
            LinearLayout box = Ui.col(this);
            box.setPadding(0, Ui.dp(this, 11), 0, Ui.dp(this, 11));
            LinearLayout top = Ui.row(this);
            TextView text = Ui.tv(this, e.getKey(), 14, Ui.TEXT, false);
            text.setMaxLines(2);
            TextView cnt = Ui.tv(this, Lang.t("stats_times", e.getValue()), 12, Ui.AMBER, true);
            top.addView(text, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(cnt);
            box.addView(top);
            View bar = Ui.bar(this, 0xFF6B7178, e.getValue() / (float) max);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = Ui.dp(this, 8);
            box.addView(bar, blp);
            card.addView(box);
        }
    }

    private void empty() {
        LinearLayout card = Ui.card(this);
        TextView t = Ui.tv(this, Lang.t("stats_empty"), 13, Ui.MUTED, false);
        card.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        content.addView(card, lp);
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
}
