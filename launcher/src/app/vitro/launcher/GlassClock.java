package app.vitro.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * O relógio de vidro: os números em vidro líquido, como na tela de bloqueio do iPhone.
 *
 * Como fica leve: a forma dos números vira um campo de distâncias, calculado na CPU numa thread de fundo,
 * uma vez por minuto (o do minuto seguinte sai alguns segundos antes da virada). Com ele a GPU desenha o
 * vidro uma única vez, numa textura própria (Gfx.bakeClock); a cada quadro o relógio é só uma cópia dessa
 * textura, o mesmo custo do relógio de texto de antes.
 *
 * Os ajustes vêm do protótipo (prototipo/relogio.html), escolhidos lá: fonte M PLUS Rounded
 * no peso 900, tamanho 104, espaçamento -6%, arredondar 30% e o estilo "Como no iPhone".
 */
final class GlassClock {
    // Medidas no espaço de desenho de 960 x 540.
    static final float SIZE = 104f, TRACK = -0.06f, ROUND = 0.30f, LEFT = 45f;
    /** Folga mínima entre o desenho de dois caracteres vizinhos, em fração do tamanho (a de "03" no protótipo). */
    static final float GAP = 0.04f;
    /** Alto dos algarismos do relógio de texto (Roboto Bold 104, base em 148): o de vidro começa ali. */
    static final float DIGIT_TOP = 148f - 0.7109f * 104f;
    // "Como no iPhone": transparência 62%, refração 30, relevo 10, desfoque 15%, brilho 100%, dispersão 20%,
    // sombra 40% e contorno 30%, convertidos como no protótipo.
    static final float REFR = 30f, BEZEL = 10f, FROST = 0.15f, TINT = 0.6f * (1f - 0.62f), SPEC = 1f, DISP = 0.20f,
            SHADOW = 0.55f * 0.40f, SHADOW_SIGMA = 9f, SHADOW_DY = 3f, EDGE = 0.30f;
    /** Faixas da distância guardada, em px: fina (borda nítida) e larga (relevo e sombra). */
    static final float FINE = 4f, COARSE = 64f;
    private static final float INF = 1e20f;

    /** A forma de um horário, pronta para virar textura. */
    static final class Shape {
        String text;
        float k;
        /** R = distância fina, G = distância larga, B e A = direção da borda (ver clockglass.frag). */
        ByteBuffer rgba;
        int w, h;
        /** Origem do texto (início da linha de base) dentro da imagem e altura dos algarismos, em px. */
        float ox, oy, asc;
        long ms;
    }

    /**
     * A tela está animando (laço de quadros ligado). O cálculo da forma pausa enquanto isso: no Fire TV Stick, CPU e
     * GPU dividem a mesma memória, e o cálculo correndo junto com uma animação fazia quadros perderem a vez.
     */
    static volatile boolean uiBusy;
    /** Até quando o cálculo atual pode esperar a tela parar (depois disso segue mesmo com animação). */
    private static volatile long deadline;

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    // Tudo abaixo é da thread principal, menos a fonte, que só a thread de fundo usa.
    private final ArrayList<Shape> ready = new ArrayList<>();
    private String building, queued;
    private float buildingK, queuedK;
    private long queuedUntil;
    private Runnable buildingDone, queuedDone;
    private volatile Typeface face;

    GlassClock(Context c) {
        ctx = c.getApplicationContext();
    }

    /**
     * Pede a forma de um horário. Quando ficar pronta, done roda na thread principal. Um cálculo por vez.
     * until: até quando o cálculo pode pausar durante animações (hora do relógio do sistema, em ms).
     */
    void want(String text, float k, Runnable done, long until) {
        for (Shape s : ready) if (s.k == k && s.text.equals(text)) return;
        if (text.equals(building) && k == buildingK) {
            if (done != null) buildingDone = done;
            if (until < deadline) deadline = until;
            return;
        }
        if (building != null) {
            queued = text;
            queuedK = k;
            queuedDone = done;
            queuedUntil = until;
            return;
        }
        start(text, k, done, until);
    }

    /** Entrega a forma pronta de um horário (e a esquece), ou null se ainda não há. */
    Shape take(String text, float k) {
        for (int i = 0; i < ready.size(); i++) {
            Shape s = ready.get(i);
            if (s.k == k && s.text.equals(text)) {
                ready.remove(i);
                return s;
            }
        }
        return null;
    }

    /** Esquece as formas prontas que ninguém pegou (o Vitro saiu da tela): não ficam ocupando memória. */
    void clear() {
        ready.clear();
        queued = null;
        queuedDone = null;
    }

    private void start(final String text, final float k, Runnable done, long until) {
        building = text;
        buildingK = k;
        buildingDone = done;
        deadline = until;
        Thread t = new Thread(() -> {
            // Prioridade baixa, mas fora do grupo "de fundo" do Android: lá a thread roda com o processador em
            // frequência baixa e leva várias vezes mais tempo. Com a navegação, a tela continua na frente.
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE);
            Shape sh = null;
            try {
                sh = build(text, k);
            } catch (Throwable e) {
                Log.w("Vitro", "relógio de vidro: " + e);
            }
            final Shape res = sh;
            ui.post(() -> {
                Runnable d = buildingDone;
                building = null;
                buildingDone = null;
                if (res != null) {
                    ready.add(res);
                    // Guarda no máximo o minuto atual e o seguinte.
                    while (ready.size() > 2) ready.remove(0);
                    if ((HomeActivity.PERF & 1) != 0) {
                        Log.i("VitroPerf", "relógio: forma de " + res.text + " em " + res.ms + " ms (" + res.w + "x" + res.h + ")");
                    }
                }
                if (queued != null) {
                    String q = queued;
                    float qk = queuedK;
                    Runnable qd = queuedDone;
                    queued = null;
                    queuedDone = null;
                    start(q, qk, qd, queuedUntil);
                }
                if (d != null) d.run();
            });
        }, "vitro-relogio");
        t.start();
    }

    // ---- Forma dos números (thread de fundo) ----------------------------------------------------

    /** Espera a animação da tela acabar (até o prazo do cálculo). Chamado entre linhas e etapas. */
    private static void pause() {
        while (uiBusy && System.currentTimeMillis() < deadline) SystemClock.sleep(5);
    }

    private Shape build(String text, float k) {
        long t0 = SystemClock.uptimeMillis();
        if (face == null) {
            Typeface tf;
            try {
                tf = Typeface.createFromAsset(ctx.getAssets(), "fonts/relogio.ttf");
            } catch (RuntimeException e) {
                tf = Typeface.create("sans-serif", Typeface.BOLD);
            }
            face = tf;
        }
        float size = SIZE * k, track = TRACK * size;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(Color.WHITE);
        // Onde cada caractere começa: o espaçamento apertado (-6%), mas nunca menos que GAP entre o desenho
        // de um número e o do seguinte. Sem isso, pares como 04, 06 e 00 encostavam e o arredondamento
        // fundia os dois números.
        int n = text.length();
        float[] org = new float[n];
        Path path = new Path();
        RectF ink = new RectF();
        float gap = GAP * size, prevAdv = 0f, prevRight = 0f, total = 0f;
        for (int i = 0; i < n; i++) {
            float adv = p.measureText(text, i, i + 1);
            p.getTextPath(text, i, i + 1, 0f, 0f, path);
            path.computeBounds(ink, true);
            if (i > 0) org[i] = Math.max(org[i - 1] + prevAdv + track, org[i - 1] + prevRight + gap - ink.left);
            prevAdv = adv;
            prevRight = ink.right;
            total = org[i] + Math.max(adv, ink.right);
        }
        Rect b = new Rect();
        p.getTextBounds("0123456789:", 0, 11, b);
        float digitAsc = -b.top;
        int pad = (int) COARSE + 2;
        int asc = (int) Math.ceil(digitAsc) + 2, desc = Math.max(0, b.bottom) + 2;
        int w = (int) Math.ceil(total + 2 * pad), h = asc + desc + 2 * pad, count = w * h;

        // Cobertura de cada pixel, com os caracteres um a um.
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(bm);
        for (int i = 0; i < n; i++) c.drawText(text, i, i + 1, pad + org[i], pad + asc, p);
        int stride = bm.getRowBytes();
        ByteBuffer px = ByteBuffer.allocate(stride * h);
        bm.copyPixelsToBuffer(px);
        bm.recycle();
        byte[] raw = px.array();
        float[] a = new float[count], t = new float[count], u = new float[count];
        for (int y = 0; y < h; y++) {
            for (int xx = 0; xx < w; xx++) a[y * w + xx] = (raw[y * stride + xx] & 0xFF) / 255f;
        }
        long t1 = SystemClock.uptimeMillis();
        // Os desfoques rodam em meia resolução: os campos desfocados são lisos, então meia resolução e
        // interpolação dão o mesmo resultado a menos de centésimos de pixel, com um oitavo da conta.
        int w2 = (w + 1) / 2, h2 = (h + 1) / 2, half = w2 * h2;
        float[] s1 = new float[half], s2 = new float[half], s3 = new float[half];

        // Cantos redondos: desfoca a cobertura e corta de novo no meio, com meio-tom de 1 px pela inclinação.
        float sig = ROUND * 0.045f * size;
        if (sig >= 0.3f) {
            down2(a, w, h, s1, w2, h2);
            gauss(s1, s2, s3, w2, h2, sig * 0.5f);
            up2(s2, w2, h2, u, w, h);
            java.util.Arrays.fill(a, 0f);
            for (int y = 1; y < h - 1; y++) {
                pause();
                for (int xx = 1; xx < w - 1; xx++) {
                    int i = y * w + xx;
                    float gx = (u[i + 1] - u[i - 1]) * 0.5f, gy = (u[i + w] - u[i - w]) * 0.5f;
                    float gm = (float) Math.sqrt(gx * gx + gy * gy);
                    a[i] = gm < 1e-5f ? (u[i] > 0.5f ? 1f : 0f) : clamp(0.5f + (u[i] - 0.5f) / gm, 0f, 1f);
                }
            }
        }

        long t2 = SystemClock.uptimeMillis();
        pause();
        // Distância com sinal até a borda (negativa dentro): transformada exata de dentro e de fora.
        for (int i = 0; i < count; i++) {
            float v = a[i];
            if (v >= 0.999f) { t[i] = 0f; u[i] = INF; }
            else if (v <= 0.001f) { t[i] = INF; u[i] = 0f; }
            else {
                float e = 0.5f - v;
                t[i] = e > 0f ? e * e : 0f;
                u[i] = e < 0f ? e * e : 0f;
            }
        }
        // As duas transformadas (de fora e de dentro) são independentes: uma roda numa segunda thread.
        final int m = Math.max(w, h);
        final float[] halfInv = new float[m + 1];
        for (int d = 1; d <= m; d++) halfInv[d] = 0.5f / d;
        Thread inside = new Thread(() -> edt(u, w, h, new float[m], new int[m], new float[m + 1], halfInv), "vitro-relogio-2");
        inside.start();
        edt(t, w, h, new float[m], new int[m], new float[m + 1], halfInv);
        try {
            inside.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (int i = 0; i < count; i++) t[i] = (float) Math.sqrt(t[i]) - (float) Math.sqrt(u[i]);
        long t25 = SystemClock.uptimeMillis();

        // Direção da borda: gradiente da distância desfocada (sigma de 3 px), para o brilho do aro não granular.
        // Faixa larga (relevo e sombra): a distância levemente desfocada (1,5 px), para o relevo não ondular.
        pause();
        down2(t, w, h, s1, w2, h2);
        gauss(s1, s2, s3, w2, h2, 1.5f);
        float[] gx2 = new float[half], gy2 = new float[half];
        for (int y = 1; y < h2 - 1; y++) {
            for (int xx = 1; xx < w2 - 1; xx++) {
                int i = y * w2 + xx;
                // Diferença em meia resolução: dividir por 4 dá a inclinação por pixel da tela.
                gx2[i] = clamp((s2[i + 1] - s2[i - 1]) * 0.25f, -1f, 1f);
                gy2[i] = clamp((s2[i + w2] - s2[i - w2]) * 0.25f, -1f, 1f);
            }
        }
        gauss(s1, s2, s3, w2, h2, 0.75f);
        long t3 = SystemClock.uptimeMillis();
        // Textura numa passada só: distância fina em tamanho cheio e os três campos de meia resolução
        // interpolados com os mesmos pesos.
        byte[] rgba = new byte[count * 4];
        int[] xa = new int[w], xb = new int[w];
        float[] tx = new float[w];
        upTable(w, w2, xa, xb, tx);
        float fineK = 1f / (2f * FINE), coarseK = 1f / (2f * COARSE);
        for (int y = 0; y < h; y++) {
            pause();
            float fy = y * 0.5f - 0.25f;
            int y0 = (int) Math.floor(fy);
            float ty = fy - y0;
            int ya = Math.max(0, Math.min(h2 - 1, y0)) * w2, yb = Math.max(0, Math.min(h2 - 1, y0 + 1)) * w2;
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int i = row + x, o = i << 2, ia = xa[x], ib = xb[x];
                float kx = tx[x];
                rgba[o] = enc(t[i] * fineK + 0.5f);
                rgba[o + 1] = enc(bil(s2, ya, yb, ia, ib, kx, ty) * coarseK + 0.5f);
                rgba[o + 2] = enc(bil(gx2, ya, yb, ia, ib, kx, ty) * 0.5f + 0.5f);
                rgba[o + 3] = enc(bil(gy2, ya, yb, ia, ib, kx, ty) * 0.5f + 0.5f);
            }
        }
        ByteBuffer out = ByteBuffer.wrap(rgba);
        if ((HomeActivity.PERF & 1) != 0) {
            Log.i("VitroPerf", "relógio: etapas " + (t1 - t0) + " (texto) + " + (t2 - t1) + " (arredondar) + "
                    + (t25 - t2) + " (distância) + " + (t3 - t25) + " (desfoques) + " + (SystemClock.uptimeMillis() - t3) + " (textura) ms");
        }

        Shape s = new Shape();
        s.text = text;
        s.k = k;
        s.rgba = out;
        s.w = w;
        s.h = h;
        s.ox = pad;
        s.oy = pad + asc;
        s.asc = digitAsc;
        s.ms = SystemClock.uptimeMillis() - t0;
        return s;
    }

    private static byte enc(float v) {
        float q = v * 255f + 0.5f;
        return (byte) (q <= 0f ? 0 : (q >= 255f ? 255 : (int) q));
    }

    /** Interpolação bilinear na meia resolução: linhas ya e yb (já multiplicadas pela largura), colunas a e b. */
    private static float bil(float[] s, int ya, int yb, int a, int b, float tx, float ty) {
        float top = s[ya + a] + (s[ya + b] - s[ya + a]) * tx;
        float bot = s[yb + a] + (s[yb + b] - s[yb + a]) * tx;
        return top + (bot - top) * ty;
    }

    /** Colunas e pesos da interpolação para cada x da tela (o centro do pixel x fica em x/2 - 0,25). */
    private static void upTable(int w, int w2, int[] xa, int[] xb, float[] tx) {
        for (int x = 0; x < w; x++) {
            float fx = x * 0.5f - 0.25f;
            int x0 = (int) Math.floor(fx);
            tx[x] = fx - x0;
            xa[x] = Math.max(0, Math.min(w2 - 1, x0));
            xb[x] = Math.max(0, Math.min(w2 - 1, x0 + 1));
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Reduz à metade, pela média de cada quadrado de 2 x 2 pixels. */
    private static void down2(float[] src, int w, int h, float[] dst, int w2, int h2) {
        for (int y = 0; y < h2; y++) {
            int r0 = 2 * y * w, r1 = Math.min(2 * y + 1, h - 1) * w;
            for (int x = 0; x < w2; x++) {
                int x0 = 2 * x, x1 = Math.min(2 * x + 1, w - 1);
                dst[y * w2 + x] = 0.25f * (src[r0 + x0] + src[r0 + x1] + src[r1 + x0] + src[r1 + x1]);
            }
        }
    }

    /**
     * Volta ao tamanho cheio por interpolação bilinear. O centro do pixel x da tela fica em x/2 - 0,25 na
     * meia resolução (cada pixel reduzido cobre dois da tela).
     */
    private static void up2(float[] src, int w2, int h2, float[] dst, int w, int h) {
        int[] xa = new int[w], xb = new int[w];
        float[] tx = new float[w];
        upTable(w, w2, xa, xb, tx);
        for (int y = 0; y < h; y++) {
            pause();
            float fy = y * 0.5f - 0.25f;
            int y0 = (int) Math.floor(fy);
            float ty = fy - y0;
            int ya = Math.max(0, Math.min(h2 - 1, y0)) * w2, yb = Math.max(0, Math.min(h2 - 1, y0 + 1)) * w2;
            int row = y * w;
            for (int x = 0; x < w; x++) dst[row + x] = bil(src, ya, yb, xa[x], xb[x], tx[x], ty);
        }
    }

    /** Desfoque gaussiano separável: src vai para dst, usando tmp no meio. Bordas repetem o último pixel. */
    private static void gauss(float[] src, float[] dst, float[] tmp, int w, int h, float sig) {
        int r = Math.max(1, (int) Math.ceil(sig * 3f)), taps = 2 * r + 1;
        float[] ker = new float[taps];
        float sum = 0f;
        for (int i = -r; i <= r; i++) {
            ker[i + r] = (float) Math.exp(-(i * i) / (2f * sig * sig));
            sum += ker[i + r];
        }
        for (int i = 0; i < taps; i++) ker[i] /= sum;
        for (int y = 0; y < h; y++) {
            pause();
            int row = y * w;
            for (int x = 0; x < w; x++) {
                float acc = 0f;
                if (x >= r && x < w - r) {
                    int base = row + x - r;
                    for (int j = 0; j < taps; j++) acc += src[base + j] * ker[j];
                } else {
                    for (int j = -r; j <= r; j++) {
                        int xx = x + j;
                        xx = xx < 0 ? 0 : (xx >= w ? w - 1 : xx);
                        acc += src[row + xx] * ker[j + r];
                    }
                }
                tmp[row + x] = acc;
            }
        }
        for (int y = 0; y < h; y++) {
            pause();
            boolean inside = y >= r && y < h - r;
            for (int x = 0; x < w; x++) {
                float acc = 0f;
                if (inside) {
                    int base = (y - r) * w + x;
                    for (int j = 0; j < taps; j++, base += w) acc += tmp[base] * ker[j];
                } else {
                    for (int j = -r; j <= r; j++) {
                        int yy = y + j;
                        yy = yy < 0 ? 0 : (yy >= h ? h - 1 : yy);
                        acc += tmp[yy * w + x] * ker[j + r];
                    }
                }
                dst[y * w + x] = acc;
            }
        }
    }

    /**
     * Transformada de distância exata (Felzenszwalb e Huttenlocher), coluna a coluna e linha a linha.
     * halfInv[d] = 1/(2d): troca as divisões, lentas no processador da TV, por multiplicações.
     */
    private static void edt(float[] g, int w, int h, float[] f, int[] v, float[] z, float[] halfInv) {
        for (int x = 0; x < w; x++) {
            if ((x & 15) == 0) pause();
            edt1d(g, x, w, h, f, v, z, halfInv);
        }
        for (int y = 0; y < h; y++) {
            if ((y & 7) == 0) pause();
            edt1d(g, y * w, 1, w, f, v, z, halfInv);
        }
    }

    private static void edt1d(float[] g, int off, int stride, int n, float[] f, int[] v, float[] z, float[] halfInv) {
        v[0] = 0;
        z[0] = -INF;
        z[1] = INF;
        f[0] = g[off];
        for (int q = 1, j = 0; q < n; q++) {
            f[q] = g[off + q * stride];
            float s;
            do {
                int r = v[j];
                s = (f[q] - f[r] + (q * q - r * r)) * halfInv[q - r];
            } while (s <= z[j] && --j > -1);
            j++;
            v[j] = q;
            z[j] = s;
            z[j + 1] = INF;
        }
        for (int q = 0, j = 0; q < n; q++) {
            while (z[j + 1] < q) j++;
            int r = v[j];
            float d = q - r;
            g[off + q * stride] = f[r] + d * d;
        }
    }
}
