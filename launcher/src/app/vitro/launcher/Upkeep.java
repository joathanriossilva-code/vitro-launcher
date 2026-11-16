package app.vitro.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Manutenção que o Vitro faz sozinho, pelo mesmo ADB da própria TV que ele usa para o Prime Video.
 *
 * Guarda do debloat. Os apps desativados (lista em assets/debloat.txt, montada pelo build; ver build.sh)
 * continuam desativados depois de atualizações. Reiniciar a TV não reativa
 * nada (o Android guarda o "desativado"); o que pode reativar é uma atualização do Fire OS ou do próprio app.
 * A conferência usa só o PackageManager, sem ADB, e roda quando a TV liga, quando acorda e quando um app da
 * lista é atualizado ou muda de estado. Um app da lista ativo de novo só é desativado se voltou junto com uma
 * atualização (mudou a versão do Fire OS ou a data de atualização do app). Se a pessoa o reativou por conta
 * própria, pelo computador ou pelas configurações da TV, ele fica ativo. O aviso do que foi desativado de novo aparece
 * quando a tela do Vitro vem para a frente, para não aparecer no meio de um filme.
 *
 * Janela de atualização do Prime Video. Quem atualiza o Prime Video é o atualizador do sistema da Amazon, e
 * desativado ele talvez fique sem atualizações. Toda quarta-feira, à 00:00, o Vitro o ativa por uma hora e
 * depois o desativa de novo (se não estiver na tela). Se a TV dormiu durante a janela (o processador ficou
 * parado a maior parte da hora, então o atualizador não teve como trabalhar), ou se ela estava sem energia
 * na hora marcada, a janela se repete na próxima vez que a TV for ligada.
 */
final class Upkeep {
    private static final String TAG = "VitroHome";
    private static final String PRIME = "com.amazon.firebat";
    private static final Locale PT = new Locale("pt", "BR");
    /** Quarta-feira, 00:00, por uma hora. */
    private static final int WINDOW_DAY = Calendar.WEDNESDAY, WINDOW_HOUR = 0;
    private static final long WINDOW_MS = 60 * 60_000L;
    /** Com a TV recém-ligada, a janela repetida espera o Vitro subir e as conferências do início. */
    private static final long MAKEUP_DELAY_MS = 100_000L;
    /** Acordada menos que isto da hora: a TV dormiu e o atualizador não teve como trabalhar. */
    private static final float AWAKE_ENOUGH = 0.5f;
    /** Quando a guarda confere: depois de o serviço ligar, de a TV acordar e de um app da lista mudar. */
    private static final long GUARD_AFTER_CONNECT_MS = 70_000L, GUARD_AFTER_WAKE_MS = 45_000L,
            GUARD_AFTER_CHANGE_MS = 15_000L;
    static final String START = "app.vitro.launcher.JANELA_INICIO", END = "app.vitro.launcher.JANELA_FIM";

    private final HomeService s;
    /** Estado da janela (sobrevive a reinícios) e da guarda (o que estava desativado, versões e Fire OS). */
    private final SharedPreferences w, g;
    private List<String> list;
    private final Runnable guard = this::checkDebloat;
    private final Runnable makeup = () -> {
        if (pending() && interactive()) startWindow(true, WINDOW_MS);
    };

    Upkeep(HomeService service) {
        s = service;
        w = s.getSharedPreferences("janela", Context.MODE_PRIVATE);
        g = s.getSharedPreferences("guarda", Context.MODE_PRIVATE);
    }

    // ---- Eventos (chamados pelo HomeService, na thread principal) ------------------------------

    /** O serviço do Home ligou: TV ligada, Vitro instalado ou reiniciado. */
    void onConnect() {
        Handler h = s.adbHandler();
        if (h == null) return;
        h.post(() -> {
            long now = System.currentTimeMillis();
            long until = w.getLong("ativaAte", 0L);
            if (until != 0L && until <= now) {
                endWindow();   // a janela acabou com o Vitro parado (a TV reiniciou no meio)
            } else if (until > now) {
                alarm(END, until);   // continua aberta: o alarme do fim se perde num reinício
                if (interactive()) w.edit().putBoolean("viuTela", true).apply();
            }
            long next = w.getLong("proxima", 0L);
            if (next != 0L && next <= now && w.getLong("ativaAte", 0L) == 0L) {
                // A janela marcada não aconteceu: a TV estava sem energia ou o Vitro parado na hora.
                w.edit().putBoolean("pendente", true).apply();
                Log.i(TAG, "a janela do Prime Video de " + when(next) + " não aconteceu; repete com a TV ligada");
            }
            // A janela do Prime Video só existe onde ele tem a regra própria (Fire OS, com.amazon.firebat).
            if (primeHere()) {
                scheduleNext(now);
                if (pending()) h.postDelayed(makeup, MAKEUP_DELAY_MS);
            }
            h.postDelayed(guard, GUARD_AFTER_CONNECT_MS);
            Log.i(TAG, "manutenção: guarda do debloat com " + list().size() + " apps; próxima janela do Prime Video "
                    + when(w.getLong("proxima", 0L)) + (pending() ? "; janela pendente, repete com a TV ligada" : ""));
        });
    }

    /** A TV acordou (tela ligada). */
    void screenOn() {
        Handler h = s.adbHandler();
        if (h == null) return;
        h.post(() -> {
            if (windowOpen()) w.edit().putBoolean("viuTela", true).apply();
            if (pending()) {
                h.removeCallbacks(makeup);
                h.postDelayed(makeup, MAKEUP_DELAY_MS);
                Log.i(TAG, "TV ligada com a janela do Prime Video pendente: abre em " + MAKEUP_DELAY_MS / 1000 + " s");
            }
        });
        h.removeCallbacks(guard);
        h.postDelayed(guard, GUARD_AFTER_WAKE_MS);
    }

    /** Um app foi atualizado ou mudou de estado (ativado, desativado). */
    void packageChanged(String pkg, String action) {
        Handler h = s.adbHandler();
        if (h == null || pkg == null) return;
        if (PRIME.equals(pkg)) {
            if (Intent.ACTION_PACKAGE_REPLACED.equals(action)) Log.i(TAG, "Prime Video atualizado: " + primeVersion());
            return;
        }
        h.post(() -> {
            if (!list().contains(pkg)) return;
            h.removeCallbacks(guard);
            h.postDelayed(guard, GUARD_AFTER_CHANGE_MS);
        });
    }

    /** Alarme da janela (na linha do ADB, com o processador mantido acordado pelo recebedor). */
    void alarmFired(String action) {
        if (START.equals(action)) startWindow(false, WINDOW_MS);
        else if (END.equals(action)) endWindow();
    }

    /** A janela está aberta: o Prime Video fica ativo de propósito, e o HomeService não o desativa. */
    boolean windowOpen() {
        return w.getLong("ativaAte", 0L) > System.currentTimeMillis();
    }

    /** Resumo para o Autoreparar do menu. Na linha do ADB. */
    String summary() {
        String r = L.t("Guarda do debloat: ", "Debloat guard: ") + list().size() + " apps.";
        if (!primeHere()) return r;
        if (windowOpen()) return r + L.t(" Janela de atualização do Prime Video aberta até ", " Prime Video update window open until ")
                + clock(w.getLong("ativaAte", 0L)) + ".";
        if (pending()) return r + L.t(" A janela de atualização do Prime Video repete na próxima vez que a TV ligar.",
                " The Prime Video update window repeats next time the TV turns on.");
        long next = w.getLong("proxima", 0L);
        return next == 0L ? r : r + L.t(" Próxima janela de atualização do Prime Video: ", " Next Prime Video update window: ")
                + when(next) + ".";
    }

    /** Autoreparar: confere a guarda do debloat agora. Na linha do ADB. */
    void checkNow() {
        checkDebloat();
    }

    // ---- Janela de atualização do Prime Video ------------------------------------------------

    /** O Prime Video do Fire OS está instalado (fora dele, não há janela de atualização). */
    private boolean primeHere() {
        try {
            s.getPackageManager().getPackageInfo(PRIME, PackageManager.GET_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException x) {
            return false;
        }
    }

    private boolean pending() {
        return w.getBoolean("pendente", false);
    }

    /** Na linha do ADB: ativa o Prime Video pela duração da janela e marca o fim. */
    void startWindow(boolean repeat, long durationMs) {
        if (!primeHere()) return;
        long now = System.currentTimeMillis();
        if (windowOpen()) {
            scheduleNext(now);   // um segundo alarme na mesma janela: só garante o da semana seguinte
            return;
        }
        boolean screen = interactive();
        w.edit().putLong("ativaAte", now + durationMs)
                .putLong("inicio", now)
                .putLong("inicioUp", SystemClock.uptimeMillis())
                .putLong("inicioEl", SystemClock.elapsedRealtime())
                .putLong("inicioUpd", primeUpdate())
                .putBoolean("viuTela", screen)
                .putBoolean("pendente", false)
                .putBoolean("falhou", false)
                .commit();
        alarm(END, now + durationMs);
        scheduleNext(now);
        try {
            if (!Apps.isEnabled(s, PRIME)) {
                String r = s.shell("pm enable " + PRIME, false, 0L).trim();
                if (!r.contains("enabled")) throw new IllegalStateException("resposta inesperada: " + r);
            }
            Log.i(TAG, "janela de atualização do Prime Video aberta até " + clock(now + durationMs)
                    + (repeat ? " (repetida, TV ligada)" : screen ? " (TV ligada)" : " (TV em espera)")
                    + "; versão " + primeVersion());
        } catch (Exception x) {
            w.edit().putBoolean("falhou", true).apply();
            Log.w(TAG, "janela do Prime Video: não consegui ativá-lo (" + x + ")");
        }
    }

    /**
     * Na linha do ADB: fecha a janela, desativa o Prime Video se ele não estiver na tela e decide se a janela
     * valeu. Valeu se o app foi atualizado, se a TV esteve ligada, ou se ela ficou acordada a maior parte da hora
     * (aí o atualizador pôde trabalhar e simplesmente não havia atualização). Se não valeu, repete com a TV ligada.
     */
    private void endWindow() {
        long until = w.getLong("ativaAte", 0L);
        if (until == 0L) return;
        long now = System.currentTimeMillis();
        long wall = Math.max(1L, now - w.getLong("inicio", now));
        long el = SystemClock.elapsedRealtime() - w.getLong("inicioEl", 0L);
        long up = SystemClock.uptimeMillis() - w.getLong("inicioUp", 0L);
        // Um reinício no meio zera os contadores do sistema: aí não dá para saber quanto a TV ficou acordada.
        boolean known = el > 0L && up >= 0L && Math.abs(el - wall) < 120_000L;
        float awake = known ? Math.min(1f, up / (float) el) : 0f;
        boolean updated = primeUpdate() != w.getLong("inicioUpd", Long.MIN_VALUE);
        boolean screen = w.getBoolean("viuTela", false) || interactive();
        boolean failed = w.getBoolean("falhou", false);
        boolean worked = updated || (!failed && (screen || awake >= AWAKE_ENOUGH));
        w.edit().putLong("ativaAte", 0L).putBoolean("pendente", !worked).putLong("ultima", now).commit();
        Log.i(TAG, "janela do Prime Video fechada: " + (updated ? "atualizado para " + primeVersion() : "sem atualização")
                + "; TV acordada " + (known ? Math.round(awake * 100f) + "%" : "?") + " do tempo; tela ligada: "
                + (screen ? "sim" : "não") + (failed ? "; o ADB falhou" : "")
                + (worked ? "" : "; repete na próxima vez que a TV ligar"));
        s.primeOff(true);
    }

    /** Agenda (ou reagenda, depois de um reinício) o alarme da próxima quarta-feira à 00:00. */
    private void scheduleNext(long now) {
        long next = w.getLong("proxima", 0L);
        if (next <= now) {
            next = nextWindow(now);
            w.edit().putLong("proxima", next).apply();
        }
        alarm(START, next);
    }

    /** Próxima quarta-feira à 00:00 (hora da TV) depois de "after". */
    static long nextWindow(long after) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(after);
        c.set(Calendar.HOUR_OF_DAY, WINDOW_HOUR);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, (WINDOW_DAY - c.get(Calendar.DAY_OF_WEEK) + 7) % 7);
        if (c.getTimeInMillis() <= after) c.add(Calendar.DAY_OF_MONTH, 7);
        return c.getTimeInMillis();
    }

    private void alarm(String action, long at) {
        try {
            AlarmManager am = (AlarmManager) s.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(s, Alarm.class).setAction(action);
            PendingIntent pi = PendingIntent.getBroadcast(s, START.equals(action) ? 7 : 8, i, PendingIntent.FLAG_UPDATE_CURRENT);
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (RuntimeException x) {
            Log.w(TAG, "não consegui agendar o alarme da janela do Prime Video (" + x + ")");
        }
    }

    private boolean interactive() {
        PowerManager pm = (PowerManager) s.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    private long primeUpdate() {
        try {
            return s.getPackageManager().getPackageInfo(PRIME, PackageManager.GET_DISABLED_COMPONENTS).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException x) {
            return -1L;
        }
    }

    private String primeVersion() {
        try {
            return s.getPackageManager().getPackageInfo(PRIME, PackageManager.GET_DISABLED_COMPONENTS).versionName;
        } catch (PackageManager.NameNotFoundException x) {
            return "?";
        }
    }

    private static String when(long t) {
        if (t == 0L) return "?";
        return L.en() ? new SimpleDateFormat("EEE, M/d 'at' HH:mm", java.util.Locale.US).format(new Date(t))
                : new SimpleDateFormat("EEE, d/M 'às' HH:mm", PT).format(new Date(t));
    }

    private static String clock(long t) {
        return new SimpleDateFormat("HH:mm", PT).format(new Date(t));
    }

    /** Alarmes da janela (início e fim). Acordam o processador mesmo com a TV em espera. */
    public static final class Alarm extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            final String action = i.getAction();
            final PendingResult res = goAsync();
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            final PowerManager.WakeLock wl = pm == null ? null : pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vitro:janela");
            if (wl != null) wl.acquire(50_000L);
            Runnable done = () -> {
                if (wl != null && wl.isHeld()) wl.release();
                res.finish();
            };
            if (!HomeService.upkeepAlarm(action, done)) {
                // Sem o serviço do Home (acessibilidade desligada): a janela fica para quando ele voltar.
                if (START.equals(action)) {
                    c.getSharedPreferences("janela", Context.MODE_PRIVATE).edit().putBoolean("pendente", true)
                            .putLong("proxima", nextWindow(System.currentTimeMillis() + 60_000L)).apply();
                }
                Log.w(TAG, "alarme da janela do Prime Video sem o serviço do Home ligado");
                done.run();
            }
        }
    }

    /**
     * Testes pelo adb (só quem tem a permissão DUMP, como o shell do adb, consegue enviar):
     *   am broadcast -n app.vitro.launcher/.Upkeep\$Diag --es teste estado
     *   ... --es teste fireos      a próxima conferência da guarda age como se o Fire OS tivesse sido atualizado
     *   ... --es teste janela --ei minutos 3     abre agora uma janela curta do Prime Video
     *   ... --es teste sono --ei minutos 2     timer de desligamento curto (sono-cancelar o cancela)
     *   ... --es teste reparo      confere o autorreparo agora (reparo-desligar / reparo-ligar o desligam e religam)
     *   ... --es teste alexa-desligar     o Fire TV deixa de dormir junto com o "desligar a TV" da Alexa (alexa-ligar volta)
     *   ... --es teste registro-adb     lê o registro do sistema pelo ADB da TV, como no Android 13+ (registro-local volta)
     *   ... --es teste parear     espera o código de pareamento da depuração sem fio e mostra o painel (ver PairWatch)
     *   ... --es teste sem-fio-forcar     ignora a porta 5555, como numa TV só com a depuração sem fio (sem-fio-normal volta)
     *   ... --es teste adb-teste     roda um comando pelo ADB da TV e registra a resposta
     */
    public static final class Diag extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String t = i.getStringExtra("teste");
            int min = i.getIntExtra("minutos", 3);
            HomeService.upkeepTest(t == null ? "estado" : t, Math.max(1, Math.min(60, min)));
        }
    }

    /** Na linha do ADB: diagnóstico pedido pelo adb (ver Diag). */
    void test(String what, int minutes) {
        if ("fireos".equals(what)) {
            g.edit().putString("fingerprint", "teste").apply();
            Log.i(TAG, "teste: a próxima conferência da guarda age como depois de uma atualização do Fire OS");
            Handler h = s.adbHandler();
            if (h != null) {
                h.removeCallbacks(guard);
                h.postDelayed(guard, GUARD_AFTER_CHANGE_MS);
            }
        } else if ("janela".equals(what)) {
            startWindow(false, minutes * 60_000L);
        } else if ("sono".equals(what)) {
            SleepTimer.set(s, minutes);
        } else if ("sono-cancelar".equals(what)) {
            SleepTimer.cancel(s);
        } else if ("reparo".equals(what)) {
            s.repairWithAdb();
        } else if ("reparo-desligar".equals(what) || "reparo-ligar".equals(what)) {
            Repair.setEnabled(s, "reparo-ligar".equals(what));
        } else if ("alexa-desligar".equals(what) || "alexa-ligar".equals(what)) {
            SleepTimer.setFollowsAlexa(s, "alexa-ligar".equals(what));
        } else if ("parear".equals(what)) {
            HomeService.startPairing();
        } else if ("sem-fio-forcar".equals(what) || "sem-fio-normal".equals(what)) {
            s.getSharedPreferences("vitro", Context.MODE_PRIVATE).edit()
                    .putBoolean(HomeService.FORCE_WIRELESS, "sem-fio-forcar".equals(what)).apply();
            Log.i(TAG, "teste: ADB " + ("sem-fio-forcar".equals(what) ? "só pela depuração sem fio" : "normal"));
        } else if ("adb-teste".equals(what)) {
            try {
                Log.i(TAG, "teste: ADB respondeu: " + s.shell("echo vitro-ok; id -u", false, 0L).trim().replace('\n', ' '));
            } catch (Exception x) {
                Log.w(TAG, "teste: ADB falhou: " + x);
            }
        } else if ("registro-adb".equals(what) || "registro-local".equals(what)) {
            s.getSharedPreferences("vitro", Context.MODE_PRIVATE).edit()
                    .putBoolean(HomeService.LOG_VIA_ADB, "registro-adb".equals(what)).apply();
            Log.i(TAG, "teste: leitura do registro " + ("registro-adb".equals(what) ? "pelo ADB da TV" : "pelo próprio app"));
            s.restartLogWatcher();
        } else {
            Log.i(TAG, "teste: " + summary() + " " + Repair.status(s) + " Leitura do registro: "
                    + (Repair.has(s, Repair.READ_LOGS) ? "sim" : "não") + ". Dorme com a Alexa: "
                    + (SleepTimer.followsAlexa(s) ? "sim" : "não") + ". Janela aberta: " + windowOpen() + "; pendente: " + pending()
                    + "; Prime Video " + primeVersion() + (Apps.isEnabled(s, PRIME) ? " (ativo)" : " (desativado)"));
        }
    }

    // ---- Guarda do debloat -------------------------------------------------------------------

    /** Lista do debloat empacotada no Vitro, um pacote por linha (o Prime Video tem regra própria). */
    private List<String> list() {
        if (list != null) return list;
        List<String> out = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getAssets().open(Platform.guardList(s)), "UTF-8"))) {
            for (String line; (line = in.readLine()) != null; ) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && !line.equals(PRIME) && !out.contains(line)) out.add(line);
            }
        } catch (Exception x) {
            Log.w(TAG, "guarda do debloat sem lista (" + x + ")");
        }
        for (String p : s.getSharedPreferences("vitro", Context.MODE_PRIVATE)
                .getStringSet(SetupActivity.EXTRA, java.util.Collections.<String>emptySet())) {
            if (!p.equals(PRIME) && !out.contains(p)) out.add(p);
        }
        list = out;
        return out;
    }

    /** Na linha do ADB: confere a lista e desativa de novo o que voltou com uma atualização. */
    private void checkDebloat() {
        List<String> pkgs = list();
        if (pkgs.isEmpty()) return;
        PackageManager pm = s.getPackageManager();
        String fp = Build.FINGERPRINT;
        boolean osUpdated = g.contains("fingerprint") && !fp.equals(g.getString("fingerprint", fp));
        SharedPreferences.Editor e = g.edit();
        List<String> back = new ArrayList<>();
        List<Long> backUpd = new ArrayList<>();
        int off = 0, mine = 0;
        for (String p : pkgs) {
            PackageInfo pi;
            try {
                pi = pm.getPackageInfo(p, PackageManager.GET_DISABLED_COMPONENTS);
            } catch (PackageManager.NameNotFoundException x) {
                continue;   // não está instalado (uma atualização pode ter removido)
            }
            boolean enabled = pi.applicationInfo != null && pi.applicationInfo.enabled;
            long upd = pi.lastUpdateTime;
            boolean wasOff = g.getBoolean("off:" + p, !enabled);
            long oldUpd = g.getLong("upd:" + p, upd);
            if (!enabled) {
                e.putBoolean("off:" + p, true).putLong("upd:" + p, upd);
                off++;
            } else if (wasOff && (osUpdated || upd != oldUpd)) {
                back.add(p);
                backUpd.add(upd);
            } else {
                // Ativo sem atualização no meio: alguém reativou (ou nunca desativou). Fica como está.
                if (wasOff) Log.i(TAG, "guarda do debloat: " + p + " reativado por escolha de quem usa a TV; fica ativo");
                e.putBoolean("off:" + p, false).putLong("upd:" + p, upd);
                mine++;
            }
        }
        boolean allDone = true;
        if (!back.isEmpty()) {
            StringBuilder cmd = new StringBuilder();
            for (String p : back) {
                if (cmd.length() > 0) cmd.append("; ");
                // Não mexe em app que estiver na tela: fica para a próxima conferência.
                cmd.append("if dumpsys window windows | grep -m1 mCurrentFocus | grep -q ").append(p)
                        .append("; then echo EM_USO ").append(p).append("; else pm disable-user --user 0 ").append(p).append("; fi");
            }
            try {
                String r = s.shell(cmd.toString(), false, 0L);
                Log.i(TAG, "guarda do debloat: " + r.replace('\n', ' ').trim());
            } catch (Exception x) {
                Log.w(TAG, "guarda do debloat: não consegui desativar de novo " + back + " (" + x + ")");
            }
            List<String> names = new ArrayList<>();
            for (int i = 0; i < back.size(); i++) {
                String p = back.get(i);
                if (!Apps.isEnabled(s, p)) {
                    e.putBoolean("off:" + p, true).putLong("upd:" + p, backUpd.get(i));
                    names.add(label(pm, p));
                    off++;
                } else {
                    allDone = false;   // a próxima conferência tenta de novo
                }
            }
            if (!names.isEmpty()) {
                s.note("O Vitro desativou de novo " + join(names) + (names.size() == 1 ? ", que voltou" : ", que voltaram")
                        + " com uma atualização.");
            }
        }
        // Só esquece a versão antiga do Fire OS quando tudo o que voltou já foi desativado de novo.
        if (allDone) e.putString("fingerprint", fp);
        e.apply();
        Log.i(TAG, "guarda do debloat conferida: " + off + " desativados" + (mine > 0 ? ", " + mine + " ativos por escolha de quem usa a TV" : "")
                + (back.isEmpty() ? "" : ", " + back.size() + " tinham voltado") + (osUpdated ? " (Fire OS atualizado)" : ""));
    }

    private static String label(PackageManager pm, String p) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(p, PackageManager.GET_DISABLED_COMPONENTS);
            CharSequence l = pm.getApplicationLabel(ai);
            return l == null ? p : l.toString();
        } catch (PackageManager.NameNotFoundException x) {
            return p;
        }
    }

    private static String join(List<String> names) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) b.append(i == names.size() - 1 ? " e " : ", ");
            b.append(names.get(i));
        }
        return b.toString();
    }
}
