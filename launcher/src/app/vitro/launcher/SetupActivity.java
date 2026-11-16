package app.vitro.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Primeira configuração: numa Fire TV que ainda não foi preparada, guia a pessoa pelo que só ela pode fazer e faz
 * o resto pelo ADB da própria TV (ver Adb e Tunnel), com a confirmação dela em cada passo.
 *
 * Só ela pode: ligar a depuração ADB, permitir a VPN local e autorizar a chave do Vitro no aviso do ADB. Depois,
 * o Vitro sozinho: desativa os apps escolhidos (nada é apagado), guarda a escolha do Prime Video sob demanda,
 * concede a si as permissões de ler o registro e de mudar ajustes, liga o próprio serviço do botão Home (ver
 * Repair.settings) e reinicia, para a leitura do registro passar a valer.
 *
 * Os apps: os recomendados, os recomendados e mais alguns da Amazon, ou o diagnóstico, que mede na hora o que mais
 * pesa na memória e mostra app por app, com os recomendados já marcados (lista em assets/debloat-grupos.txt, montada
 * pelo build a partir do debloat.txt; a Alexa nunca é oferecida).
 *
 * Abre sozinha quando a tela do Vitro aparece e o aparelho ainda não está pronto (ver shouldOpen). "Agora não" ou
 * Voltar a dispensam; ela volta pelo menu Autoreparar. Numa TV já preparada nunca abre sozinha.
 * Telas: prototipo/configuracao.html. Português e inglês (ver L).
 */
public final class SetupActivity extends GlassActivity {
    private static final String TAG = "VitroConfig";
    static final String DONE = "configurado", DISMISSED = "configuracaoDispensada", PRIME_ON_DEMAND = "primeSobDemanda";
    /** Apps desativados pela configuração fora da lista da guarda: a guarda também os mantém desativados (ver Upkeep). */
    static final String EXTRA = "debloatExtra";
    /** Abre direto no passo do pareamento com a depuração sem fio (pedido pelo Autoreparar). */
    static final String EXTRA_PAIR = "parear";
    private static final String PRIME = "com.amazon.firebat";
    /** Grupos do debloat.txt: os recomendados e os "mais". A Alexa nunca; os outros só pelo diagnóstico. */
    private static final List<String> RECOMMENDED = Arrays.asList("anuncios", "telemetria", "extras"),
            MORE = Arrays.asList("apps-amazon", "tv-ao-vivo", "atalhos", "apps-google", "apps-tcl");
    /** Controle por voz (Alexa no Fire OS, Assistente no Android TV): nunca é oferecido. */
    private static final List<String> VOICE = Arrays.asList("alexa", "voz");
    private static final int REQ_VPN = 42;
    private static final long APPROVAL_WAIT_MS = 120_000L;
    /** No diagnóstico, os apps do sistema (que não dá para desativar) entram só entre os que mais pesam. */
    private static final int SYSTEM_SHOWN = 6;

    private static final int WELCOME = 0, ADB = 1, VPN = 2, KEY = 3, APPS = 4, PRIME_STEP = 5, HOME = 6, FINISHED = 7,
            DIAG = 8, PAIR = 9;
    private static final int NUMBERED = 6;

    /** A tela está aberta (para a tela inicial não abrir outra por cima). */
    static volatile boolean showing;

    /** Fire OS (true) ou Android TV / Google TV (ver Platform). */
    private boolean fire;
    /** Fora do Fire OS: como o ADB da TV responde direto (HomeService.probeDirect); null = ainda não conferido. */
    private Integer direct;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread work;
    private Handler bg;
    private int step = WELCOME;
    private final List<String> rec = new ArrayList<>(), more = new ArrayList<>(), others = new ArrayList<>();
    private final Map<String, String> groupOf = new HashMap<>(), descPt = new HashMap<>(), descEn = new HashMap<>();
    private int appsOff = -1, appsRefused;

    /** Uma linha do diagnóstico. */
    private static final class Row {
        String pkg, name, desc, tag;
        long kb;
        boolean selectable, recommended, running;
    }
    private final List<Row> diag = new ArrayList<>();
    private final Set<String> checked = new LinkedHashSet<>();
    private TextView diagGo;
    private View lastRow;

    // ---- Quando abrir ------------------------------------------------------------------------

    /** O aparelho já está pronto: serviço do botão Home ligado e as duas permissões concedidas. */
    static boolean complete(Context c) {
        return serviceListed(c) && Repair.has(c, Repair.READ_LOGS) && Repair.has(c, Repair.WSS);
    }

    /** Abre sozinha só numa TV ainda não preparada, e não de novo depois de "Agora não". */
    static boolean shouldOpen(Context c) {
        SharedPreferences p = c.getSharedPreferences("vitro", MODE_PRIVATE);
        if (showing || p.getBoolean(DONE, false)) return false;
        if (complete(c)) {
            p.edit().putBoolean(DONE, true).apply();
            return false;
        }
        return !p.getBoolean(DISMISSED, false);
    }

    static boolean serviceListed(Context c) {
        ComponentName me = new ComponentName(c, HomeService.class);
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        for (String e : list.split(":")) {
            if (me.equals(ComponentName.unflattenFromString(e.trim()))) return true;
        }
        return false;
    }

    // ---- Ciclo de vida -----------------------------------------------------------------------

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        showing = true;
        work = new HandlerThread("vitro-config");
        work.start();
        bg = new Handler(work.getLooper());
        fire = Platform.fire(this);
        loadCandidates();
        if (getIntent().getBooleanExtra(EXTRA_PAIR, false) && !fire) {
            direct = HomeService.ADB_REFUSED;
            go(PAIR);
        } else {
            go(WELCOME);
        }
        Log.i(TAG, "primeira configuração aberta; apps oferecidos: " + rec.size() + " recomendados, " + more.size() + " a mais");
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.getBooleanExtra(EXTRA_PAIR, false) && !fire && step != PAIR && !busy) {
            direct = HomeService.ADB_REFUSED;
            go(PAIR);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        // Voltando das Configurações: se a depuração ADB já foi ligada, segue sozinho.
        if (step == ADB && !busy && adbOn()) go(VPN);
        // Voltando do pareamento (ou de ligar o serviço do Vitro em Acessibilidade).
        if (step == PAIR && !busy) {
            if (AdbTls.paired(this)) {
                direct = HomeService.ADB_ANSWERS;
                go(KEY);
            } else {
                render();
            }
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        showing = false;
        ui.removeCallbacksAndMessages(null);
        work.quitSafely();
    }

    @Override public void onBackPressed() {
        if (busy || step == FINISHED) return;
        if (step == DIAG) {
            go(APPS);
            return;
        }
        dismiss();
    }

    private void dismiss() {
        prefs.edit().putBoolean(DISMISSED, true).apply();
        Log.i(TAG, "primeira configuração dispensada no passo " + step);
        finish();
    }

    // ---- Passos ------------------------------------------------------------------------------

    /** Vai para o passo s, pulando o que já está feito. */
    private void go(int s) {
        if (s == ADB && adbOn()) s = VPN;
        if (s == VPN && Tunnel.allowed(this)) s = KEY;
        if (s == VPN && !fire) {
            // Android TV e Google TV: se o ADB da TV responde direto, a VPN nem é pedida. Se ninguém escuta na porta
            // 5555 (Android/Google TV 13 e 14), o caminho é o pareamento com a depuração sem fio (Android 12 em diante).
            if (direct == null) {
                probeDirect();
                return;
            }
            if (direct == HomeService.ADB_ANSWERS) s = KEY;
            else if (direct == HomeService.ADB_REFUSED) s = Build.VERSION.SDK_INT >= 31 ? PAIR : KEY;
        }
        if (s == PRIME_STEP && !installed(PRIME)) s = HOME;
        step = s;
        render();
        if (s == KEY) keySilently();
    }

    private void probeDirect() {
        setBusy(L.t("Procurando o ADB da TV…", "Looking for the TV's ADB…"));
        bg.post(() -> {
            int r = HomeService.probeDirect(this);
            Log.i(TAG, "ADB da TV direto, sem a VPN: " + (r == HomeService.ADB_ANSWERS ? "responde"
                    : r == HomeService.ADB_REFUSED ? "porta 5555 fechada" : "fecha sem responder"));
            ui.post(() -> {
                direct = r;
                go(VPN);
            });
        });
    }

    private void render() {
        clear(step == APPS || step == DIAG ? WIDE : NARROW);
        int shown = step == DIAG ? APPS : step == PAIR ? VPN : step;
        stepLabel.setText(step == WELCOME ? L.t("CONFIGURAÇÃO DO VITRO", "VITRO SETUP")
                : step == FINISHED ? L.t("CONCLUÍDO", "DONE")
                : L.t("PASSO ", "STEP ") + shown + L.t(" DE ", " OF ") + NUMBERED
                        + (step == DIAG ? L.t(" · DIAGNÓSTICO", " · CHECKUP") : ""));
        dots(shown + 1, FINISHED + 1);
        switch (step) {
            case WELCOME:
                title.setText(fire ? L.t("Vamos preparar a sua Fire TV", "Let's set up your Fire TV")
                        : L.t("Vamos preparar a sua TV", "Let's set up your TV"));
                text(fire ? L.t("O Vitro precisa de três autorizações suas e faz o resto sozinho: liga o botão Home, desativa os "
                                + "apps de anúncios e telemetria da Amazon e cuida do Prime Video.",
                        "Vitro needs three approvals from you and does the rest by itself: it takes over the Home button, "
                                + "disables Amazon's ad and telemetry apps and looks after Prime Video.")
                        : L.t("O Vitro precisa de duas ou três autorizações suas e faz o resto sozinho: liga o botão Home e "
                                + "desativa os apps de anúncios, recomendações e telemetria do Google. Sistema encontrado: "
                                + Platform.name(this) + ".",
                        "Vitro needs two or three approvals from you and does the rest by itself: it takes over the Home "
                                + "button and disables Google's ad, recommendation and telemetry apps. System found: "
                                + Platform.name(this) + "."));
                text(L.t("Leva uns 3 minutos. Nada é apagado, e tudo pode ser desfeito.",
                        "It takes about 3 minutes. Nothing is deleted, and everything can be undone."));
                key(L.t("Começar", "Start"), v -> go(ADB));
                key(L.t("Agora não", "Not now"), v -> dismiss());
                key(L.t("English", "Português"), v -> {
                    L.set(this, !L.en());
                    recreate();
                });
                break;
            case ADB:
                title.setText(L.t("Ligue a depuração ADB", "Turn on ADB debugging"));
                text(L.t("É por ela que o Vitro faz os ajustes, de dentro da própria TV.",
                        "This is how Vitro makes its changes, from inside the TV itself."));
                if (fire) {
                    text(L.t("1. Em Configurações › Minha Fire TV › Sobre, aperte OK 7 vezes sobre o nome do aparelho, até "
                                    + "aparecer “Você agora é um desenvolvedor”.",
                            "1. In Settings › My Fire TV › About, press OK 7 times on the device name, until “You are now a "
                                    + "developer” shows up."));
                    text(L.t("2. Volte uma tela, entre em Opções do desenvolvedor e ligue Depuração ADB. Depois, volte para o Vitro.",
                            "2. Go back one screen, open Developer options and turn on ADB debugging. Then come back to Vitro."));
                } else if (Platform.kind(this) == Platform.GOOGLE_TV) {
                    text(L.t("1. Em Configurações › Sistema › Sobre, aperte OK 7 vezes sobre “Build do SO do Android TV”, até "
                                    + "aparecer que você é um desenvolvedor.",
                            "1. In Settings › System › About, press OK 7 times on “Android TV OS build”, until it says you are "
                                    + "a developer."));
                    text(L.t("2. Volte para Sistema, entre em Opções do desenvolvedor e ligue Depuração USB (e, se houver, "
                                    + "Depuração pela rede). Depois, volte para o Vitro.",
                            "2. Go back to System, open Developer options and turn on USB debugging (and Network debugging, if "
                                    + "listed). Then come back to Vitro."));
                } else {
                    text(L.t("1. Em Configurações › Preferências do dispositivo › Sobre, aperte OK 7 vezes sobre “Build”, até "
                                    + "aparecer que você é um desenvolvedor.",
                            "1. In Settings › Device Preferences › About, press OK 7 times on “Build”, until it says you are a "
                                    + "developer."));
                    text(L.t("2. Volte uma tela, entre em Opções do desenvolvedor e ligue Depuração USB (e, se houver, "
                                    + "Depuração pela rede). Depois, volte para o Vitro.",
                            "2. Go back one screen, open Developer options and turn on USB debugging (and Network debugging, "
                                    + "if listed). Then come back to Vitro."));
                }
                key(L.t("Abrir as Configurações", "Open Settings"), v -> openSettings());
                key(L.t("Já liguei", "Done"), v -> {
                    if (adbOn()) go(VPN);
                    else status.setText(L.t("A depuração ADB ainda aparece desligada.", "ADB debugging still shows as off."));
                });
                status.setText(L.t("Depuração ADB: desligada", "ADB debugging: off"));
                break;
            case VPN:
                title.setText(L.t("Permita a VPN local", "Allow the local VPN"));
                text(L.t("O " + Platform.name(this) + " não deixa um app falar com o próprio ADB pela rede comum. O Vitro "
                                + "abre uma VPN que só existe dentro da TV: nada sai dela, e a sua internet não passa por ela.",
                        Platform.name(this) + " does not let an app talk to its own ADB over the regular network. Vitro opens "
                                + "a VPN that only exists inside the TV: nothing leaves it, and your internet does not go "
                                + "through it."));
                text(L.t("No aviso a seguir, escolha OK.", "In the next prompt, choose OK."));
                key(L.t("Permitir", "Allow"), v -> askVpn());
                key(L.t("Voltar", "Back"), v -> go(WELCOME));
                break;
            case PAIR: {
                boolean service = HomeService.isRunning();
                title.setText(L.t("Pareie o Vitro com a depuração sem fio", "Pair Vitro with wireless debugging"));
                text(L.t("Esta TV só abre o ADB pela Depuração sem fio. É uma vez só:",
                        "This TV only opens ADB through Wireless debugging. You do this once:"));
                text(service ? L.t("1. Serviço do Vitro em Acessibilidade: ligado.", "1. Vitro's service in Accessibility: on.")
                        : L.t("1. Ligue o serviço do Vitro em Configurações › Acessibilidade. É ele que mostra o painel do "
                                + "código por cima das Configurações.",
                        "1. Turn on Vitro's service in Settings › Accessibility. It shows the code panel on top of "
                                + "Settings."));
                text(L.t("2. Em Opções do desenvolvedor › Depuração sem fio: ligue (marque “Sempre permitir nesta rede”) "
                                + "e entre em “Parear dispositivo com código de pareamento”.",
                        "2. In Developer options › Wireless debugging: turn it on (tick “Always allow on this network”) "
                                + "and open “Pair device with pairing code”."));
                text(L.t("3. Com o código na tela, o Vitro abre um painel embaixo: ↑ ↓ escolhem o número, ← → trocam de "
                                + "casa, OK pareia.",
                        "3. With the code on screen, Vitro opens a panel at the bottom: ↑ ↓ pick the digit, ← → move, "
                                + "OK pairs."));
                if (!service) {
                    key(L.t("Abrir Acessibilidade", "Open Accessibility"), v -> openAccessibility());
                } else {
                    key(L.t("Abrir a depuração sem fio", "Open wireless debugging"), v -> {
                        HomeService.startPairing();
                        openSettings();
                    });
                }
                key(L.t("Voltar", "Back"), v -> go(WELCOME));
                break;
            }
            case KEY:
                title.setText(L.t("Autorize o Vitro no ADB", "Authorize Vitro on ADB"));
                text(L.t("Vai aparecer o aviso “Permitir depuração USB?” com a chave do Vitro. Marque “Sempre permitir "
                                + "deste computador” e escolha OK.",
                        "The “Allow USB debugging?” prompt will show Vitro's key. Tick “Always allow from this computer” "
                                + "and choose OK."));
                text(L.t("A chave é criada na própria TV e nunca sai dela.", "The key is created on the TV and never leaves it."));
                key(L.t("Pedir autorização", "Ask for approval"), v -> askKey());
                key(L.t("Voltar", "Back"), v -> go(WELCOME));
                break;
            case APPS:
                title.setText(L.t("Desativar apps que você não usa", "Disable apps you don't use"));
                boolean none = rec.isEmpty() && more.isEmpty();
                text(none ? L.t("Nenhum app da lista está ativo nesta TV. O diagnóstico mostra o que mais pesa agora, se "
                                + "você quiser escolher algum.",
                        "None of the listed apps is active on this TV. The checkup shows what weighs the most right now, "
                                + "if you want to pick something.")
                        : L.t("Escolha uma opção. Nada é apagado: tudo volta em Configurações › Aplicativos. "
                                + (fire ? "A Alexa" : "O Assistente") + " e o controle por voz nunca são desativados.",
                        "Pick an option. Nothing is deleted: everything comes back in Settings › Applications. "
                                + (fire ? "Alexa" : "The Assistant") + " and voice control are never disabled."));
                if (!rec.isEmpty()) {
                    card(L.t("Recomendados", "Recommended"), rec.size() + " apps",
                            fire ? L.t("Anúncios, recomendações, telemetria (dados enviados à Amazon) e extras como modo de "
                                            + "demonstração e tutoriais.",
                                    "Ads, recommendations, telemetry (data sent to Amazon) and extras such as store demo "
                                            + "mode and tutorials.")
                                    : L.t("Recomendações e anúncios da tela inicial, relatórios enviados ao Google e à "
                                            + "fabricante da TV e extras como o modo ambiente com conteúdo promovido.",
                                    "Home screen recommendations and ads, reports sent to Google and to the TV maker, and "
                                            + "extras such as ambient mode with promoted content."), () -> disable(rec));
                }
                if (!more.isEmpty()) {
                    List<String> all = new ArrayList<>(rec);
                    all.addAll(more);
                    card(L.t("Recomendados e apps " + Platform.ofMaker(this), "Recommended and " + Platform.makers(this) + " apps"),
                            all.size() + " apps",
                            L.t("Também ", "Also ") + labels(more) + ".", () -> disable(all));
                }
                card(L.t("Diagnóstico agora", "Checkup now"), L.t("você escolhe", "you choose"),
                        L.t("O Vitro mede o que mais pesa na memória neste momento e mostra app por app, com os "
                                        + "recomendados já marcados.",
                                "Vitro measures what uses the most memory right now and lists app by app, with the "
                                        + "recommended ones already ticked."), this::diagnose);
                key(none ? L.t("Continuar", "Continue") : L.t("Pular", "Skip"), v -> go(PRIME_STEP));
                break;
            case DIAG:
                title.setText(L.t("O que mais pesa na sua TV agora", "What weighs the most on your TV now"));
                text(L.t("Memória em uso medida agora. Os recomendados já vêm marcados; OK marca ou desmarca.",
                        "Memory in use, measured just now. Recommended apps come ticked; OK ticks or unticks."));
                diagList();
                diagGo = key("", v -> {
                    if (checked.isEmpty()) go(PRIME_STEP);
                    else disable(new ArrayList<>(checked));
                });
                key(L.t("Voltar", "Back"), v -> go(APPS));
                // Da última linha, a seta para baixo vai para "Desativar" (e não para o botão mais perto).
                diagGo.setId(View.generateViewId());
                if (lastRow != null) lastRow.setNextFocusDownId(diagGo.getId());
                updateDiag();
                break;
            case PRIME_STEP:
                title.setText(L.t("Prime Video só quando você usar", "Prime Video only when you use it"));
                text(L.t("O Prime Video é o app que mais pesa na memória da Fire TV. O Vitro pode deixá-lo desativado "
                                + "enquanto ninguém usa e ativá-lo na hora em que você abre pela barra do Vitro.",
                        "Prime Video is the heaviest app on a Fire TV's memory. Vitro can keep it disabled while nobody "
                                + "uses it and enable it the moment you open it from Vitro's bar."));
                text(L.t("Toda quarta-feira, à meia-noite, ele fica ativo por uma hora para receber atualizações.",
                        "Every Wednesday at midnight it stays enabled for an hour to get updates."));
                key(L.t("Sim, só quando eu usar", "Yes, only when I use it"), v -> prime(true));
                key(L.t("Não, sempre ativo", "No, always on"), v -> prime(false));
                break;
            case HOME:
                title.setText(L.t("Último passo: o botão Home", "Last step: the Home button"));
                text(L.t("O Vitro liga o próprio serviço do botão Home e as permissões de que precisa, e reinicia.",
                        "Vitro turns on its Home button service and the permissions it needs, then restarts."));
                text(L.t("Depois disso, o Home do controle abre o Vitro.", "After that, the remote's Home button opens Vitro."));
                key(L.t("Concluir", "Finish"), v -> finishSetup());
                break;
            case FINISHED:
                title.setText(L.t("Tudo pronto", "All set"));
                text(appsResult());
                if (installed(PRIME)) {
                    text(L.t("Prime Video: ", "Prime Video: ") + (prefs.getBoolean(PRIME_ON_DEMAND, true)
                            ? L.t("só quando você usar.", "only when you use it.") : L.t("sempre ativo.", "always on.")));
                }
                text(L.t("O Vitro reinicia em instantes.", "Vitro restarts in a moment."));
                break;
        }
        focusFirst();
    }

    private String appsResult() {
        if (appsOff < 0) return L.t("Nenhum app foi desativado.", "No apps were disabled.");
        String os = Platform.name(this);
        return L.t("Apps desativados: " + appsOff + (appsRefused > 0 ? " (" + appsRefused + " o " + os + " não deixa desativar)." : "."),
                "Apps disabled: " + appsOff + (appsRefused > 0 ? " (" + os + " would not allow " + appsRefused + ")." : "."));
    }

    private void prime(boolean onDemand) {
        prefs.edit().putBoolean(PRIME_ON_DEMAND, onDemand).apply();
        Log.i(TAG, "Prime Video sob demanda: " + (onDemand ? "sim" : "não"));
        go(HOME);
    }

    private boolean adbOn() {
        return Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
    }

    /** As Opções do desenvolvedor, se já aparecem; senão, a tela "Sobre"; senão, as Configurações. */
    private void openSettings() {
        boolean dev = Settings.Global.getInt(getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        String[] actions = dev
                ? new String[] {Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_SETTINGS}
                : new String[] {Settings.ACTION_DEVICE_INFO_SETTINGS, Settings.ACTION_SETTINGS};
        for (String a : actions) {
            try {
                startActivity(new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException ignored) { }
        }
        status.setText(L.t("Não consegui abrir as Configurações: abra pelo menu da TV.",
                "Could not open Settings: open them from the TV's menu."));
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException x) {
            status.setText(L.t("Não consegui abrir: vá em Configurações › Acessibilidade.",
                    "Could not open it: go to Settings › Accessibility."));
        }
    }

    private void askVpn() {
        Intent v;
        try {
            v = VpnService.prepare(this);
        } catch (RuntimeException x) {
            v = null;
        }
        if (v == null) {
            go(KEY);
            return;
        }
        try {
            startActivityForResult(v, REQ_VPN);
        } catch (RuntimeException x) {
            status.setText(L.t("Esta TV não deixou pedir a VPN local.", "This TV did not allow asking for the local VPN."));
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_VPN) return;
        if (res == RESULT_OK) go(KEY);
        else status.setText(L.t("Sem a VPN local, o Vitro não consegue usar o ADB da TV. Tente de novo e escolha OK.",
                "Without the local VPN, Vitro cannot use the TV's ADB. Try again and choose OK."));
    }

    /** Ao chegar no passo: a chave pode já estar autorizada (configuração refeita). */
    private void keySilently() {
        bg.post(() -> {
            try {
                HomeService.shellAt(this, "true", false, 0L);
                prefs.edit().putBoolean("adbAutorizado", true).apply();
                ui.post(() -> { if (step == KEY) go(APPS); });
            } catch (Adb.NotAuthorized x) {
                Log.i(TAG, "chave do Vitro ainda não autorizada");
                if (!fire && AdbTls.paired(this) && HomeService.directPort(this) != 5555) {
                    // Só a depuração sem fio, e ela não aceitou o Vitro: o pedido de autorização não existe lá. Pareia de novo.
                    AdbTls.setPaired(this, false);
                    ui.post(() -> {
                        if (step != KEY) return;
                        direct = HomeService.ADB_REFUSED;
                        go(PAIR);
                        status.setText(L.t("A TV não aceitou o pareamento. Abra o código de novo e repita.",
                                "The TV did not accept the pairing. Open the code again and repeat."));
                    });
                }
            } catch (Exception x) {
                Log.w(TAG, "ADB da TV sem resposta (" + x + ")");
                ui.post(() -> { if (step == KEY) status.setText(noAdb(x)); });
            }
        });
    }

    private void askKey() {
        setBusy(L.t("Esperando a sua resposta no aviso da TV…", "Waiting for your answer on the TV prompt…"));
        bg.post(() -> {
            String err = null;
            try {
                HomeService.shellAt(this, "true", true, APPROVAL_WAIT_MS);
                prefs.edit().putBoolean("adbAutorizado", true).apply();
                Log.i(TAG, "chave do Vitro autorizada");
            } catch (Adb.NotAuthorized | java.net.SocketTimeoutException x) {
                // Recusado no aviso, ou sem resposta a tempo (o ADB não avisa quando a pessoa cancela).
                err = L.t("A autorização não chegou. Peça de novo, marque “Sempre permitir” e escolha OK no aviso.",
                        "The approval did not arrive. Ask again, tick “Always allow” and choose OK in the prompt.");
            } catch (Exception x) {
                Log.w(TAG, "ADB da TV sem resposta (" + x + ")");
                err = noAdb(x);
            }
            String e = err;
            ui.post(() -> {
                if (e == null) go(APPS);
                else {
                    render();
                    status.setText(e);
                }
            });
        });
    }

    private String noAdb(Exception x) {
        if (x instanceof HomeService.NoTunnel) return L.t("A VPN local não ligou: ", "The local VPN did not start: ") + x.getMessage() + ".";
        if (x instanceof Adb.NotAuthorized) {
            return L.t("A TV não reconhece mais a chave do Vitro: volte ao passo da autorização e marque “Sempre permitir”.",
                    "The TV no longer recognizes Vitro's key: go back to the approval step and tick “Always allow”.");
        }
        if (!fire) {
            return L.t("O ADB da TV não respondeu pela rede (porta 5555). Confira a Depuração USB e, se houver, a Depuração "
                            + "pela rede nas Opções do desenvolvedor. Algumas TVs só abrem essa porta com um computador; o guia "
                            + "do Vitro explica.",
                    "The TV's ADB did not answer over the network (port 5555). Check USB debugging and, if listed, Network "
                            + "debugging in Developer options. Some TVs only open that port with a computer; Vitro's guide "
                            + "explains how.");
        }
        return L.t("O ADB da TV não respondeu. Confira se a Depuração ADB está ligada.",
                "The TV's ADB did not answer. Check that ADB debugging is on.");
    }

    /** Desativa os apps pelo shell da TV (pm disable-user: nada é apagado) e conta o que o Fire OS aceitou. */
    private void disable(List<String> pkgs) {
        setBusy(L.t("Desativando " + pkgs.size() + " apps… Se aparecer o aviso de autorização de novo, escolha OK.",
                "Disabling " + pkgs.size() + " apps… If the approval prompt shows up again, choose OK."));
        bg.post(() -> {
            StringBuilder cmd = new StringBuilder();
            for (String p : pkgs) {
                if (cmd.length() > 0) cmd.append("; ");
                cmd.append("pm disable-user --user 0 ").append(p);
            }
            String err = null;
            try {
                // Com a chave oferecida: se a TV não a guardou ("Sempre permitir" desmarcado), o aviso volta.
                HomeService.shellAt(this, cmd.toString(), true, APPROVAL_WAIT_MS);
            } catch (Exception x) {
                Log.w(TAG, "desativar pelo ADB falhou", x);
                err = noAdb(x);
            }
            List<String> off = new ArrayList<>();
            for (String p : pkgs) if (!Apps.isEnabled(this, p)) off.add(p);
            appsOff = off.size();
            appsRefused = pkgs.size() - off.size();
            rememberForGuard(off);
            Log.i(TAG, "apps desativados: " + appsOff + ", recusados pelo sistema: " + appsRefused);
            String e = err;
            boolean none = off.isEmpty();
            ui.post(() -> {
                if (e != null && none) {
                    render();
                    status.setText(e);
                } else {
                    go(PRIME_STEP);
                }
            });
        });
    }

    /** Os desativados que não estão na lista da guarda (escolhidos no diagnóstico) entram na guarda também. */
    private void rememberForGuard(List<String> off) {
        Set<String> guard = new HashSet<>();
        try (InputStream is = getAssets().open(Platform.guardList(this));
             BufferedReader in = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            for (String line; (line = in.readLine()) != null; ) guard.add(line.trim());
        } catch (Exception ignored) { }
        Set<String> extra = new HashSet<>(prefs.getStringSet(EXTRA, Collections.emptySet()));
        for (String p : off) if (!guard.contains(p)) extra.add(p);
        prefs.edit().putStringSet(EXTRA, extra).apply();
    }

    // ---- Diagnóstico -------------------------------------------------------------------------

    /** Mede a memória em uso de cada app pelo ADB da TV (dumpsys meminfo) e monta a lista para escolher. */
    private void diagnose() {
        setBusy(L.t("Medindo o que mais pesa na memória agora…", "Measuring what uses the most memory right now…"));
        bg.post(() -> {
            String out = null, err = null;
            try {
                out = HomeService.shellAt(this, "dumpsys meminfo", true, APPROVAL_WAIT_MS);
            } catch (Exception x) {
                Log.w(TAG, "diagnóstico pelo ADB falhou", x);
                err = noAdb(x);
            }
            List<Row> rows = out == null ? null : buildDiag(out);
            String e = err;
            ui.post(() -> {
                if (rows == null) {
                    render();
                    status.setText(e);
                    return;
                }
                diag.clear();
                diag.addAll(rows);
                checked.clear();
                for (Row r : rows) if (r.selectable && r.recommended) checked.add(r.pkg);
                Log.i(TAG, "diagnóstico: " + rows.size() + " apps na lista, " + checked.size() + " marcados");
                go(DIAG);
            });
        });
    }

    private static final Pattern MEM = Pattern.compile("^\\s*([\\d,]+)K:\\s+(\\S+)\\s+\\(pid");

    private List<Row> buildDiag(String out) {
        // "Total PSS by process" (memória proporcional, a medida certa); sem ela, "Total RSS by process".
        Map<String, Long> pss = new HashMap<>(), rss = new HashMap<>();
        Map<String, Long> cur = null;
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("Total PSS by process")) { cur = pss; continue; }
            if (t.startsWith("Total RSS by process")) { cur = rss; continue; }
            if (cur == null) continue;
            if (t.isEmpty() || t.startsWith("Total ")) {
                if (!cur.isEmpty()) cur = null;
                continue;
            }
            Matcher m = MEM.matcher(line);
            if (!m.find()) continue;
            String pkg = m.group(2);
            int colon = pkg.indexOf(':');
            if (colon > 0) pkg = pkg.substring(0, colon);
            long kb = Long.parseLong(m.group(1).replace(",", ""));
            Long old = cur.get(pkg);
            cur.put(pkg, old == null ? kb : old + kb);
        }
        Map<String, Long> mem = pss.isEmpty() ? rss : pss;

        List<Map.Entry<String, Long>> byMem = new ArrayList<>(mem.entrySet());
        Collections.sort(byMem, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        String imePkg = ime == null ? "" : ime.split("/")[0];

        List<Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int systemShown = 0;
        for (Map.Entry<String, Long> e : byMem) {
            String pkg = e.getKey();
            if (pkg.equals(getPackageName()) || !installed(pkg)) continue;
            Row r = classify(pkg, imePkg);
            r.kb = e.getValue();
            r.running = true;
            if (!r.selectable) {
                if (systemShown >= SYSTEM_SHOWN) continue;
                systemShown++;
            }
            rows.add(r);
            seen.add(pkg);
        }
        // Os recomendados que não estão rodando agora (marcados) e os outros da Amazon (desmarcados).
        for (String p : rec) if (!seen.contains(p)) { rows.add(classify(p, imePkg)); seen.add(p); }
        for (String p : others) if (!seen.contains(p)) { rows.add(classify(p, imePkg)); seen.add(p); }
        return rows;
    }

    /** O que é o app e se pode ser desativado: da lista do debloat (menos a Alexa), ou instalado pela pessoa. */
    private Row classify(String pkg, String imePkg) {
        Row r = new Row();
        r.pkg = pkg;
        String g = groupOf.get(pkg);
        String label = labelOf(pkg);
        String desc = L.en() ? descEn.get(pkg) : descPt.get(pkg);
        boolean userApp = false;
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            userApp = (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (Exception ignored) { }
        if (VOICE.contains(g)) {
            r.name = label;
            r.desc = L.t("controle por voz, fica ligado", "voice control, stays on");
            r.tag = fire ? "Alexa" : L.t("Assistente", "Assistant");
        } else if (PRIME.equals(pkg)) {
            r.name = label;
            r.desc = L.t("tem um passo próprio, a seguir", "has its own step, next");
            r.tag = "Prime Video";
        } else if (g != null) {
            r.selectable = true;
            r.recommended = RECOMMENDED.contains(g);
            r.name = desc != null && !desc.isEmpty() ? capital(desc) : label;
            r.desc = label.equals(r.name) ? pkg : label;
            r.tag = r.recommended ? L.t("recomendado", "recommended") : pkg.startsWith("com.tcl.") ? "TCL" : Platform.maker(this);
        } else if (userApp && !pkg.equals(imePkg)) {
            r.selectable = true;
            r.name = label;
            r.desc = L.t("instalado por você", "installed by you");
            r.tag = L.t("seu app", "your app");
        } else {
            r.name = label;
            r.desc = L.t("do sistema, fica ligado", "system, stays on");
            r.tag = L.t("sistema", "system");
        }
        return r;
    }

    private static String capital(String s) {
        return s.substring(0, 1).toUpperCase(L.locale()) + s.substring(1);
    }

    private void diagList() {
        lastRow = null;
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        String section = null;
        for (Row r : diag) {
            String sec = r.running ? L.t("Rodando agora", "Running now")
                    : r.recommended ? L.t("Recomendados parados agora", "Recommended, not running now")
                    : L.t("Outros apps " + Platform.ofMaker(this) + ", parados agora",
                            "Other " + Platform.makers(this) + " apps, not running now");
            if (!sec.equals(section)) {
                section = sec;
                TextView h = label(12, false, 0x99FFFFFF);
                h.setText(sec.toUpperCase(L.locale()));
                h.setLetterSpacing(0.07f);
                LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                hlp.topMargin = px(list.getChildCount() == 0 ? 2 : 10);
                hlp.bottomMargin = px(4);
                hlp.leftMargin = px(4);
                list.addView(h, hlp);
            }
            lastRow = row(r);
            list.addView(lastRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(40)));
        }
        if (diag.isEmpty()) {
            TextView t = label(15, false, 0xCCFFFFFF);
            t.setText(L.t("Nada para desativar apareceu na medição.", "Nothing to disable showed up in the measurement."));
            list.addView(t);
        }
        sv.addView(list);
        body.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(214)));
    }

    private View row(Row r) {
        // Linhas do sistema também recebem o foco (as setas andam uma a uma até os botões), mas ficam apagadas e o
        // OK não faz nada nelas.
        boolean sel = r.selectable;
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(px(12), 0, px(12), 0);

        TextView box = label(13, true, 0);
        box.setGravity(Gravity.CENTER);
        box.setIncludeFontPadding(false);
        v.addView(box, new LinearLayout.LayoutParams(px(20), px(20)));

        TextView name = label(15, false, 0);
        name.setText(r.name);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextColor(sel ? focusColor(0xFFFFFFFF) : android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        name.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = px(12);
        v.addView(name, nlp);

        TextView desc = label(12.5f, false, 0);
        desc.setText(r.desc);
        desc.setSingleLine(true);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        desc.setTextColor(sel ? focusColor(0xA6FFFFFF) : android.content.res.ColorStateList.valueOf(0xA6FFFFFF));
        desc.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dlp.leftMargin = px(8);
        v.addView(desc, dlp);

        TextView mb = label(14, false, 0);
        mb.setText(r.kb > 0 ? Math.max(1, Math.round(r.kb / 1024f)) + " MB" : L.t("parado", "stopped"));
        mb.setGravity(Gravity.END);
        mb.setTextColor(sel ? focusColor(0xFFFFFFFF) : android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        mb.setDuplicateParentStateEnabled(true);
        v.addView(mb, new LinearLayout.LayoutParams(px(70), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tag = label(11.5f, false, 0);
        tag.setText(r.tag);
        tag.setGravity(Gravity.CENTER);
        tag.setSingleLine(true);
        tag.setTextColor(sel ? focusColor(0xE6FFFFFF) : android.content.res.ColorStateList.valueOf(0xE6FFFFFF));
        tag.setDuplicateParentStateEnabled(true);
        tag.setBackground(round(0x24FFFFFF, 0, 0, 99));
        tag.setPadding(px(6), px(2), px(6), px(2));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(px(104), ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = px(12);
        v.addView(tag, tlp);

        if (r.selectable) {
            v.setBackground(focusBg(0x00000000, 0, 12));
            v.setFocusable(true);
            v.setClickable(true);
            paintBox(box, checked.contains(r.pkg), false, false);
            v.setOnFocusChangeListener((x, f) -> paintBox(box, checked.contains(r.pkg), f, false));
            v.setOnClickListener(x -> {
                if (busy) return;
                if (!checked.remove(r.pkg)) checked.add(r.pkg);
                paintBox(box, checked.contains(r.pkg), x.hasFocus(), false);
                updateDiag();
            });
            if (firstFocus == null) firstFocus = v;
        } else {
            android.graphics.drawable.StateListDrawable dim = new android.graphics.drawable.StateListDrawable();
            dim.addState(new int[] {android.R.attr.state_focused}, round(0x40FFFFFF, 0, 0, 12));
            dim.addState(new int[] {}, round(0x00000000, 0, 0, 12));
            v.setBackground(dim);
            v.setFocusable(true);
            v.setAlpha(0.55f);
            paintBox(box, false, false, true);
        }
        return v;
    }

    /** A caixinha de marcar: vazia, marcada (✓) ou tracejada (não dá para desativar). */
    private void paintBox(TextView box, boolean on, boolean focused, boolean locked) {
        int fg = focused ? 0xFF15171C : 0xFFFFFFFF;
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(6 * dp);
        if (locked) g.setStroke(px(2), fg, px(3), px(2.5f));
        else g.setStroke(px(2), fg);
        g.setColor(on ? fg : 0x00000000);
        box.setBackground(g);
        box.setText(on ? "✓" : "");
        box.setTextColor(focused ? 0xFFFFFFFF : 0xFF15171C);
    }

    private void updateDiag() {
        int n = checked.size();
        long kb = 0;
        for (Row r : diag) if (checked.contains(r.pkg)) kb += r.kb;
        diagGo.setText(n > 0 ? L.t("Desativar " + n + (n == 1 ? " marcado" : " marcados"), "Disable " + n + " ticked")
                : L.t("Continuar sem desativar", "Continue without disabling"));
        status.setText(L.t(n + (n == 1 ? " marcado" : " marcados"), n + " ticked")
                + (kb > 0 ? L.t(" · em uso agora por eles: ", " · using right now: ") + Math.round(kb / 1024f) + " MB" : ""));
    }

    // ---- Último passo ------------------------------------------------------------------------

    /**
     * Último passo, em duas partes. Primeiro a permissão de mudar ajustes, pelo ADB; com ela o próprio Vitro liga o
     * serviço do botão Home e o Home rápido (Repair.settings). Depois, com "Tudo pronto" na tela, a de ler o registro:
     * ao concedê-la o Android pode encerrar o processo do Vitro (muda os grupos do app), por isso ela vem por último.
     */
    private void finishSetup() {
        setBusy(L.t("Ligando o botão Home… Se aparecer o aviso de autorização de novo, escolha OK.",
                "Turning on the Home button… If the approval prompt shows up again, choose OK."));
        bg.post(() -> {
            String err = null;
            try {
                String r = HomeService.shellAt(this, "pm grant " + getPackageName() + " " + Repair.WSS, true,
                        APPROVAL_WAIT_MS);
                if (!r.trim().isEmpty()) Log.w(TAG, "pm grant respondeu: " + r.trim());
            } catch (Exception x) {
                Log.w(TAG, "permissão de mudar ajustes pelo ADB falhou", x);
                err = noAdb(x);
            }
            String e = err;
            ui.post(() -> {
                List<String> done = Repair.settings(this);
                Log.i(TAG, "primeira configuração: refeito " + done);
                if (!serviceListed(this)) {
                    render();
                    status.setText(e != null ? e : L.t("Não consegui ligar o serviço do botão Home sozinho. Ligue “Vitro” "
                                    + "em Configurações › Acessibilidade.",
                            "Could not turn on the Home button service by myself. Turn on “Vitro” in Settings › "
                                    + "Accessibility."));
                    return;
                }
                // adbPreparoVisto: a tela inicial não refaz por conta própria a conferência do ADB que a configuração acabou de fazer.
                prefs.edit().putBoolean(DONE, true).putBoolean("adbPreparoVisto", true).remove(DISMISSED).commit();
                go(FINISHED);
                ui.postDelayed(this::grantLogsAndRestart, 4000L);
            });
        });
    }

    /**
     * A permissão de ler o registro e o reinício. O trabalho vai para a fila de ADB do serviço do botão Home, que
     * acabou de ligar e também usa o túnel. Ao concedê-la, o Android pode encerrar o processo na hora (muda os grupos
     * do app); se não encerrar, o Vitro se reinicia. Ao religar num processo novo, o serviço do botão Home reabre a
     * tela inicial (HomeService.OPEN_AFTER_RESTART), sem prazo fixo: espera o tempo que for preciso para a pessoa
     * responder a um aviso de autorização.
     */
    private void grantLogsAndRestart() {
        prefs.edit().putInt(HomeService.OPEN_AFTER_RESTART, android.os.Process.myPid()).commit();
        Runnable job = () -> {
            try {
                if (!Repair.has(this, Repair.READ_LOGS)) {
                    Log.i(TAG, "concedendo a leitura do registro (o Android pode reiniciar o Vitro)");
                    String r = HomeService.shellAt(this, "pm grant " + getPackageName() + " " + Repair.READ_LOGS, true,
                            APPROVAL_WAIT_MS);
                    if (!r.trim().isEmpty()) Log.w(TAG, "pm grant respondeu: " + r.trim());
                }
            } catch (Exception x) {
                Log.w(TAG, "permissão de ler o registro pelo ADB falhou", x);
                String msg = noAdb(x);
                ui.post(() -> {
                    prefs.edit().remove(HomeService.OPEN_AFTER_RESTART).putBoolean(DONE, false).commit();
                    go(HOME);
                    status.setText(msg);
                });
                return;
            }
            Log.i(TAG, "primeira configuração concluída; reiniciando o Vitro");
            ui.post(() -> {
                finish();
                android.os.Process.killProcess(android.os.Process.myPid());
            });
        };
        if (!HomeService.postAdb(job)) bg.post(job);
    }

    // ---- Lista do debloat --------------------------------------------------------------------

    /**
     * assets/debloat-grupos.txt, uma linha por app: "grupo pacote descrição em português | em inglês". Guarda o
     * grupo e as descrições de todos; oferece os recomendados e os "mais" que estão instalados e ativos.
     */
    private void loadCandidates() {
        try (InputStream is = getAssets().open(Platform.debloatGroups(this));
             BufferedReader in = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            for (String line; (line = in.readLine()) != null; ) {
                String[] f = line.trim().split("\\s+", 3);
                if (f.length < 2 || f[0].startsWith("#")) continue;
                String g = f[0], p = f[1];
                groupOf.put(p, g);
                if (f.length > 2) {
                    String[] d = f[2].split("\\s*\\|\\s*", 2);
                    descPt.put(p, d[0].trim());
                    descEn.put(p, d.length > 1 ? d[1].trim() : d[0].trim());
                }
                if (VOICE.contains(g) || !installed(p) || !Apps.isEnabled(this, p)) continue;
                if (RECOMMENDED.contains(g)) { if (!rec.contains(p)) rec.add(p); }
                else {
                    if (MORE.contains(g) && !more.contains(p)) more.add(p);
                    if (!others.contains(p)) others.add(p);
                }
            }
        } catch (Exception x) {
            Log.w(TAG, "sem a lista do debloat (" + x + ")");
        }
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getApplicationInfo(pkg, android.content.pm.PackageManager.GET_DISABLED_COMPONENTS);
            return true;
        } catch (Exception x) {
            return false;
        }
    }

    private String labelOf(String p) {
        try {
            CharSequence l = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(p, 0));
            return l == null || l.length() == 0 ? p : l.toString();
        } catch (Exception x) {
            return p;
        }
    }

    private String labels(List<String> pkgs) {
        List<String> names = new ArrayList<>();
        for (String p : pkgs) names.add(labelOf(p));
        return L.en() ? joinEn(names) : Repair.join(names);
    }

    private static String joinEn(List<String> names) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) b.append(i == names.size() - 1 ? " and " : ", ");
            b.append(names.get(i));
        }
        return b.toString();
    }
}
