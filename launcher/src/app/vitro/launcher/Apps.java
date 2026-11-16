package app.vitro.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lista de apps instalados, banners e favoritos da barra. */
final class Apps {
    // Banner guardado no dobro do tamanho do bloco da gaveta em 1080p: nítido e ainda pequeno (~187 KB cada).
    static final int BW = 288, BH = 162;
    static final int MAX_DOCK = 7;

    static final class App {
        String label, pkg;
        ComponentName cn;
        ActivityInfo info;
        /** Instalado por quem usa a TV (não veio com o aparelho). */
        boolean user;
        /** Mantido desativado pelo Vitro e ativado só na hora de abrir (ver ON_DEMAND). */
        boolean onDemand;
        volatile Bitmap banner;
        volatile float[] glow = {1f, 1f, 1f};
        // Textura do banner na GPU: identificador, contexto em que foi criada e versão (para o redesenho parcial).
        int tex, texGen, texVer;
    }

    // Peças internas do Fire OS que aparecem como "apps" mas não servem para abrir.
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            "com.amazon.ftv.profilepicker", "com.amazon.ftv.screensaver", "com.amazon.ssm",
            "com.amazon.vizzini.ftvcds", "com.android.providers.downloads.ui", "com.amazon.tv.launcher"));

    // Ordem inicial da barra quando o usuário ainda não escolheu nada.
    private static final String[] PREFERRED = {
            "com.netflix.ninja", "com.amazon.firetv.youtube", "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom", "com.globo.globotv", "com.disney.disneyplus",
            "com.spotify.tv.android", "com.wbd.stream", "com.hbo.hbonow"};

    /**
     * Apps que o Vitro mantém desativados enquanto ninguém os usa e ativa na hora de abrir pela barra ou
     * pela gaveta (ver HomeService). O Prime Video é carregado pela tela da Amazon ao ligar a TV e fica
     * gastando memória e processador em segundo plano; desativado, ninguém consegue ligá-lo.
     */
    static final Set<String> ON_DEMAND = new HashSet<>(Arrays.asList("com.amazon.firebat"));

    private Apps() {}

    static List<App> load(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> found;
            try {
                found = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
            } catch (RuntimeException e) {
                continue;
            }
            for (ResolveInfo r : found) {
                ActivityInfo ai = r.activityInfo;
                if (ai == null) continue;
                String p = ai.packageName;
                if (byPkg.containsKey(p) || HIDDEN.contains(p) || p.equals(c.getPackageName())) continue;
                byPkg.put(p, make(pm, r));
            }
        }
        // Apps sob demanda continuam na barra e na gaveta mesmo desativados.
        for (String p : ON_DEMAND) {
            App known = byPkg.get(p);
            if (known != null) {
                known.onDemand = true;
                continue;
            }
            if (android.os.Build.VERSION.SDK_INT < 24) continue;
            for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                List<ResolveInfo> found;
                try {
                    found = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat).setPackage(p),
                            PackageManager.MATCH_DISABLED_COMPONENTS);
                } catch (RuntimeException e) {
                    continue;
                }
                if (found == null || found.isEmpty() || found.get(0).activityInfo == null) continue;
                App a = make(pm, found.get(0));
                a.onDemand = true;
                byPkg.put(p, a);
                break;
            }
        }
        List<App> out = new ArrayList<>(byPkg.values());
        Collections.sort(out, (x, y) -> x.label.compareToIgnoreCase(y.label));
        return out;
    }

    private static App make(PackageManager pm, ResolveInfo r) {
        ActivityInfo ai = r.activityInfo;
        App a = new App();
        a.pkg = ai.packageName;
        a.info = ai;
        a.cn = new ComponentName(ai.packageName, ai.name);
        a.user = ai.applicationInfo != null && (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        CharSequence label = r.loadLabel(pm);
        a.label = label == null ? ai.packageName : label.toString();
        return a;
    }

    /** O app está ativado (um app sob demanda fica desativado enquanto ninguém o usa). */
    static boolean isEnabled(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS).enabled;
        } catch (Exception e) {
            return false;
        }
    }

    /** Desenha o banner do app num bitmap pequeno. Pode rodar fora da thread principal. */
    static void loadBanner(Context c, App a) {
        if (a.banner != null) return;
        PackageManager pm = c.getPackageManager();
        Drawable d = null;
        try { d = a.info.loadBanner(pm); } catch (RuntimeException ignored) { }
        if (d == null) {
            try { d = pm.getApplicationBanner(a.pkg); } catch (Exception ignored) { }
        }
        Bitmap b = Bitmap.createBitmap(BW, BH, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        try {
            if (d != null) {
                d.setBounds(0, 0, BW, BH);
                d.draw(cv);
            } else {
                // App sem banner de TV: ícone centralizado sobre um fundo neutro.
                cv.drawColor(0xFF2A2E38);
                Drawable icon = a.info.loadIcon(pm);
                if (icon != null) {
                    int s = 104;
                    icon.setBounds((BW - s) / 2, (BH - s) / 2, (BW + s) / 2, (BH + s) / 2);
                    icon.draw(cv);
                }
            }
        } catch (RuntimeException e) {
            cv.drawColor(0xFF2A2E38);
        }
        a.glow = glowOf(b);
        a.banner = b;
    }

    static void release(App a) {
        a.banner = null;
    }

    /** Cor da luz sob o app: a média dos pixels mais vivos do banner, clareada. Sem cor forte, branco. */
    static float[] glowOf(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 16, 9, true);
        float r = 0f, g = 0f, bl = 0f, w = 0f;
        float[] hsv = new float[3];
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 16; x++) {
                int px = s.getPixel(x, y);
                Color.colorToHSV(px, hsv);
                float k = hsv[1] * hsv[1] * hsv[2];
                r += Color.red(px) * k; g += Color.green(px) * k; bl += Color.blue(px) * k; w += k;
            }
        }
        if (s != b) s.recycle();
        if (w < 4f) return new float[] {1f, 1f, 1f};
        r /= w; g /= w; bl /= w;
        float top = Math.max(r, Math.max(g, bl));
        if (top < 1f) return new float[] {1f, 1f, 1f};
        return new float[] {r / top * 0.68f + 0.32f, g / top * 0.68f + 0.32f, bl / top * 0.68f + 0.32f};
    }

    // ---- Ordem da gaveta ---------------------------------------------------------------------

    /**
     * Ordem da gaveta "Todos". Sem ordem salva: primeiro os apps instalados por quem usa a TV, depois os que
     * vieram com o aparelho, cada grupo em ordem alfabética. Com ordem salva, ela vale; um app novo entra logo
     * depois do último instalado à parte (se também for) ou no fim (se veio com o sistema).
     */
    static List<App> drawer(Context c, List<App> alphabetical) {
        String saved = c.getSharedPreferences("vitro", Context.MODE_PRIVATE).getString("drawer", null);
        List<App> out = new ArrayList<>();
        if (saved == null) {
            for (App a : alphabetical) if (a.user) out.add(a);
            for (App a : alphabetical) if (!a.user) out.add(a);
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (String pkg : saved.split("\n")) {
            App a = find(alphabetical, pkg);
            if (a != null && seen.add(a.pkg)) out.add(a);
        }
        int at = 0;
        for (int i = 0; i < out.size(); i++) if (out.get(i).user) at = i + 1;
        for (App a : alphabetical) {
            if (!seen.add(a.pkg)) continue;
            if (a.user) out.add(at++, a);
            else out.add(a);
        }
        return out;
    }

    static void saveDrawer(Context c, List<App> order) {
        StringBuilder sb = new StringBuilder();
        for (App a : order) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(a.pkg);
        }
        c.getSharedPreferences("vitro", Context.MODE_PRIVATE).edit().putString("drawer", sb.toString()).apply();
    }

    // ---- Favoritos da barra ------------------------------------------------------------------

    static List<App> dock(Context c, List<App> all) {
        SharedPreferences sp = c.getSharedPreferences("vitro", Context.MODE_PRIVATE);
        String saved = sp.getString("dock", null);
        List<App> out = new ArrayList<>();
        if (saved != null) {
            for (String pkg : saved.split("\n")) {
                App a = find(all, pkg);
                if (a != null && !out.contains(a)) out.add(a);
            }
            return out;
        }
        for (String pkg : PREFERRED) {
            App a = find(all, pkg);
            if (a != null && !out.contains(a) && out.size() < MAX_DOCK) out.add(a);
        }
        for (App a : all) {
            if (out.size() >= MAX_DOCK) break;
            if (!out.contains(a)) out.add(a);
        }
        return out;
    }

    static void saveDock(Context c, List<App> dock) {
        StringBuilder sb = new StringBuilder();
        for (App a : dock) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(a.pkg);
        }
        c.getSharedPreferences("vitro", Context.MODE_PRIVATE).edit().putString("dock", sb.toString()).apply();
    }

    private static App find(List<App> all, String pkg) {
        for (App a : all) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    static Intent launchIntent(Context c, App a) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(a.pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(a.pkg);
        if (i == null) i = new Intent(Intent.ACTION_MAIN).setComponent(a.cn);
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }
}
