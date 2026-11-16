package app.vitro.launcher;

import android.os.Bundle;

/**
 * Sobre: o que é o Vitro, de quem é e a escolha do idioma (português ou inglês, ver L). O conserto do ADB e das
 * permissões fica em Autoreparar, no mesmo menu (ver HomeActivity.repair).
 */
public final class AboutActivity extends GlassActivity {
    static final String GITHUB = "github.com/joathanriossilva-code";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
    }

    private void render() {
        // Com o bloco de apoio (versão pública), o painel largo: os textos ocupam menos linhas e tudo cabe na tela.
        clear(hasAsset("doacao/pix.png") ? WIDE : NARROW);
        stepLabel.setText(L.t("SOBRE O VITRO", "ABOUT VITRO"));
        dots(0, 0);
        title.setText("Vitro " + version());
        text(L.t("Tela inicial de vidro para Fire TV, Android TV e Google TV: botão Home, apps de anúncios e telemetria "
                        + "desativados com cuidado e timer para desligar a TV. Sistema desta TV: " + Platform.name(this) + ".",
                "A glass home screen for Fire TV, Android TV and Google TV: Home button, ad and telemetry apps carefully "
                        + "disabled, and a timer to turn off the TV. This TV's system: " + Platform.name(this) + "."));
        text(L.t("Um projeto de um entusiasta brasileiro, que só queria resolver um problema da própria televisão e "
                        + "resolveu dividir a solução com o mundo.",
                "A project by a Brazilian enthusiast who just wanted to fix a problem with their own TV, and decided to "
                        + "share the fix with the world."));
        text(GITHUB).setTextColor(0xFFC9CCFF);
        donation();
        android.view.View pt = key((L.en() ? "" : "✓ ") + "Português", v -> language(false));
        android.view.View en = key((L.en() ? "✓ " : "") + "English", v -> language(true));
        firstFocus = L.en() ? en : pt;
        key(L.t("Fechar", "Close"), v -> finish());
        status.setText(Repair.status(this));
        focusFirst();
    }

    /**
     * Apoio ao projeto, só na versão pública: o QR Code do PIX (assets/doacao/pix.png, gerado por
     * publico-extra/gerar-pix.py), a chave escrita e, quando existir assets/doacao/sponsors.txt, o endereço do GitHub
     * Sponsors. Sem esses arquivos (a versão da TV do dono), o Sobre fica como antes.
     */
    private void donation() {
        android.graphics.Bitmap qr;
        try (java.io.InputStream in = getAssets().open("doacao/pix.png")) {
            qr = android.graphics.BitmapFactory.decodeStream(in);
        } catch (java.io.IOException x) {
            return;
        }
        if (qr == null) return;
        String key = asset("doacao/chave.txt"), sponsors = asset("doacao/sponsors.txt");

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.ImageView img = new android.widget.ImageView(this);
        // Ampliado sem suavizar: os módulos do QR continuam nítidos para a câmera do celular.
        img.setImageBitmap(android.graphics.Bitmap.createScaledBitmap(qr, px(124), px(124), false));
        img.setBackground(round(0xFFFFFFFF, 0, 0, 10));
        img.setPadding(px(4), px(4), px(4), px(4));
        row.addView(img, new android.widget.LinearLayout.LayoutParams(px(132), px(132)));

        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setPadding(px(18), 0, 0, 0);
        android.widget.TextView t = label(17, true, 0xFFFFFFFF);
        t.setText(L.t("Apoie o Vitro", "Support Vitro"));
        col.addView(t);
        android.widget.TextView d = label(14, false, 0xD9FFFFFF);
        d.setText(L.t("Aponte a câmera do celular para doar pelo PIX. Qualquer valor ajuda a manter o projeto.",
                "Point your phone's camera to donate through PIX (Brazil). Any amount helps keep the project going."));
        d.setLineSpacing(0f, 1.25f);
        d.setPadding(0, px(4), 0, 0);
        col.addView(d);
        if (key != null) {
            android.widget.TextView k = label(12, false, 0x99FFFFFF);
            k.setText(L.t("Chave aleatória: ", "Random key: ") + key);
            k.setPadding(0, px(6), 0, 0);
            col.addView(k);
        }
        if (sponsors != null) {
            android.widget.TextView g = label(13, false, 0xFFC9CCFF);
            g.setText(L.t("Ou pelo GitHub Sponsors: ", "Or through GitHub Sponsors: ") + sponsors);
            g.setPadding(0, px(6), 0, 0);
            col.addView(g);
        }
        row.addView(col, new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(6);
        lp.bottomMargin = px(12);
        body.addView(row, lp);
    }

    private boolean hasAsset(String name) {
        try (java.io.InputStream in = getAssets().open(name)) {
            return true;
        } catch (java.io.IOException x) {
            return false;
        }
    }

    private String asset(String name) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(getAssets().open(name), "UTF-8"))) {
            String line = r.readLine();
            return line == null || line.trim().isEmpty() ? null : line.trim();
        } catch (java.io.IOException x) {
            return null;
        }
    }

    private void language(boolean en) {
        if (en == L.en()) return;
        L.set(this, en);
        // O painel inteiro, inclusive a dica do rodapé, é refeito no novo idioma.
        recreate();
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception x) {
            return "";
        }
    }
}
