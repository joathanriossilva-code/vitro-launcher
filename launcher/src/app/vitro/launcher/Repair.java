package app.vitro.launcher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Autorreparo: o que uma atualização do Fire OS pode desfazer, o Vitro refaz sozinho.
 *
 * Sem ADB, com a permissão WRITE_SECURE_SETTINGS (dada uma vez por adb; ver restaurar.sh): ao ligar a TV, quando
 * o Vitro é atualizado e quando o serviço do Home conecta, religa o serviço de acessibilidade do botão Home, a
 * depuração ADB e o alarme imediato (min_futurity=0). Isto roda mesmo que uma atualização tenha desligado o
 * serviço do Home, porque o Android chama o recebedor Boot a cada inicialização.
 *
 * Com o ADB da própria TV (no HomeService, depois que o serviço volta): concede de novo a leitura do registro
 * (READ_LOGS) e a própria WRITE_SECURE_SETTINGS, se uma atualização as tirou. A leitura do registro só vale num
 * processo novo, então o Vitro se reinicia sozinho na próxima vez que a TV entrar em espera.
 *
 * O que depende de quem usa a TV (VPN local, autorização da chave do ADB) o Vitro continua pedindo na tela. O que foi
 * refeito aparece num aviso quando a tela do Vitro vem para a frente.
 * Para desligar (para deixar o ADB ou o serviço desligados de propósito):
 *   adb shell am broadcast -n 'app.vitro.launcher/.Upkeep$Diag' --es teste reparo-desligar
 */
final class Repair {
    private static final String TAG = "VitroHome";
    static final String WSS = "android.permission.WRITE_SECURE_SETTINGS";
    static final String READ_LOGS = "android.permission.READ_LOGS";
    private static final String ALARM_KEY = "alarm_manager_constants";

    private Repair() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("vitro", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("autorreparo", true);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("autorreparo", on).apply();
        Log.i(TAG, "autorreparo " + (on ? "ligado" : "desligado"));
    }

    static boolean has(Context c, String perm) {
        return c.getPackageManager().checkPermission(perm, c.getPackageName()) == PackageManager.PERMISSION_GRANTED;
    }

    /** Uma linha para o menu "Sobre este launcher". */
    static String status(Context c) {
        if (!enabled(c)) return L.t("Autorreparo desligado.", "Self-repair off.");
        return has(c, WSS) ? L.t("Autorreparo ligado.", "Self-repair on.")
                : L.t("Autorreparo sem permissão: refaça a primeira configuração.", "Self-repair has no permission: run the setup again.");
    }

    /** Sem ADB: confere e refaz os ajustes do sistema. Devolve o que foi refeito, para o aviso. */
    static List<String> settings(Context c) {
        List<String> done = new ArrayList<>();
        if (!enabled(c)) return done;
        if (!has(c, WSS)) {
            Log.w(TAG, "autorreparo sem a permissão WRITE_SECURE_SETTINGS: os ajustes do sistema ficam como estão");
            return done;
        }
        ContentResolver r = c.getContentResolver();
        ComponentName me = new ComponentName(c, HomeService.class);
        try {
            String list = Settings.Secure.getString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean listed = false;
            if (list != null) {
                for (String e : list.split(":")) {
                    if (me.equals(ComponentName.unflattenFromString(e.trim()))) listed = true;
                }
            }
            boolean on = Settings.Secure.getInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
            if (!listed || !on) {
                if (!listed) {
                    String svc = me.flattenToString();
                    Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            list == null || list.trim().isEmpty() ? svc : list + ":" + svc);
                }
                Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                done.add(L.t("o serviço do botão Home", "the Home button service"));
            }
        } catch (RuntimeException x) {
            Log.w(TAG, "autorreparo: não consegui religar o serviço do botão Home (" + x + ")");
        }
        try {
            if (Settings.Global.getInt(r, Settings.Global.ADB_ENABLED, 0) != 1) {
                Settings.Global.putInt(r, Settings.Global.ADB_ENABLED, 1);
                done.add(L.t("a depuração ADB", "ADB debugging"));
            }
        } catch (RuntimeException x) {
            Log.w(TAG, "autorreparo: não consegui religar a depuração ADB (" + x + ")");
        }
        try {
            String ac = Settings.Global.getString(r, ALARM_KEY);
            String fixed = withMinFuturity(ac);
            if (!fixed.equals(ac)) {
                Settings.Global.putString(r, ALARM_KEY, fixed);
                done.add(L.t("o ajuste do Home rápido", "the fast Home setting"));
            }
        } catch (RuntimeException x) {
            Log.w(TAG, "autorreparo: não consegui refazer o ajuste do Home rápido (" + x + ")");
        }
        if (!done.isEmpty()) Log.i(TAG, "autorreparo: refeito " + done);
        return done;
    }

    /** As constantes do AlarmManager com min_futurity=0, mantendo as outras que existirem. */
    static String withMinFuturity(String ac) {
        if (ac == null || ac.trim().isEmpty() || "null".equals(ac.trim())) return "min_futurity=0";
        StringBuilder b = new StringBuilder();
        boolean found = false;
        for (String kv : ac.split(",")) {
            String t = kv.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("min_futurity")) {
                t = "min_futurity=0";
                found = true;
            }
            if (b.length() > 0) b.append(',');
            b.append(t);
        }
        if (!found) {
            if (b.length() > 0) b.append(',');
            b.append("min_futurity=0");
        }
        return b.toString();
    }

    /** O aviso do que foi refeito, guardado para quando a tela do Vitro vier para a frente. */
    static String message(List<String> done) {
        return L.t("O Vitro refez sozinho ", "Vitro fixed by itself ") + join(done) + ".";
    }

    static void keepNote(Context c, String msg) {
        SharedPreferences p = prefs(c);
        String old = p.getString("aviso", null);
        p.edit().putString("aviso", old == null ? msg : old + " " + msg).apply();
    }

    static String join(List<String> names) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) b.append(i == names.size() - 1 ? L.t(" e ", " and ") : ", ");
            b.append(names.get(i));
        }
        return b.toString();
    }

    /** O Android chama a cada inicialização e quando o Vitro é atualizado, mesmo com o serviço do Home desligado. */
    public static final class Boot extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
            // O timer de desligamento não sobrevive a um reinício (os alarmes do Android também não), e o ajuste do
            // HDMI que ele liga na hora de dormir volta ao que era.
            if (Intent.ACTION_BOOT_COMPLETED.equals(a)) {
                SleepTimer.cancel(c);
                SleepTimer.restoreTvOff(c);
            }
            List<String> done = settings(c);
            Log.i(TAG, "autorreparo ao " + (Intent.ACTION_BOOT_COMPLETED.equals(a) ? "ligar a TV" : "atualizar o Vitro")
                    + (done.isEmpty() ? ": tudo em ordem" : ": refeito " + done));
            if (!done.isEmpty()) keepNote(c, message(done));
        }
    }
}
