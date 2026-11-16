package com.grillme.app;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 所有界面都靠代码拼，不用 XML 布局、不用 AndroidX。这里放共用的小零件。 */
public final class Ui {

    public static final int BG = 0xFF0E1014;
    public static final int CARD = 0xFF1A1D23;
    public static final int CARD2 = 0xFF23262E;
    public static final int LINE = 0xFF2A2E36;
    public static final int TEXT = 0xFFE8EAED;
    public static final int MUTED = 0xFF9AA0A6;
    public static final int DIM = 0xFF6B7178;
    public static final int AMBER = 0xFFFFB02E;
    public static final int AMBER_DARK = 0xFF2A2116;
    public static final int RED = 0xFFFF5A45;
    public static final int GREEN = 0xFF3DD68C;
    public static final int INK = 0xFF14161A;

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()) + 0.5f);
    }

    public static int sp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                c.getResources().getDisplayMetrics());
    }

    public static GradientDrawable shape(int fill, float radiusDp, int strokeColor, float strokeDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0f) {
            d.setStroke(Math.max(1, dp(c, strokeDp)), strokeColor);
        }
        return d;
    }

    /** 有按压反馈的圆角背景。 */
    public static Drawable pressable(Context c, int fill, float radiusDp, int rippleColor) {
        GradientDrawable base = shape(fill, radiusDp, 0, 0f, c);
        return new RippleDrawable(android.content.res.ColorStateList.valueOf(rippleColor), base, null);
    }

    public static TextView tv(Context c, CharSequence text, float sizeSp, int color, boolean medium) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(sizeSp);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        if (medium) {
            v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return v;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** 一张卡片：深灰圆角，内边距 16。 */
    public static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        l.setBackground(shape(CARD, 16, 0, 0f, c));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static TextView primary(Context c, String text) {
        TextView b = tv(c, text, 16, INK, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(pressable(c, AMBER, 12, 0x55FFFFFF));
        b.setPadding(dp(c, 18), dp(c, 14), dp(c, 18), dp(c, 14));
        b.setClickable(true);
        return b;
    }

    public static TextView ghost(Context c, String text) {
        TextView b = tv(c, text, 15, TEXT, false);
        b.setGravity(Gravity.CENTER);
        b.setBackground(pressable(c, CARD2, 12, 0x33FFFFFF));
        b.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        b.setClickable(true);
        return b;
    }

    /** 顶部返回条：左边一个「← 返回」，右边可选标题。 */
    public static LinearLayout topBar(final android.app.Activity a, String title) {
        LinearLayout bar = row(a);
        bar.setPadding(dp(a, 12), dp(a, 12), dp(a, 12), dp(a, 8));
        TextView back = tv(a, "←  " + Lang.t("back"), 15, MUTED, false);
        back.setPadding(dp(a, 8), dp(a, 8), dp(a, 12), dp(a, 8));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.finish();
            }
        });
        bar.addView(back);
        View spacer = new View(a);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView t = tv(a, title, 15, TEXT, true);
        t.setPadding(0, dp(a, 8), dp(a, 8), dp(a, 8));
        bar.addView(t);
        return bar;
    }

    /** 分组标题。 */
    public static TextView section(Context c, String text) {
        TextView t = tv(c, text, 12, DIM, true);
        t.setPadding(dp(c, 6), dp(c, 18), dp(c, 6), dp(c, 8));
        t.setLetterSpacing(0.08f);
        return t;
    }

    /**
     * 分隔线。必须走 addDivider 加进父容器：裸 View 用 wrap_content 时，
     * View.getDefaultSize() 在 AT_MOST 下会返回整个可用高度，分隔线会撑满整张卡片。
     */
    public static void addDivider(LinearLayout parent) {
        addDivider(parent, 0);
    }

    public static void addDivider(LinearLayout parent, int topMarginDp) {
        Context c = parent.getContext();
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.7f)));
        lp.topMargin = dp(c, topMarginDp);
        parent.addView(v, lp);
    }

    /**
     * Android 15 起（targetSdk 35+）系统强制 edge-to-edge：窗口直接画到状态栏和导航栏底下。
     * 所以每个页面都要自己把系统栏的高度让出来，否则顶部文字压在通知栏上、底部按钮被三键导航盖住。
     */
    public static void padForSystemBars(final View v) {
        final int l0 = v.getPaddingLeft();
        final int t0 = v.getPaddingTop();
        final int r0 = v.getPaddingRight();
        final int b0 = v.getPaddingBottom();
        final boolean[] got = {false};
        v.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                int l;
                int t;
                int r;
                int b;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    l = bars.left;
                    t = bars.top;
                    r = bars.right;
                    b = bars.bottom;
                } else {
                    l = insets.getSystemWindowInsetLeft();
                    t = insets.getSystemWindowInsetTop();
                    r = insets.getSystemWindowInsetRight();
                    b = insets.getSystemWindowInsetBottom();
                }
                got[0] = true;
                // 竖屏手机上系统栏只可能在上下，左右内边距必须是 0。
                // 但 recreate()（切语言会走这条路）时系统派发的 insets 里会带上一个
                // 四十多 dp 的左边值，照抄进去整页就往右缩一截，「返回」会从最左边
                // 跑到屏幕中间。横屏才真的需要侧边距（导航栏在侧边）。
                boolean landscape = view.getResources().getConfiguration().orientation
                        == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                if (!landscape) {
                    l = 0;
                    r = 0;
                }
                view.setPadding(l0 + l, t0 + t, r0 + r, b0 + b);
                return insets;
            }
        });
        v.requestApplyInsets();

        // 少数 ROM 不派发 insets，退化成用系统资源里的固定高度兜底，
        // 至少不会出现"内容压在通知栏下面"这种明显错位
        v.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (got[0]) {
                    return;
                }
                Context c = v.getContext();
                // 注意左边距只能给 l0。这里以前写的是 l0 + statusBarHeight(c)，
                // 等于把状态栏高度当成左边距，竖屏下整页往右缩一截。切语言会走
                // recreate()，那一次 insets 经常赶不上这 400ms，于是每次都命中兜底，
                // 「返回」就从最左边跳到屏幕中间。
                v.setPadding(l0, t0 + statusBarHeight(c), r0, b0 + navBarHeight(c));
            }
        }, 400);
    }

    /** 从系统资源里取状态栏高度，给悬浮窗用（悬浮窗拿不到可靠的 insets）。 */
    public static int statusBarHeight(Context c) {
        return dimen(c, "status_bar_height", 24);
    }

    /** 导航栏高度；手势导航时通常很小。 */
    public static int navBarHeight(Context c) {
        return dimen(c, "navigation_bar_height", 48);
    }

    private static int dimen(Context c, String name, int fallbackDp) {
        try {
            Resources r = c.getResources();
            int id = r.getIdentifier(name, "dimen", "android");
            if (id > 0) {
                return r.getDimensionPixelSize(id);
            }
        } catch (Exception ignored) {
        }
        return dp(c, fallbackDp);
    }

    public static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }

    /** 一行「标题 + 说明」，右侧留给你塞控件。 */
    public static LinearLayout line(Context c, String title, String sub, View right) {
        return line(c, title, sub, right, null);
    }

    /** 同上，但标题后面挂一个问号，点开解释这一项到底在干什么。 */
    public static LinearLayout line(Context c, String title, String sub, View right, String help) {
        // 右侧控件必须和标题同一行。之前把标题+副标题放在左侧一列里、右侧控件
        // 垂直居中，副标题一长，状态文字就会跑到副标题那一行上去，读起来像一句话被截断。
        LinearLayout box = col(c);
        box.setPadding(0, dp(c, 10), 0, dp(c, 10));

        LinearLayout top = row(c);
        final boolean hasHelp = help != null && help.length() > 0;
        TextView t = tv(c, hasHelp ? titleWithHelp(title) : title, 15, TEXT, false);
        if (hasHelp) {
            // 整条标题可点。本来想只让问号那几个像素可点（ClickableSpan），但
            // LinkMovementMethod 在这台机器上根本没命中 span，点了没反应。
            // 改成整条标题可点反而更一致：点标题看解释，点右侧控件改数值。
            final String h = help;
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new android.app.AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle(title)
                            .setMessage(h)
                            .setPositiveButton(Lang.t("ok"), null)
                            .show();
                }
            });
        }
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (right != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(c, 12);
            top.addView(right, lp);
        }
        box.addView(top);

        if (sub != null && sub.length() > 0) {
            TextView s = tv(c, sub, 12, DIM, false);
            s.setPadding(0, dp(c, 4), 0, 0);
            box.addView(s);
        }
        return box;
    }

    /** 标题末尾缀一个小问号，作为「这里能点」的提示。 */
    private static CharSequence titleWithHelp(String title) {
        // 用窄空格 + 0.85 倍的问号，只占二十来像素。用普通空格 + 原尺寸的话，
        // 「两次质问的最短间隔」这种长标题会正好被挤到第二行，中文断成「隔 ?」很难看。
        // 隔两个窄空格，是为了让问号别贴着文字——贴着读起来像「间隔?」这种疑问句。
        String s = title + "\u2009\u2009?";
        android.text.SpannableString sp = new android.text.SpannableString(s);
        int a = s.length() - 1;
        int b = s.length();
        int flags = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
        sp.setSpan(new android.text.style.RelativeSizeSpan(0.85f), a, b, flags);
        sp.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), a, b, flags);
        sp.setSpan(new android.text.style.ForegroundColorSpan(MUTED), a, b, flags);
        return sp;
    }

    /** 细长的水平进度条，用来画占比。 */
    public static View bar(Context c, int color, float fraction) {
        LinearLayout holder = new LinearLayout(c);
        holder.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = shape(0xFF262A31, 3, 0, 0f, c);
        holder.setBackground(bg);
        View fill = new View(c);
        fill.setBackground(shape(color, 3, 0, 0f, c));
        float f = Math.max(0.02f, Math.min(1f, fraction));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(c, 6), f);
        holder.addView(fill, lp);
        View rest = new View(c);
        holder.addView(rest, new LinearLayout.LayoutParams(0, dp(c, 6), 1f - f));
        return holder;
    }

    public static Drawable solid(int color) {
        return new ColorDrawable(color);
    }

    public static int alpha(int color, float a) {
        return Color.argb((int) (a * 255), Color.red(color), Color.green(color), Color.blue(color));
    }
}
