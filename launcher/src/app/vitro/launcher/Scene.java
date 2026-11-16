package app.vitro.launcher;

/**
 * Estado da tela e toda a animação, sem depender do Android.
 * As medidas estão no espaço de desenho de 960 x 540 (metade de 1080p), o mesmo do protótipo.
 *
 * Movimento medido no protótipo e reproduzido aqui com as mesmas molas:
 *  - troca de foco na barra (93 unidades): 87% em 167 ms, passa 0,6% do ponto e assenta em ~370 ms;
 *  - abertura de menu e gaveta: 50% em ~85 ms, 93% em ~215 ms, assenta em ~300 ms, sem passar do ponto.
 */
final class Scene {
    interface Host {
        void openDock(int i);
        void openGrid(int i);
        void pinGrid(int i);
        void nextWallpaper();
        void fireSettings();
        void amazonHome();
        void about();
        /** Autoreparar: o Vitro confere e refaz sozinho o que uma atualização desfez (ver HomeActivity.repair). */
        void repair();
        /** Timer de desligamento: OK passa para a próxima opção (ver SleepTimer). */
        void sleepTimer();
        /** Liga ou desliga os sons de navegação (ver Sounds). */
        void toggleSounds();
        void appInfo(int i);
        void removeFromDock(int i);
        void moveDock(int from, int to);
        void drawerOpened();
        /** Leva o app da posição from para to na gaveta (os do meio andam uma casa). */
        void moveGrid(int from, int to);
        void gridInfo(int i);
    }

    static final int DOCK = 0, TOP = 1, MENU = 2, CTX = 3, DRAWER = 4;
    static final int MENU_N = 7, CTX_N = 4, COLS = 5, ROWS_VISIBLE = 3;
    /** Menu de um app da gaveta: Mover, Fixar (ou Tirar) da barra, Informações. */
    static final int GCTX_N = 3;
    static final float GCTX_HH = 74f;

    // Geometria (cx, cy, meia largura, meia altura, raio).
    static final float ITEM_W = 93f, DOCK_CY = 452f, DOCK_HH = 42f, DOCK_R = 42f;
    static final float BANNER_W = 72f, BANNER_H = 41f, BASE_HH = 20.5f;
    static final float[] SHEET = {480f, 270f, 432f, 243f, 36f};
    static final float[] TOP_PILL = {860f, 49f, 52f, 22f, 22f};
    // Painel do menu: 7 linhas (de 27 a 378), centro, meia largura, meia altura e raio.
    static final float[] TOP_MENU = {762f, 202.5f, 150f, 175.5f, 30f};
    static final float CTX_CY = 293.5f, CTX_HW = 135f, CTX_HH = 102.5f, CTX_R = 29f;
    static final float MENU_LEFT = 620f, MENU_TOP = 35f, MENU_W = 284f, MENU_ROW = 46f;
    static final float CTX_TOP = 199f, CTX_W = 254f, CTX_ROW = 44f, SEP = 13f;
    static final float GRID_LEFT = 80f, GRID_TOP = 108f, TILE_W = 140f, TILE_H = 79f, PITCH_X = 165f, PITCH_Y = 109f;

    // Ajuste fino escolhido no protótipo.
    static final float MAG = 1.50f, REACH = 2.5f * ITEM_W, LIGHT = 50f / 60f;

    int zone = DOCK, focus = 0, top = 1, menu = 0, ctx = 0, grid = 0, rowTop = 0;
    int n = 1;          // itens na barra; o último é sempre "Todos"
    int gridCount = 0;
    boolean ctxGrid;    // o menu de app aberto é de um app da gaveta (e não da barra)
    boolean moving;     // app da gaveta levantado, andando com as setas
    float ctxTop = CTX_TOP;
    // Deslocamento de cada bloco da gaveta em relação à sua casa: ao mover um app, os outros escorregam até o lugar novo.
    float[] offX = new float[0], offY = new float[0];
    float[][] glow = {{1f, 1f, 1f}};

    // Molas: posição e velocidade.
    final float[] lens = {0f, 0f}, hot = {1f, 0f}, oMenu = {0f, 0f}, oCtx = {0f, 0f}, oDrawer = {0f, 0f};
    final float[] hlMenu = {0f, 0f}, hlCtx = {0f, 0f}, scroll = {0f, 0f};
    float[] gridScale = new float[0];

    // Resultado do layout, lido pelo desenho.
    float[] sc = new float[1], cx = new float[1];
    float total, fx, fs;
    final float[] dockRect = new float[5], topRect = new float[5], ctxRect = new float[5], glowCol = new float[3];
    float ctxCx, aDock, aClock, aTop, aMenu, aCtx, aDrawer, vis;

    void setCounts(int dockItems, int gridItems) {
        n = Math.max(1, dockItems);
        gridCount = Math.max(0, gridItems);
        if (sc.length != n) { sc = new float[n]; cx = new float[n]; }
        if (glow.length != n) {
            // Até as cores reais chegarem, luz branca para todos.
            glow = new float[n][];
            for (int i = 0; i < n; i++) glow[i] = new float[] {1f, 1f, 1f};
        }
        if (gridScale.length != gridCount) {
            gridScale = new float[gridCount];
            java.util.Arrays.fill(gridScale, 1f);
            offX = new float[gridCount];
            offY = new float[gridCount];
            moving = false;
        }
        focus = Math.min(focus, n - 1);
        grid = Math.max(0, Math.min(grid, gridCount - 1));
        if (zone == CTX && !ctxGrid && focus == n - 1) zone = DOCK;
        if ((zone == DRAWER || (zone == CTX && ctxGrid)) && gridCount == 0) zone = DOCK;
    }

    void home() {
        zone = DOCK;
        moving = false;
    }

    /** Home segurado: o Vitro abre com o menu de cima aberto. */
    void openMenu() {
        top = 1;
        menu = 0; hlMenu[0] = 0f; hlMenu[1] = 0f;
        zone = MENU;
    }

    // ---- Navegação pelo controle -------------------------------------------------------------

    /** Onde o foco está: muda quando uma tecla fez algo (para o som saber se foi um passo ou o fim da fileira). */
    String where() {
        return zone + ":" + focus + ":" + top + ":" + menu + ":" + ctx + ":" + grid + ":" + moving;
    }

    void move(Host h, int dx, int dy) {
        if (zone == DOCK) {
            if (dx != 0) focus = clampI(focus + dx, 0, n - 1);
            else if (dy < 0) zone = TOP;
        } else if (zone == TOP) {
            if (dx != 0) top = clampI(top + dx, 0, 1);
            else if (dy > 0) zone = DOCK;
        } else if (zone == MENU) {
            menu = clampI(menu + dy, 0, MENU_N - 1);
        } else if (zone == CTX) {
            ctx = clampI(ctx + dy, 0, ctxCount() - 1);
        } else if (zone == DRAWER) {
            int i = grid, to = i;
            if (dx < 0 && i % COLS > 0) to--;
            if (dx > 0 && i % COLS < COLS - 1 && i + 1 < gridCount) to++;
            if (dy < 0 && i - COLS >= 0) to -= COLS;
            if (dy > 0) to = i + COLS < gridCount ? i + COLS : (i / COLS < (gridCount - 1) / COLS ? gridCount - 1 : i);
            if (moving && to != i) {
                // O app levantado vai junto com o foco; os do caminho andam uma casa para abrir espaço.
                h.moveGrid(i, to);
                shiftGrid(i, to);
            }
            grid = to;
            int row = grid / COLS;
            if (row < rowTop) rowTop = row;
            if (row > rowTop + ROWS_VISIBLE - 1) rowTop = row - ROWS_VISIBLE + 1;
        }
    }

    /**
     * Reordena o estado de animação da gaveta como a lista de apps foi reordenada: cada bloco continua desenhado
     * onde estava e escorrega até a casa nova.
     */
    private void shiftGrid(int from, int to) {
        int count = gridCount;
        if (offX.length != count || gridScale.length != count) return;
        float[] nx = new float[count], ny = new float[count], ns = new float[count];
        for (int j = 0; j < count; j++) {
            int nj = j == from ? to
                    : (from < to && j > from && j <= to) ? j - 1
                    : (to < from && j >= to && j < from) ? j + 1 : j;
            nx[nj] = slotX(j) + offX[j] - slotX(nj);
            ny[nj] = slotY(j) + offY[j] - slotY(nj);
            ns[nj] = gridScale[j];
        }
        offX = nx; offY = ny; gridScale = ns;
    }

    private static float slotX(int i) { return (i % COLS) * PITCH_X; }
    private static float slotY(int i) { return (i / COLS) * PITCH_Y; }

    void ok(Host h) {
        if (zone == DOCK) {
            if (focus == n - 1) {
                if (gridCount == 0) return;
                grid = 0; rowTop = 0; scroll[0] = 0f; scroll[1] = 0f;
                zone = DRAWER;
                h.drawerOpened();
            } else {
                h.openDock(focus);
            }
        } else if (zone == TOP) {
            if (top == 0) {
                h.nextWallpaper();
            } else {
                menu = 0; hlMenu[0] = 0f; hlMenu[1] = 0f;
                zone = MENU;
            }
        } else if (zone == MENU) {
            if (menu == 0) h.fireSettings();
            else if (menu == 1) h.amazonHome();
            else if (menu == 2) h.nextWallpaper();
            else if (menu == 3) h.sleepTimer();
            else if (menu == 4) h.toggleSounds();
            else if (menu == 5) h.repair();
            else h.about();
        } else if (zone == CTX && ctxGrid) {
            zone = DRAWER;
            if (ctx == 0) moving = true;
            else if (ctx == 1) h.pinGrid(grid);
            else h.gridInfo(grid);
        } else if (zone == CTX) {
            if (ctx == 0) {
                if (focus > 0) { h.moveDock(focus, focus - 1); focus--; }
            } else if (ctx == 1) {
                if (focus < n - 2) { h.moveDock(focus, focus + 1); focus++; }
            } else if (ctx == 2) {
                h.appInfo(focus);
            } else {
                zone = DOCK;
                h.removeFromDock(focus);
            }
        } else if (zone == DRAWER) {
            if (moving) moving = false;   // solta o app na casa em que está
            else h.openGrid(grid);
        }
    }

    /** Segurar OK, ou o botão Menu do controle. */
    void hold(Host h) {
        if (zone == DOCK && focus != n - 1) {
            ctxGrid = false;
            ctx = focus == 0 ? 1 : 0;
            hlCtx[0] = ctx; hlCtx[1] = 0f;
            zone = CTX;
        } else if (zone == DRAWER && !moving && gridCount > 0) {
            ctxGrid = true;
            ctx = 0;
            hlCtx[0] = 0f; hlCtx[1] = 0f;
            zone = CTX;
        }
    }

    void back() {
        if (zone == MENU) zone = TOP;
        else if (zone == DRAWER && moving) moving = false;
        else if (zone == DRAWER) { focus = n - 1; zone = DOCK; }
        else if (zone == CTX) zone = ctxGrid ? DRAWER : DOCK;
        else if (zone == TOP) zone = DOCK;
    }

    int ctxCount() {
        return ctxGrid ? GCTX_N : CTX_N;
    }

    /** Linhas apagadas do menu da barra (não há para onde mover). O da gaveta não tem. */
    boolean ctxOff(int row) {
        if (ctxGrid) return false;
        return (row == 0 && focus == 0) || (row == 1 && focus >= n - 2);
    }

    /** A gaveta está aberta (com ou sem o menu de um app dela por cima). */
    boolean inGrid() {
        return zone == DRAWER || (zone == CTX && ctxGrid);
    }

    // ---- Animação ----------------------------------------------------------------------------

    /**
     * Avança a animação pelo tempo real que passou. Se um quadro atrasar, o tempo é dividido em passos
     * de até 1/60 s: a animação dura o mesmo tempo no relógio, em vez de ficar em câmera lenta.
     */
    boolean advance(float dt) {
        if (dt <= 0.022f) return step(dt);
        int n = Math.min(8, (int) Math.ceil(dt * 60f));
        boolean busy = false;
        for (int i = 0; i < n; i++) busy = step(dt / n);
        return busy;
    }

    /** Avança as molas um passo. Devolve true enquanto algo ainda se move. */
    boolean step(float dt) {
        boolean busy = spring(lens, focus, dt, 230f, 23f, 0.0006f, 0.006f);
        busy |= spring(hot, zone == DOCK || (zone == CTX && !ctxGrid) ? 1f : 0f, dt, 260f, 27f, 0.002f, 0.02f);
        busy |= spring(oMenu, zone == MENU ? 1f : 0f, dt, 260f, 27f, 0.002f, 0.02f);
        busy |= spring(oCtx, zone == CTX ? 1f : 0f, dt, 260f, 27f, 0.002f, 0.02f);
        busy |= spring(oDrawer, inGrid() ? 1f : 0f, dt, 260f, 27f, 0.002f, 0.02f);
        busy |= spring(hlMenu, menu, dt, 420f, 33f, 0.002f, 0.02f);
        busy |= spring(hlCtx, ctx, dt, 420f, 33f, 0.002f, 0.02f);
        busy |= spring(scroll, rowTop, dt, 260f, 27f, 0.002f, 0.02f);
        float ease = Math.min(1f, dt * 18f);
        boolean open = inGrid();
        for (int i = 0; i < gridScale.length; i++) {
            float goal = open && i == grid ? (moving ? 1.16f : 1.1f) : 1f;
            float d = goal - gridScale[i];
            if (Math.abs(d) > 0.002f) { gridScale[i] += d * ease; busy = true; } else gridScale[i] = goal;
        }
        // Blocos rearrumados escorregam até a casa nova (um terço de pixel de folga para parar).
        float slide = Math.min(1f, dt * 16f);
        for (int i = 0; i < offX.length; i++) {
            if (Math.abs(offX[i]) > 0.15f || Math.abs(offY[i]) > 0.15f) {
                offX[i] -= offX[i] * slide; offY[i] -= offY[i] * slide; busy = true;
            } else {
                offX[i] = 0f; offY[i] = 0f;
            }
        }
        return busy;
    }

    private static boolean spring(float[] s, float goal, float dt, float k, float c, float eps, float epsV) {
        s[1] += (-k * (s[0] - goal) - c * s[1]) * dt;
        s[0] += s[1] * dt;
        if (Math.abs(s[0] - goal) > eps || Math.abs(s[1]) > epsV) return true;
        s[0] = goal; s[1] = 0f;
        return false;
    }

    /** Ampliação no estilo do dock do macOS, mais os retângulos de vidro e as opacidades do quadro. */
    void compute() {
        float h = clamp01(hot[0]);
        float m = 1f + (MAG - 1f) * h;
        total = 0f;
        for (int i = 0; i < n; i++) {
            float d = Math.abs(i - lens[0]) * ITEM_W;
            float bump = d < REACH ? 0.5f * (1f + (float) Math.cos(Math.PI * d / REACH)) : 0f;
            sc[i] = 1f + (m - 1f) * bump;
            total += ITEM_W * sc[i];
        }
        float x = 480f - total / 2f;
        for (int i = 0; i < n; i++) {
            cx[i] = x + ITEM_W * sc[i] / 2f;
            x += ITEM_W * sc[i];
        }
        float t = Math.max(0f, Math.min(n - 1, lens[0]));
        if (n > 1) {
            int j = Math.min(n - 2, (int) Math.floor(t));
            float f = t - j;
            fx = cx[j] + (cx[j + 1] - cx[j]) * f + (lens[0] - t) * ITEM_W;
            fs = sc[j] + (sc[j + 1] - sc[j]) * f;
            for (int c = 0; c < 3; c++) glowCol[c] = glow[j][c] + (glow[j + 1][c] - glow[j][c]) * f;
        } else {
            fx = cx[0]; fs = sc[0];
            System.arraycopy(glow[0], 0, glowCol, 0, 3);
        }

        float oD = Math.max(0f, oDrawer[0]), oM = Math.max(0f, oMenu[0]), oC = Math.max(0f, oCtx[0]);
        // A barra se abre na gaveta; os atalhos do topo se abrem no menu; o menu do app nasce do próprio app.
        dockRect[0] = mix(480f, SHEET[0], oD); dockRect[1] = mix(DOCK_CY, SHEET[1], oD);
        dockRect[2] = mix(total / 2f + 8f, SHEET[2], oD); dockRect[3] = mix(DOCK_HH, SHEET[3], oD);
        dockRect[4] = mix(DOCK_R, SHEET[4], oD);
        for (int i = 0; i < 5; i++) topRect[i] = mix(TOP_PILL[i], TOP_MENU[i], oM);
        if (ctxGrid && gridCount > 0) {
            // Menu de um app da gaveta: nasce do bloco e abre ao lado dele (à direita nas três primeiras colunas).
            int gi = clampI(grid, 0, gridCount - 1);
            float gs = gi < gridScale.length ? gridScale[gi] : 1f;
            float tcx = GRID_LEFT + slotX(gi) + TILE_W / 2f + (gi < offX.length ? offX[gi] : 0f);
            float tcy = GRID_TOP + (gi / COLS - scroll[0]) * PITCH_Y + TILE_H / 2f + (gi < offY.length ? offY[gi] : 0f);
            float side = TILE_W * gs / 2f + 14f + CTX_HW;
            float mcx = gi % COLS < 3 ? tcx + side : tcx - side;
            float mcy = Math.max(SHEET[1] - SHEET[3] + GCTX_HH + 12f, Math.min(SHEET[1] + SHEET[3] - GCTX_HH - 12f, tcy));
            ctxCx = mcx;
            ctxTop = mcy - GCTX_HH + 8f;
            ctxRect[0] = mix(tcx, mcx, oC); ctxRect[1] = mix(tcy, mcy, oC);
            ctxRect[2] = mix(TILE_W * gs / 2f, CTX_HW, oC); ctxRect[3] = mix(TILE_H * gs / 2f, GCTX_HH, oC);
            ctxRect[4] = mix(14f * gs, CTX_R, oC);
        } else {
            ctxTop = CTX_TOP;
            ctxCx = Math.max(48f + CTX_HW, Math.min(912f - CTX_HW, fx));
            ctxRect[0] = mix(fx, ctxCx, oC); ctxRect[1] = mix(DOCK_CY - 8f, CTX_CY, oC);
            ctxRect[2] = mix(36f, CTX_HW, oC); ctxRect[3] = mix(22f, CTX_HH, oC); ctxRect[4] = mix(9f, CTX_R, oC);
        }

        aDock = 1f - clamp01(oD * 2.5f);
        aClock = 1f - clamp01(oD * 2f);
        aTop = (1f - clamp01(oM * 2.5f)) * (1f - clamp01(oD * 3f));
        aMenu = clamp01((oM - 0.45f) / 0.5f);
        aCtx = clamp01((oC - 0.45f) / 0.5f);
        aDrawer = clamp01((oD - 0.5f) / 0.45f);
        vis = h * (1f - clamp01(oD * 3f));
    }

    float menuRowY(float i) {
        return MENU_TOP + i * MENU_ROW + SEP * clamp01(i - 1f);
    }

    float ctxRowY(float i) {
        return ctxTop + i * CTX_ROW + (ctxGrid ? 0f : SEP * clamp01(i - 2f));
    }

    static float mix(float a, float b, float t) { return a + (b - a) * t; }
    static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
    static int clampI(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
