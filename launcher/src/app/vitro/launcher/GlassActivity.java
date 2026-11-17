package app.vitro.launcher;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.util.Arrays;

/**
 * Painel de vidro com Views comuns, sem OpenGL: a primeira configuração (SetupActivity) e o Sobre (AboutActivity).
 * O fundo é o papel de parede do Vitro, bem reduzido (a ampliação suave o desfoca), com um véu escuro por cima.
 * Telas: prototipo/configuracao.html.
 */
abstract class GlassActivity extends Activity {
    static final int NARROW = 660, WIDE = 820;

    SharedPreferences prefs;
    float dp;
    LinearLayout panel, dots, body, keys;
    TextView stepLabel, title, status;
    boolean busy;
    /** O que recebe o foco ao desenhar a tela: o primeiro cartão ou linha; sem eles, o primeiro botão. */
    View firstFocus;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("vitro", MODE_PRIVATE);
        L.load(this);
        dp = getResources().getDisplayMetrics().density;
        setContentView(buildUi());
    }

    /** Começo de cada tela: limpa o painel e ajusta a largura. */
    void clear(int widthDp) {
        busy = false;
        firstFocus = null;
        body.removeAllViews();
        keys.removeAllViews();
        status.setText("");
        ViewGroup.LayoutParams lp = panel.getLayoutParams();
        if (lp.width != px(widthDp)) {
            lp.width = px(widthDp);
            panel.setLayoutParams(lp);
        }
    }

    /** Fim de cada tela: o foco no primeiro cartão ou linha; sem eles, no primeiro botão. */
    void focusFirst() {
        View f = firstFocus != null ? firstFocus : keys.getChildCount() > 0 ? keys.getChildAt(0) : null;
        if (f != null) f.requestFocus();
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(background());

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(40), px(32), px(40), px(28));
        panel.setBackground(round(0x1AFFFFFF, 0x38FFFFFF, 1, 30));
        panel.setMinimumHeight(px(400));
        root.addView(panel, new FrameLayout.LayoutParams(px(NARROW), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        stepLabel = label(13, false, 0xB3FFFFFF);
        stepLabel.setLetterSpacing(0.08f);
        head.addView(stepLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dots = new LinearLayout(this);
        head.addView(dots);
        panel.addView(head);

        title = label(30, true, 0xFFFFFFFF);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = px(10);
        tlp.bottomMargin = px(12);
        panel.addView(title, tlp);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        panel.addView(body);

        keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.topMargin = px(18);
        panel.addView(keys, klp);

        // Última linha do painel: o aviso à esquerda e, à direita, a dica do controle. A dica fica dentro do vidro
        // e na mesma linha do aviso, sem aumentar o painel; no canto da tela, um painel alto passava por cima dela.
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.BOTTOM);
        status = label(13, false, 0xB3FFFFFF);
        foot.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView hint = label(12, false, 0x8CFFFFFF);
        hint.setText(L.t("Setas para escolher · OK para confirmar", "Arrows to choose · OK to confirm"));
        hint.setSingleLine(true);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.leftMargin = px(16);
        foot.addView(hint, hlp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = px(14);
        panel.addView(foot, slp);
        return root;
    }

    /** Os pontinhos do alto: on acesos de n. */
    void dots(int on, int n) {
        dots.removeAllViews();
        for (int i = 0; i < n; i++) {
            View d = new View(this);
            d.setBackground(round(i < on ? 0xFFFFFFFF : 0x4DFFFFFF, 0, 0, 3.5f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(7), px(7));
            lp.leftMargin = px(6);
            dots.addView(d, lp);
        }
    }

    TextView text(String s) {
        TextView t = label(16, false, 0xEBFFFFFF);
        t.setText(s);
        t.setLineSpacing(0f, 1.3f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(9);
        body.addView(t, lp);
        return t;
    }

    TextView key(String s, View.OnClickListener l) {
        TextView k = label(16, false, 0xFFFFFFFF);
        k.setText(s);
        k.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        k.setPadding(px(22), px(11), px(22), px(11));
        k.setBackground(focusBg(0x24FFFFFF, 0x33FFFFFF, 999));
        k.setTextColor(focusColor(0xFFFFFFFF));
        k.setFocusable(true);
        k.setClickable(true);
        k.setOnClickListener(v -> { if (!busy) l.onClick(v); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (keys.getChildCount() > 0) lp.leftMargin = px(12);
        keys.addView(k, lp);
        return k;
    }

    /** Uma opção em cartão, clicável: nome, um número ao lado e uma descrição curta embaixo. */
    View card(String name, String count, String desc, Runnable action) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(px(16), px(10), px(16), px(11));
        c.setBackground(focusBg(0x12FFFFFF, 0x24FFFFFF, 16));
        c.setFocusable(true);
        c.setClickable(true);
        c.setOnClickListener(v -> { if (!busy) action.run(); });
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.BOTTOM);
        top.setDuplicateParentStateEnabled(true);
        TextView n = label(16, true, 0);
        n.setText(name);
        n.setTextColor(focusColor(0xFFFFFFFF));
        n.setDuplicateParentStateEnabled(true);
        top.addView(n);
        TextView cnt = label(13, false, 0);
        cnt.setText(count);
        cnt.setTextColor(focusColor(0xB3FFFFFF));
        cnt.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = px(8);
        top.addView(cnt, clp);
        c.addView(top);
        TextView d = label(13.5f, false, 0);
        d.setText(desc);
        d.setLineSpacing(0f, 1.2f);
        d.setTextColor(focusColor(0xD9FFFFFF));
        d.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = px(2);
        c.addView(d, dlp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(8);
        body.addView(c, lp);
        if (firstFocus == null) firstFocus = c;
        return c;
    }

    void setBusy(String msg) {
        busy = true;
        status.setText(msg);
        for (int i = 0; i < keys.getChildCount(); i++) keys.getChildAt(i).setEnabled(false);
    }

    /** Fundo que fica branco com o foco (o item escolhido pelas setas). */
    StateListDrawable focusBg(int fill, int stroke, float radius) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, round(0xFFFFFFFF, 0xFFFFFFFF, 1, radius));
        s.addState(new int[] {}, round(fill, stroke, stroke == 0 ? 0 : 1, radius));
        return s;
    }

    /** Texto escuro sobre o fundo branco do foco; a cor dada no resto do tempo. */
    static ColorStateList focusColor(int normal) {
        int a = normal >>> 24;
        int dark = (Math.max(0x99, a) << 24) | 0x15171C;
        return new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}}, new int[] {dark, normal});
    }

    TextView label(float sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    GradientDrawable round(int fill, int stroke, int strokeDp, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (strokeDp > 0) g.setStroke(px(strokeDp), stroke);
        g.setCornerRadius(radiusDp * dp);
        return g;
    }

    private Drawable background() {
        Drawable veil = new ColorDrawable(0x8C080A10);
        try {
            String name = prefs.getString("wallpaperName", null);
            String[] all = getAssets().list("wallpapers");
            if (name == null || !name.endsWith(".jpg")) {
                // Sem foto escolhida: o papel padrão (o mesmo da tela inicial numa instalação nova), ou a primeira foto.
                name = null;
                if (all != null) {
                    Arrays.sort(all);
                    if (Arrays.asList(all).contains(HomeActivity.DEFAULT_WALLPAPER)) name = HomeActivity.DEFAULT_WALLPAPER;
                    else for (String n : all) if (n.endsWith(".jpg")) { name = n; break; }
                }
            }
            if (name != null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 16;
                try (InputStream is = getAssets().open("wallpapers/" + name)) {
                    Bitmap small = BitmapFactory.decodeStream(is, null, o);
                    if (small != null) {
                        Bitmap tiny = Bitmap.createScaledBitmap(small, 48, 27, true);
                        BitmapDrawable b = new BitmapDrawable(getResources(), Bitmap.createScaledBitmap(tiny, 192, 108, true));
                        b.setFilterBitmap(true);
                        return new LayerDrawable(new Drawable[] {b, veil});
                    }
                }
            }
        } catch (Exception x) {
            Log.w("VitroConfig", "fundo sem papel de parede (" + x + ")");
        }
        return new LayerDrawable(new Drawable[] {new ColorDrawable(0xFF14161C), veil});
    }

    int px(float v) {
        return Math.round(v * dp);
    }
}
