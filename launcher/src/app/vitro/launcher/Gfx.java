package app.vitro.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;

/**
 * As peças de desenho em OpenGL ES 2: papel de parede, vidro, sombras, blocos e texto.
 *
 * O que torna isto leve numa GPU fraca:
 *  - o papel de parede e o desfoque são gerados uma única vez por troca de fundo;
 *  - o fundo é uma cópia simples de textura; o shader caro do vidro roda só no retângulo de cada peça;
 *  - sombras são calculadas pela distância, em malhas que evitam a área escondida sob o vidro.
 */
final class Gfx {
    static final int WALL = 0, SHADOW = 1, GLASS = 2, GLOW = 3, RRECT = 4, TILE = 5, SOFT = 6, SPRITE = 7, MASK = 8,
            CLOCK = 9, KINDS = 10;
    private static final String[] FRAG = {"blit", "shadow", "glass", "glow", "rrect", "tile", "soft", "sprite", "mask", "clock"};

    private static final class Prog {
        int id;
        final HashMap<String, Integer> locs = new HashMap<>();

        int u(String name) {
            Integer l = locs.get(name);
            if (l == null) {
                l = GLES20.glGetUniformLocation(id, name);
                locs.put(name, l);
            }
            return l;
        }
    }

    private final Context ctx;
    int w = 1, h = 1;
    /** Pixels por unidade do espaço de desenho de 960 x 540. */
    float k = 1f;
    private int bw = 1, bh = 1;
    private Prog pWall, pBlur, pPhoto, pCore, pSoftTex, pClockBake;
    private int clockFbo;
    private final Prog[] prog = new Prog[KINDS];
    private final int[] tex = new int[3], fbo = new int[3];   // 0 papel de parede, 1 e 2 desfoque
    private int blurTex, vbo;
    private Prog bound;
    private int blend = -1;
    private final FloatBuffer mesh = ByteBuffer.allocateDirect(96 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private int wallKind = 3;
    private String wallAsset;
    boolean wallDirty = true;
    /** Sobe a cada papel de parede novo, para o redesenho parcial perceber a troca. */
    int wallVersion;

    Gfx(Context c) {
        ctx = c.getApplicationContext();
    }

    /** Compila os programas. Chamado uma vez por contexto de OpenGL. */
    void init() {
        String common = asset("shaders/common.glsl");
        String quadVs = asset("shaders/quad.vert");
        String bakeVs = "attribute vec2 a;void main(){gl_Position=vec4(a*2.0-1.0,0.0,1.0);}";
        pWall = program(bakeVs, asset("shaders/wall.frag"));
        pBlur = program(bakeVs, asset("shaders/blur.frag"));
        pPhoto = program(bakeVs, asset("shaders/photo.frag"));
        for (int i = 0; i < KINDS; i++) {
            boolean sh = i == SHADOW;
            prog[i] = program(sh ? asset("shaders/shadow.vert") : quadVs, (sh ? "" : common) + asset("shaders/" + FRAG[i] + ".frag"));
        }
        pCore = program(quadVs, common + asset("shaders/core.frag"));
        pSoftTex = program(quadVs, common + asset("shaders/softtex.frag"));
        pClockBake = program(asset("shaders/clockbake.vert"), common + asset("shaders/clockglass.frag"));
        clockFbo = 0;
        int[] b = new int[1];
        GLES20.glGenBuffers(1, b, 0);
        vbo = b[0];
        FloatBuffer q = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        q.put(new float[] {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f}).position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 32, q, GLES20.GL_STATIC_DRAW);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_DITHER);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        tex[0] = 0;
        bound = null;
        blend = -1;
        wallDirty = true;
    }

    void resize(int width, int height) {
        w = Math.max(1, width);
        h = Math.max(1, height);
        k = w / 960f;
        bw = (w + 3) / 4;
        bh = (h + 3) / 4;
        if (tex[0] != 0) {
            GLES20.glDeleteTextures(3, tex, 0);
            GLES20.glDeleteFramebuffers(3, fbo, 0);
        }
        GLES20.glGenTextures(3, tex, 0);
        GLES20.glGenFramebuffers(3, fbo, 0);
        for (int i = 0; i < 3; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, i == 0 ? w : bw, i == 0 ? h : bh, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            params();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[i], 0);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        wallDirty = true;
    }

    /** kind 0..3 escolhe um fundo gerado por código; asset, quando não nulo, é uma foto em assets/. */
    void setWallpaper(int kind, String asset) {
        wallKind = kind;
        wallAsset = asset;
        wallDirty = true;
    }

    /** Gera o papel de parede na textura 0 e o desfoque dele, uma vez só por troca. */
    void bake() {
        wallDirty = false;
        wallVersion++;
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        blend = -1;
        bound = null;
        Bitmap bmp = wallAsset == null ? null : decode(wallAsset);
        if (bmp != null) {
            int t = texture(bmp);
            float ia = bmp.getWidth() / (float) bmp.getHeight(), va = w / (float) h;
            bmp.recycle();
            target(fbo[0], w, h);
            GLES20.glUseProgram(pPhoto.id);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t);
            GLES20.glUniform1i(pPhoto.u("u_tex"), 0);
            GLES20.glUniform2f(pPhoto.u("u_size"), w, h);
            GLES20.glUniform2f(pPhoto.u("u_fit"), ia > va ? va / ia : 1f, ia > va ? 1f : ia / va);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            delete(t);
        } else {
            target(fbo[0], w, h);
            GLES20.glUseProgram(pWall.id);
            GLES20.glUniform2f(pWall.u("u_res"), w, h);
            GLES20.glUniform1i(pWall.u("u_kind"), wallKind);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
        // Reduz para um quarto e passa o desfoque quatro vezes, alternando horizontal e vertical.
        float[][] steps = {{0, 0}, {1, 0}, {0, 1}, {2, 0}, {0, 2}, {3, 0}, {0, 3}, {4, 0}, {0, 4}};
        GLES20.glUseProgram(pBlur.id);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glUniform1i(pBlur.u("u_tex"), 0);
        GLES20.glUniform2f(pBlur.u("u_size"), bw, bh);
        int src = 0, dst = 1;
        for (float[] s : steps) {
            target(fbo[dst], bw, bh);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[src]);
            GLES20.glUniform2f(pBlur.u("u_step"), s[0] / bw, s[1] / bh);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            src = dst;
            dst = dst == 1 ? 2 : 1;
        }
        blurTex = tex[src];
        target(0, w, h);
    }

    // ---- Quadro ------------------------------------------------------------------------------

    /** Prepara o quadro: tela inteira (limpa) ou só uma região (o resto do buffer é preservado). */
    void begin(boolean full, float x0, float y0, float x1, float y1) {
        target(0, w, h);
        if (full) {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        } else {
            scissor(x0, y0, x1, y1);
        }
    }

    /** Limita o desenho a um retângulo em px (y para baixo). */
    void scissor(float x0, float y0, float x1, float y1) {
        int ix0 = Math.max(0, (int) Math.floor(x0)), iy0 = Math.max(0, (int) Math.floor(y0));
        int ix1 = Math.min(w, (int) Math.ceil(x1)), iy1 = Math.min(h, (int) Math.ceil(y1));
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glScissor(ix0, h - iy1, Math.max(0, ix1 - ix0), Math.max(0, iy1 - iy0));
    }

    void noScissor() {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }

    void draw(Painter.Item it) {
        final float[] p = it.p;
        Prog g = use(it.kind);
        switch (it.kind) {
            case WALL:
                blend(0);
                bind(0, tex[0]);
                GLES20.glUniform1i(g.u("u_wall"), 0);
                GLES20.glUniform1f(g.u("u_mul"), p[0]);
                quad(g, 0f, 0f, w, h);
                break;
            case SHADOW:
                blend(2);
                GLES20.glUniform4f(g.u("u_shape"), p[0], p[1], p[2], p[3]);
                GLES20.glUniform1f(g.u("u_r"), p[4]);
                GLES20.glUniform3f(g.u("u_k"), p[5], p[6], p[4]);
                shadowMesh(p[2], p[3], p[4], p[7], p[8], p[9]);
                break;
            case GLASS:
                glass(it);
                break;
            case GLOW:
                blend(3);
                GLES20.glUniform1f(g.u("u_px"), k);
                GLES20.glUniform4f(g.u("u_shape"), p[0], p[1], p[2], p[3]);
                GLES20.glUniform1f(g.u("u_r"), p[4]);
                GLES20.glUniform3f(g.u("u_glow"), p[5], p[6], p[7]);
                GLES20.glUniform3f(g.u("u_col"), p[8], p[9], p[10]);
                quad(g, it.x0, it.y0, it.x1 - it.x0, it.y1 - it.y0);
                break;
            case RRECT:
                blend(1);
                GLES20.glUniform4f(g.u("u_shape"), p[0], p[1], p[2], p[3]);
                GLES20.glUniform1f(g.u("u_r"), p[4]);
                GLES20.glUniform4f(g.u("u_color"), p[5], p[6], p[7], p[8]);
                quad(g, it.x0, it.y0, it.x1 - it.x0, it.y1 - it.y0);
                break;
            case TILE:
                blend(1);
                bind(0, it.tex);
                GLES20.glUniform1i(g.u("u_tex"), 0);
                GLES20.glUniform4f(g.u("u_shape"), p[0], p[1], p[2], p[3]);
                GLES20.glUniform1f(g.u("u_r"), p[4]);
                GLES20.glUniform1f(g.u("u_alpha"), p[5]);
                quad(g, it.x0, it.y0, it.x1 - it.x0, it.y1 - it.y0);
                break;
            case SOFT:
                blend(1);
                if (it.tex != 0) {
                    // Halo com as cores do banner.
                    Prog t = pSoftTex;
                    GLES20.glUseProgram(t.id);
                    GLES20.glUniform2f(t.u("u_res"), w, h);
                    bound = t;
                    bind(0, it.tex);
                    GLES20.glUniform1i(t.u("u_tex"), 0);
                    GLES20.glUniform4f(t.u("u_shape"), p[0], p[1], p[2], p[3]);
                    GLES20.glUniform1f(t.u("u_r"), p[4]);
                    GLES20.glUniform1f(t.u("u_sigma"), p[5]);
                    GLES20.glUniform1f(t.u("u_alpha"), p[9]);
                    quad(t, it.x0, it.y0, it.x1 - it.x0, it.y1 - it.y0);
                    break;
                }
                GLES20.glUniform4f(g.u("u_shape"), p[0], p[1], p[2], p[3]);
                GLES20.glUniform1f(g.u("u_r"), p[4]);
                GLES20.glUniform1f(g.u("u_sigma"), p[5]);
                GLES20.glUniform4f(g.u("u_color"), p[6], p[7], p[8], p[9]);
                quad(g, it.x0, it.y0, it.x1 - it.x0, it.y1 - it.y0);
                break;
            case SPRITE:
                blend(1);
                bind(0, it.tex);
                GLES20.glUniform1i(g.u("u_tex"), 0);
                GLES20.glUniform1f(g.u("u_alpha"), p[4]);
                quad(g, p[0], p[1], p[2], p[3]);
                break;
            case MASK:
                blend(1);
                bind(0, it.tex);
                GLES20.glUniform1i(g.u("u_tex"), 0);
                GLES20.glUniform4f(g.u("u_color"), p[4], p[5], p[6], p[7]);
                quad(g, p[0], p[1], p[2], p[3]);
                break;
            case CLOCK:
                blend(1);
                bind(0, it.tex);
                GLES20.glUniform1i(g.u("u_tex"), 0);
                GLES20.glUniform2f(g.u("u_k"), p[4], p[5]);
                quad(g, p[0], p[1], p[2], p[3]);
                break;
            default:
                break;
        }
    }

    /**
     * Uma peça de vidro. Quando é opaca, o miolo (mais longe da borda do que a largura da refração) vai por
     * um shader curto e sem mistura; o shader completo roda só na moldura. O resultado é o mesmo pixel a pixel.
     */
    private void glass(Painter.Item it) {
        final float[] p = it.p;
        float cx = p[0], cy = p[1], hw = p[2], hh = p[3], r = p[4], b = p[5];
        // Miolo em cruz: um retângulo central e, se o canto for maior que a borda, duas tiras laterais.
        float ax = hw - Math.max(r, b), ay = hh - b, bx = hw - b, by = hh - r;
        boolean core = p[12] >= 1f && ax > 1f && ay > 1f;
        boolean strips = core && r > b && by > 1f;
        if (core) {
            Prog c = pCore;
            if (bound != c) {
                GLES20.glUseProgram(c.id);
                GLES20.glUniform2f(c.u("u_res"), w, h);
                bound = c;
            }
            blend(0);
            bind(1, blurTex);
            bind(0, tex[0]);
            GLES20.glUniform1i(c.u("u_wall"), 0);
            GLES20.glUniform1i(c.u("u_blur"), 1);
            float frost = p[7], keep = (1f - p[10]) * (1f - p[8]);
            GLES20.glUniform4f(c.u("u_c"), (1f - frost) * keep, frost * keep, p[8], p[13]);
            GLES20.glUniform2f(c.u("u_y"), cy - hh, 1f / (1.2f * hh));
            quad(c, cx - ax, cy - ay, 2f * ax, 2f * ay);
            if (strips) {
                quad(c, cx - bx, cy - by, bx - ax, 2f * by);
                quad(c, cx + ax, cy - by, bx - ax, 2f * by);
            }
        }
        Prog g = use(GLASS);
        blend(1);
        bind(1, blurTex);
        bind(0, tex[0]);
        GLES20.glUniform1i(g.u("u_wall"), 0);
        GLES20.glUniform1i(g.u("u_blur"), 1);
        GLES20.glUniform2f(g.u("u_inv"), 1f / w, -1f / h);
        GLES20.glUniform1f(g.u("u_px"), k);
        GLES20.glUniform4f(g.u("u_shape"), cx, cy, hw, hh);
        GLES20.glUniform4f(g.u("u_g1"), r, b, p[6], p[7]);
        GLES20.glUniform4f(g.u("u_g2"), p[8], p[9], p[10], p[11]);
        GLES20.glUniform2f(g.u("u_g3"), p[12], p[13]);
        if (!core) {
            quad(g, it.x0, it.y0, it.x1 - it.x0, it.y1 - it.y0);
            return;
        }
        // Moldura: tudo o que sobra do retângulo da peça depois de tirar o miolo.
        float x0 = it.x0, y0 = it.y0, x1 = it.x1, y1 = it.y1;
        quad(g, x0, y0, x1 - x0, (cy - ay) - y0);
        quad(g, x0, cy + ay, x1 - x0, y1 - (cy + ay));
        if (strips) {
            quad(g, x0, cy - ay, (cx - bx) - x0, 2f * ay);
            quad(g, cx + bx, cy - ay, x1 - (cx + bx), 2f * ay);
            quad(g, cx - bx, cy - ay, bx - ax, ay - by);
            quad(g, cx - bx, cy + by, bx - ax, ay - by);
            quad(g, cx + ax, cy - ay, bx - ax, ay - by);
            quad(g, cx + ax, cy + by, bx - ax, ay - by);
        } else {
            quad(g, x0, cy - ay, (cx - ax) - x0, 2f * ay);
            quad(g, cx + ax, cy - ay, x1 - (cx + ax), 2f * ay);
        }
    }

    /**
     * Malha da sombra: oito retângulos em volta da peça. Ficam de fora o miolo e a faixa do meio que o
     * vidro cobre (entre holeTop e holeBottom), onde a sombra seria calculada à toa.
     */
    private void shadowMesh(float hw, float hh, float r, float m, float holeTop, float holeBottom) {
        float x0 = -(hw + m), x1 = -(hw - r), x2 = hw - r, x3 = hw + m;
        float y0 = -(hh + m), y1 = -(hh - r), y2 = hh - r, y3 = hh + m;
        mesh.clear();
        int n = 0;
        n += rect(x0, y0, x1, y1) + rect(x0, y1, x1, y2) + rect(x0, y2, x1, y3);
        n += rect(x2, y0, x3, y1) + rect(x2, y1, x3, y2) + rect(x2, y2, x3, y3);
        n += rect(x1, y0, x2, Math.min(holeTop, y1)) + rect(x1, Math.max(holeBottom, y2), x2, y3);
        if (n == 0) return;
        mesh.position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, mesh);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, n);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);
    }

    private int rect(float x0, float y0, float x1, float y1) {
        if (x1 <= x0 || y1 <= y0) return 0;
        mesh.put(x0).put(y0).put(x1).put(y0).put(x0).put(y1);
        mesh.put(x1).put(y0).put(x1).put(y1).put(x0).put(y1);
        return 6;
    }

    private Prog use(int kind) {
        Prog p = prog[kind];
        if (bound != p) {
            GLES20.glUseProgram(p.id);
            GLES20.glUniform2f(p.u("u_res"), w, h);
            bound = p;
        }
        return p;
    }

    /** 0 sem mistura, 1 cor já multiplicada pelo alfa, 2 multiplica o que está embaixo, 3 soma. */
    private void blend(int mode) {
        if (blend == mode) return;
        blend = mode;
        if (mode == 0) {
            GLES20.glDisable(GLES20.GL_BLEND);
            return;
        }
        GLES20.glEnable(GLES20.GL_BLEND);
        if (mode == 1) GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        else if (mode == 2) GLES20.glBlendFunc(GLES20.GL_ZERO, GLES20.GL_SRC_COLOR);
        else GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
    }

    private void bind(int unit, int texture) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
    }

    private void quad(Prog p, float x, float y, float qw, float qh) {
        GLES20.glUniform4f(p.u("u_rect"), x, y, qw, qh);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void target(int framebuffer, int width, int height) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glViewport(0, 0, width, height);
    }

    // ---- Texturas ----------------------------------------------------------------------------

    /** Textura colorida a partir de um bitmap (cores já multiplicadas pelo alfa, como o Android guarda). */
    int texture(Bitmap b) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        params();
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        return t[0];
    }

    /** Textura de um canal só (cobertura), tirada do alfa do bitmap: um quarto da memória. */
    int alphaTexture(Bitmap b) {
        int bwid = b.getWidth(), bhei = b.getHeight();
        int[] px = new int[bwid * bhei];
        b.getPixels(px, 0, bwid, 0, 0, bwid, bhei);
        ByteBuffer buf = ByteBuffer.allocateDirect(px.length);
        for (int v : px) buf.put((byte) (v >>> 24));
        buf.position(0);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        params();
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, bwid, bhei, 0, GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, buf);
        return t[0];
    }

    void delete(int texture) {
        if (texture != 0) GLES20.glDeleteTextures(1, new int[] {texture}, 0);
    }

    // ---- Relógio de vidro --------------------------------------------------------------------

    /** Textura da forma do relógio (ver GlassClock). Reaproveita a anterior quando o tamanho é o mesmo. */
    int clockShape(int old, int oldW, int oldH, ByteBuffer rgba, int tw, int th) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        if (old != 0 && oldW == tw && oldH == th) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, old);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, tw, th, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba);
            return old;
        }
        delete(old);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        params();
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, tw, th, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba);
        return t[0];
    }

    /**
     * Desenha o vidro do relógio na textura dele (criada aqui se cache for 0), a partir da forma e do papel de
     * parede atual. Roda uma vez por minuto ou por troca de fundo; a cada quadro o relógio é só essa textura.
     * x e y: canto superior esquerdo do relógio na tela (px); top e bottom: alto e base dos algarismos (0..1).
     */
    int bakeClock(int shape, int cache, int tw, int th, float x, float y, float top, float bottom) {
        if (cache == 0) {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            cache = t[0];
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cache);
            params();
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, tw, th, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        }
        if (clockFbo == 0) {
            int[] f = new int[1];
            GLES20.glGenFramebuffers(1, f, 0);
            clockFbo = f[0];
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, clockFbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, cache, 0);
        GLES20.glViewport(0, 0, tw, th);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        blend(0);
        Prog p = pClockBake;
        GLES20.glUseProgram(p.id);
        bound = p;
        GLES20.glUniform2f(p.u("u_res"), w, h);
        bind(2, shape);
        bind(1, blurTex);
        bind(0, tex[0]);
        GLES20.glUniform1i(p.u("u_wall"), 0);
        GLES20.glUniform1i(p.u("u_blur"), 1);
        GLES20.glUniform1i(p.u("u_sdf"), 2);
        GLES20.glUniform2f(p.u("u_inv"), 1f / w, -1f / h);
        GLES20.glUniform2f(p.u("u_texel"), 1f / tw, 1f / th);
        GLES20.glUniform2f(p.u("u_range"), GlassClock.FINE, GlassClock.COARSE);
        GLES20.glUniform4f(p.u("u_g1"), GlassClock.BEZEL * k, GlassClock.REFR * k, GlassClock.FROST, GlassClock.TINT);
        GLES20.glUniform4f(p.u("u_g2"), GlassClock.SPEC, GlassClock.DISP, GlassClock.EDGE, k);
        GLES20.glUniform3f(p.u("u_sh"), GlassClock.SHADOW, GlassClock.SHADOW_SIGMA * k, GlassClock.SHADOW_DY * k);
        GLES20.glUniform2f(p.u("u_band"), top, bottom);
        quad(p, x, y, tw, th);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, w, h);
        return cache;
    }

    private Bitmap decode(String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            Log.w("Vitro", "papel de parede " + name + ": " + e);
            return null;
        }
    }

    private static void params() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    // ---- Programas ---------------------------------------------------------------------------

    private Prog program(String vs, String fs) {
        Prog p = new Prog();
        p.id = GLES20.glCreateProgram();
        GLES20.glAttachShader(p.id, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p.id, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glBindAttribLocation(p.id, 0, "a");
        GLES20.glLinkProgram(p.id);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p.id, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) Log.e("Vitro", "link: " + GLES20.glGetProgramInfoLog(p.id));
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.e("Vitro", "shader: " + GLES20.glGetShaderInfoLog(s) + "\n" + src);
        return s;
    }

    private String asset(String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            Log.e("Vitro", "shader " + name + ": " + e);
            return "precision mediump float;void main(){gl_FragColor=vec4(0.0,0.0,0.0,1.0);}";
        }
    }
}
