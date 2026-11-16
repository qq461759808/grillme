package com.grillme.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/** 记录：倒序排，按天分段。每条就是一次被问和当时的回答。 */
public class HistoryActivity extends Activity {

    /** 一次最多解几张缩略图，避免一屏几十张图把内存顶爆。 */
    private int thumbBudget = 50;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Lang.init(this);
        Lang.setLang(this, Cfg.get(this).lang());
        build();
    }

    private void build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);

        LinearLayout root = Ui.col(this);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 28));
        root.addView(Ui.topBar(this, Lang.t("history_title")));

        List<Store.Rec> rows = Store.get(this).records(300);
        if (rows.isEmpty()) {
            LinearLayout empty = Ui.card(this);
            TextView t = Ui.tv(this, Lang.t("history_empty"), 14, Ui.MUTED, false);
            t.setLineSpacing(Ui.dp(this, 4), 1f);
            empty.addView(t);
            root.addView(empty);
            sv.addView(root);
            setContentView(sv);
            Ui.padForSystemBars(sv);
            return;
        }

        TextView count = Ui.tv(this, Lang.t("history_count", rows.size()), 12, Ui.DIM, false);
        count.setPadding(Ui.dp(this, 6), 0, 0, Ui.dp(this, 10));
        root.addView(count);

        String lastDay = "";
        LinearLayout card = null;
        for (Store.Rec r : rows) {
            if (!r.day.equals(lastDay)) {
                lastDay = r.day;
                TextView day = Ui.tv(this, dayText(r.day), 12, Ui.DIM, true);
                day.setPadding(Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 8));
                root.addView(day);
                card = Ui.card(this);
                root.addView(card);
            }
            if (card == null) {
                continue;
            }
            if (card.getChildCount() > 0) {
                Ui.addDivider(card);
            }
            card.addView(item(r));
        }

        sv.addView(root);
        setContentView(sv);
        Ui.padForSystemBars(sv);
    }

    private String dayText(String day) {
        String today = Store.today();
        if (today.equals(day)) {
            return Lang.t("today");
        }
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.add(java.util.Calendar.DAY_OF_MONTH, -1);
            String y = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(c.getTime());
            if (y.equals(day)) {
                return Lang.t("yesterday");
            }
        } catch (Exception ignored) {
        }
        return day;
    }

    private View item(Store.Rec r) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));

        TextView time = Ui.tv(this, r.time, 12, Ui.DIM, false);
        time.setMinWidth(Ui.dp(this, 46));
        row.addView(time);

        LinearLayout body = Ui.col(this);
        TextView answer = Ui.tv(this, r.answer, 15, Ui.TEXT, false);
        answer.setLineSpacing(Ui.dp(this, 3), 1f);
        body.addView(answer);

        String meta = metaOf(r);
        if (meta.length() > 0) {
            TextView m = Ui.tv(this, meta, 11, Ui.DIM, false);
            m.setPadding(0, Ui.dp(this, 5), 0, 0);
            body.addView(m);
        }

        final File shot = (r.shot != null && r.shot.length() > 0)
                ? Shots.fileOf(this, r.shot) : null;
        if (shot != null && thumbBudget > 0) {
            thumbBudget--;
            Bitmap bmp = Shots.decode(shot, 480);
            if (bmp != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(bmp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(Ui.shape(Ui.CARD2, 10, 0, 0f, this));
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 150));
                ilp.topMargin = Ui.dp(this, 9);
                iv.setLayoutParams(ilp);
                iv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showShot(shot);
                    }
                });
                body.addView(iv);
                TextView hint = Ui.tv(this, Lang.t("shot_tap"), 10, Ui.DIM, false);
                hint.setPadding(0, Ui.dp(this, 5), 0, 0);
                body.addView(hint);
            }
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(this, 8);
        row.addView(body, lp);
        return row;
    }

    /** 点缩略图看大图，也可以从这里删掉这张。 */
    private void showShot(final File f) {
        Bitmap b = Shots.decode(f, 1600);
        if (b == null) {
            Ui.toast(this, Lang.t("shot_none"));
            return;
        }
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(b);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);

        LinearLayout box = Ui.col(this);
        int p = Ui.dp(this, 12);
        box.setPadding(p, p, p, p);
        box.addView(iv);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setView(box)
                .setPositiveButton(Lang.t("shot_delete"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (f.delete()) {
                            Ui.toast(HistoryActivity.this, Lang.t("done"));
                            recreate();
                        }
                    }
                })
                .setNegativeButton(Lang.t("close"), null)
                .show();
    }

    private String metaOf(Store.Rec r) {
        StringBuilder sb = new StringBuilder();
        if ("slip".equals(r.kind)) {
            sb.append(Lang.t("history_slip"));
        } else if ("rest".equals(r.kind)) {
            sb.append(Lang.t("group_long"));
        } else if ("long".equals(r.kind)) {
            sb.append(Lang.t("group_long"));
        } else if ("back".equals(r.kind)) {
            sb.append(Lang.t("popup_back_sub"));
        }
        if (r.span > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("+" + GrillService.fmtDuration(r.span));
        }
        return sb.toString();
    }
}
