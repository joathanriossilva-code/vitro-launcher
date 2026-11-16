package app.vitro.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;

import java.util.Random;

/**
 * Sons de navegação da tela inicial, família "Toque": feitos do zero por síntese, sem gravação de terceiros
 * (prototipo/gerar-sons.py; prévia em prototipo/sons.html). Quatro sons curtos em res/raw: mover (o foco andou),
 * ok, voltar e limite (tentar passar do fim de uma fileira). Ligam e desligam na linha Sons do menu; desligados de saída.
 *
 * Tocam pelo canal de sons do sistema, baixos, com uma variação leve de tom (±3%) para a navegação longa não soar
 * mecânica. Segurando a seta, o "mover" repete no máximo a cada 60 ms.
 */
final class Sounds {
    static final int MOVE = 0, OK = 1, BACK = 2, EDGE = 3;
    private static final String KEY = "sons";
    private static final float VOLUME = 0.6f;
    private static final long MOVE_GAP_MS = 60L;
    private static final int[] RES = {R.raw.som_mover, R.raw.som_ok, R.raw.som_voltar, R.raw.som_limite};

    private final SharedPreferences prefs;
    private final SoundPool pool;
    private final int[] ids = new int[RES.length];
    private final boolean[] ready = new boolean[RES.length];
    private final Random random = new Random();
    private boolean on;
    private long lastMove;

    Sounds(Context c) {
        prefs = c.getSharedPreferences("vitro", Context.MODE_PRIVATE);
        on = prefs.getBoolean(KEY, false);
        pool = new SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        // Chamado na linha principal, a mesma que toca: cada som só toca depois de carregado.
        pool.setOnLoadCompleteListener((p, id, status) -> {
            for (int i = 0; i < ids.length; i++) if (ids[i] == id) ready[i] = status == 0;
        });
        for (int i = 0; i < RES.length; i++) ids[i] = pool.load(c, RES[i], 1);
    }

    boolean on() {
        return on;
    }

    /** Linha Sons do menu. Ao ligar, o próprio OK já soa; ao desligar, fica em silêncio. */
    void toggle() {
        on = !on;
        prefs.edit().putBoolean(KEY, on).apply();
    }

    void play(int which) {
        if (!on || !ready[which]) return;
        if (which == MOVE) {
            long now = SystemClock.uptimeMillis();
            if (now - lastMove < MOVE_GAP_MS) return;
            lastMove = now;
        }
        pool.play(ids[which], VOLUME, VOLUME, 1, 0, 0.97f + random.nextFloat() * 0.06f);
    }

    void release() {
        pool.release();
    }
}
