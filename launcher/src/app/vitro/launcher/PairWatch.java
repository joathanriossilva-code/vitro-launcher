package app.vitro.launcher;

import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Pareamento com a "Depuração sem fio", feito pelo serviço do botão Home (ver AdbPairing).
 *
 * Numa TV não dá para ter duas telas lado a lado, e sair das Configurações pode fechar o código de pareamento. Então
 * o Vitro fica de olho na rede local: quando a TV anuncia o serviço de pareamento ("_adb-tls-pairing._tcp", sinal de
 * que a tela do código está aberta), ele mostra por cima das Configurações, embaixo, um painel com seis casas. A
 * pessoa digita o código com o controle (↑ ↓ mudam o número, ← → trocam de casa, os números do controle também
 * servem, OK pareia, Voltar fecha) e as Configurações continuam abertas atrás, com o código valendo.
 */
final class PairWatch {
    private static final String TAG = "VitroHome";
    /** Quanto tempo o Vitro fica esperando a pessoa abrir o código de pareamento. */
    private static final long WATCH_MS = 10 * 60_000L;

    private final HomeService s;
    private final WindowManager wm;
    private final Handler ui;
    private volatile boolean watching;
    private volatile int shownPort = -1;
    private LinearLayout panel;
    private TextView[] boxes;
    private TextView status;
    private final int[] digits = new int[6];
    private int at;
    private boolean busy;

    PairWatch(HomeService s, WindowManager wm, Handler ui) {
        this.s = s;
        this.wm = wm;
        this.ui = ui;
    }

    void start() {
        if (watching) return;
        watching = true;
        shownPort = -1;
        Thread t = new Thread(this::watch, "vitro-pareamento");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "esperando o código de pareamento da depuração sem fio");
    }

    void stop() {
        watching = false;
        ui.post(this::hide);
    }

    private void watch() {
        long until = SystemClock.uptimeMillis() + WATCH_MS;
        while (watching && SystemClock.uptimeMillis() < until) {
            int p = AdbTls.discover(s, AdbTls.PAIRING_SERVICE, 4000L);
            if (p > 0 && p != shownPort) {
                shownPort = p;
                ui.post(() -> show(p));
            }
            SystemClock.sleep(1000L);
        }
        if (watching) Log.i(TAG, "pareamento: o código não apareceu a tempo");
        watching = false;
    }

    // ---- Painel ---------------------------------------------------------------------------------

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, s.getResources().getDisplayMetrics()));
    }

    private void show(int port) {
        java.util.Arrays.fill(digits, -1);
        at = 0;
        busy = false;
        if (panel == null) build();
        status.setText(L.t("Digite o código de 6 dígitos que a TV mostra.", "Type the 6-digit code the TV shows."));
        paint();
        panel.requestFocus();
        Log.i(TAG, "código de pareamento na tela (porta " + port + "): painel do Vitro aberto");
    }

    private void build() {
        LinearLayout p = new LinearLayout(s);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER_HORIZONTAL);
        p.setPadding(dp(28), dp(20), dp(28), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0161A2A);
        bg.setStroke(dp(1), 0x55FFFFFF);
        bg.setCornerRadius(dp(22));
        p.setBackground(bg);

        TextView title = new TextView(s);
        title.setText(L.t("Vitro: código de pareamento", "Vitro: pairing code"));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        p.addView(title);

        LinearLayout row = new LinearLayout(s);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(14), 0, dp(10));
        boxes = new TextView[6];
        for (int i = 0; i < 6; i++) {
            TextView b = new TextView(s);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(64));
            lp.leftMargin = lp.rightMargin = dp(5);
            row.addView(b, lp);
            boxes[i] = b;
        }
        p.addView(row);

        status = new TextView(s);
        status.setTextColor(0xFFE9EAF5);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setGravity(Gravity.CENTER);
        p.addView(status);

        TextView hint = new TextView(s);
        hint.setText(L.t("↑ ↓ mudam o número · ← → trocam de casa · OK pareia · Voltar fecha",
                "↑ ↓ change the digit · ← → move · OK pairs · Back closes"));
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setPadding(0, dp(8), 0, 0);
        p.addView(hint);

        p.setFocusable(true);
        p.setFocusableInTouchMode(true);
        p.setOnKeyListener((v, code, e) -> key(code, e));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(36);
        try {
            wm.addView(p, lp);
            panel = p;
        } catch (RuntimeException x) {
            Log.e(TAG, "não foi possível mostrar o painel do código", x);
        }
    }

    private void paint() {
        if (boxes == null) return;
        for (int i = 0; i < 6; i++) {
            boolean here = i == at && !busy;
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dp(12));
            d.setColor(here ? 0xFFFFFFFF : 0x22FFFFFF);
            d.setStroke(dp(1), 0x44FFFFFF);
            boxes[i].setBackground(d);
            boxes[i].setTextColor(here ? 0xFF161A2A : 0xFFFFFFFF);
            boxes[i].setText(digits[i] < 0 ? "–" : String.valueOf(digits[i]));
        }
    }

    private boolean key(int code, KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (code == KeyEvent.KEYCODE_BACK) {
            watching = false;
            hide();
            return true;
        }
        if (busy) return true;
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            digits[at] = (digits[at] + 1) % 10;
        } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            digits[at] = digits[at] <= 0 ? 9 : digits[at] - 1;
        } else if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            at = Math.max(0, at - 1);
        } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            at = Math.min(5, at + 1);
        } else if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            digits[at] = code - KeyEvent.KEYCODE_0;
            at = Math.min(5, at + 1);
        } else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            submit();
        } else {
            return false;
        }
        paint();
        return true;
    }

    private void submit() {
        StringBuilder code = new StringBuilder();
        for (int d : digits) {
            if (d < 0) {
                status.setText(L.t("Faltam números: são 6.", "Some digits are missing: there are 6."));
                return;
            }
            code.append(d);
        }
        busy = true;
        status.setText(L.t("Pareando…", "Pairing…"));
        final int port = shownPort;
        Handler h = s.adbHandler();
        if (h == null) {
            status.setText(L.t("O serviço do Vitro parou. Tente de novo.", "Vitro's service stopped. Try again."));
            busy = false;
            return;
        }
        h.post(() -> {
            String msg;
            boolean ok = false;
            try {
                AdbPairing.pair(s, AdbTls.pairingHost(), port, code.toString());
                AdbTls.setPaired(s, true);
                s.getSharedPreferences("vitro", android.content.Context.MODE_PRIVATE).edit()
                        .putBoolean("adbAutorizado", true).apply();
                ok = true;
                msg = L.t("Pronto: o Vitro está pareado. Voltando para a configuração…",
                        "Done: Vitro is paired. Going back to setup…");
                Log.i(TAG, "pareamento com a depuração sem fio concluído");
            } catch (AdbPairing.WrongCode x) {
                msg = L.t("O código não confere. Abra de novo o pareamento na TV e digite o código novo.",
                        "The code does not match. Open pairing on the TV again and type the new code.");
                Log.w(TAG, "pareamento: código errado");
            } catch (Exception x) {
                msg = L.t("Não consegui parear: ", "Could not pair: ") + x.getMessage();
                Log.w(TAG, "pareamento falhou", x);
            }
            final String m = msg;
            final boolean done = ok;
            ui.post(() -> {
                if (status != null) status.setText(m);
                busy = false;
                paint();
                if (done) {
                    watching = false;
                    ui.postDelayed(() -> {
                        hide();
                        try {
                            s.startActivity(new Intent(s, SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                        } catch (RuntimeException ignored) { }
                    }, 2000L);
                } else {
                    // Depois de uma tentativa, a TV encerra aquele código: o próximo vem com outra porta.
                    shownPort = -1;
                    ui.postDelayed(this::hide, 4000L);
                }
            });
        });
    }

    private void hide() {
        if (panel == null) return;
        try {
            wm.removeView(panel);
        } catch (RuntimeException ignored) { }
        panel = null;
        boxes = null;
        status = null;
    }
}
