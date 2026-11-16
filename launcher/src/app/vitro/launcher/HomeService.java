package app.vitro.launcher;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Faz o botão Home do controle abrir o Vitro.
 *
 * No Fire OS 7 o Home é tratado pelo próprio sistema (a tecla nem chega aos serviços de acessibilidade):
 * ele abre a tela da Amazon e, por uns 5 segundos, segura qualquer outro app que tente abrir em seguida
 * (o "app-switch lock" do Android 9). Este serviço percebe a tela da Amazon sendo aberta e põe o Vitro
 * no lugar dela.
 *
 * Mais cedo, pelo registro do sistema: com a permissão READ_LOGS (concedida pelo ADB, na primeira configuração), o
 * serviço lê só as linhas do ActivityManager e vê a tela da Amazon sendo aberta uns 50 ms depois do toque.
 * Só valem linhas escritas pelo próprio sistema (uid 1000), que app nenhum consegue forjar; nada é
 * guardado. Sem a permissão, o aviso vem da janela da Amazon, quando ela já está desenhando. (Técnica do
 * projeto livre Homestead.)
 *
 * Sem a espera de 5 s: o Vitro não é aberto diretamente, e sim por um alarme para 20 ms depois, entregue
 * pelo próprio sistema, que não fica sujeito à espera. É imediato com o ajuste "min_futurity=0" do
 * AlarmManager, feito uma vez na primeira configuração. (Técnica dos projetos livres dumbtv e firetv-home-guard.)
 *
 * Quando a tela da Amazon já chegou a desenhar, uma cobertura escura entra por cima e só sai quando a
 * janela do Vitro aparece, com uma pausa curta e um esmaecimento. (Como no projeto livre Home on Fire.)
 *
 * Home segurado: o Fire OS abre o seu menu rápido. Se o recebedor de espelhamento AirCast estiver instalado,
 * o serviço o abre por cima, voltando para o início ao sair dele; sem ele, o menu rápido fica.
 *
 * Prime Video sob demanda: a tela da Amazon liga o Prime Video ao ligar a TV e o mantém ligado (~175 MB e
 * 8 a 11% de um núcleo, sem uso); encerrá-lo não adianta, o Android o religa. Então o Vitro o mantém
 * desativado (pm disable-user, pelo shell do ADB da própria TV; ver Adb e Tunnel) e o ativa só na hora em
 * que alguém o abre pela barra ou pela gaveta. Cinco minutos depois de sair dele, desativa de novo (o
 * protetor de tela não conta como sair). Ao ligar a TV, ao acordar e depois que a tela da Amazon aparece,
 * confere: ativo e fora da tela, desativa. Pedidos de voz que dependem dele não funcionam enquanto estiver
 * desativado; por isso a primeira configuração pergunta. Em Autoreparar, o Vitro confere se o ADB da TV está
 * autorizado e, se não estiver, pede de novo.
 *
 * Manutenção (ver Upkeep): a guarda do debloat desativa de novo os apps da lista que voltarem com uma
 * atualização, e toda quarta-feira à 00:00 o Prime Video fica ativo por uma hora para poder ser atualizado.
 *
 * O serviço não lê nada da tela nem recebe as teclas do controle: só o nome do app e da janela que vêm
 * para a frente.
 */
public final class HomeService extends AccessibilityService {
    private static final String TAG = "VitroHome";
    private static final String AMAZON_HOME_ACTIVITY = "com.amazon.tv.launcher.ui.HomeActivity";
    /** Pacote da tela inicial do sistema (Amazon, Google TV ou Android TV; ver Platform.systemHome). */
    static volatile String homePkg = Platform.AMAZON_HOME;
    /** Menu rápido do Fire OS, aberto ao segurar o Home. */
    private static final String HUD_PKG = "com.amazon.tv.settings.v2";
    private static final String HUD_ACTIVITY = "com.amazon.tv.settings.v2.hud.HudActivity";
    /** Recebedor de espelhamento AirPlay que o Home segurado abre, se estiver instalado (ver replaceHud). */
    private static final String MIRROR_APP = "com.azone.aircast";
    private static final String VITRO_ACTIVITY = "app.vitro.launcher.HomeActivity";
    /** Logo depois de a TV ligar, a tela da Amazon já está aberta: o Vitro entra no lugar. */
    private static final long BOOT_WINDOW_MS = 3 * 60_000L;
    /** Uma troca por vez: a tela da Amazon costuma avisar mais de uma vez enquanto carrega. */
    private static final long DEBOUNCE_MS = 1200L;
    /** Depois de o registro já ter tratado um Home, o aviso tardio da janela da Amazon é ignorado. */
    private static final long LOG_GRACE_MS = 3000L;
    /** Depois de a janela do Vitro aparecer, a cobertura fica mais um instante (1º quadro) e dissolve. */
    private static final long COVER_HOLD_MS = 120L, COVER_FADE_MS = 150L;
    /** Rede de segurança: com o alarme imediato o Vitro aparece em menos de meio segundo. */
    private static final long COVER_MAX_MS = 2500L;
    private static final int COVER_COLOR = 0xFF08080C;

    /** Prime Video, que a tela da Amazon carrega ao ligar a TV. */
    private static final String PRIME = "com.amazon.firebat";
    /** Quando encerrar o Prime Video: tempo desde que a TV ligou, e depois de sair do modo de espera. */
    private static final long[] CLOSE_AFTER_BOOT_MS = {60_000L, 150_000L};
    private static final long CLOSE_AFTER_WAKE_MS = 30_000L;
    /** Tempo fora do Prime Video até desativá-lo de novo. */
    private static final long PRIME_AWAY_MS = 5 * 60_000L;
    /** Logo depois de o serviço ligar (instalação, reinício): confere se o Prime Video ficou ativo. */
    private static final long CHECK_AFTER_CONNECT_MS = 5_000L;
    /** Depois que a tela da Amazon desenhou (ou foi deixada, se aberta de propósito no menu do Vitro). */
    private static final long CLOSE_AFTER_AMAZON_MS = 20_000L;
    /** Duas tentativas seguidas de encerrar, com menos que isto entre elas, viram uma só. */
    private static final long CLOSE_MIN_GAP_MS = 10_000L;
    /** Pedido de autorização da chave na tela: no máximo um a cada 12 h, se ninguém aceitou. */
    private static final long ASK_AGAIN_MS = 12 * 3_600_000L;
    private static final long APPROVAL_WAIT_MS = 120_000L;
    /** Autorreparo pelo ADB: depois de o serviço ligar e das conferências do início. */
    private static final long REPAIR_AFTER_CONNECT_MS = 40_000L;

    /** Pedido no menu do Vitro: deixa a tela da Amazon aberta até outro app vir para a frente. */
    static volatile boolean amazonWanted;
    /** O serviço em funcionamento (mesmo processo da tela do Vitro), para o menu "Sobre" consultar o ADB. */
    private static volatile HomeService running;
    /** A primeira configuração guarda aqui o número do processo antes de reiniciá-lo: num processo novo, o serviço
     *  reabre o Vitro. */
    static final String OPEN_AFTER_RESTART = "abrirInicioAoReligar";

    /**
     * Põe um trabalho de ADB na fila do serviço, se ele estiver ligado. O túnel é um só: dois usos ao mesmo tempo se
     * derrubam (quem termina primeiro desliga o túnel do outro). Devolve false se o serviço não está ligado.
     */
    static boolean postAdb(Runnable r) {
        HomeService s = running;
        return s != null && s.adb != null && s.adb.post(r);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private long lastClose = -CLOSE_MIN_GAP_MS;   // só na linha do ADB
    private WindowManager wm;
    private View cover;
    private SharedPreferences prefs;
    private HandlerThread adbThread;
    private Handler adb;
    private boolean registered;
    /** Guarda do debloat e janela de atualização do Prime Video. */
    private Upkeep upkeep;
    /** Pareamento com a depuração sem fio (TVs que não abrem a porta 5555; ver PairWatch). */
    private PairWatch pairing;
    private long lastOpen = -DEBOUNCE_MS, lastHud = -DEBOUNCE_MS, lastLogHome = -10_000L;
    private volatile String front = "";
    // Leitor do registro do sistema (só com READ_LOGS).
    private volatile boolean watching;
    private volatile Process logProc;
    // Leitor do "desligar a TV" da Alexa (também só com READ_LOGS; ver watchAlexaOff).
    private volatile boolean watchingAlexa;
    private volatile Process alexaProc;
    private final Runnable alexaSleep = this::sleepAfterAlexa;

    /** Linha que o controle de equipamentos da Amazon escreve quando a Alexa manda desligar a TV. */
    static final String ALEXA_OFF_TAG = "OZ-DCS:NativeDirectiveServiceHandler",
            ALEXA_OFF_TEXT = "Deserialized directive: TurnOff_Alexa.PowerController";
    /** Do "desligar a TV" da Alexa até o Fire TV dormir: dá tempo de a Alexa responder e de o controle mandar o sinal. */
    private static final long ALEXA_OFF_SLEEP_MS = 6_000L;

    private final Runnable uncover = this::fadeCover;
    private final Runnable closePrime = this::primeOffNow;
    // Separado do de cima: voltar ao Prime Video cancela só este (os de ligar e acordar a TV continuam).
    private final Runnable primeAway = this::primeOffNow;

    /** O autorreparo concedeu de novo a leitura do registro: ela só vale num processo novo (ver Repair). */
    private volatile boolean restartWhenIdle;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                Log.i(TAG, "tela ligada; Prime Video conferido em " + CLOSE_AFTER_WAKE_MS / 1000 + " s");
                schedulePrimeClose(CLOSE_AFTER_WAKE_MS);
                if (upkeep != null) upkeep.screenOn();
                SleepTimer.restoreTvOff(HomeService.this);
            } else if (Intent.ACTION_SCREEN_OFF.equals(i.getAction()) && SleepTimer.end(HomeService.this) != 0L) {
                // A TV foi desligada antes da hora: o timer não vale mais para quando ela for ligada de novo.
                SleepTimer.cancel(HomeService.this);
            }
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction()) && restartWhenIdle && adb != null) {
                // TV em espera: reinicia o processo do Vitro (o Android religa o serviço do Home logo em seguida),
                // para a leitura do registro concedida de novo passar a valer. Só se a TV continuar em espera.
                adb.postDelayed(() -> {
                    android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                    if (pm != null && !pm.isInteractive()) {
                        Log.i(TAG, "autorreparo: reiniciando o Vitro para a leitura do registro voltar a valer");
                        android.os.Process.killProcess(android.os.Process.myPid());
                    }
                }, 5000L);
            }
        }
    };

    /** Apps atualizados ou que mudaram de estado: a guarda do debloat confere os da lista. */
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String p = i.getData() == null ? null : i.getData().getSchemeSpecificPart();
            if (upkeep != null) upkeep.packageChanged(p, i.getAction());
        }
    };

    @Override protected void onServiceConnected() {
        homePkg = Platform.systemHome(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        pairing = new PairWatch(this, wm, ui);
        prefs = getSharedPreferences("vitro", MODE_PRIVATE);
        L.load(this);
        if (adbThread == null) {
            adbThread = new HandlerThread("vitro-adb");
            adbThread.start();
            adb = new Handler(adbThread.getLooper());
        }
        if (upkeep == null) upkeep = new Upkeep(this);
        if (!registered) {
            IntentFilter sf = new IntentFilter(Intent.ACTION_SCREEN_ON);
            sf.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screen, sf);
            IntentFilter pf = new IntentFilter();
            pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
            pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
            pf.addDataScheme("package");
            registerReceiver(packages, pf);
            registered = true;
        }
        running = this;
        Log.i(TAG, "botão Home ligado ao Vitro; alarme imediato: " + alarmIsImmediate());
        int before = 0;
        try {
            before = prefs.getInt(OPEN_AFTER_RESTART, 0);
        } catch (ClassCastException x) {
            prefs.edit().remove(OPEN_AFTER_RESTART).apply();
        }
        if (before != 0 && before != android.os.Process.myPid()) {
            // Fim da primeira configuração: o processo foi reiniciado para a leitura do registro valer. (O serviço
            // também reconecta sem reiniciar o processo; aí o pedido fica para o reinício de verdade.)
            prefs.edit().remove(OPEN_AFTER_RESTART).apply();
            Log.i(TAG, "primeira configuração: reabrindo a tela inicial depois do reinício");
            open();
        }
        startLogWatcher();
        long up = SystemClock.elapsedRealtime();
        if (up < BOOT_WINDOW_MS) {
            // Logo depois de a TV ligar, o Vitro vem para a frente; não durante a primeira configuração, que é
            // justamente quem acabou de ligar este serviço (abrir a tela inicial por cima a fecharia).
            if (!SetupActivity.showing) open();
            for (long at : CLOSE_AFTER_BOOT_MS) schedulePrimeClose(Math.max(0L, at - up));
        }
        schedulePrimeClose(CHECK_AFTER_CONNECT_MS);
        // TVs só com a depuração sem fio: ela desliga quando a TV reinicia, e o Vitro a religa.
        if (AdbTls.paired(this)) adb.post(() -> AdbTls.ensureOn(this));
        authorizeIfNeeded();
        upkeep.onConnect();
        // Autorreparo: os ajustes do sistema agora (rápido, sem ADB) e as permissões pelo ADB um pouco depois.
        List<String> fixed = Repair.settings(this);
        if (!fixed.isEmpty()) Repair.keepNote(this, Repair.message(fixed));
        adb.postDelayed(this::repairWithAdb, REPAIR_AFTER_CONNECT_MS);
    }

    /**
     * Na linha do ADB: confere o autorreparo (ver Repair). Sem ADB, só os ajustes do sistema; com o ADB da TV,
     * concede de novo a leitura do registro e a permissão do próprio autorreparo, se uma atualização as tirou.
     */
    boolean repairWithAdb() {
        if (!Repair.enabled(this)) return false;
        List<String> done = new ArrayList<>(Repair.settings(this));
        boolean logs = !Repair.has(this, Repair.READ_LOGS), wss = !Repair.has(this, Repair.WSS);
        if (logs || wss) {
            StringBuilder cmd = new StringBuilder();
            if (logs) cmd.append("pm grant ").append(getPackageName()).append(' ').append(Repair.READ_LOGS);
            if (wss) {
                if (cmd.length() > 0) cmd.append("; ");
                cmd.append("pm grant ").append(getPackageName()).append(' ').append(Repair.WSS);
            }
            try {
                shell(cmd.toString(), false, 0L);
                if (logs && Repair.has(this, Repair.READ_LOGS)) {
                    done.add(L.t("a leitura do registro do sistema", "reading the system log"));
                    restartWhenIdle = true;
                }
                if (wss && Repair.has(this, Repair.WSS)) {
                    done.add(L.t("a permissão do autorreparo", "the self-repair permission"));
                    done.addAll(Repair.settings(this));
                }
            } catch (Adb.NotAuthorized x) {
                prefs.edit().putBoolean("adbAutorizado", false).apply();
                Log.w(TAG, "autorreparo: a chave do Vitro não está autorizada na TV");
            } catch (Exception x) {
                Log.w(TAG, "autorreparo: sem o ADB da TV (" + x + ")");
            }
        }
        Log.i(TAG, "autorreparo conferido: " + (done.isEmpty() ? "tudo em ordem" : "refeito " + done)
                + (Repair.has(this, Repair.WSS) ? "" : "; falta a permissão WRITE_SECURE_SETTINGS"));
        if (!done.isEmpty()) note(Repair.message(done));
        return !done.isEmpty();
    }

    @Override public boolean onUnbind(Intent intent) {
        shutdown();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        if (pairing != null) pairing.stop();
        if (running == this) running = null;
        ui.removeCallbacksAndMessages(null);
        stopLogWatcher();
        removeCoverNow();
        if (registered) {
            try { unregisterReceiver(screen); } catch (RuntimeException ignored) { }
            try { unregisterReceiver(packages); } catch (RuntimeException ignored) { }
            registered = false;
        }
        if (adbThread != null) {
            adbThread.quit();
            adbThread = null;
            adb = null;
        }
    }

    @Override public void onInterrupt() { }

    // ---- App na frente -----------------------------------------------------------------------

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String pkg = e.getPackageName() == null ? "" : e.getPackageName().toString();
        String cls = e.getClassName() == null ? "" : e.getClassName().toString();
        long now = SystemClock.uptimeMillis();

        if (systemHomeWindow(pkg, cls)) {
            if (!amazonWanted && now - lastOpen > DEBOUNCE_MS && now - lastLogHome > LOG_GRACE_MS) redirect();
            return;
        }
        if (HUD_PKG.equals(pkg) && HUD_ACTIVITY.equals(cls)) {
            replaceHud(false);
            return;
        }
        if (getPackageName().equals(pkg)) {
            // Só a janela do Vitro conta. A própria cobertura e os avisos também são janelas deste app,
            // e não podem tirá-la antes da hora.
            if (VITRO_ACTIVITY.equals(cls)) {
                setFront(pkg);
                leftAmazon();
                scheduleUncover();
            }
            return;
        }
        if (isApp(pkg)) {
            setFront(pkg);
            leftAmazon();
            scheduleUncover();
        }
    }

    /**
     * A janela principal da tela inicial do sistema. No Fire OS, a atividade da Amazon (a tela da Amazon tem outras
     * janelas que não contam); nos outros, qualquer atividade do pacote da tela inicial (não as janelinhas
     * android.widget de dentro dela).
     */
    private boolean systemHomeWindow(String pkg, String cls) {
        if (!pkg.equals(homePkg)) return false;
        if (Platform.AMAZON_HOME.equals(pkg)) return cls.startsWith(AMAZON_HOME_ACTIVITY);
        return !cls.isEmpty() && !cls.startsWith("android.");
    }

    /** Novo app na frente: saindo do Prime Video, agenda desativá-lo; voltando a ele, cancela. */
    private void setFront(String pkg) {
        String before = front;
        front = pkg;
        // Aviso guardado (a guarda do debloat agiu com outro app na tela): aparece agora, na tela do Vitro.
        if (getPackageName().equals(pkg) && prefs != null && prefs.contains("aviso")) {
            String a = prefs.getString("aviso", null);
            prefs.edit().remove("aviso").apply();
            if (a != null) toast(a);
        }
        Handler h = adb;
        if (h == null) return;
        if (PRIME.equals(pkg)) {
            h.removeCallbacks(primeAway);
        } else if (PRIME.equals(before)) {
            h.removeCallbacks(primeAway);
            h.postDelayed(primeAway, PRIME_AWAY_MS);
            Log.i(TAG, "saiu do Prime Video; desativado daqui a " + PRIME_AWAY_MS / 60_000L + " min se não voltar");
        }
    }

    /** Saiu da tela da Amazon aberta de propósito: ela pode ter carregado o Prime Video. */
    private void leftAmazon() {
        if (!amazonWanted) return;
        amazonWanted = false;
        schedulePrimeClose(CLOSE_AFTER_AMAZON_MS);
    }

    /**
     * Teclado, Alexa ouvindo, protetor de tela e janelas do sistema não contam como "um app veio para a
     * frente" (um filme pausado no Prime Video não conta como sair dele quando o protetor de tela entra).
     */
    private static boolean isApp(String pkg) {
        return !pkg.isEmpty() && !pkg.equals("android") && !pkg.startsWith("com.android.systemui")
                && !pkg.startsWith("com.amazon.tv.ime") && !pkg.equals("com.amazon.vizzini")
                && !pkg.equals("com.amazon.ftv.screensaver")
                // Android TV e Google TV: teclado, Assistente e protetores de tela.
                && !pkg.startsWith("com.google.android.inputmethod") && !pkg.equals("com.google.android.leanback.ime")
                && !pkg.equals("com.google.android.katniss") && !pkg.equals("com.google.android.apps.tv.dreamx")
                && !pkg.equals("com.google.android.backdrop");
    }

    /** A tela da Amazon já desenhou: cobre e põe o Vitro no lugar, pelo alarme. */
    private void redirect() {
        lastOpen = SystemClock.uptimeMillis();
        schedulePrimeClose(CLOSE_AFTER_AMAZON_MS);
        boolean fast = alarmIsImmediate();
        if (fast) showCover();
        if (startViaAlarm(vitroIntent(), 1)) {
            Log.i(TAG, fast ? "tela da Amazon apareceu; Vitro pelo alarme imediato, com cobertura"
                    : "tela da Amazon apareceu; Vitro pelo alarme (sem min_futurity=0 o Fire OS adia uns 5 s)");
        } else {
            removeCoverNow();
            open();
        }
    }

    /**
     * Home segurado: abre o AirCast por cima do menu rápido do Fire OS. Ao sair do AirCast, a TV volta
     * para o início (e o início é o Vitro), em vez de voltar para o menu rápido.
     */
    private void replaceHud(boolean early) {
        long now = SystemClock.uptimeMillis();
        if (now - lastHud <= DEBOUNCE_MS) return;
        Intent cast = mirrorIntent();
        if (cast == null) return;   // sem AirCast, o menu rápido fica
        lastHud = now;
        Log.i(TAG, "Home segurado: AirCast no lugar do menu rápido" + (early ? " (pelo registro)" : ""));
        try {
            startActivity(cast);
        } catch (RuntimeException x) {
            Log.e(TAG, "não foi possível abrir o AirCast", x);
        }
    }

    private Intent mirrorIntent() {
        Intent cast = getPackageManager().getLeanbackLaunchIntentForPackage(MIRROR_APP);
        if (cast == null) cast = getPackageManager().getLaunchIntentForPackage(MIRROR_APP);
        if (cast == null) return null;
        return cast.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
    }

    private Intent vitroIntent() {
        return new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    /** Abertura direta, para quando não há a espera do Home (logo depois de a TV ligar). */
    private void open() {
        lastOpen = SystemClock.uptimeMillis();
        try {
            startActivity(vitroIntent());
        } catch (RuntimeException x) {
            Log.e(TAG, "não foi possível abrir o Vitro", x);
        }
    }

    /**
     * No Android 9 (Fire OS 7), logo depois do Home o sistema segura por uns 5 s qualquer app que tente abrir; o alarme
     * passa por essa trava. Do Android 10 em diante (Fire OS 8, Android TV, Google TV) é o contrário: a abertura pelo
     * alarme é bloqueada, e o serviço de acessibilidade pode abrir o Vitro direto. Aí devolve false e quem chamou abre
     * direto (ver open).
     */
    private boolean startViaAlarm(Intent i, int code) {
        if (Build.VERSION.SDK_INT >= 29) return false;
        try {
            PendingIntent pi = PendingIntent.getActivity(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT);
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return false;
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 20L, pi);
            return true;
        } catch (RuntimeException x) {
            Log.e(TAG, "não foi possível agendar o alarme", x);
            return false;
        }
    }

    /** Com o ajuste min_futurity=0 (feito pelo Autorreparo), o alarme dispara na hora, e não 5 s depois. */
    private boolean alarmIsImmediate() {
        String c;
        try {
            c = Settings.Global.getString(getContentResolver(), "alarm_manager_constants");
        } catch (RuntimeException x) {
            return false;
        }
        if (c == null) return false;
        for (String kv : c.split(",")) {
            String[] p = kv.trim().split("=");
            if (p.length == 2 && p[0].trim().equals("min_futurity")) {
                try {
                    return Long.parseLong(p[1].trim()) <= 100L;
                } catch (NumberFormatException x) {
                    return false;
                }
            }
        }
        return false;
    }

    // ---- Home pelo registro do sistema (com READ_LOGS) ---------------------------------------

    /**
     * Do Android 13 em diante, um app em segundo plano não lê mais o registro do sistema, nem com READ_LOGS (o Android
     * entrega só as linhas do próprio app, sem avisar). O shell do ADB continua lendo tudo: com o ADB da TV direto e a
     * chave autorizada, o Vitro segue o registro por ele. (Também ligável num Android mais antigo, para teste: ver
     * Upkeep.Diag, "registro-adb".)
     */
    private boolean logViaAdb() {
        if (!Platform.tryDirectAdb(this) || !prefs.getBoolean("adbAutorizado", false)) return false;
        return Build.VERSION.SDK_INT >= 33 || prefs.getBoolean(LOG_VIA_ADB, false);
    }

    static final String LOG_VIA_ADB = "registroPeloAdb";
    private final java.util.concurrent.atomic.AtomicReference<java.net.Socket> logSocket =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** Teste e Autoreparar: recomeça a leitura do registro (pelo ADB ou não, conforme o caso). */
    void restartLogWatcher() {
        stopLogWatcher();
        ui.postDelayed(this::startLogWatcher, 1500L);
    }

    private void startLogWatcher() {
        if (watching) return;
        if (logViaAdb()) {
            watching = true;
            Thread t = new Thread(this::watchLogOverAdb, "vitro-home-log-adb");
            t.setDaemon(true);
            t.start();
            return;
        }
        if (getPackageManager().checkPermission("android.permission.READ_LOGS", getPackageName())
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "sem READ_LOGS: o Home é percebido pela janela da tela inicial do sistema, mais tarde");
            return;
        }
        watching = true;
        Thread t = new Thread(this::watchLog, "vitro-home-log");
        t.setDaemon(true);
        t.start();
        if (!Platform.fire(this)) return;   // o "desligar a TV" da Alexa só existe no Fire OS
        watchingAlexa = true;
        Thread a = new Thread(this::watchAlexaOff, "vitro-alexa-off");
        a.setDaemon(true);
        a.start();
    }

    private void stopLogWatcher() {
        watching = false;
        java.net.Socket ls = logSocket.get();
        if (ls != null) try { ls.close(); } catch (IOException ignored) { }
        watchingAlexa = false;
        ui.removeCallbacks(alexaSleep);
        Process p = logProc;
        if (p != null) p.destroy();
        Process q = alexaProc;
        if (q != null) q.destroy();
    }

    /** Como watchLog, mas pelo shell do ADB da própria TV (ver logViaAdb). */
    private void watchLogOverAdb() {
        final long[] seen = {System.currentTimeMillis() - 500L};
        while (watching) {
            long started = SystemClock.uptimeMillis();
            final long[] lines = {0};
            try {
                String since = String.format(java.util.Locale.US, "%.3f", seen[0] / 1000.0);
                Log.i(TAG, "lendo o registro do sistema pelo ADB da TV, para perceber o Home cedo");
                int port = directPort(this);
                if (port < 0) throw new IOException("o ADB da TV não respondeu");
                Adb.stream(this, DIRECT_HOST, port, null, "logcat -b system -v epoch -v uid -T " + since
                        + " ActivityManager:I ActivityTaskManager:I '*:S'", line -> {
                    lines[0]++;
                    long at = lineTime(line);
                    if (at <= seen[0]) return;
                    seen[0] = at;
                    int kind = homeLine(line, 0L, android.os.Process.myUid());
                    if (kind == 1) ui.post(this::homeFromLog);
                    else if (kind == 2) ui.post(() -> replaceHud(true));
                }, logSocket);
            } catch (Exception x) {
                if (watching) Log.w(TAG, "leitura do registro pelo ADB falhou: " + x);
            }
            if (!watching) break;
            long ran = SystemClock.uptimeMillis() - started;
            SystemClock.sleep(ran < 5000L && lines[0] == 0 ? 30_000L : 1000L);
        }
    }

    /**
     * Segue o registro do sistema, só as linhas do ActivityManager, a partir de agora. Se o logcat sair
     * (o registro foi limpo, por exemplo), recomeça do instante da última linha lida, para não perder um
     * Home nesse meio-tempo; linhas já vistas não valem de novo.
     */
    private void watchLog() {
        long seen = System.currentTimeMillis() - 500L;   // instante (ms) da última linha já tratada
        while (watching) {
            long started = SystemClock.uptimeMillis();
            long lines = 0;
            Process p = null;
            try {
                String since = String.format(java.util.Locale.US, "%.3f", seen / 1000.0);
                p = new ProcessBuilder("logcat", "-b", "system", "-v", "epoch", "-v", "uid",
                        "-T", since, "ActivityManager:I", "ActivityTaskManager:I", "*:S").redirectErrorStream(true).start();
                logProc = p;
                BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
                Log.i(TAG, "lendo o registro do sistema para perceber o Home cedo");
                String line;
                while (watching && (line = in.readLine()) != null) {
                    lines++;
                    long at = lineTime(line);
                    if (at <= seen) continue;
                    seen = at;
                    int kind = homeLine(line, 0L, android.os.Process.myUid());
                    if (kind == 1) ui.post(this::homeFromLog);
                    else if (kind == 2) ui.post(() -> replaceHud(true));
                }
            } catch (IOException | RuntimeException x) {
                Log.w(TAG, "leitura do registro falhou: " + x);
            } finally {
                if (p != null) p.destroy();
                logProc = null;
            }
            if (!watching) break;
            // logcat saiu. Se saiu logo e sem nada, provavelmente o processo ainda não tem a permissão
            // (ela vale a partir do próximo início do Vitro): tenta de novo com calma.
            long ran = SystemClock.uptimeMillis() - started;
            SystemClock.sleep(ran < 5000L && lines == 0 ? 30_000L : 1000L);
        }
    }

    /**
     * Segue o registro principal só nas linhas do "desligar a TV" da Alexa, por voz ou por rotina. Numa TV sem
     * HDMI-CEC, a Amazon só consegue desligá-la pelo infravermelho do controle, e só quando o controle acabou de ser
     * usado; numa rotina com espera, a TV continua ligada. Então o Vitro põe o Fire TV para dormir, e a TV se desliga
     * sozinha se tiver o desligamento automático sem sinal ligado. O logcat filtra o texto (-e), e o Vitro recebe só essas linhas.
     */
    private void watchAlexaOff() {
        long seen = System.currentTimeMillis() - 500L;
        String regex = ALEXA_OFF_TEXT.replace(".", "\\.");
        while (watchingAlexa) {
            long started = SystemClock.uptimeMillis();
            long lines = 0;
            Process p = null;
            try {
                String since = String.format(java.util.Locale.US, "%.3f", seen / 1000.0);
                p = new ProcessBuilder("logcat", "-b", "main", "-v", "epoch", "-T", since, "-e", regex)
                        .redirectErrorStream(true).start();
                alexaProc = p;
                BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
                Log.i(TAG, "lendo o registro para perceber o \"desligar a TV\" da Alexa");
                String line;
                while (watchingAlexa && (line = in.readLine()) != null) {
                    lines++;
                    long at = lineTime(line);
                    if (at <= seen) continue;
                    seen = at;
                    if (line.contains(ALEXA_OFF_TAG) && line.contains(ALEXA_OFF_TEXT)) ui.post(this::alexaTurnedOff);
                }
            } catch (IOException | RuntimeException x) {
                Log.w(TAG, "leitura do desligar da Alexa falhou: " + x);
            } finally {
                if (p != null) p.destroy();
                alexaProc = null;
            }
            if (!watchingAlexa) break;
            long ran = SystemClock.uptimeMillis() - started;
            SystemClock.sleep(ran < 5000L && lines == 0 ? 30_000L : 1000L);
        }
    }

    /** A Alexa mandou desligar a TV: daqui a pouco o Fire TV dorme (a TV se desliga sozinha por falta de sinal). */
    private void alexaTurnedOff() {
        if (!SleepTimer.followsAlexa(this)) {
            Log.i(TAG, "Alexa mandou desligar a TV; dormir junto está desligado");
            return;
        }
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return;
        ui.removeCallbacks(alexaSleep);
        ui.postDelayed(alexaSleep, ALEXA_OFF_SLEEP_MS);
        Log.i(TAG, "Alexa mandou desligar a TV: o Fire TV dorme em " + ALEXA_OFF_SLEEP_MS / 1000 + " s");
    }

    private void sleepAfterAlexa() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        Handler h = adb;
        if (h == null || (pm != null && !pm.isInteractive())) return;
        h.post(() -> goToSleep("desligar a TV da Alexa"));
    }

    /** Instante da linha em ms ("-v epoch"), ou -1 se a linha não começa com um. */
    static long lineTime(String line) {
        String t = line.trim();
        int sp = t.indexOf(' ');
        if (sp <= 0) return -1L;
        try {
            return (long) (Double.parseDouble(t.substring(0, sp)) * 1000.0);
        } catch (NumberFormatException x) {
            return -1L;
        }
    }

    /**
     * 1 = o sistema abriu a tela da Amazon (Home ou outro pedido de início); 2 = o menu rápido (Home
     * segurado); 0 = outra coisa. Formato: "época  uid  pid  tid  I ActivityManager: START u0 {...}".
     * Só valem linhas escritas pelo próprio sistema (uid 1000), e não as que o próprio Vitro causou
     * ("Tela inicial da Amazon" no menu do Vitro).
     */
    static int homeLine(String line, long notBefore, int ownUid) {
        int s = line.indexOf("START u0 {");
        if (s < 0) return 0;
        String[] f = line.trim().split("\\s+", 3);
        if (f.length < 3) return 0;
        if (!"1000".equals(f[1]) && !"system".equals(f[1])) return 0;
        try {
            if ((long) (Double.parseDouble(f[0]) * 1000.0) < notBefore) return 0;
        } catch (NumberFormatException x) {
            return 0;
        }
        if (line.indexOf("SHOW_HUD", s) >= 0) return 2;
        if (line.indexOf("cat=[android.intent.category.HOME]", s) < 0) return 0;
        if (line.indexOf("cmp=" + homePkg + "/", s) < 0) return 0;
        if (line.endsWith("from uid " + ownUid)) return 0;
        return 1;
    }

    /** O registro mostrou a tela da Amazon sendo aberta: o Vitro vai antes de ela desenhar. */
    private void homeFromLog() {
        long now = SystemClock.uptimeMillis();
        if (now - lastLogHome < 600L) return;
        lastLogHome = now;
        lastOpen = now;
        leftAmazon();   // saindo da tela da Amazon aberta de propósito: agenda o encerramento do Prime Video
        boolean fast = alarmIsImmediate();
        // Com o Vitro já na frente, a tela da Amazon (já carregada) volta rápido: cobre por cima até o
        // Vitro voltar. Vindo de outro app, ela ainda leva mais de meio segundo para desenhar.
        if (fast && getPackageName().equals(front)) showCover();
        boolean alarm = startViaAlarm(vitroIntent(), 1);
        if (!alarm) open();
        Log.i(TAG, "Home visto no registro do sistema; Vitro " + (!alarm ? "aberto direto"
                : "pelo alarme" + (fast ? "" : " (sem min_futurity=0 o Fire OS adia uns 5 s)")));
    }

    // ---- Prime Video depois de ligar ou acordar a TV (pelo ADB da própria TV) -----------------

    /** Agenda uma tentativa; as já agendadas continuam valendo (as próximas da mesma leva viram uma só). */
    private void schedulePrimeClose(long delayMs) {
        Handler h = adb;
        if (h == null) {
            Log.w(TAG, "linha do ADB parada; encerramento do Prime Video não agendado");
            return;
        }
        boolean ok = h.postDelayed(closePrime, delayMs);
        Log.i(TAG, "Prime Video conferido daqui a " + delayMs / 1000 + " s" + (ok ? "" : " (agendamento recusado)"));
    }

    /** Na linha do ADB: desativa o Prime Video se estiver ativo e fora da tela. */
    private void primeOffNow() {
        primeOff(false);
    }

    /** force: no fim da janela de atualização, confere mesmo que uma conferência tenha acabado de rodar. */
    void primeOff(boolean force) {
        if (!prefs.getBoolean(SetupActivity.PRIME_ON_DEMAND, true)) {
            Log.i(TAG, "Prime Video sob demanda desligado na primeira configuração: fica ativo");
            return;
        }
        if (upkeep != null && upkeep.windowOpen()) {
            Log.i(TAG, "janela de atualização aberta: o Prime Video fica ativo");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastClose < CLOSE_MIN_GAP_MS) {
            Log.i(TAG, "Prime Video conferido há pouco; esta vez fica");
            return;
        }
        lastClose = now;
        if (!Apps.isEnabled(this, PRIME)) {
            Log.i(TAG, "Prime Video já desativado");
            return;
        }
        if (PRIME.equals(front)) {
            Log.i(TAG, "Prime Video na tela; fica ativo");
            return;
        }
        // Confere no próprio shell da TV que ele não está na tela antes de desativar: logo depois de o serviço
        // ligar, "front" ainda não sabe o que está na frente, e um filme em andamento não pode cair.
        String cmd = "if dumpsys activity activities | grep -m1 -E 'mResumedActivity|ResumedActivity:' | grep -q " + PRIME
                + " || dumpsys window windows | grep -m1 mCurrentFocus | grep -q " + PRIME
                + "; then echo EM_USO; else pm disable-user --user 0 " + PRIME + "; fi";
        try {
            String r = shell(cmd, false, 0L).trim();
            prefs.edit().putBoolean("adbAutorizado", true).apply();
            if (r.contains("EM_USO")) Log.i(TAG, "Prime Video na tela; fica ativo");
            else if (r.contains("disabled")) Log.i(TAG, "Prime Video desativado até ser aberto pela barra do Vitro");
            else Log.w(TAG, "Prime Video: resposta inesperada ao desativar: " + r);
        } catch (Adb.NotAuthorized x) {
            prefs.edit().putBoolean("adbAutorizado", false).apply();
            Log.w(TAG, "Prime Video não desativado: a chave do Vitro não está autorizada na TV");
        } catch (NoTunnel x) {
            Log.w(TAG, "Prime Video não desativado: " + x.getMessage());
        } catch (Exception x) {
            Log.w(TAG, "Prime Video não desativado: sem conexão com o ADB da TV (" + x + ")");
        }
    }

    /**
     * Barra ou gaveta do Vitro: ativa o app sob demanda pelo ADB da TV e o abre. O túnel já está desligado
     * quando o app sobe (apps de vídeo podem recusar tocar com uma VPN ativa). false = serviço desligado.
     */
    static boolean openOnDemand(Apps.App a) {
        HomeService s = running;
        Handler h = s == null ? null : s.adb;
        if (h == null) return false;
        h.removeCallbacks(s.primeAway);
        h.post(() -> s.enableAndOpen(a));
        return true;
    }

    private void enableAndOpen(Apps.App a) {
        try {
            if (!Apps.isEnabled(this, a.pkg)) {
                String r = shell("pm enable " + a.pkg, false, 0L).trim();
                if (!r.contains("enabled")) {
                    Log.w(TAG, a.label + ": resposta inesperada ao ativar: " + r);
                    toast(L.t("Não consegui ativar ", "Could not enable ") + a.label + ".");
                    return;
                }
                Log.i(TAG, a.label + " ativado para abrir");
            }
            final Intent i = Apps.launchIntent(this, a);
            ui.post(() -> {
                try {
                    startActivity(i);
                } catch (RuntimeException x) {
                    toast(L.t("Não foi possível abrir ", "Could not open ") + a.label + ".");
                }
            });
        } catch (Adb.NotAuthorized x) {
            prefs.edit().putBoolean("adbAutorizado", false).apply();
            toast(L.t("O ADB da TV não reconhece o Vitro. Use Autoreparar, no menu do Vitro.",
                    "The TV's ADB does not recognize Vitro. Use Self-repair in Vitro's menu."));
        } catch (Exception x) {
            Log.w(TAG, "não consegui ativar " + a.label + " (" + x + ")");
            toast(L.t("Não consegui ativar ", "Could not enable ") + a.label + ": " + x.getMessage());
        }
    }

    /**
     * Se a TV ainda não conhece a chave do Vitro, pede a autorização na tela (no máximo uma vez a cada
     * 12 h) e espera a resposta. Autorizada, já encerra o Prime Video se ele estiver parado.
     */
    private void authorizeIfNeeded() {
        if (adb == null || prefs.getBoolean("adbAutorizado", false) || (!Tunnel.allowed(this) && !Platform.tryDirectAdb(this))) return;
        if (System.currentTimeMillis() - prefs.getLong("adbPedidoEm", 0L) < ASK_AGAIN_MS) return;
        adb.post(() -> {
            try {
                // Primeiro sem pedir nada: a chave pode já estar autorizada.
                shell("true", false, 0L);
                prefs.edit().putBoolean("adbAutorizado", true).apply();
                Log.i(TAG, "a chave do Vitro já está autorizada na TV");
            } catch (Adb.NotAuthorized x) {
                askForKey();
            } catch (Exception x) {
                Log.w(TAG, "sem conexão com o ADB da TV (" + x + ")");
            }
        });
    }

    /**
     * Na linha do ADB: mostra o pedido de autorização na TV e espera a resposta. Depois confere, numa conexão
     * nova, se a TV guardou a chave: sem "Sempre permitir", a autorização vale só para aquela conexão, e todo uso
     * seguinte do ADB (Prime Video, guarda, Autoreparar) falharia calado. Nesse caso pede mais uma vez, explicando.
     * true = autorizada e guardada pela TV.
     */
    private boolean askForKey() {
        try {
            prefs.edit().putLong("adbPedidoEm", System.currentTimeMillis()).apply();
            Log.i(TAG, "pedindo na tela que a TV autorize a chave do Vitro; impressão digital "
                    + Adb.fingerprint(this));
            toast(L.t("O Vitro pede acesso ao ADB da TV para cuidar de si sozinho: marque Sempre permitir e confirme.",
                    "Vitro asks for access to the TV's ADB to look after itself: tick Always allow and confirm."));
            shell("true", true, APPROVAL_WAIT_MS);
            if (!keySaved()) {
                Log.w(TAG, "chave autorizada só para uma conexão (sem Sempre permitir); pedindo de novo");
                toast(L.t("A TV não guardou a autorização. No novo aviso, marque Sempre permitir antes de confirmar.",
                        "The TV did not keep the approval. In the new prompt, tick Always allow before confirming."));
                shell("true", true, APPROVAL_WAIT_MS);
                if (!keySaved()) {
                    prefs.edit().putBoolean("adbAutorizado", false).apply();
                    Log.w(TAG, "a TV não guardou a chave do Vitro");
                    toast(L.t("A TV ainda não guardou a autorização do Vitro. Use Autoreparar de novo e marque Sempre "
                                    + "permitir no aviso.",
                            "The TV still did not keep Vitro's approval. Use Self-repair again and tick Always allow "
                                    + "in the prompt."));
                    return false;
                }
            }
            prefs.edit().putBoolean("adbAutorizado", true).apply();
            Log.i(TAG, "chave do Vitro autorizada");
            toast(L.t("Vitro autorizado no ADB da TV.", "Vitro authorized on the TV's ADB."));
            primeOffNow();
            return true;
        } catch (Exception x) {
            Log.w(TAG, "a chave do Vitro não foi autorizada (" + x + ")");
            toast(L.t("O Vitro não foi autorizado no ADB da TV. Para tentar de novo: Autoreparar, no menu do Vitro.",
                    "Vitro was not authorized on the TV's ADB. To try again: Self-repair, in Vitro's menu."));
            return false;
        }
    }

    /** Uma conexão nova sem oferecer a chave (nenhum aviso na tela): só passa se a TV guardou a autorização. */
    private boolean keySaved() throws Exception {
        try {
            shell("true", false, 0L);
            return true;
        } catch (Adb.NotAuthorized x) {
            return false;
        }
    }

    /**
     * Menu "Sobre este launcher": confere o ADB da TV. Se a chave do Vitro não estiver autorizada (o aviso
     * passou despercebido, ou foi revogada), pede de novo na hora. Sem o serviço ligado, não faz nada.
     */
    static void adbFromMenu() {
        HomeService s = running;
        Handler h = s == null ? null : s.adb;
        if (h != null) h.post(s::adbCheckOrAsk);
    }

    /** Autoreparar, no menu: confere o ADB da TV (pede a chave se preciso) e refaz o que uma atualização desfez. */
    private void adbCheckOrAsk() {
        if (needsPairing()) {
            openPairing();
            return;
        }
        try {
            shell("true", false, 0L);
            prefs.edit().putBoolean("adbAutorizado", true).apply();
            repairAndReport();
        } catch (Adb.NotAuthorized x) {
            prefs.edit().putBoolean("adbAutorizado", false).apply();
            if (onlyWireless()) {
                // A TV esqueceu o pareamento (reset, "revogar autorizações"): pareia de novo.
                AdbTls.setPaired(this, false);
                openPairing();
                return;
            }
            // Autorizada agora e guardada pela TV: segue com o conserto. Se não, askForKey já disse o que fazer.
            if (askForKey()) repairAndReport();
        } catch (NoTunnel x) {
            toast(L.t("O Vitro não ligou o túnel local: ", "Vitro could not start the local tunnel: ") + x.getMessage() + ".");
        } catch (Exception x) {
            Log.w(TAG, "ADB da TV sem resposta (" + x + ")");
            toast(Platform.fire(this)
                    ? L.t("O ADB da TV não respondeu. A depuração ADB está ligada?", "The TV's ADB did not answer. Is ADB debugging on?")
                    : L.t("O ADB da TV não respondeu pela rede (porta 5555). Confira a Depuração USB e a Depuração pela rede; o "
                            + "guia do Vitro explica.", "The TV's ADB did not answer over the network (port 5555). Check USB "
                            + "debugging and Network debugging; Vitro's guide explains."));
        }
    }

    /** TV em que o ADB só abre pela depuração sem fio (porta 5555 fechada, Android 12 em diante). */
    private boolean onlyWireless() {
        if (Platform.fire(this) || Build.VERSION.SDK_INT < 31) return false;
        if (prefs.getBoolean(FORCE_WIRELESS, false)) return true;
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(DIRECT_HOST, 5555), 500);
            return false;
        } catch (IOException x) {
            return true;
        }
    }

    /** Só a depuração sem fio, e o Vitro ainda não foi pareado. */
    private boolean needsPairing() {
        return onlyWireless() && !AdbTls.paired(this);
    }

    /** Abre a primeira configuração direto no passo do pareamento. */
    private void openPairing() {
        Log.i(TAG, "Autoreparar: esta TV precisa do pareamento com a depuração sem fio");
        toast(L.t("Esta TV só abre o ADB pela depuração sem fio: pareie o Vitro uma vez.",
                "This TV only opens ADB through wireless debugging: pair Vitro once."));
        ui.post(() -> {
            try {
                startActivity(new Intent(this, SetupActivity.class).putExtra(SetupActivity.EXTRA_PAIR, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } catch (RuntimeException ignored) { }
        });
    }

    private void repairAndReport() {
        boolean fixed = repairWithAdb();
        if (upkeep != null) upkeep.checkNow();
        if (Repair.enabled(this) && (!Repair.has(this, Repair.READ_LOGS) || !Repair.has(this, Repair.WSS))) {
            // O ADB da TV não deixou conceder de novo as permissões: dizer isso, e não "tudo em ordem".
            toast(L.t("Autoreparar: o ADB da TV não deixou refazer as permissões do Vitro. Tente de novo em instantes.",
                    "Self-repair: the TV's ADB did not let Vitro restore its permissions. Try again in a moment."));
        } else if (!fixed) {
            toast(L.t("Autoreparar: tudo em ordem.", "Self-repair: everything is fine.")
                    + (upkeep == null ? "" : " " + upkeep.summary()) + " " + Repair.status(this));
        }
    }

    /** Fora do Fire OS, o Vitro tenta primeiro o ADB da TV direto, neste endereço (ver Platform.tryDirectAdb). */
    static final String DIRECT_HOST = "127.0.0.1";

    static final String FORCE_WIRELESS = "testeForcarSemFio";

    /** Resultado de probeDirect. */
    static final int ADB_ANSWERS = 0, ADB_REFUSED = 1, ADB_SILENT = 2;

    /**
     * Como o ADB da TV responde direto, sem o túnel (serve para a primeira configuração escolher o caminho):
     * ADB_ANSWERS se responde (um "não conheço a sua chave" também é resposta; vale a depuração sem fio já pareada),
     * ADB_REFUSED se ninguém escuta na porta 5555 (TVs só com a depuração sem fio), ADB_SILENT se ele fecha a conexão
     * sem responder (como o Fire OS faz: aí o túnel resolve).
     */
    static int probeDirect(Context c) {
        if (!Platform.tryDirectAdb(c)) return ADB_SILENT;
        if (c.getSharedPreferences("vitro", Context.MODE_PRIVATE).getBoolean(FORCE_WIRELESS, false)) {
            return AdbTls.paired(c) && AdbTls.connectPort(c) > 0 ? ADB_ANSWERS : ADB_REFUSED;
        }
        try {
            Adb.run(c, DIRECT_HOST, 5555, null, "true", false, 0L);
            return ADB_ANSWERS;
        } catch (Adb.NotAuthorized x) {
            return ADB_ANSWERS;
        } catch (java.net.ConnectException x) {
            if (AdbTls.paired(c) && AdbTls.connectPort(c) > 0) return ADB_ANSWERS;
            Log.i(TAG, "ninguém escuta no ADB da TV pela porta 5555");
            return ADB_REFUSED;
        } catch (Exception x) {
            Log.i(TAG, "ADB direto sem resposta (" + x + ")");
            return ADB_SILENT;
        }
    }

    /**
     * Porta do ADB da própria TV fora do Fire OS: 5555 se ele escuta nela (depuração pela rede, ou "adb tcpip"); senão
     * a da depuração sem fio, se o Vitro já foi pareado (ver AdbTls); -1 se nenhuma responde.
     */
    static int directPort(Context c) {
        // Teste (Upkeep.Diag, "sem-fio-forcar"): ignora a porta 5555, como numa TV que só tem a depuração sem fio.
        if (c.getSharedPreferences("vitro", Context.MODE_PRIVATE).getBoolean(FORCE_WIRELESS, false)) {
            return AdbTls.paired(c) ? AdbTls.connectPort(c) : -1;
        }
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(DIRECT_HOST, 5555), 500);
            return 5555;
        } catch (IOException x) {
            return AdbTls.paired(c) ? AdbTls.connectPort(c) : -1;
        }
    }

    /** O túnel não pôde ser ligado: VPN local não autorizada, ou outra VPN ligada. */
    static final class NoTunnel extends IOException {
        NoTunnel(String m) { super(m); }
    }

    /** Linha do ADB (ou null com o serviço parado): tudo que usa o shell da TV roda nela, um de cada vez. */
    Handler adbHandler() {
        return adb;
    }

    /** Alarme da janela do Prime Video: roda na linha do ADB e chama done no fim. false = serviço parado. */
    static boolean upkeepAlarm(String action, Runnable done) {
        HomeService s = running;
        Handler h = s == null ? null : s.adb;
        if (h == null || s.upkeep == null) return false;
        return h.post(() -> {
            try {
                s.upkeep.alarmFired(action);
            } finally {
                done.run();
            }
        });
    }

    /** Timer de desligamento (ver SleepTimer): põe a TV para dormir na linha do ADB e chama done no fim. */
    static boolean sleepNow(Runnable done) {
        HomeService s = running;
        Handler h = s == null ? null : s.adb;
        if (h == null) return false;
        return h.post(() -> {
            try {
                s.goToSleep("timer de desligamento");
            } finally {
                done.run();
            }
        });
    }

    /**
     * Na linha do ADB: a tecla de dormir, como a do controle. Antes, liga só para este momento o "desligar a TV junto"
     * pelo HDMI-CEC (volta ao que era quando a TV acordar; ver SleepTimer.restoreTvOff). Sem CEC na TV, ela fica sem sinal.
     */
    private void goToSleep(String why) {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return;
        if (SleepTimer.tvOffForSleep(this)) SystemClock.sleep(800L);   // o serviço de HDMI do Android lê o ajuste
        try {
            shell("input keyevent KEYCODE_SLEEP", false, 0L);
            Log.i(TAG, why + ": Fire TV posto para dormir");
        } catch (Exception x) {
            Log.w(TAG, why + ": não consegui pôr a TV para dormir (" + x + ")");
            SleepTimer.restoreTvOff(this);
        }
    }

    /** Diagnóstico pedido pelo adb (ver Upkeep.Diag). */
    /**
     * Primeira configuração: começa a esperar o código de pareamento da depuração sem fio (o painel aparece por cima
     * das Configurações). false se o serviço não está ligado.
     */
    static boolean startPairing() {
        HomeService s = running;
        if (s == null || s.pairing == null) return false;
        s.ui.post(s.pairing::start);
        return true;
    }

    static void upkeepTest(String what, int minutes) {
        HomeService s = running;
        Handler h = s == null ? null : s.adb;
        if (h != null && s.upkeep != null) h.post(() -> s.upkeep.test(what, minutes));
        else Log.w(TAG, "teste da manutenção sem o serviço do Home ligado");
    }

    /** Aviso da manutenção: na hora se o Vitro estiver na tela; senão, quando ele vier para a frente. */
    void note(String msg) {
        Log.i(TAG, msg);
        if (getPackageName().equals(front)) toast(msg);
        else Repair.keepNote(this, msg);
    }

    /** Roda um comando no shell da própria TV, pelo túnel local (ver Tunnel). Na linha do ADB. */
    String shell(String cmd, boolean offerKey, long approvalMs) throws Exception {
        return shellAt(this, cmd, offerKey, approvalMs);
    }

    private static final Object TUNNEL_USERS_LOCK = new Object();
    private static int tunnelUsers;      // protegido por TUNNEL_USERS_LOCK

    /**
     * O mesmo, para quem roda fora do serviço (a primeira configuração, ver SetupActivity). Fora de qualquer thread
     * principal. Pode haver dois usos ao mesmo tempo (a configuração e o serviço que ela acabou de ligar): o túnel
     * só espelha pacotes, então aguenta as duas conexões, e só é desligado quando o último uso termina.
     */
    static String shellAt(Context c, String cmd, boolean offerKey, long approvalMs) throws Exception {
        if (Platform.tryDirectAdb(c)) {
            // Android TV e Google TV: o ADB da própria TV costuma aceitar a conexão direta, sem a VPN: na porta 5555
            // ou, nas TVs que só têm a depuração sem fio, na porta dela, depois do pareamento (ver AdbTls).
            int port = directPort(c);
            if (port > 0) {
                try {
                    return Adb.run(c, DIRECT_HOST, port, null, cmd, offerKey && port == 5555, approvalMs);
                } catch (java.io.EOFException | java.net.SocketException x) {
                    if (port != 5555 || x instanceof java.net.ConnectException) {
                        if (!Tunnel.allowed(c)) throw x;
                    } else {
                        // O ADB fechou a conexão sem responder (como o Fire OS faz): daqui em diante, só o túnel.
                        Log.i(TAG, "o ADB da TV recusou a conexão direta (" + x + "); daqui em diante, pelo túnel");
                        Platform.directAdbFailed(c);
                    }
                }
            } else if (!Tunnel.allowed(c)) {
                throw new java.net.ConnectException("o ADB da TV não escuta na porta 5555 e o Vitro ainda não foi "
                        + "pareado com a depuração sem fio");
            }
        }
        if (!Tunnel.allowed(c)) throw new NoTunnel("a VPN local do Vitro ainda não foi autorizada");
        synchronized (TUNNEL_USERS_LOCK) {
            if (tunnelUsers == 0 && Tunnel.otherVpnActive(c)) throw new NoTunnel("outra VPN está ligada");
            if (!Tunnel.up(c, 4000L)) {
                if (tunnelUsers == 0) Tunnel.down(c);
                throw new IOException("o túnel local não abriu");
            }
            tunnelUsers++;
        }
        try {
            return Adb.run(c, Tunnel.PEER, Tunnel.network(c), cmd, offerKey, approvalMs);
        } finally {
            synchronized (TUNNEL_USERS_LOCK) {
                if (--tunnelUsers == 0) Tunnel.down(c);
            }
        }
    }

    /** O serviço está ligado (acessibilidade ativa): o Vitro pode preparar o ADB da TV. */
    static boolean isRunning() {
        return running != null;
    }

    private void toast(String t) {
        ui.post(() -> Toast.makeText(this, t, Toast.LENGTH_LONG).show());
    }

    // ---- Cobertura que esconde a tela da Amazon enquanto o Vitro sobe ------------------------

    private void showCover() {
        ui.removeCallbacks(uncover);
        ui.postDelayed(uncover, COVER_MAX_MS);
        if (cover != null || wm == null) return;
        View v = new View(this);
        v.setBackgroundColor(COVER_COLOR);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        try {
            wm.addView(v, lp);
            cover = v;
        } catch (RuntimeException x) {
            Log.e(TAG, "não foi possível pôr a cobertura", x);
        }
    }

    /** A janela de destino apareceu: a cobertura fica mais um instante e dissolve. */
    private void scheduleUncover() {
        if (cover == null) return;
        ui.removeCallbacks(uncover);
        ui.postDelayed(uncover, COVER_HOLD_MS);
    }

    private void fadeCover() {
        ui.removeCallbacks(uncover);
        final View v = cover;
        cover = null;
        if (v == null) return;
        v.animate().alpha(0f).setDuration(COVER_FADE_MS).withEndAction(() -> {
            try { if (wm != null) wm.removeView(v); } catch (RuntimeException ignored) { }
        }).start();
    }

    private void removeCoverNow() {
        ui.removeCallbacks(uncover);
        View v = cover;
        cover = null;
        if (v == null || wm == null) return;
        try { wm.removeView(v); } catch (RuntimeException ignored) { }
    }
}
