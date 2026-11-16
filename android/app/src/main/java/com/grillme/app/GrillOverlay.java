package com.grillme.app;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 全屏悬浮质问窗。用 TYPE_APPLICATION_OVERLAY 而不是 Activity，
 * 这样在 Android 10+ 的后台启动限制下也能可靠弹出。
 */
public class GrillOverlay {

    public interface Listener {
        void onAnswer(String text, String kind, boolean slip);
    }

    public static class Ask {
        public String kind = "normal";   // normal / long / back / rest
        public int longMinutes;
        public String restTip = "";
        public String appLabel = "";
    }

    private final Context ctx;
    private final Cfg cfg;
    private final Store store;
    private final Ask ask;
    private final Listener listener;

    private WindowManager wm;
    private FrameLayout root;
    private EditText input;
    private TextView passBtn;
    private TextView warn;
    private boolean showing;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private long openedAt;
    private boolean submitted;

    public GrillOverlay(Context ctx, Cfg cfg, Store store, Ask ask, Listener listener) {
        this.ctx = ctx;
        this.cfg = cfg;
        this.store = store;
        this.ask = ask;
        this.listener = listener;
    }

    public boolean isShowing() {
        return showing;
    }

    public void show() {
        if (showing) {
            return;
        }
        try {
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            root = build();
            int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    type,
                    flags,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;
            wm.addView(root, lp);
            showing = true;
            openedAt = System.currentTimeMillis();
            if (input != null) {
                input.requestFocus();
            }
            // 键盘是弹起来的动画，insets 会分几次到，催它重算一下
            if (root != null) {
                root.requestApplyInsets();
                root.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (root != null) {
                            root.requestApplyInsets();
                        }
                    }
                }, 350);
            }
            ui.postDelayed(tick, 100);
        } catch (Exception e) {
            showing = false;
        }
    }

    public void dismiss() {
        showing = false;
        ui.removeCallbacksAndMessages(null);
        try {
            if (root != null && wm != null) {
                wm.removeView(root);
            }
        } catch (Exception ignored) {
        }
        root = null;
    }

    // ------------------------------------------------------------ 界面

    private FrameLayout build() {
        FrameLayout frame = new FrameLayout(ctx);
        frame.setBackgroundColor(0xF2080A0C);

        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        int pad = Ui.dp(ctx, 18);
        // 悬浮窗是 MATCH_PARENT + LAYOUT_NO_LIMITS，会盖住状态栏和导航栏，
        // 所以自己把这两块高度让出来，否则卡片会被三键导航压住
        sv.setPadding(pad, pad + Ui.statusBarHeight(ctx), pad, pad + Ui.navBarHeight(ctx));
        sv.setClipToPadding(false);

        // 键盘弹起来会盖住下面的「放行」按钮，而且输入法的窗口在悬浮窗之上，
        // 落在键盘区域的点击会被输入法吃掉——用户会觉得"按钮点不动"。
        // 悬浮窗不是 Activity，系统不会替它让位，只能自己把 IME 高度补成底部留白。
        final ScrollView fsv = sv;
        final int basePad = pad;
        frame.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int ime = 0;
                if (Build.VERSION.SDK_INT >= 30) {
                    ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                }
                if (ime <= 0) {
                    ime = imeByVisibleFrame(v);
                }
                // 再多留一点：输入法窗口的触摸区往往比看得见的键盘略高
                int bottom = basePad + Ui.navBarHeight(ctx)
                        + (ime > 0 ? ime + Ui.dp(ctx, 10) : 0);
                if (bottom != fsv.getPaddingBottom()) {
                    fsv.setPadding(basePad, basePad + Ui.statusBarHeight(ctx), basePad, bottom);
                }
                return insets;
            }
        });

        LinearLayout holder = new LinearLayout(ctx);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout card = Ui.col(ctx);
        boolean rest = "rest".equals(ask.kind);
        card.setBackground(Ui.shape(rest ? 0xFF1F1A12 : Ui.CARD, 20, rest ? 0xFF3A2E18 : 0, rest ? 1.2f : 0f, ctx));
        int p = Ui.dp(ctx, 22);
        card.setPadding(p, p, p, p);

        // 顶部小字：质问我 · 监督我
        TextView brand = Ui.tv(ctx, Lang.t("app_name"), 11, Ui.DIM, true);
        brand.setLetterSpacing(0.12f);
        card.addView(brand);

        String title;
        String sub;
        if ("long".equals(ask.kind)) {
            title = Lang.t("popup_long_title", ask.longMinutes);
            sub = Lang.t("popup_long_sub");
        } else if ("back".equals(ask.kind)) {
            title = Lang.t("popup_back_title");
            sub = Lang.t("popup_back_sub");
        } else if (rest) {
            title = Lang.t("popup_rest_title");
            sub = Lang.t("popup_rest_sub");
        } else {
            title = Lang.t("popup_normal_title");
            sub = Lang.t("popup_normal_sub");
        }

        TextView t = Ui.tv(ctx, title, 21, rest ? Ui.AMBER : Ui.TEXT, true);
        t.setLineSpacing(Ui.dp(ctx, 4), 1f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(ctx, 12);
        card.addView(t, tlp);

        TextView s = Ui.tv(ctx, sub, 14, rest ? 0xFFB49A6A : Ui.MUTED, false);
        s.setLineSpacing(Ui.dp(ctx, 3), 1f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(ctx, 8);
        card.addView(s, slp);

        if (ask.appLabel != null && ask.appLabel.length() > 0 && !rest) {
            TextView app = Ui.tv(ctx, "▸ " + ask.appLabel, 12, Ui.DIM, false);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.topMargin = Ui.dp(ctx, 12);
            card.addView(app, alp);
        }

        if (rest && ask.restTip != null && ask.restTip.length() > 0) {
            LinearLayout tip = Ui.col(ctx);
            tip.setBackground(Ui.shape(Ui.AMBER_DARK, 14, 0xFF4A3A1C, 1f, ctx));
            int tp = Ui.dp(ctx, 16);
            tip.setPadding(tp, tp, tp, tp);
            TextView tipText = Ui.tv(ctx, ask.restTip, 18, Ui.AMBER, true);
            tipText.setLineSpacing(Ui.dp(ctx, 4), 1f);
            tip.addView(tipText);
            TextView foot = Ui.tv(ctx, Lang.t("popup_rest_footer"), 12, 0xFF8A7A56, false);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            flp.topMargin = Ui.dp(ctx, 8);
            tip.addView(foot, flp);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.topMargin = Ui.dp(ctx, 18);
            card.addView(tip, tl);
        }

        input = new NoPasteEditText(ctx, cfg);
        input.setHint(Lang.t("popup_placeholder"));
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.DIM);
        input.setTextSize(16);
        input.setBackground(Ui.shape(0xFF101216, 12, Ui.LINE, 1f, ctx));
        input.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 12));
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_NONE);
        input.setMinLines(3);
        input.setMaxLines(5);
        input.setVerticalScrollBarEnabled(true);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = Ui.dp(ctx, 18);
        card.addView(input, ilp);

        warn = Ui.tv(ctx, "", 13, Ui.RED, false);
        warn.setVisibility(View.GONE);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = Ui.dp(ctx, 10);
        card.addView(warn, wlp);

        LinearLayout buttons = Ui.row(ctx);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(ctx, 18);

        TextView slip = Ui.ghost(ctx, Lang.t("popup_slip"));
        slip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit(Lang.t("popup_slip_answer"), true);
            }
        });
        // 起身提醒本来就不给这个出口；设置里也能关掉。关掉之后只剩
        // 「说清楚了，继续」一条路——想彻底不给自己留台阶就关掉它。
        final boolean showSlip = !rest && cfg.showSlip();
        if (!showSlip) {
            slip.setVisibility(View.GONE);
        }
        LinearLayout.LayoutParams slipLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slipLp.rightMargin = Ui.dp(ctx, 10);
        buttons.addView(slip, slipLp);

        passBtn = Ui.primary(ctx, Lang.t("popup_pass"));
        passBtn.setAlpha(0.45f);
        passBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit(input.getText().toString(), false);
            }
        });
        buttons.addView(passBtn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, showSlip ? 1.2f : 1f));

        card.addView(buttons, blp);

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                refreshPass();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        holder.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.addView(holder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        frame.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 吞掉返回键：不许绕过去
        frame.setFocusableInTouchMode(true);
        frame.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                return keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE;
            }
        });
        return frame;
    }

    /** API 30 以下拿不到 IME inset，用「可见区域比窗口矮多少」来估。 */
    private int imeByVisibleFrame(View v) {
        try {
            android.graphics.Rect r = new android.graphics.Rect();
            v.getWindowVisibleDisplayFrame(r);
            int h = v.getRootView().getHeight();
            int diff = h - r.bottom;
            return Math.max(0, diff - Ui.navBarHeight(ctx));
        } catch (Exception e) {
            return 0;
        }
    }

    private void refreshPass() {
        if (passBtn == null || input == null) {
            return;
        }
        int len = input.getText().toString().trim().length();
        boolean enough = len >= cfg.minChars();
        boolean cool = remaining() <= 0f;
        passBtn.setEnabled(enough && cool);
        passBtn.setAlpha(enough && cool ? 1f : 0.45f);
        if (cool) {
            passBtn.setText(Lang.t("popup_pass"));
        }
    }

    private float remaining() {
        float cd = cfg.cooldown();
        float gone = (System.currentTimeMillis() - openedAt) / 1000f;
        float left = cd - gone;
        return left > 0 ? left : 0f;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!showing) {
                return;
            }
            float left = remaining();
            if (left > 0 && passBtn != null) {
                passBtn.setText(Lang.t("popup_cooldown", Lang.one(left)));
                passBtn.setEnabled(false);
                passBtn.setAlpha(0.45f);
            } else {
                refreshPass();
            }
            ui.postDelayed(this, 100);
        }
    };

    private void showWarn(String text) {
        if (warn == null) {
            return;
        }
        warn.setText(text);
        warn.setVisibility(View.VISIBLE);
    }

    private void submit(String raw, boolean slip) {
        if (submitted) {
            return;
        }
        if (remaining() > 0f) {
            return;
        }
        String text = raw == null ? "" : raw.trim();

        if (slip) {
            text = Lang.t("popup_slip_answer");
        } else {
            if (text.length() < cfg.minChars()) {
                showWarn(Lang.t("popup_min_chars", cfg.minChars()));
                return;
            }
            String last = store.lastAnswer();
            boolean same = last.length() > 0 && last.equals(text);
            if (same && !cfg.allowRepeat()) {
                showWarn(Lang.t("popup_repeat", repeatCount(text) + 1) + " · "
                        + Lang.t("popup_repeat_hint"));
                return;
            }
            if (same && cfg.repeatWarn() > 0 && repeatCount(text) + 1 >= cfg.repeatWarn()) {
                showWarn(Lang.t("popup_repeat", repeatCount(text) + 1) + " · "
                        + Lang.t("popup_repeat_hint"));
                // 只警告，不拦：允许重复时用户自己承担
            }
        }

        submitted = true;
        String kind = slip ? "slip" : ask.kind;
        dismiss();
        if (listener != null) {
            listener.onAnswer(text, kind, slip);
        }
    }

    /** 最近有多少条连续相同的回答（不含本条）。 */
    private int repeatCount(String text) {
        List<String> recent = store.recentAnswers(20);
        int n = 0;
        for (String a : recent) {
            if (text.equals(a)) {
                n++;
            } else {
                break;
            }
        }
        return n;
    }

    /** 不许粘贴：长按菜单里的「粘贴」直接吃掉，并提示自己打。 */
    private class NoPasteEditText extends EditText {
        private final Cfg c;

        NoPasteEditText(Context context, Cfg c) {
            super(context);
            this.c = c;
        }

        @Override
        public boolean onTextContextMenuItem(int id) {
            if (c.blockPaste()
                    && (id == android.R.id.paste || id == android.R.id.pasteAsPlainText)) {
                showWarn(Lang.t("popup_paste_blocked"));
                return true;
            }
            return super.onTextContextMenuItem(id);
        }
    }
}
