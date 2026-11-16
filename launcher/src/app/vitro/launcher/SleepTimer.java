package app.vitro.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Timer de desligamento, no menu de ajustes do Vitro ("Desligar a TV"): cada OK passa para a próxima opção,
 * 15, 30, 60 e 90 minutos, e depois desligado. Um minuto antes aparece um aviso; na hora, o Vitro põe o Fire TV
 * para dormir pelo ADB da própria TV (a tecla de dormir, como a do controle; ver HomeService.goToSleep).
 *
 * A Alexa da Fire TV não aceita "timer de desligamento", e o timer do próprio Fire OS fica no menu rápido do
 * Home segurado, que o Vitro pode trocar por um app de espelhamento (ver HomeService). Por isso o timer é do Vitro.
 *
 * Para a TV desligar junto, ela precisa aceitar o HDMI-CEC (em muitas TVs ele vem desligado). Se a TV
 * for desligada ou entrar em espera antes da hora, ou se o Fire TV reiniciar, o timer é cancelado.
 */
final class SleepTimer {
    private static final String TAG = "VitroHome";
    static final int[] OPTIONS = {15, 30, 60, 90};
    static final String WARN = "app.vitro.launcher.SONO_AVISO", SLEEP = "app.vitro.launcher.SONO_DORMIR";
    private static final String END = "sonoFim", MIN = "sonoMin";

    private SleepTimer() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("vitro", Context.MODE_PRIVATE);
    }

    /** Hora marcada (relógio do sistema, em ms), ou 0 sem timer. */
    static long end(Context c) {
        long e = prefs(c).getLong(END, 0L);
        return e > System.currentTimeMillis() ? e : 0L;
    }

    /** A opção escolhida (15, 30, 60 ou 90), ou 0 sem timer. */
    static int minutes(Context c) {
        return end(c) == 0L ? 0 : prefs(c).getInt(MIN, 0);
    }

    /** OK no menu: passa para a próxima opção. Devolve o aviso para a tela. */
    static String cycle(Context c) {
        int cur = minutes(c), next = OPTIONS[0];
        if (cur != 0) {
            next = 0;
            for (int i = 0; i < OPTIONS.length; i++) {
                if (OPTIONS[i] == cur) next = i + 1 < OPTIONS.length ? OPTIONS[i + 1] : 0;
            }
        }
        if (next == 0) {
            cancel(c);
            return L.t("Timer de desligamento cancelado.", "Sleep timer canceled.");
        }
        long at = set(c, next);
        return L.t("A TV desliga às " + hhmm(at) + " (em " + next + " min).", "The TV turns off at " + hhmm(at) + " (in " + next + " min).");
    }

    /** Marca o desligamento para daqui a min minutos (o aviso vem um minuto antes). */
    static long set(Context c, int min) {
        long at = System.currentTimeMillis() + min * 60_000L;
        prefs(c).edit().putLong(END, at).putInt(MIN, min).apply();
        if (min > 1) alarm(c, WARN, at - 60_000L);
        else cancelAlarm(c, WARN);
        alarm(c, SLEEP, at);
        Log.i(TAG, "timer de desligamento: a TV dorme às " + hhmm(at) + " (" + min + " min)");
        return at;
    }

    static void cancel(Context c) {
        boolean had = prefs(c).contains(END);
        cancelAlarm(c, WARN);
        cancelAlarm(c, SLEEP);
        prefs(c).edit().remove(END).remove(MIN).apply();
        if (had) Log.i(TAG, "timer de desligamento cancelado");
    }

    /** Texto da direita na linha do menu: "desligado", "em 28 min", "em 1 h 30". */
    static String label(Context c) {
        long e = end(c);
        if (e == 0L) return L.t("desligado", "off");
        int left = (int) Math.ceil((e - System.currentTimeMillis()) / 60_000.0);
        if (left < 60) return L.t("em ", "in ") + left + " min";
        int h = left / 60, m = left % 60;
        return m == 0 ? L.t("em ", "in ") + h + " h" : L.t("em ", "in ") + h + " h " + m;
    }

    /**
     * O Fire TV dorme junto quando a Alexa manda desligar a TV (por voz ou pelas rotinas "desligar tv em ..."; ver
     * HomeService.watchAlexaOff). Ligado de fábrica; o adb liga e desliga (Upkeep.Diag "alexa-ligar"/"alexa-desligar").
     */
    static boolean followsAlexa(Context c) {
        return prefs(c).getBoolean("dormeComAlexa", true);
    }

    static void setFollowsAlexa(Context c, boolean on) {
        prefs(c).edit().putBoolean("dormeComAlexa", on).apply();
        Log.i(TAG, "dormir junto com o \"desligar a TV\" da Alexa: " + (on ? "ligado" : "desligado"));
    }

    /** Ajuste do Android que faz o Fire TV mandar a TV desligar pelo HDMI-CEC quando ele entra em espera. */
    private static final String TV_OFF = "hdmi_control_auto_device_off_enabled", TV_OFF_MINE = "sonoDesligaTv";

    /**
     * Na hora de dormir: liga só para este momento o "desligar a TV junto". Devolve true se ligou (estava desligado).
     * A marca é gravada antes do ajuste: se algo der errado no meio, o restoreTvOff ainda volta o ajuste.
     */
    static boolean tvOffForSleep(Context c) {
        if (!Repair.has(c, Repair.WSS)) return false;
        try {
            if (Settings.Global.getInt(c.getContentResolver(), TV_OFF, 0) != 0) return false;
            prefs(c).edit().putBoolean(TV_OFF_MINE, true).commit();
            Settings.Global.putInt(c.getContentResolver(), TV_OFF, 1);
            return true;
        } catch (RuntimeException x) {
            Log.w(TAG, "timer de desligamento: não consegui ligar o desligar-a-TV-junto (" + x + ")");
            return false;
        }
    }

    /**
     * O "desligar a TV junto" volta a desligado, como era antes do timer: quando a TV acorda, quando o Fire TV liga
     * (pode ter reiniciado dormindo, numa atualização) e se a tecla de dormir falhar. Se não conseguir, tenta de novo
     * na próxima vez.
     */
    static void restoreTvOff(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean(TV_OFF_MINE, false)) return;
        try {
            Settings.Global.putInt(c.getContentResolver(), TV_OFF, 0);
        } catch (RuntimeException x) {
            Log.w(TAG, "timer de desligamento: não consegui voltar o ajuste do HDMI (" + x + ")");
            return;
        }
        p.edit().remove(TV_OFF_MINE).apply();
        Log.i(TAG, "timer de desligamento: o desligar-a-TV-junto voltou a desligado");
    }

    private static String hhmm(long t) {
        return new SimpleDateFormat("HH:mm", new Locale("pt", "BR")).format(new Date(t));
    }

    private static PendingIntent pending(Context c, String action) {
        Intent i = new Intent(c, Alarm.class).setAction(action);
        return PendingIntent.getBroadcast(c, WARN.equals(action) ? 21 : 22, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void alarm(Context c, String action, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(c, action);
        if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
    }

    private static void cancelAlarm(Context c, String action) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c, action));
    }

    /** Aviso um minuto antes, e a hora de dormir. */
    public static final class Alarm extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            long e = prefs(c).getLong(END, 0L), now = System.currentTimeMillis();
            if (WARN.equals(a)) {
                if (e > now) {
                    L.load(c);
                    Toast.makeText(c.getApplicationContext(), L.t("A TV desliga em 1 minuto. Para cancelar: Home, ajustes do "
                            + "Vitro, Desligar a TV.", "The TV turns off in 1 minute. To cancel: Home, Vitro settings, Turn off "
                            + "the TV."), Toast.LENGTH_LONG).show();
                }
                return;
            }
            if (!SLEEP.equals(a)) return;
            // Cancelado, trocado por outro horário ou atrasado demais (a TV estava desligada): não faz nada.
            if (e == 0L || Math.abs(now - e) > 120_000L) return;
            prefs(c).edit().remove(END).remove(MIN).apply();
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                Log.i(TAG, "timer de desligamento: a TV já estava em espera");
                return;
            }
            final PendingResult res = goAsync();
            final PowerManager.WakeLock wl = pm == null ? null : pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vitro:sono");
            if (wl != null) wl.acquire(30_000L);
            Runnable done = () -> {
                if (wl != null && wl.isHeld()) wl.release();
                res.finish();
            };
            if (!HomeService.sleepNow(done)) {
                Log.w(TAG, "timer de desligamento: sem o serviço do Home, não consegui pôr a TV para dormir");
                done.run();
            }
        }
    }
}
