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
        clear(NARROW);
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
        android.view.View pt = key((L.en() ? "" : "✓ ") + "Português", v -> language(false));
        android.view.View en = key((L.en() ? "✓ " : "") + "English", v -> language(true));
        firstFocus = L.en() ? en : pt;
        key(L.t("Fechar", "Close"), v -> finish());
        status.setText(Repair.status(this));
        focusFirst();
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
