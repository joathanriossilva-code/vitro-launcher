package app.vitro.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;

import java.util.List;

/**
 * Em que sistema o Vitro está rodando, e o que muda em cada um:
 *
 *   FIRE        Fire OS (Fire TV Stick, Cube, TVs Fire TV). A tela inicial é a da Amazon; o ADB da própria TV só
 *               responde pelo túnel (ver Tunnel); a lista do debloat é a do debloat.txt; há Prime Video sob demanda
 *               e o "desligar a TV" da Alexa.
 *   GOOGLE_TV   Android TV com a tela inicial do Google TV (Chromecast com Google TV, Google TV Streamer, TVs
 *               com Google TV).
 *   ANDROID_TV  Android TV com a tela inicial clássica (Nvidia Shield, Mi Box, TVs mais antigas).
 *
 * Nos dois últimos, a lista do debloat é a do debloat-androidtv.txt, e o Vitro tenta primeiro o ADB da TV direto
 * (127.0.0.1:5555); só usa o túnel se o direto não responder.
 *
 * A tela inicial do sistema é procurada entre as que atendem ao Home (e não fixada num nome), porque cada fabricante
 * pode trazer a sua.
 */
final class Platform {
    static final int FIRE = 0, GOOGLE_TV = 1, ANDROID_TV = 2;

    /** Telas iniciais conhecidas, na ordem de preferência quando há mais de uma. */
    static final String AMAZON_HOME = "com.amazon.tv.launcher",
            GOOGLE_TV_HOME = "com.google.android.apps.tv.launcherx",
            ANDROID_TV_HOME = "com.google.android.tvlauncher",
            LEANBACK_HOME = "com.google.android.leanbacklauncher";

    /** Prime Video: no Fire OS ele tem nome próprio (e regra própria, ver HomeService). */
    static final String PRIME_FIRE = "com.amazon.firebat";

    /** Escolha guardada quando o ADB direto não respondeu: daí em diante, só o túnel. */
    private static final String DIRECT_FAILED = "adbDiretoFalhou";

    private static volatile int kind = -1;
    private static volatile String home;

    private Platform() { }

    static int kind(Context c) {
        int k = kind;
        if (k >= 0) return k;
        PackageManager pm = c.getPackageManager();
        // Sinais oficiais: "amazon.hardware.fire_tv" (Amazon) e "com.google.android.feature.AMATI_EXPERIENCE" (Google TV).
        // A tela inicial instalada (mesmo desativada) confirma.
        if (pm.hasSystemFeature("amazon.hardware.fire_tv") || "Amazon".equalsIgnoreCase(Build.MANUFACTURER)
                || installed(pm, AMAZON_HOME)) {
            k = FIRE;
        } else if (pm.hasSystemFeature("com.google.android.feature.AMATI_EXPERIENCE") || installed(pm, GOOGLE_TV_HOME)) {
            k = GOOGLE_TV;
        } else {
            k = ANDROID_TV;
        }
        kind = k;
        return k;
    }

    static boolean fire(Context c) {
        return kind(c) == FIRE;
    }

    /** Nome do sistema, para os textos: "Fire OS", "Google TV" ou "Android TV". */
    static String name(Context c) {
        switch (kind(c)) {
            case FIRE: return "Fire OS";
            case GOOGLE_TV: return "Google TV";
            default: return "Android TV";
        }
    }

    /** Quem faz o sistema: "Amazon" ou "Google" (nos textos do debloat). */
    static String maker(Context c) {
        return fire(c) ? "Amazon" : "Google";
    }

    /** "da Amazon" ou "do Google" (e "da TCL", nas TVs TCL), para os textos em português. */
    static String ofMaker(Context c) {
        return fire(c) ? "da Amazon" : tcl() ? "do Google e da TCL" : "do Google";
    }

    /** "Amazon", "Google" ou "Google and TCL", para os textos em inglês. */
    static String makers(Context c) {
        return fire(c) ? "Amazon" : tcl() ? "Google and TCL" : "Google";
    }

    /** TV da TCL (Google TV ou Android TV): a lista do debloat tem uma parte só dela. */
    static boolean tcl() {
        return "TCL".equalsIgnoreCase(Build.MANUFACTURER) || "TCL".equalsIgnoreCase(Build.BRAND);
    }

    /**
     * Pacote da tela inicial do sistema (a que o Home abre e que o Vitro substitui). Procura entre as que atendem
     * ao Home, fora o próprio Vitro e a tela provisória do Android (FallbackHome), preferindo as conhecidas.
     */
    static String systemHome(Context c) {
        String h = home;
        if (h != null) return h;
        PackageManager pm = c.getPackageManager();
        String found = null;
        for (String known : new String[] {AMAZON_HOME, GOOGLE_TV_HOME, ANDROID_TV_HOME, LEANBACK_HOME}) {
            if (installed(pm, known)) { found = known; break; }
        }
        if (found == null) {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                List<ResolveInfo> all = pm.queryIntentActivities(i, PackageManager.GET_DISABLED_COMPONENTS);
                for (ResolveInfo r : all) {
                    if (r.activityInfo == null) continue;
                    String p = r.activityInfo.packageName, n = r.activityInfo.name;
                    if (p.equals(c.getPackageName()) || n.endsWith("FallbackHome")) continue;
                    boolean system = (r.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    if (system) { found = p; break; }
                    if (found == null) found = p;
                }
            } catch (RuntimeException ignored) { }
        }
        h = found == null ? AMAZON_HOME : found;
        home = h;
        return h;
    }

    /** Lista do debloat da primeira configuração (assets, montada pelo build). */
    static String debloatGroups(Context c) {
        return fire(c) ? "debloat-grupos.txt" : "debloat-grupos-androidtv.txt";
    }

    /** Lista da guarda do debloat (assets, montada pelo build). */
    static String guardList(Context c) {
        return fire(c) ? "debloat.txt" : "debloat-androidtv.txt";
    }

    /** Fora do Fire OS, o ADB da TV costuma responder direto, sem o túnel. */
    static boolean tryDirectAdb(Context c) {
        return !fire(c) && !c.getSharedPreferences("vitro", Context.MODE_PRIVATE).getBoolean(DIRECT_FAILED, false);
    }

    static void directAdbFailed(Context c) {
        c.getSharedPreferences("vitro", Context.MODE_PRIVATE).edit().putBoolean(DIRECT_FAILED, true).apply();
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try {
            pm.getApplicationInfo(pkg, PackageManager.GET_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException x) {
            return false;
        }
    }
}
