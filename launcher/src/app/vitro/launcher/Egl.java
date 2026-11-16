package app.vitro.launcher;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.util.Log;
import android.view.Surface;

/**
 * OpenGL direto na superfície da própria janela, tudo na thread principal.
 *
 * O launcher toma a superfície da janela para si (Window.takeSurface), então não existe uma segunda
 * superfície nem o desenhista de Views do Android: é uma camada só para o compositor e menos memória de vídeo.
 */
final class Egl {
    private static final int EGL_BUFFER_AGE_EXT = 0x313D;

    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLConfig cfg;
    private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surf = EGL14.EGL_NO_SURFACE;
    private boolean hasAge;
    /** Muda a cada contexto novo: texturas criadas em contextos anteriores deixam de valer. */
    int generation;
    /** Erro do último eglSwapBuffers que falhou; 0 quando o último deu certo. */
    int swapError;

    boolean hasContext() { return ctx != EGL14.EGL_NO_CONTEXT; }
    boolean hasSurface() { return surf != EGL14.EGL_NO_SURFACE; }

    /** Cria o contexto se ainda não existe. Devolve true quando criou um novo. */
    boolean ensureContext() {
        if (hasContext()) return false;
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        if (!EGL14.eglInitialize(dpy, v, 0, v, 1)) throw new IllegalStateException("eglInitialize falhou");
        int[] want = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE};
        EGLConfig[] list = new EGLConfig[64];
        int[] n = new int[1];
        if (!EGL14.eglChooseConfig(dpy, want, 0, list, 0, list.length, n, 0) || n[0] == 0) {
            throw new IllegalStateException("nenhuma configuração de EGL serve");
        }
        // A janela é opaca e não usa profundidade: prefere a configuração mais enxuta (8-8-8, sem alfa,
        // sem profundidade, sem estêncil, sem multiamostragem).
        cfg = list[0];
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < n[0]; i++) {
            if (attr(list[i], EGL14.EGL_RED_SIZE) != 8 || attr(list[i], EGL14.EGL_GREEN_SIZE) != 8 || attr(list[i], EGL14.EGL_BLUE_SIZE) != 8) continue;
            int cost = attr(list[i], EGL14.EGL_ALPHA_SIZE) * 4 + attr(list[i], EGL14.EGL_DEPTH_SIZE) + attr(list[i], EGL14.EGL_STENCIL_SIZE)
                    + attr(list[i], EGL14.EGL_SAMPLES) * 100;
            if (cost < best) { best = cost; cfg = list[i]; }
        }
        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) {
            ctx = EGL14.EGL_NO_CONTEXT;
            throw new IllegalStateException("eglCreateContext falhou: 0x" + Integer.toHexString(EGL14.eglGetError()));
        }
        String ext = EGL14.eglQueryString(dpy, EGL14.EGL_EXTENSIONS);
        hasAge = ext != null && ext.contains("EGL_EXT_buffer_age");
        generation++;
        return true;
    }

    private int attr(EGLConfig c, int name) {
        int[] v = new int[1];
        return EGL14.eglGetConfigAttrib(dpy, c, name, v, 0) ? v[0] : 0;
    }

    /** Liga o contexto à superfície da janela. */
    boolean createSurface(Surface s) {
        destroySurface();
        try {
            surf = EGL14.eglCreateWindowSurface(dpy, cfg, s, new int[] {EGL14.EGL_NONE}, 0);
        } catch (RuntimeException e) {
            Log.e("Vitro", "eglCreateWindowSurface: " + e);
            surf = EGL14.EGL_NO_SURFACE;
        }
        if (surf == null || surf == EGL14.EGL_NO_SURFACE) {
            surf = EGL14.EGL_NO_SURFACE;
            Log.e("Vitro", "sem superfície de EGL: 0x" + Integer.toHexString(EGL14.eglGetError()));
            return false;
        }
        if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) {
            Log.e("Vitro", "eglMakeCurrent falhou: 0x" + Integer.toHexString(EGL14.eglGetError()));
            // Sem isto, hasSurface() diria que há onde desenhar e nada apareceria.
            EGL14.eglDestroySurface(dpy, surf);
            surf = EGL14.EGL_NO_SURFACE;
            return false;
        }
        EGL14.eglSwapInterval(dpy, 1);
        swapError = 0;
        return true;
    }

    /**
     * Idade do quadro que está no buffer em que vamos desenhar: 1 = o quadro anterior, 2 = o de dois quadros
     * atrás, e assim por diante. 0 = conteúdo indefinido (é preciso redesenhar a tela toda).
     */
    int bufferAge() {
        if (!hasAge || !hasSurface()) return 0;
        int[] v = new int[1];
        return EGL14.eglQuerySurface(dpy, surf, EGL_BUFFER_AGE_EXT, v, 0) ? v[0] : 0;
    }

    boolean swap() {
        if (EGL14.eglSwapBuffers(dpy, surf)) {
            swapError = 0;
            return true;
        }
        swapError = EGL14.eglGetError();
        Log.w("Vitro", "eglSwapBuffers falhou: 0x" + Integer.toHexString(swapError));
        return false;
    }

    /** A superfície (ou o contexto) morreu por baixo, sem aviso da janela: é preciso religar. */
    boolean lost() {
        return swapError == EGL14.EGL_BAD_SURFACE || swapError == EGL14.EGL_BAD_NATIVE_WINDOW
                || swapError == EGL14.EGL_BAD_CURRENT_SURFACE || swapError == EGL14.EGL_CONTEXT_LOST;
    }

    void destroySurface() {
        swapError = 0;
        if (!hasSurface()) return;
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(dpy, surf);
        surf = EGL14.EGL_NO_SURFACE;
    }

    /** Solta o contexto e, com ele, todas as texturas e programas. */
    void destroyContext() {
        destroySurface();
        if (!hasContext()) return;
        EGL14.eglDestroyContext(dpy, ctx);
        ctx = EGL14.EGL_NO_CONTEXT;
        EGL14.eglReleaseThread();
    }
}
