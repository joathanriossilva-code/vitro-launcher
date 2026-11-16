package app.vitro.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.opengl.GLES20;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/**
 * Monta e desenha cada quadro: vidro, apps, relógio, menus e gaveta, tudo na mesma superfície.
 *
 * Redesenho parcial: cada quadro vira uma lista de itens (o que desenhar e onde). Comparando com a lista
 * do quadro anterior, só a região que mudou é redesenhada; o resto do buffer é reaproveitado, usando a
 * "idade" que o EGL informa para cada buffer. Parado, nada é desenhado.
 */
final class Painter {
    /** Um item do quadro. Os parâmetros dependem do tipo (ver Gfx.draw). */
    static final class Item {
        int kind, tex, ver, clip;
        float x0, y0, x1, y1;
        final float[] p = new float[14];
    }

    /** Uma imagem pronta na GPU: texto ou ícone. */
    private static final class Sprite {
        int tex, ver, w, h;
        float ox, oy;      // onde fica a origem do texto (linha de base) dentro da imagem
        float adv, mid;    // largura do texto e deslocamento do centro vertical em relação à linha de base
    }

    // Ajuste fino escolhido no protótipo.
    private static final float REFR = 51f, BEZEL = 18f, FROST = 0.30f, TINT = 0.05f, SPEC = 0.70f, DISP = 0.14f;
    // Textos em português e inglês (ver L): lidos a cada quadro, então a troca de idioma em Sobre vale na hora.
    private String menuText(int i) {
        switch (i) {
            case 0: return Platform.fire(a) ? L.t("Configurações do Fire OS", "Fire OS settings") : L.t("Configurações", "Settings");
            case 1: return Platform.fire(a) ? L.t("Tela inicial da Amazon", "Amazon home screen")
                    : L.t("Tela inicial do " + Platform.name(a), Platform.name(a) + " home screen");
            case 2: return L.t("Papel de parede", "Wallpaper");
            case 3: return L.t("Desligar a TV", "Turn off the TV");
            case 4: return L.t("Sons", "Sounds");
            case 5: return L.t("Autoreparar", "Self-repair");
            default: return L.t("Sobre", "About");
        }
    }
    private static String ctxText(int i) {
        switch (i) {
            case 0: return L.t("Mover para a esquerda", "Move left");
            case 1: return L.t("Mover para a direita", "Move right");
            case 2: return L.t("Informações do app", "App info");
            default: return L.t("Tirar da barra", "Remove from bar");
        }
    }
    private static final int[] MENU_ICONS = {R.drawable.ic_gear, R.drawable.ic_home, R.drawable.ic_image, R.drawable.ic_moon, R.drawable.ic_sound, R.drawable.ic_repair, R.drawable.ic_info};
    private static final int[] CTX_ICONS = {R.drawable.ic_left, R.drawable.ic_right, R.drawable.ic_info, R.drawable.ic_trash};
    // Menu de um app da gaveta. Com o app já na barra, a segunda linha vira "Tirar da barra", em vermelho.
    private static String gctxText(int i) {
        return i == 0 ? L.t("Mover", "Move") : i == 1 ? L.t("Fixar na barra", "Pin to bar") : L.t("Informações do app", "App info");
    }
    private static final int[] GCTX_ICONS = {R.drawable.ic_move, R.drawable.ic_pin, R.drawable.ic_info};
    private static String unpin() { return L.t("Tirar da barra", "Remove from bar"); }
    private static String hint() { return L.t("Segure OK sobre um app para mover ou fixar na barra", "Hold OK on an app to move it or pin it to the bar"); }
    private static String hintMove() { return L.t("Use as setas para levar o app  ·  OK para soltar", "Use the arrows to carry the app  ·  OK to drop it"); }
    private static String all() { return L.t("Todos", "All"); }
    private static String allApps() { return L.t("Todos os apps", "All apps"); }
    private static final int[] TOP_ICONS = {R.drawable.ic_image, R.drawable.ic_sliders};
    private static final float[] WHITE = {1f, 1f, 1f}, RED = {1f, 0x8F / 255f, 0x9A / 255f};
    private static final int REGULAR = 0, MEDIUM = 1, BOLD = 2;
    /** TIME: o relógio de texto antigo, só no diagnóstico "--ez relogioTexto true" (comparação de desempenho). */
    private static final int PLAIN = 0, DATE = 1, TIME = 2;
    private static final int HISTORY = 3;
    // Desfoque do halo do app em foco, no espaço de desenho (medido nas capturas da versão anterior).
    private static final float HALO_SIGMA = 5.75f;
    // Onde os apps da gaveta podem aparecer (o resto é recortado).
    private static final float CLIP_X0 = 56f, CLIP_Y0 = 94f, CLIP_X1 = 904f, CLIP_Y1 = 448f;

    private final HomeActivity a;
    private final Scene s;
    private final Egl egl;
    final Gfx g;

    private ArrayList<Item> cur = new ArrayList<>(), prev = new ArrayList<>();
    private int nCur, nPrev, clip;
    private final float[] dmg = new float[4], region = new float[4];
    private final float[][] history = new float[HISTORY][4];
    private float k = 1f;

    private final HashMap<String, Sprite> sprites = new HashMap<>();
    private int spriteGen = -1, sizeGen = -1, verCounter;
    private final ArrayList<int[]> trash = new ArrayList<>();
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Typeface[] faces = {
            Typeface.create("sans-serif", Typeface.NORMAL), Typeface.create("sans-serif-medium", Typeface.NORMAL),
            Typeface.create("sans-serif", Typeface.BOLD)};
    /** A data muda de formato com o idioma (ver L): refeito quando ele muda. */
    private SimpleDateFormat dateFmt;
    private boolean dateEn;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", new Locale("pt", "BR"));
    private final Date now = new Date();
    private String dateStr = "", timeStr = "";
    private long clockMinute = -1;

    // Relógio de vidro (ver GlassClock): forma e imagem pronta na GPU, horário e posição da imagem na tela.
    private final GlassClock glassClock;
    private int clockShape, clockTex, clockW, clockH, clockVer, clockGen = -1, clockWall = -1;
    private float clockX, clockY, clockTop, clockBottom, clockK;
    private String clockText;
    /** A imagem guardada pode estar velha (o Vitro esteve fora da tela): só aparece a do minuto certo. */
    private boolean clockHide = true;
    /** Quando a imagem apareceu depois de escondida, para entrar suave; 0 = já inteira. */
    private long clockShown;

    // Diagnóstico (ligado por "am start --ei perf N").
    private long perfSum, perfMax, perfCpu;
    private int perfN, perfFull, perfLate, perfPaced;
    private float perfArea;

    /** Intervalo entre dois quadros seguidos de uma animação; acima de um quadro e meio, conta como atraso. */
    void pace(long intervalNs) {
        perfPaced++;
        if (intervalNs > 25000000L) perfLate++;
    }

    Painter(HomeActivity activity, Scene scene, Egl e) {
        a = activity;
        s = scene;
        egl = e;
        g = new Gfx(activity);
        glassClock = new GlassClock(activity);
    }

    /** Contexto de OpenGL novo: programas compilados, texturas antigas esquecidas. */
    void contextCreated() {
        g.init();
        sprites.clear();
        spriteGen = egl.generation;
        clockMinute = -1;
        forgetClock();
        nPrev = 0;
    }

    void resize(int w, int h) {
        nPrev = 0;
        // A superfície volta do mesmo tamanho sempre que o launcher reaparece: nada a refazer.
        if (w == g.w && h == g.h && sizeGen == egl.generation) return;
        boolean scaleChanged = w != g.w;
        g.resize(w, h);
        k = g.k;
        sizeGen = egl.generation;
        if (scaleChanged) dropSprites();
    }

    /** Marca uma textura para ser solta no próximo quadro (banner que saiu de uso). */
    void trash(int tex, int texGen) {
        if (tex != 0) trash.add(new int[] {tex, texGen});
    }

    // ---- Quadro ------------------------------------------------------------------------------

    /**
     * Desenha se algo mudou desde o último quadro (ou se force). Devolve true quando apresentou um quadro.
     */
    boolean draw(boolean force) {
        long t0 = System.nanoTime();
        if (!trash.isEmpty()) {
            for (int[] t : trash) if (t[1] == egl.generation) g.delete(t[0]);
            trash.clear();
        }
        if (g.wallDirty) g.bake();
        int age = egl.bufferAge();
        build();
        boolean comparable = diff();
        boolean changed = dmg[2] > dmg[0] && dmg[3] > dmg[1];
        if (!changed && !force) return false;

        boolean perf = (HomeActivity.PERF & 1) != 0;
        boolean full = force || !comparable || age < 1 || age > HISTORY + 1 || (HomeActivity.PERF & 2) != 0;
        if (!full) {
            // O buffer tem o quadro de "age" quadros atrás: além do que mudou agora, refaz o que mudou
            // nos quadros que ele não viu.
            System.arraycopy(dmg, 0, region, 0, 4);
            for (int i = 0; i < age - 1; i++) union(region, history[i]);
            full = region[0] <= 0f && region[1] <= 0f && region[2] >= g.w && region[3] >= g.h;
        }
        if (full) { region[0] = 0f; region[1] = 0f; region[2] = g.w; region[3] = g.h; }

        g.begin(full, region[0], region[1], region[2], region[3]);
        int clipNow = 0;
        for (int i = 0; i < nCur; i++) {
            Item it = cur.get(i);
            if ((HomeActivity.SKIP >> it.kind & 1) != 0) continue;
            float x0 = Math.max(it.x0, region[0]), y0 = Math.max(it.y0, region[1]);
            float x1 = Math.min(it.x1, region[2]), y1 = Math.min(it.y1, region[3]);
            if (it.clip != 0) {
                x0 = Math.max(x0, CLIP_X0 * k); y0 = Math.max(y0, CLIP_Y0 * k);
                x1 = Math.min(x1, CLIP_X1 * k); y1 = Math.min(y1, CLIP_Y1 * k);
            }
            if (x1 <= x0 || y1 <= y0) continue;
            if (it.clip != clipNow) {
                clipNow = it.clip;
                if (clipNow != 0) {
                    g.scissor(Math.max(region[0], CLIP_X0 * k), Math.max(region[1], CLIP_Y0 * k),
                            Math.min(region[2], CLIP_X1 * k), Math.min(region[3], CLIP_Y1 * k));
                } else if (full) {
                    g.noScissor();
                } else {
                    g.scissor(region[0], region[1], region[2], region[3]);
                }
            }
            g.draw(it);
        }
        long tc = System.nanoTime();
        if (perf) GLES20.glFinish();
        long t1 = System.nanoTime();
        boolean ok = egl.swap();

        // Histórico do que mudou em cada quadro apresentado, do mais recente para o mais antigo.
        float[] last = history[HISTORY - 1];
        System.arraycopy(history, 0, history, 1, HISTORY - 1);
        history[0] = last;
        if (force || !comparable) { last[0] = 0f; last[1] = 0f; last[2] = g.w; last[3] = g.h; }
        else System.arraycopy(dmg, 0, last, 0, 4);
        ArrayList<Item> tmp = prev; prev = cur; cur = tmp;
        nPrev = nCur;

        if (perf) {
            long dt = t1 - t0;
            perfSum += dt; perfMax = Math.max(perfMax, dt); perfCpu += tc - t0; perfN++;
            if (full) perfFull++;
            perfArea += (region[2] - region[0]) * (region[3] - region[1]) / (g.w * (float) g.h);
            if (perfN == 30) {
                Log.i("VitroPerf", String.format(Locale.ROOT, "quadro GPU+CPU: media %.1f ms (CPU %.1f), pior %.1f ms; area %.0f%%; completos %d/30; itens %d; atrasos %d/%d",
                        perfSum / 30e6, perfCpu / 30e6, perfMax / 1e6, perfArea / 30f * 100f, perfFull, nPrev, perfLate, perfPaced));
                perfSum = 0; perfMax = 0; perfCpu = 0; perfN = 0; perfFull = 0; perfArea = 0f; perfLate = 0; perfPaced = 0;
            }
        }
        if (!ok) Log.w("Vitro", "eglSwapBuffers falhou");
        return ok;
    }

    /** Compara a lista deste quadro com a anterior e junta em dmg a área que mudou. false = não dá para comparar. */
    private boolean diff() {
        dmg[0] = 0f; dmg[1] = 0f; dmg[2] = 0f; dmg[3] = 0f;
        if (nCur != nPrev) {
            dmg[2] = g.w; dmg[3] = g.h;
            return false;
        }
        boolean any = false;
        for (int i = 0; i < nCur; i++) {
            Item c = cur.get(i), o = prev.get(i);
            if (c.kind == o.kind && c.tex == o.tex && c.ver == o.ver && c.clip == o.clip
                    && c.x0 == o.x0 && c.y0 == o.y0 && c.x1 == o.x1 && c.y1 == o.y1 && Arrays.equals(c.p, o.p)) continue;
            any = grow(c, any);
            any = grow(o, any);
        }
        return true;
    }

    private boolean grow(Item it, boolean any) {
        float x0 = it.x0 - 1f, y0 = it.y0 - 1f, x1 = it.x1 + 1f, y1 = it.y1 + 1f;
        if (it.clip != 0) {
            x0 = Math.max(x0, CLIP_X0 * k); y0 = Math.max(y0, CLIP_Y0 * k);
            x1 = Math.min(x1, CLIP_X1 * k); y1 = Math.min(y1, CLIP_Y1 * k);
        }
        x0 = Math.max(0f, x0); y0 = Math.max(0f, y0); x1 = Math.min(g.w, x1); y1 = Math.min(g.h, y1);
        if (x1 <= x0 || y1 <= y0) return any;
        if (!any) { dmg[0] = x0; dmg[1] = y0; dmg[2] = x1; dmg[3] = y1; }
        else { dmg[0] = Math.min(dmg[0], x0); dmg[1] = Math.min(dmg[1], y0); dmg[2] = Math.max(dmg[2], x1); dmg[3] = Math.max(dmg[3], y1); }
        return true;
    }

    private static void union(float[] r, float[] o) {
        if (o[2] <= o[0] || o[3] <= o[1]) return;
        if (r[2] <= r[0] || r[3] <= r[1]) { System.arraycopy(o, 0, r, 0, 4); return; }
        r[0] = Math.min(r[0], o[0]); r[1] = Math.min(r[1], o[1]); r[2] = Math.max(r[2], o[2]); r[3] = Math.max(r[3], o[3]);
    }

    // ---- Montagem da lista -------------------------------------------------------------------

    private void build() {
        if (spriteGen != egl.generation) { sprites.clear(); spriteGen = egl.generation; clockMinute = -1; }
        nCur = 0;
        clip = 0;
        final float oD = Math.max(0f, s.oDrawer[0]), oM = Math.max(0f, s.oMenu[0]), oC = Math.max(0f, s.oCtx[0]);
        // Com um menu aberto, o fundo escurece um pouco. O menu de ajustes escurece fundo e barra;
        // o menu do app escurece também os atalhos do topo.
        final float sA = 1f - 0.28f * c01(oM), sB = 1f - 0.28f * c01(oC);

        Item it = put(Gfx.WALL, 0f, 0f, g.w, g.h);
        it.p[0] = sA * sB;
        it.ver = g.wallVersion;

        // A barra de apps, que se abre na gaveta.
        float open = c01(oD);
        shadow(s.dockRect, 7f, 0.32f, 16f);
        glass(s.dockRect, BEZEL, heavy(open), TINT, 0.36f * open, 1f, sA * sB);
        float lk = Scene.LIGHT * s.vis * sA * sB;
        float cr = s.glowCol[0] * lk, cg = s.glowCol[1] * lk, cb = s.glowCol[2] * lk;
        if (Math.max(cr, Math.max(cg, cb)) > 0.002f) {
            float gx = s.fx * k, gy = (Scene.DOCK_CY + Scene.BASE_HH + 2f) * k, sx = 34f * s.fs * k;
            float dx0 = (s.dockRect[0] - s.dockRect[2]) * k, dx1 = (s.dockRect[0] + s.dockRect[2]) * k;
            float dy0 = (s.dockRect[1] - s.dockRect[3]) * k, dy1 = (s.dockRect[1] + s.dockRect[3]) * k;
            float x0 = Math.max(dx0, gx - 3.2f * sx), x1 = Math.min(dx1, gx + 3.2f * sx);
            float y0 = Math.max(dy0, gy - 34f * k), y1 = Math.min(dy1 + 1f, gy + 34f * k);
            if (x1 > x0 && y1 > y0) {
                it = put(Gfx.GLOW, x0, y0, x1, y1);
                shape(it, s.dockRect, 0f);
                it.p[5] = gx; it.p[6] = gy; it.p[7] = sx;
                it.p[8] = cr; it.p[9] = cg; it.p[10] = cb;
            }
        }

        // Os atalhos do topo, que se abrem no menu de ajustes.
        float ta = 1f - c01(oD * 3f), th = c01(oM);
        if (ta > 0.001f) {
            shadow(s.topRect, 6f, 0.30f * ta * (0.35f + 0.65f * th), 18f);
            glass(s.topRect, BEZEL * 0.7f, heavy(th), TINT + 0.03f * th, 0.50f * th, ta, sB);
        }
        // O menu do app, que nasce do próprio app. O de um app da gaveta vem depois, por cima dos blocos.
        float ca = c01(oC * 2.5f);
        boolean gridMenu = s.ctxGrid;
        if (ca > 0.001f && !gridMenu) ctxGlass(ca);

        if (s.aClock > 0.01f) clock(sA * sB);
        if (s.aDock > 0.01f) dock();
        if (s.aTop > 0.01f) top();
        if (s.aMenu > 0.01f) menu();
        if (s.aCtx > 0.01f && !gridMenu) ctx();
        if (s.aDrawer > 0.01f) drawer();
        if (gridMenu) {
            if (ca > 0.001f) ctxGlass(ca);
            if (s.aCtx > 0.01f) ctx();
        }
    }

    private void ctxGlass(float ca) {
        shadow(s.ctxRect, 6f, 0.30f * ca, 18f);
        glass(s.ctxRect, BEZEL * 0.7f, heavy(1f), TINT + 0.03f, 0.50f, ca, 1f);
    }

    /** mul: o quanto o fundo está escurecido por um menu aberto (o vidro, que mostra o fundo, acompanha). */
    private void clock(float mul) {
        long minute = System.currentTimeMillis() / 60000L;
        if (dateFmt == null || dateEn != L.en()) {
            dateEn = L.en();
            dateFmt = new SimpleDateFormat(dateEn ? "EEEE, MMMM d" : "EEEE, d 'de' MMMM", L.locale());
            clockMinute = -1;
        }
        if (minute != clockMinute) {
            clockMinute = minute;
            now.setTime(System.currentTimeMillis());
            String d = dateFmt.format(now);
            d = d.substring(0, 1).toUpperCase(L.locale()) + d.substring(1);
            if (!d.equals(dateStr)) drop(key(MEDIUM, 19f, DATE, dateStr));
            String t = timeFmt.format(now);
            if (!t.equals(timeStr)) drop(key(BOLD, 104f, TIME, timeStr));
            dateStr = d;
            timeStr = t;
        }
        sprite(text(dateStr, MEDIUM, 19f, DATE), 48f, 49f, s.aClock);
        if (HomeActivity.TEXT_CLOCK) sprite(text(timeStr, BOLD, 104f, TIME), 45f, 148f, s.aClock);
        else glassClock(mul);
    }

    /**
     * O relógio de vidro. A forma de cada minuto chega pronta da thread de fundo; aqui ela vira textura e o vidro
     * é desenhado uma vez na imagem do relógio. Na virada do minuto, se a forma nova ainda não chegou, fica a
     * anterior por uma fração de segundo. De volta de outro app, a imagem velha não aparece: o relógio entra
     * suave quando a do minuto certo fica pronta.
     */
    private void glassClock(float mul) {
        if (clockGen != egl.generation || clockK != k) forgetClock();
        String hr = HomeActivity.HORA;
        String timeStr = hr != null ? hr : this.timeStr;
        if (!timeStr.equals(clockText)) {
            GlassClock.Shape sh = glassClock.take(timeStr, k);
            if (sh != null) installClock(sh);
            else glassClock.want(timeStr, k, a::kick, System.currentTimeMillis() + 800L);
        }
        if (clockShape != 0 && clockWall != g.wallVersion) {
            long t0 = System.nanoTime();
            clockTex = g.bakeClock(clockShape, clockTex, clockW, clockH, clockX, clockY, clockTop, clockBottom);
            clockWall = g.wallVersion;
            clockVer = ++verCounter;
            if ((HomeActivity.PERF & 1) != 0) {
                GLES20.glFinish();
                Log.i("VitroPerf", String.format(Locale.ROOT, "relógio: vidro desenhado em %.1f ms", (System.nanoTime() - t0) / 1e6));
            }
        }
        boolean fresh = timeStr.equals(clockText);
        if (clockTex == 0 || (!fresh && clockHide)) return;
        float appear = 1f;
        if (clockShown != 0L) {
            appear = c01((android.os.SystemClock.uptimeMillis() - clockShown) / 220f);
            if (appear >= 1f) clockShown = 0L;
            appear = appear * appear * (3f - 2f * appear);
        }
        Item it = put(Gfx.CLOCK, clockX, clockY, clockX + clockW, clockY + clockH);
        it.p[0] = clockX; it.p[1] = clockY; it.p[2] = clockW; it.p[3] = clockH;
        it.p[4] = s.aClock * appear; it.p[5] = mul;
        it.tex = clockTex; it.ver = clockVer;
    }

    private void installClock(GlassClock.Shape sh) {
        clockShape = g.clockShape(clockShape, clockW, clockH, sh.rgba, sh.w, sh.h);
        if (clockTex != 0 && (clockW != sh.w || clockH != sh.h)) {
            g.delete(clockTex);
            clockTex = 0;
        }
        clockW = sh.w;
        clockH = sh.h;
        float baseline = GlassClock.DIGIT_TOP * k + sh.asc;
        clockX = Math.round(GlassClock.LEFT * k - sh.ox);
        clockY = Math.round(baseline - sh.oy);
        clockTop = (sh.oy - sh.asc) / sh.h;
        clockBottom = sh.oy / sh.h;
        clockText = sh.text;
        clockWall = -1;
        if (clockHide) {
            clockHide = false;
            clockShown = android.os.SystemClock.uptimeMillis();
        }
    }

    /** As texturas do relógio morreram com o contexto (ou a escala mudou): começa de novo, escondido. */
    private void forgetClock() {
        if (clockGen == egl.generation) {
            g.delete(clockShape);
            g.delete(clockTex);
        }
        clockShape = 0;
        clockTex = 0;
        clockText = null;
        clockHide = true;
        clockShown = 0L;
        clockGen = egl.generation;
        clockK = k;
    }

    /** O relógio ainda está entrando na tela: o laço de quadros continua. */
    boolean animating() {
        return clockShown != 0L;
    }

    /** O Vitro volta para a tela: já pede a forma do minuto atual, antes do primeiro quadro. */
    void wake() {
        if (g.w > 1) glassClock.want(timeFmt.format(new java.util.Date()), k, a::kick, System.currentTimeMillis() + 800L);
    }

    /** O Vitro saiu da tela: a imagem do relógio pode ficar velha até a volta. */
    void hidden() {
        clockHide = true;
        glassClock.clear();
    }

    /** Alguns segundos antes da virada: calcula em segundo plano a forma do minuto seguinte. */
    void prepareNextClock() {
        if (g.w <= 1 || HomeActivity.TEXT_CLOCK) return;
        long next = (System.currentTimeMillis() / 60000L + 1L) * 60000L;
        glassClock.want(timeFmt.format(new java.util.Date(next)), k, null, next);
    }

    private void dock() {
        boolean inDock = s.zone == Scene.DOCK || s.zone == Scene.CTX;
        for (int i = 0; i < s.n; i++) {
            float sc = s.sc[i];
            float cy = Scene.DOCK_CY - Scene.BASE_HH * (sc - 1f);
            boolean focused = inDock && i == s.focus;
            float alpha = (focused ? 1f : 0.92f) * s.aDock;
            if (i == s.n - 1) {
                rrect(s.cx[i], cy, Scene.BANNER_W * sc, Scene.BANNER_H * sc, 9f * sc, WHITE, 0.2f * alpha);
                if (s.lens[1] == 0f && s.hot[1] == 0f) {
                    // Parado: o texto é traçado no tamanho exato do bloco, nítido como na versão anterior.
                    Sprite m = text(all(), MEDIUM, 11f * sc, PLAIN);
                    textAt(all(), MEDIUM, 11f * sc, s.cx[i] * k - m.adv / 2f, cy * k - m.mid, WHITE, alpha);
                } else {
                    // Em movimento: amplia a imagem pronta (a grande, se o bloco estiver crescido).
                    boolean big = sc > 1.03f;
                    Sprite sp = text(all(), MEDIUM, big ? 11f * Scene.MAG : 11f, PLAIN);
                    maskCentered(sp, s.cx[i], cy, big ? sc / Scene.MAG : sc, WHITE, alpha);
                }
            } else {
                Apps.App app = i < a.dock.size() ? a.dock.get(i) : null;
                tile(app, s.cx[i], cy, Scene.BANNER_W * sc, Scene.BANNER_H * sc, 9f * sc, alpha, focused, 0x59 / 255f * s.aDock);
            }
        }
    }

    private void top() {
        float[] xs = {836.67f, 883.33f};
        for (int i = 0; i < 2; i++) {
            boolean focused = s.zone == Scene.TOP && i == s.top;
            if (focused) rrect(xs[i], 49f, 38.8f, 38.8f, 19.4f, WHITE, 66f / 255f * s.aTop);
            // Mesmos limites da versão anterior, truncados no espaço de desenho (em foco o ícone cresce 8%).
            float half = focused ? 10.8f : 10f;
            int l = (int) (xs[i] - half), t = (int) (49f - half), r = (int) (xs[i] + half), b = (int) (49f + half);
            Sprite sp = icon(TOP_ICONS[i], Math.round((r - l) * k), Math.round((b - t) * k));
            mask(sp, l * k, t * k, 1f, WHITE, (focused ? 1f : 0.88f) * s.aTop);
        }
    }

    /** Texto do timer de desligamento já desenhado na linha do menu. */
    private String sleepShown;

    private void menu() {
        float al = s.aMenu, left = Scene.MENU_LEFT, w = Scene.MENU_W, row = Scene.MENU_ROW;
        float hy = s.menuRowY(s.hlMenu[0]);
        rrect(left + w / 2f, hy + row / 2f, w, row, row / 2f, WHITE, 0.2f * al);
        float sy = s.menuRowY(1f) + row + 6f;
        rrect(left + w / 2f, sy + 0.5f, w - 32f, 1f, 0f, WHITE, 41f / 255f * al);
        for (int i = 0; i < Scene.MENU_N; i++) {
            boolean focused = s.zone == Scene.MENU && i == s.menu;
            float y = s.menuRowY(i) + row / 2f;
            float ra = (focused ? 1f : 0.9f) * al;
            iconAt(MENU_ICONS[i], left + 16f, y, 21f, WHITE, ra);
            label(text(menuText(i), focused ? MEDIUM : REGULAR, 17f, PLAIN), left + 50f, y, WHITE, ra);
            if (i == 2) {
                Sprite name = text(a.wallpaperName(), REGULAR, 15f, PLAIN);
                textAt(a.wallpaperName(), REGULAR, 15f, (left + w - 16f) * k - name.adv, y * k - name.mid, WHITE, 0.7f * al);
            } else if (i == 3) {
                // Tempo até a TV desligar; o texto muda a cada minuto, e o anterior sai da memória.
                String t = a.sleepLabel();
                if (!t.equals(sleepShown)) {
                    if (sleepShown != null) drop(key(REGULAR, 15f, PLAIN, sleepShown));
                    sleepShown = t;
                }
                Sprite v = text(t, REGULAR, 15f, PLAIN);
                textAt(t, REGULAR, 15f, (left + w - 16f) * k - v.adv, y * k - v.mid, WHITE, 0.7f * al);
            } else if (i == 4) {
                String t = a.soundsLabel();
                Sprite v = text(t, REGULAR, 15f, PLAIN);
                textAt(t, REGULAR, 15f, (left + w - 16f) * k - v.adv, y * k - v.mid, WHITE, 0.7f * al);
            }
        }
    }

    private void ctx() {
        float al = s.aCtx, left = s.ctxCx - Scene.CTX_W / 2f, w = Scene.CTX_W, row = Scene.CTX_ROW;
        float hy = s.ctxRowY(s.hlCtx[0]);
        rrect(left + w / 2f, hy + row / 2f, w, row, row / 2f, WHITE, 0.2f * al * (s.ctxOff(s.ctx) ? 0.45f : 1f));
        boolean grid = s.ctxGrid;
        if (!grid) {
            float sy = s.ctxRowY(2f) + row + 6f;
            rrect(left + w / 2f, sy + 0.5f, w - 32f, 1f, 0f, WHITE, 41f / 255f * al);
        }
        boolean pinned = grid && s.grid < a.all.size() && a.dock.contains(a.all.get(s.grid));
        for (int i = 0; i < s.ctxCount(); i++) {
            boolean focused = s.zone == Scene.CTX && i == s.ctx;
            float y = s.ctxRowY(i) + row / 2f;
            float ra = (s.ctxOff(i) ? (focused ? 0.5f : 0.35f) : (focused ? 1f : 0.9f)) * al;
            String str;
            int icon;
            float[] color;
            if (grid && i == 1 && pinned) {
                str = unpin(); icon = R.drawable.ic_trash; color = RED;
            } else if (grid) {
                str = gctxText(i); icon = GCTX_ICONS[i]; color = WHITE;
            } else {
                str = ctxText(i); icon = CTX_ICONS[i]; color = i == 3 ? RED : WHITE;
            }
            iconAt(icon, left + 14f, y, 21f, color, ra);
            label(text(str, focused ? MEDIUM : REGULAR, 16f, PLAIN), left + 48f, y, color, ra);
        }
    }

    private void drawer() {
        float al = s.aDrawer;
        Sprite title = text(allApps(), MEDIUM, 24f, PLAIN);
        mask(title, Math.round(80f * k - title.ox), Math.round(76f * k - title.oy), 1f, WHITE, al);
        Sprite hint = text(s.moving ? hintMove() : hint(), REGULAR, 15f, PLAIN);
        mask(hint, Math.round(80f * k - hint.ox), Math.round(478f * k - hint.oy), 1f, WHITE, 0.72f * al);

        // Com o menu de um app aberto, os outros blocos recuam um pouco.
        float dim = s.ctxGrid ? 1f - 0.35f * c01(Math.max(0f, s.oCtx[0])) : 1f;
        boolean open = s.inGrid();
        clip = 1;
        int first = Math.max(0, ((int) Math.floor(s.scroll[0]) - 2) * Scene.COLS);
        int last = Math.min(a.all.size(), first + (Scene.ROWS_VISIBLE + 4) * Scene.COLS);
        for (int pass = 0; pass < 2; pass++) {
            // O app em foco entra por último para ficar por cima dos vizinhos.
            for (int i = first; i < last; i++) {
                boolean focused = open && i == s.grid;
                if (focused != (pass == 1)) continue;
                Apps.App app = a.all.get(i);
                float gs = i < s.gridScale.length ? s.gridScale[i] : 1f;
                float ox = i < s.offX.length ? s.offX[i] : 0f, oy = i < s.offY.length ? s.offY[i] : 0f;
                float cx = Scene.GRID_LEFT + (i % Scene.COLS) * Scene.PITCH_X + Scene.TILE_W / 2f + ox;
                float cy = Scene.GRID_TOP + (i / Scene.COLS - s.scroll[0]) * Scene.PITCH_Y + Scene.TILE_H / 2f + oy;
                if (cy + Scene.TILE_H < CLIP_Y0 || cy - Scene.TILE_H > CLIP_Y1) continue;
                // Levantado para mover: um contorno claro em volta do bloco.
                float rim = focused && s.moving ? 0.34f * al : 0f;
                tile(app, cx, cy, Scene.TILE_W * gs, Scene.TILE_H * gs, 14f * gs, (focused ? 1f : 0.9f * dim) * al,
                        focused, 178f / 255f * al, rim);
            }
        }
        clip = 0;
    }

    // ---- Emissores ---------------------------------------------------------------------------

    private Item put(int kind, float x0, float y0, float x1, float y1) {
        if (nCur == cur.size()) cur.add(new Item());
        Item it = cur.get(nCur++);
        it.kind = kind; it.tex = 0; it.ver = 0; it.clip = clip;
        it.x0 = x0; it.y0 = y0; it.x1 = x1; it.y1 = y1;
        Arrays.fill(it.p, 0f);
        return it;
    }

    /** Copia para o item a forma (centro, meio tamanho, raio) em px. */
    private void shape(Item it, float[] r5, float offDp) {
        it.p[0] = r5[0] * k; it.p[1] = (r5[1] + offDp) * k; it.p[2] = r5[2] * k; it.p[3] = r5[3] * k; it.p[4] = r5[4] * k;
    }

    private void glass(float[] r5, float bezel, float frost, float tint, float dim, float alpha, float mul) {
        float cx = r5[0] * k, cy = r5[1] * k, hw = r5[2] * k, hh = r5[3] * k;
        Item it = put(Gfx.GLASS, (float) Math.floor(cx - hw - 1f), (float) Math.floor(cy - hh - 1f), (float) Math.ceil(cx + hw + 1f), (float) Math.ceil(cy + hh + 1f));
        shape(it, r5, 0f);
        it.p[5] = bezel * k; it.p[6] = REFR * k; it.p[7] = frost;
        it.p[8] = tint; it.p[9] = SPEC; it.p[10] = dim; it.p[11] = DISP;
        it.p[12] = alpha; it.p[13] = mul;
    }

    /** Sombra de uma peça de vidro, deslocada para baixo. A margem vai até onde a sombra deixa de ser visível. */
    private void shadow(float[] r5, float offDp, float strength, float falloffDp) {
        if (strength * 255f <= 1.02f) return;
        float fall = falloffDp * k;
        float m = fall * (float) Math.log(strength * 255f) + 1f;
        float cx = r5[0] * k, cy = (r5[1] + offDp) * k, hw = r5[2] * k, hh = r5[3] * k;
        Item it = put(Gfx.SHADOW, cx - hw - m, cy - hh - m, cx + hw + m, cy + hh + m);
        shape(it, r5, offDp);
        it.p[5] = strength; it.p[6] = 1f / fall; it.p[7] = m;
        // Faixa coberta pelo vidro, em coordenadas da sombra: não precisa ser calculada.
        it.p[8] = -hh + 1f - offDp * k;
        it.p[9] = hh - 1f - offDp * k;
    }

    private void rrect(float cxDp, float cyDp, float wDp, float hDp, float rDp, float[] rgb, float alpha) {
        float cx = cxDp * k, cy = cyDp * k, hw = wDp * k / 2f, hh = hDp * k / 2f;
        Item it = put(Gfx.RRECT, cx - hw - 1f, cy - hh - 1f, cx + hw + 1f, cy + hh + 1f);
        it.p[0] = cx; it.p[1] = cy; it.p[2] = hw; it.p[3] = hh; it.p[4] = rDp * k;
        it.p[5] = rgb[0] * alpha; it.p[6] = rgb[1] * alpha; it.p[7] = rgb[2] * alpha; it.p[8] = alpha;
    }

    /** Banner de um app. Sem banner carregado, um bloco translúcido. Em foco, uma sombra suave por baixo. */
    private void tile(Apps.App app, float cxDp, float cyDp, float wDp, float hDp, float rDp, float alpha,
            boolean shadow, float shadowAlpha) {
        tile(app, cxDp, cyDp, wDp, hDp, rDp, alpha, shadow, shadowAlpha, 0f);
    }

    /** rim: opacidade de um contorno claro de 3,5 pontos em volta do bloco (app levantado para mover). */
    private void tile(Apps.App app, float cxDp, float cyDp, float wDp, float hDp, float rDp, float alpha,
            boolean shadow, float shadowAlpha, float rim) {
        int t = bannerTexture(app);
        if (t == 0) {
            rrect(cxDp, cyDp, wDp, hDp, rDp, WHITE, 38f / 255f * alpha);
            return;
        }
        float cx = cxDp * k, cy = cyDp * k, hw = wDp * k / 2f, hh = hDp * k / 2f, r = rDp * k;
        if (shadow) {
            // Halo do app em foco, igual ao da versão anterior: o banner desfocado na borda (raio 8),
            // deslocado 5 para baixo, por baixo do bloco.
            float sigma = HALO_SIGMA * k, m = sigma * 3f + 1f, sy = cy + 5f * k;
            Item sh = put(Gfx.SOFT, cx - hw - m, sy - hh - m, cx + hw + m, sy + hh + m);
            sh.p[0] = cx; sh.p[1] = sy; sh.p[2] = hw; sh.p[3] = hh; sh.p[4] = r; sh.p[5] = sigma;
            sh.p[9] = shadowAlpha;
            sh.tex = t;
            sh.ver = app.texVer;
        }
        if (rim > 0.001f) rrect(cxDp, cyDp, wDp + 7f, hDp + 7f, rDp + 3.5f, WHITE, rim);
        Item it = put(Gfx.TILE, cx - hw - 1f, cy - hh - 1f, cx + hw + 1f, cy + hh + 1f);
        it.p[0] = cx; it.p[1] = cy; it.p[2] = hw; it.p[3] = hh; it.p[4] = r; it.p[5] = alpha;
        it.tex = t;
        it.ver = app.texVer;
    }

    private int bannerTexture(Apps.App app) {
        if (app == null) return 0;
        Bitmap b = app.banner;
        if (b == null) return 0;
        if (app.tex == 0 || app.texGen != egl.generation) {
            app.tex = g.texture(b);
            app.texGen = egl.generation;
            app.texVer = ++verCounter;
        }
        return app.tex;
    }

    /** Imagem colorida pronta (texto com sombra), com a linha de base em (xDp, yDp). */
    private void sprite(Sprite sp, float xDp, float yDp, float alpha) {
        float x = Math.round(xDp * k - sp.ox), y = Math.round(yDp * k - sp.oy);
        Item it = put(Gfx.SPRITE, x, y, x + sp.w, y + sp.h);
        it.p[0] = x; it.p[1] = y; it.p[2] = sp.w; it.p[3] = sp.h; it.p[4] = alpha;
        it.tex = sp.tex; it.ver = sp.ver;
    }

    /** Texto ou ícone de uma cor, com o canto superior esquerdo em px. */
    private void mask(Sprite sp, float x, float y, float scale, float[] rgb, float alpha) {
        float w = sp.w * scale, h = sp.h * scale;
        Item it = put(Gfx.MASK, x, y, x + w, y + h);
        it.p[0] = x; it.p[1] = y; it.p[2] = w; it.p[3] = h;
        it.p[4] = rgb[0] * alpha; it.p[5] = rgb[1] * alpha; it.p[6] = rgb[2] * alpha; it.p[7] = alpha;
        it.tex = sp.tex; it.ver = sp.ver;
    }

    /** Texto começando em xDp e centralizado verticalmente em cyDp. */
    private void label(Sprite sp, float xDp, float cyDp, float[] rgb, float alpha) {
        mask(sp, Math.round(xDp * k - sp.ox), Math.round(cyDp * k - sp.mid - sp.oy), 1f, rgb, alpha);
    }

    /**
     * Texto parado com a origem em xPx, que pode cair entre dois pixels: como o desenhista do Android, usa a
     * versão do texto deslocada em quartos de pixel, em vez de borrar a imagem.
     */
    private void textAt(String str, int face, float sizeDp, float xPx, float basePx, float[] rgb, float alpha) {
        float fl = (float) Math.floor(xPx);
        int sub = Math.round((xPx - fl) * 4f);
        if (sub == 4) { sub = 0; fl += 1f; }
        Sprite sp = text(str, face, sizeDp, PLAIN, sub);
        mask(sp, fl - sp.ox, Math.round(basePx) - sp.oy, 1f, rgb, alpha);
    }

    /** Texto centralizado em (cxDp, cyDp), ampliado por scale. */
    private void maskCentered(Sprite sp, float cxDp, float cyDp, float scale, float[] rgb, float alpha) {
        float x = cxDp * k - (sp.ox + sp.adv / 2f) * scale, y = cyDp * k - (sp.oy + sp.mid) * scale;
        mask(sp, x, y, scale, rgb, alpha);
    }

    private void iconAt(int res, float leftDp, float cyDp, float sizeDp, float[] rgb, float alpha) {
        Sprite sp = icon(res, sizeDp);
        // Mesmo arredondamento da versão anterior: a posição é truncada no espaço de desenho.
        mask(sp, (float) Math.floor(leftDp) * k, (float) Math.floor(cyDp - sizeDp / 2f) * k, 1f, rgb, alpha);
    }

    // ---- Texto e ícones ----------------------------------------------------------------------

    private static String key(int face, float size, int style, String str) {
        return face + "|" + size + "|" + style + "|" + str;
    }

    private Sprite text(String str, int face, float sizeDp, int style) {
        return text(str, face, sizeDp, style, 0);
    }

    /** sub: deslocamento horizontal do texto dentro da imagem, em quartos de pixel (0 a 3). */
    private Sprite text(String str, int face, float sizeDp, int style, int sub) {
        String key = sub == 0 ? key(face, sizeDp, style, str) : key(face, sizeDp, style, str) + "|" + sub;
        Sprite sp = sprites.get(key);
        if (sp != null) return sp;
        // O texto é traçado no tamanho do espaço de desenho e ampliado pelo canvas, exatamente como a versão
        // anterior fazia: mesmas larguras de letra, mesmas sombras.
        tp.setTypeface(faces[face]);
        tp.setTextSize(sizeDp);
        tp.setColor(Color.WHITE);
        float pad = 1.5f;
        tp.setLetterSpacing(style == TIME ? -0.035f : 0f);
        if (style == PLAIN) {
            tp.clearShadowLayer();
        } else {
            // A data: sombra suave e 86% de opacidade. TIME: o relógio de texto antigo (só no diagnóstico).
            float radius = style == DATE ? 8f : 18f, dy = style == DATE ? 1f : 2f;
            tp.setShadowLayer(radius, 0f, dy, style == DATE ? 0x59000000 : 0x47000000);
            if (style == DATE) tp.setAlpha(219);
            pad = (float) Math.ceil(radius * 1.9f + dy) + 2f;
        }
        Paint.FontMetrics fm = tp.getFontMetrics();
        float adv = tp.measureText(str);
        // Origem do texto em px inteiros dentro da imagem, para a posição final não oscilar de 1 px.
        int ox = (int) Math.ceil(pad * k), oy = (int) Math.ceil((pad - fm.top) * k);
        int w = Math.max(1, ox + (int) Math.ceil((adv + pad + sizeDp * 0.12f) * k));
        int h = Math.max(1, oy + (int) Math.ceil((fm.bottom + pad) * k));
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.scale(k, k);
        c.drawText(str, (ox + sub * 0.25f) / k, oy / k, tp);
        sp = new Sprite();
        sp.w = w; sp.h = h; sp.ox = ox; sp.oy = oy; sp.adv = adv * k;
        sp.mid = (tp.ascent() + tp.descent()) / 2f * k;
        sp.tex = style == PLAIN ? g.alphaTexture(bm) : g.texture(bm);
        sp.ver = ++verCounter;
        bm.recycle();
        tp.clearShadowLayer();
        tp.setAlpha(255);
        sprites.put(key, sp);
        return sp;
    }

    private Sprite icon(int res, float sizeDp) {
        int px = Math.max(1, Math.round(sizeDp * k));
        return icon(res, px, px);
    }

    private Sprite icon(int res, int wPx, int hPx) {
        String key = "i|" + res + "|" + wPx + "|" + hPx;
        Sprite sp = sprites.get(key);
        if (sp != null) return sp;
        Bitmap bm = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888);
        Drawable d = a.getResources().getDrawable(res).mutate();
        d.setBounds(0, 0, wPx, hPx);
        d.draw(new Canvas(bm));
        sp = new Sprite();
        sp.w = wPx; sp.h = hPx;
        sp.tex = g.alphaTexture(bm);
        sp.ver = ++verCounter;
        bm.recycle();
        sprites.put(key, sp);
        return sp;
    }

    private void drop(String key) {
        Sprite sp = sprites.remove(key);
        if (sp != null) g.delete(sp.tex);
    }

    private void dropSprites() {
        for (Sprite sp : sprites.values()) g.delete(sp.tex);
        sprites.clear();
        clockMinute = -1;
    }

    /** Cria com antecedência os textos e ícones dos menus, para a primeira abertura não engasgar. */
    void prewarm() {
        if (!egl.hasSurface()) return;
        for (int i = 0; i < Scene.MENU_N; i++) { text(menuText(i), REGULAR, 17f, PLAIN); text(menuText(i), MEDIUM, 17f, PLAIN); icon(MENU_ICONS[i], 21f); }
        for (int i = 0; i < Scene.CTX_N; i++) { text(ctxText(i), REGULAR, 16f, PLAIN); text(ctxText(i), MEDIUM, 16f, PLAIN); icon(CTX_ICONS[i], 21f); }
        for (int i = 0; i < Scene.GCTX_N; i++) { text(gctxText(i), REGULAR, 16f, PLAIN); text(gctxText(i), MEDIUM, 16f, PLAIN); icon(GCTX_ICONS[i], 21f); }
        text(allApps(), MEDIUM, 24f, PLAIN);
        text(hint(), REGULAR, 15f, PLAIN);
        text(hintMove(), REGULAR, 15f, PLAIN);
        text(all(), MEDIUM, 11f * Scene.MAG, PLAIN);
        text(a.wallpaperName(), REGULAR, 15f, PLAIN);
        text(a.soundsLabel(), REGULAR, 15f, PLAIN);
        for (int res : TOP_ICONS) icon(res, 20f);
    }

    private static float heavy(float x) {
        return FROST + (Math.max(FROST, 0.92f) - FROST) * x;
    }

    private static float c01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
