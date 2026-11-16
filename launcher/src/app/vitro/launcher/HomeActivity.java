package app.vitro.launcher;

import android.app.Activity;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * A tela inicial. Uma Activity que toma a superfície da própria janela e desenha tudo em OpenGL.
 *
 * Leveza:
 *  - nada de bibliotecas (sem AndroidX, sem Compose) e nada de Views desenhadas pelo Android:
 *    uma única superfície, uma única camada para o compositor;
 *  - só é redesenhado o que mudou, e só enquanto algo se move; parada, a tela não gasta GPU;
 *  - com outro app na frente, os banners da gaveta saem da memória na hora. O contexto de OpenGL fica:
 *    refazê-lo (compilar os efeitos, decodificar e desfocar o papel de parede) atrasava o Home em ~1 s.
 *    Custo medido na TV: ~41 MB em segundo plano com ele, ~24 MB sem. Só é solto se o sistema pedir memória.
 */
public final class HomeActivity extends Activity implements Scene.Host, Choreographer.FrameCallback, SurfaceHolder.Callback2 {
    static final String AMAZON_HOME = Platform.AMAZON_HOME;
    /** Vem do botão Home segurado (HomeService): abrir com o menu de cima aberto. */
    static final String EXTRA_MENU = "app.vitro.launcher.MENU";
    /**
     * Diagnóstico, via "am start --ei perf N": 1 mede o tempo de cada quadro, 2 desliga o redesenho parcial,
     * 4 redesenha a tela inteira sem parar (bancada, para medir o custo de um estado parado).
     */
    static volatile int PERF;
    /** Diagnóstico, via "--ei skip N": máscara dos tipos de item a não desenhar (ver Gfx). */
    static volatile int SKIP;
    /** Diagnóstico, via "--es hora HH:MM": o relógio mostra esse horário (vazio volta à hora certa). */
    static volatile String HORA;
    /** Diagnóstico, via "--ez relogioTexto true": volta o relógio de texto antigo, para comparar o desempenho. */
    static volatile boolean TEXT_CLOCK;

    /** Nomes dos papéis de parede desenhados pelo Vitro; o português também é a chave guardada da escolha. */
    private static final String[] BUILT_IN = {"Órbita", "Dunas", "Aurora", "Seda"};
    private static final String[] BUILT_IN_EN = {"Orbit", "Dunes", "Aurora", "Silk"};
    private static final int[] BUILT_IN_KIND = {3, 1, 0, 2};
    /** Papel de parede de quem acabou de instalar (sem ele na coleção, o primeiro embutido, Órbita). */
    static final String DEFAULT_WALLPAPER = "01b-ondas.jpg";

    final Scene scene = new Scene();
    private Sounds sounds;
    List<Apps.App> all = new ArrayList<>(), dock = new ArrayList<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Egl egl = new Egl();
    private Painter painter;
    // Superfície da janela, guardada para religar o OpenGL sem depender de um novo surfaceCreated.
    private SurfaceHolder holder;
    private boolean justCreated;
    private int restoreFails;
    private SharedPreferences prefs;
    private String[] photos = new String[0];
    private final java.util.HashMap<String, String> photoNames = new java.util.HashMap<>();
    private int wallpaper;
    private boolean running, held, visible;
    private long lastNs;
    private int generation;

    private final Runnable minute = new Runnable() {
        @Override public void run() {
            kick();
            long left = 60000L - System.currentTimeMillis() % 60000L;
            ui.postDelayed(this, left + 50L);
            // O relógio de vidro do minuto seguinte é calculado em segundo plano antes da virada.
            ui.removeCallbacks(nextClock);
            if (left > 10500L) ui.postDelayed(nextClock, left - 10000L);
        }
    };
    /**
     * Calcula a forma do minuto seguinte, mas só com a tela parada: durante uma animação o cálculo disputaria o
     * processador com ela. Começa 10 s antes da virada e espera a animação acabar; perto demais da virada, desiste
     * e o próprio quadro da virada pede a forma (o minuto anterior fica na tela por uma fração de segundo).
     */
    private final Runnable nextClock = new Runnable() {
        @Override public void run() {
            if (painter == null || !visible) return;
            long left = 60000L - System.currentTimeMillis() % 60000L;
            if (running) {
                if (left > 1500L) ui.postDelayed(this, 200L);
                return;
            }
            painter.prepareNextClock();
        }
    };
    private final Runnable release = this::releaseGraphics;
    private final Runnable warm = () -> { if (painter != null) painter.prewarm(); };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // A janela não desenha Views: a superfície é nossa, para o OpenGL.
        getWindow().takeSurface(this);
        getWindow().setFormat(PixelFormat.RGBX_8888);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("vitro", MODE_PRIVATE);
        L.load(this);
        try {
            String[] names = getAssets().list("wallpapers");
            List<String> jpg = new ArrayList<>();
            if (names != null) for (String n : names) if (n.endsWith(".jpg")) jpg.add(n);
            java.util.Collections.sort(jpg);
            photos = jpg.toArray(new String[0]);
        } catch (Exception ignored) { }
        // Nomes de exibição das fotos: uma linha "arquivo=Nome" por papel de parede.
        try (java.io.BufferedReader in = new java.io.BufferedReader(
                new java.io.InputStreamReader(getAssets().open("wallpapers/nomes.txt"), "UTF-8"))) {
            for (String line; (line = in.readLine()) != null; ) {
                int eq = line.indexOf('=');
                if (eq > 0) photoNames.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (Exception ignored) { }
        // O papel de parede é guardado pelo nome do arquivo: trocar a coleção não muda a escolha de quem não saiu.
        int byName = wallpaperIndex(prefs.getString("wallpaperName", null));
        // Instalação nova (nenhuma escolha salva): começa no papel padrão.
        if (byName < 0 && !prefs.contains("wallpaper")) byName = wallpaperIndex(DEFAULT_WALLPAPER);
        wallpaper = byName >= 0 ? byName
                : Math.max(0, Math.min(prefs.getInt("wallpaper", 0), BUILT_IN.length + photos.length - 1));

        sounds = new Sounds(this);
        painter = new Painter(this, scene, egl);
        // Uma View vazia só para a janela ter foco e receber as teclas do controle.
        View focus = new View(this);
        focus.setFocusable(true);
        setContentView(focus);
        focus.requestFocus();
        debug(getIntent());
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_MENU, false)) scene.openMenu();
        applyWallpaper();
        sync();
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        restoreFails = 0;
        ui.removeCallbacks(release);
        painter.wake();
        restore();
        reloadApps();
        ui.removeCallbacks(minute);
        minute.run();
        kick();
        // Primeira vez com o botão Home ligado: prepara o ADB da TV (VPN local e chave), uma vez só.
        if (HomeService.isRunning() && !prefs.getBoolean("adbPreparoVisto", false)) {
            prefs.edit().putBoolean("adbPreparoVisto", true).apply();
            ui.postDelayed(this::setupAdb, 1500L);
        }
        // Fire TV ainda não preparada: a primeira configuração (ver SetupActivity). Numa TV pronta, nunca abre.
        ui.removeCallbacks(setupCheck);
        ui.postDelayed(setupCheck, 800L);
    }

    private final Runnable setupCheck = () -> {
        if (visible && SetupActivity.shouldOpen(this)) openSetup();
    };

    private void openSetup() {
        prefs.edit().remove(SetupActivity.DISMISSED).apply();
        start(new Intent(this, SetupActivity.class));
    }

    private static final int REQ_VPN = 41;

    /**
     * Prepara o ADB da própria TV, que o Vitro usa para fechar o Prime Video ao ligar a TV: primeiro a VPN
     * local (pedido do Android, uma vez só), depois a chave do Vitro (aviso do adb, também uma vez).
     */
    private void setupAdb() {
        if (!HomeService.isRunning()) return;
        // Android TV e Google TV: o ADB direto costuma bastar; a VPN só é pedida se ele não responder.
        if (Platform.tryDirectAdb(this)) {
            HomeService.adbFromMenu();
            return;
        }
        Intent v;
        try {
            v = VpnService.prepare(this);
        } catch (RuntimeException x) {
            v = null;
        }
        if (v == null) {
            HomeService.adbFromMenu();
            return;
        }
        toast(L.t("Para cuidar de si sozinho, o Vitro usa uma VPN só dentro da TV. Confirme no aviso.",
                "To look after itself, Vitro uses a VPN that only exists inside the TV. Confirm in the prompt."));
        try {
            startActivityForResult(v, REQ_VPN);
        } catch (RuntimeException x) {
            toast(L.t("Não foi possível pedir a VPN local nesta TV.", "Could not ask for the local VPN on this TV."));
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_VPN) return;
        if (res == RESULT_OK) HomeService.adbFromMenu();
        else toast(L.t("Sem a VPN local, o Vitro não consegue se reparar sozinho. Dá para pedir de novo em Autoreparar.",
                "Without the local VPN, Vitro cannot repair itself. You can ask again in Self-repair."));
    }

    @Override protected void onStop() {
        super.onStop();
        visible = false;
        ui.removeCallbacks(minute);
        ui.removeCallbacks(nextClock);
        painter.hidden();
        freeDrawer();
        scene.home();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        egl.destroyContext();
        sounds.release();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Botão Home com o launcher já na tela: volta para a barra. Comandos de diagnóstico não mexem no estado.
        if (!debug(intent)) {
            scene.home();
            if (intent.getBooleanExtra(EXTRA_MENU, false)) scene.openMenu();
        }
        kick();
    }

    /** O protetor de tela saiu da frente sem que a Activity tenha parado: confere se ainda dá para desenhar. */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            restoreFails = 0;
            kick();
        }
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (visible) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && scene.zone != Scene.DRAWER) freeDrawer();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            // O sistema está com pouca memória e não estamos na tela: solta tudo agora.
            ui.removeCallbacks(release);
            releaseGraphics();
        }
    }

    /** Opções de diagnóstico passadas pelo adb. Não mudam nada guardado no aparelho. */
    private boolean debug(Intent intent) {
        if (intent == null) return false;
        boolean any = intent.hasExtra("perf") || intent.hasExtra("skip") || intent.hasExtra("wall") || intent.hasExtra("release")
                || intent.hasExtra("hora") || intent.hasExtra("relogioTexto");
        if (intent.hasExtra("relogioTexto")) TEXT_CLOCK = intent.getBooleanExtra("relogioTexto", false);
        if (intent.hasExtra("hora")) {
            String hr = intent.getStringExtra("hora");
            HORA = hr == null || hr.trim().isEmpty() ? null : hr.trim();
        }
        // "--ez release true": solta o OpenGL com a janela ainda na tela, como depois de um protetor de tela
        // no Fire OS. A próxima tecla tem de trazer a imagem de volta.
        if (intent.getBooleanExtra("release", false)) {
            ui.postDelayed(() -> {
                Choreographer.getInstance().removeFrameCallback(this);
                running = false;
                GlassClock.uiBusy = false;
                egl.destroyContext();
                Log.i("Vitro", "diagnóstico: OpenGL solto com a janela na tela");
            }, 600L);
        }
        // "--ez configurar true": abre a primeira configuração (também numa TV já pronta, para conferir as telas).
        if (intent.getBooleanExtra("configurar", false)) {
            any = true;
            ui.postDelayed(this::openSetup, 300L);
        }
        PERF = intent.getIntExtra("perf", PERF);
        SKIP = intent.getIntExtra("skip", SKIP);
        int w = intent.getIntExtra("wall", -1);
        if (w >= 0 && w < BUILT_IN.length + photos.length && w != wallpaper) {
            wallpaper = w;
            applyWallpaper();
        }
        return any;
    }

    // ---- Superfície e OpenGL -----------------------------------------------------------------

    @Override public void surfaceCreated(SurfaceHolder h) {
        holder = h;
        justCreated = true;
        restoreFails = 0;
        attach(false);
    }

    @Override public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
        holder = h;
        // A janela pode trocar a superfície por baixo e avisar só por aqui (mesmo tamanho, outra superfície).
        // Religar o EGL garante que o desenho vai para a superfície viva.
        boolean rebind = !justCreated;
        justCreated = false;
        if (!attach(rebind)) return;
        painter.resize(width, height);
        frame(true);
        ui.post(warm);
        kick();
    }

    @Override public void surfaceRedrawNeeded(SurfaceHolder h) {
        if (egl.hasSurface()) frame(true);
    }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        egl.destroySurface();
        holder = null;
    }

    /** Liga o OpenGL à superfície da janela, criando o contexto de novo se ele tinha sido solto. */
    private boolean attach(boolean rebind) {
        Surface s = holder == null ? null : holder.getSurface();
        if (s == null || !s.isValid()) return false;
        ui.removeCallbacks(release);
        try {
            boolean fresh = egl.ensureContext();
            if ((fresh || rebind || !egl.hasSurface()) && !egl.createSurface(s)) return false;
            if (fresh) painter.contextCreated();
            return true;
        } catch (RuntimeException e) {
            Log.e("Vitro", "OpenGL indisponível", e);
            return false;
        }
    }

    /**
     * De volta à tela sem OpenGL. Acontece quando a janela guarda a superfície enquanto o Vitro esteve fora
     * (protetor de tela, TV em espera) e o contexto foi solto nesse meio-tempo: o Android não avisa de novo,
     * então o Vitro religa sozinho e redesenha.
     */
    private boolean restore() {
        if (egl.hasSurface()) return true;
        if (!visible || holder == null || restoreFails > 3) return false;
        if (!attach(false)) {
            restoreFails++;
            return false;
        }
        Rect r = holder.getSurfaceFrame();
        painter.resize(r.width(), r.height());
        frame(true);
        return egl.hasSurface();
    }

    private void releaseGraphics() {
        if (visible) return;
        egl.destroyContext();
    }

    private void frame(boolean force) {
        scene.compute();
        painter.draw(force);
        if (egl.lost()) {
            // A superfície morreu por baixo, sem aviso da janela: solta e religa logo em seguida.
            Log.w("Vitro", "superfície perdida; religando");
            if (egl.swapError == android.opengl.EGL14.EGL_CONTEXT_LOST) egl.destroyContext();
            else egl.destroySurface();
            restoreFails++;
            ui.post(this::kick);
        }
    }

    /** Liga o laço de quadros. Ele se desliga sozinho quando todas as molas assentam. */
    void kick() {
        if (running || !visible) return;
        if (!egl.hasSurface() && !restore()) return;
        running = true;
        GlassClock.uiBusy = true;
        lastNs = 0L;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long ns) {
        if (!visible || !egl.hasSurface()) {
            running = false;
            GlassClock.uiBusy = false;
            return;
        }
        if (lastNs != 0L && (PERF & 1) != 0) painter.pace(ns - lastNs);
        float dt = lastNs == 0L ? 1f / 60f : Math.min(0.134f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        boolean busy = scene.advance(dt);
        boolean bench = (PERF & 4) != 0;
        frame(bench);
        if (busy || bench || painter.animating()) Choreographer.getInstance().postFrameCallback(this);
        else {
            running = false;
            GlassClock.uiBusy = false;
        }
    }

    // ---- Apps --------------------------------------------------------------------------------

    private void reloadApps() {
        final int gen = ++generation;
        new Thread(() -> {
            final List<Apps.App> list = Apps.drawer(this, Apps.load(this));
            ui.post(() -> {
                if (gen != generation) return;
                // Reaproveita banners e texturas já prontos: voltar de um app não refaz a barra do zero.
                for (Apps.App a : list) {
                    for (Apps.App o : all) {
                        if (o.pkg.equals(a.pkg)) {
                            a.banner = o.banner; a.glow = o.glow;
                            a.tex = o.tex; a.texGen = o.texGen; a.texVer = o.texVer;
                            break;
                        }
                    }
                }
                final List<Apps.App> favs = Apps.dock(this, list);
                boolean missing = false;
                for (Apps.App a : favs) missing |= a.banner == null;
                if (!missing) {
                    commit(list, favs);
                    return;
                }
                new Thread(() -> {
                    for (Apps.App a : favs) Apps.loadBanner(this, a);
                    ui.post(() -> { if (gen == generation) commit(list, favs); });
                }, "vitro-banners").start();
            });
        }, "vitro-apps").start();
    }

    private void commit(List<Apps.App> list, List<Apps.App> favs) {
        // Apps que saíram do aparelho: a textura deles vai para o lixo.
        for (Apps.App o : all) {
            boolean kept = false;
            for (Apps.App a : list) kept |= a.pkg.equals(o.pkg);
            if (!kept) painter.trash(o.tex, o.texGen);
        }
        all = list;
        dock = favs;
        sync();
        kick();
    }

    /** Banners da gaveta são carregados só quando ela abre. */
    private void loadDrawer() {
        final List<Apps.App> list = all;
        new Thread(() -> {
            for (Apps.App a : list) {
                if (!visible || scene.zone != Scene.DRAWER) return;
                if (a.banner == null) {
                    Apps.loadBanner(this, a);
                    ui.post(this::kick);
                }
            }
        }, "vitro-banners").start();
    }

    private void freeDrawer() {
        for (Apps.App a : all) {
            if (dock.contains(a)) continue;
            painter.trash(a.tex, a.texGen);
            a.tex = 0;
            Apps.release(a);
        }
    }

    /** Leva para a cena o tamanho da barra e da gaveta e a cor de luz de cada app. */
    private void sync() {
        scene.setCounts(dock.size() + 1, all.size());
        float[][] glow = new float[dock.size() + 1][];
        for (int i = 0; i < dock.size(); i++) glow[i] = dock.get(i).glow;
        glow[dock.size()] = new float[] {1f, 1f, 1f};
        scene.glow = glow;
    }

    private void open(Apps.App a) {
        // App sob demanda desativado: o serviço o ativa pelo ADB da TV e o abre em seguida (1 a 2 s).
        if (a.onDemand && !Apps.isEnabled(this, a.pkg)) {
            if (HomeService.openOnDemand(a)) toast(L.t("Abrindo ", "Opening ") + a.label + "…");
            else toast(a.label + L.t(" está desativado, e o botão Home do Vitro está desligado para ativá-lo.",
                    " is disabled, and Vitro's Home button service is off, so it cannot enable it."));
            return;
        }
        try {
            startActivity(Apps.launchIntent(this, a));
        } catch (Exception e) {
            toast(L.t("Não foi possível abrir ", "Could not open ") + a.label);
        }
    }

    // ---- Ações pedidas pela cena ------------------------------------------------------------

    @Override public void openDock(int i) {
        if (i < dock.size()) open(dock.get(i));
    }

    @Override public void openGrid(int i) {
        if (i < all.size()) open(all.get(i));
    }

    /** Do menu de um app da gaveta: fixa na barra ou, se já estiver nela, tira. */
    @Override public void pinGrid(int i) {
        if (i >= all.size()) return;
        Apps.App a = all.get(i);
        if (dock.contains(a)) {
            dock.remove(a);
            Apps.saveDock(this, dock);
            sync();
            toast(a.label + L.t(" saiu da barra", " removed from the bar"));
            return;
        }
        if (dock.size() >= Apps.MAX_DOCK) { toast(L.t("A barra está cheia. Tire um app antes de fixar outro.", "The bar is full. Remove an app before pinning another.")); return; }
        dock.add(a);
        Apps.saveDock(this, dock);
        sync();
        toast(a.label + L.t(" fixado na barra", " pinned to the bar"));
    }

    @Override public void moveGrid(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= all.size() || to >= all.size()) return;
        // Lista nova, e não a mesma alterada: o carregador de banners pode estar percorrendo a antiga.
        List<Apps.App> list = new ArrayList<>(all);
        list.add(to, list.remove(from));
        all = list;
        Apps.saveDrawer(this, list);
    }

    @Override public void gridInfo(int i) {
        if (i >= all.size()) return;
        start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + all.get(i).pkg)));
    }

    @Override public void removeFromDock(int i) {
        if (i >= dock.size()) return;
        dock.remove(i);
        Apps.saveDock(this, dock);
        sync();
    }

    @Override public void moveDock(int from, int to) {
        if (from >= dock.size() || to >= dock.size() || to < 0) return;
        Apps.App a = dock.remove(from);
        dock.add(to, a);
        Apps.saveDock(this, dock);
        sync();
    }

    @Override public void drawerOpened() {
        loadDrawer();
    }

    @Override public void nextWallpaper() {
        wallpaper = (wallpaper + 1) % (BUILT_IN.length + photos.length);
        prefs.edit().putInt("wallpaper", wallpaper).putString("wallpaperName", wallpaperKey(wallpaper)).apply();
        applyWallpaper();
    }

    /** Nome estável de um papel de parede: o arquivo da foto, ou "embutido:<nome>". */
    private String wallpaperKey(int i) {
        return i < BUILT_IN.length ? "embutido:" + BUILT_IN[i] : photos[i - BUILT_IN.length];
    }

    private int wallpaperIndex(String key) {
        if (key == null) return -1;
        for (int i = 0; i < BUILT_IN.length + photos.length; i++) if (key.equals(wallpaperKey(i))) return i;
        return -1;
    }

    private void applyWallpaper() {
        if (wallpaper < BUILT_IN.length) painter.g.setWallpaper(BUILT_IN_KIND[wallpaper], null);
        else painter.g.setWallpaper(0, "wallpapers/" + photos[wallpaper - BUILT_IN.length]);
        kick();
    }

    /** Nome do papel de parede atual. Sem nome cadastrado, "05-nebulosa-de-carina.jpg" vira "Nebulosa de carina". */
    String wallpaperName() {
        if (wallpaper < BUILT_IN.length) return (L.en() ? BUILT_IN_EN : BUILT_IN)[wallpaper];
        String file = photos[wallpaper - BUILT_IN.length];
        String named = photoNames.get(file);
        // nomes.txt: "arquivo=Nome" ou "arquivo=Nome em português|Name in English".
        if (named != null) {
            int bar = named.indexOf('|');
            return bar < 0 ? named : L.en() ? named.substring(bar + 1).trim() : named.substring(0, bar).trim();
        }
        String n = file.replaceFirst("^\\d+-", "").replaceFirst("\\.jpg$", "").replace('-', ' ');
        return n.isEmpty() ? L.t("Foto", "Photo") : n.substring(0, 1).toUpperCase() + n.substring(1);
    }

    @Override public void fireSettings() {
        // Settings.ACTION_SETTINGS passa pela tela da Amazon no Fire OS 7 e 8; esta entrada vai direto.
        if (Platform.fire(this)
                && start(new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS").setPackage(AMAZON_HOME))) {
            return;
        }
        if (!start(new Intent(Settings.ACTION_SETTINGS))) toast(L.t("Não foi possível abrir as configurações", "Could not open Settings"));
    }

    /** A tela inicial do sistema (Amazon, Google TV ou Android TV; ver Platform.systemHome). */
    @Override public void amazonHome() {
        // Pedida de propósito: o botão Home do Vitro não a troca de volta enquanto ela estiver na frente.
        HomeService.amazonWanted = true;
        if (!start(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(Platform.systemHome(this)))) {
            HomeService.amazonWanted = false;
            toast(L.t("A tela inicial do sistema não está disponível neste aparelho",
                    "The system home screen is not available on this device"));
        }
    }

    @Override public void appInfo(int i) {
        if (i >= dock.size()) return;
        start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + dock.get(i).pkg)));
    }

    @Override public void sleepTimer() {
        toast(SleepTimer.cycle(this));
    }

    /** Texto da linha "Desligar a TV" no menu: desligado, ou quanto falta. */
    String sleepLabel() {
        return SleepTimer.label(this);
    }

    /** Linha Sons do menu: liga ou desliga os sons de navegação (ver Sounds). */
    @Override public void toggleSounds() {
        sounds.toggle();
    }

    String soundsLabel() {
        return sounds.on() ? L.t("ligados", "on") : L.t("desligados", "off");
    }

    /** Sobre: versão, créditos e a escolha do idioma (ver AboutActivity). */
    @Override public void about() {
        start(new Intent(this, AboutActivity.class));
    }

    /**
     * Autoreparar: o Vitro se conserta sozinho. Com a permissão de mudar ajustes, religa na hora o serviço do botão
     * Home, a depuração ADB e o Home rápido; depois confere o ADB da TV (pede a VPN e a chave se faltarem) e refaz
     * as permissões e a guarda do debloat. Numa TV que ainda não foi preparada, abre a primeira configuração.
     */
    @Override public void repair() {
        java.util.List<String> done = Repair.settings(this);
        if (!done.isEmpty()) toast(Repair.message(done));
        if (!SetupActivity.complete(this)) {
            openSetup();
            return;
        }
        if (!HomeService.isRunning()) {
            toast(L.t("O serviço do botão Home está religando; tente Autoreparar de novo em instantes.",
                    "The Home button service is restarting; try Self-repair again in a moment."));
            return;
        }
        toast(L.t("Autoreparando…", "Self-repairing…"));
        setupAdb();
    }

    private boolean start(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void toast(String t) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show();
    }

    // ---- Controle remoto ---------------------------------------------------------------------

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        String before = scene.where();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: scene.move(this, -1, 0); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: scene.move(this, 1, 0); break;
            case KeyEvent.KEYCODE_DPAD_UP: scene.move(this, 0, -1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: scene.move(this, 0, 1); break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // OK curto abre; OK segurado abre o menu do app. A decisão sai no onKeyUp ou no onKeyLongPress.
                if (e.getRepeatCount() == 0) { held = false; e.startTracking(); }
                return true;
            case KeyEvent.KEYCODE_MENU:
                scene.hold(this);
                if (!before.equals(scene.where())) sounds.play(Sounds.OK);
                break;
            case KeyEvent.KEYCODE_BACK:
                scene.back();
                if (!before.equals(scene.where())) sounds.play(Sounds.BACK);
                break;
            default: return super.onKeyDown(code, e);
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            // O foco andou: passo. Não andou: fim da fileira, só no primeiro toque (segurando a seta, fica quieto).
            if (!before.equals(scene.where())) sounds.play(Sounds.MOVE);
            else if (e.getRepeatCount() == 0) sounds.play(Sounds.EDGE);
        }
        kick();
        return true;
    }

    @Override public boolean onKeyLongPress(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            held = true;
            String before = scene.where();
            scene.hold(this);
            if (!before.equals(scene.where())) sounds.play(Sounds.OK);
            kick();
            return true;
        }
        return super.onKeyLongPress(code, e);
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!held && !e.isCanceled()) {
                scene.ok(this);
                // Depois do OK: na linha Sons, ao ligar o próprio OK já soa; ao desligar, silêncio.
                sounds.play(Sounds.OK);
                kick();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) return true;
        return super.onKeyUp(code, e);
    }
}
