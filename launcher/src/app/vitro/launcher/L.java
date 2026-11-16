package app.vitro.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Idioma do Vitro: português ou inglês. Sem escolha salva, segue o idioma da TV (português numa TV em português,
 * inglês nas outras). A escolha fica em Sobre e vale para tudo: tela inicial, menus, avisos e primeira configuração.
 * Cada texto aparece no código nas duas línguas, lado a lado: L.t("português", "English").
 */
final class L {
    private static final String KEY = "idioma";
    private static volatile boolean english;
    private static volatile boolean loaded;

    private L() { }

    /** Lê a escolha salva; chamado no começo de cada tela, serviço e alarme do Vitro. */
    static void load(Context c) {
        SharedPreferences p = c.getSharedPreferences("vitro", Context.MODE_PRIVATE);
        String v = p.getString(KEY, null);
        english = v != null ? v.equals("en") : !"pt".equals(Locale.getDefault().getLanguage());
        loaded = true;
    }

    static void set(Context c, boolean en) {
        english = en;
        loaded = true;
        c.getSharedPreferences("vitro", Context.MODE_PRIVATE).edit().putString(KEY, en ? "en" : "pt").apply();
    }

    static boolean en() {
        return english;
    }

    static boolean loaded() {
        return loaded;
    }

    static String t(String pt, String en) {
        return english ? en : pt;
    }

    /** Para datas e maiúsculas: o idioma em uso. */
    static Locale locale() {
        return english ? Locale.US : new Locale("pt", "BR");
    }
}
