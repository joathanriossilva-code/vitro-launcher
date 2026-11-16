package app.vitro.launcher;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Túnel local para o Vitro falar com o ADB da própria TV.
 *
 * O Fire OS recusa conexões ADB que começam na própria TV (medido: de 127.0.0.1 e do endereço Wi-Fi da TV,
 * o adbd fecha sem responder; do computador, responde). Esta "VPN" não leva nada para fora da TV: cria uma
 * rede privada minúscula (10.111.222.0/30), usada só pelo próprio Vitro. O Vitro conecta em
 * 10.111.222.2:5555; o túnel devolve cada pacote para a TV trocando origem e destino. Assim o adbd recebe a
 * conexão vinda de 10.111.222.2, que não é um endereço da TV, e a aceita; a resposta dele volta pelo túnel
 * e é trocada de novo. Trocar origem e destino não altera as somas de verificação (IP e TCP somam os dois
 * endereços), então os pacotes seguem válidos. (Técnica do projeto livre Homestead, testada num Fire TV Stick 3ª geração.)
 *
 * Fica ligado só o tempo de rodar o comando e não derruba outra VPN: se houver uma ativa, não liga.
 */
public final class Tunnel extends VpnService {
    static final String LOCAL = "10.111.222.1", PEER = "10.111.222.2";
    private static final String TAG = "VitroHome";
    private static final Object LOCK = new Object();
    private static Tunnel live;          // protegido por LOCK

    private ParcelFileDescriptor tun;    // protegido por LOCK
    private volatile boolean pumping;

    /** A VPN local do Vitro já foi autorizada (o pedido do Android aparece uma vez só). */
    static boolean allowed(Context c) {
        try {
            return VpnService.prepare(c) == null;
        } catch (RuntimeException x) {
            return false;
        }
    }

    /** Outra VPN (de outro app) ligada agora: o Vitro não liga a sua, para não derrubá-la. */
    static boolean otherVpnActive(Context c) {
        synchronized (LOCK) {
            if (live != null) return false;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (RuntimeException x) {
            Log.w(TAG, "túnel: não deu para conferir outras VPNs (" + x + ")");
        }
        return false;
    }

    /**
     * Liga o túnel e espera ele ficar pronto, inclusive a rede da VPN aparecer para o Android (logo depois
     * de criada, uma conexão ainda pode sair pela rede comum). Fora da linha principal.
     */
    static boolean up(Context c, long timeoutMs) throws InterruptedException {
        c.startService(new Intent(c, Tunnel.class));
        long end = SystemClock.uptimeMillis() + timeoutMs;
        synchronized (LOCK) {
            while (live == null || live.tun == null) {
                long left = end - SystemClock.uptimeMillis();
                if (left <= 0) return false;
                LOCK.wait(left);
            }
        }
        while (network(c) == null && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
        return true;
    }

    /** A rede da VPN local, quando ligada: a conexão do Vitro é presa a ela. */
    static Network network(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return n;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    /**
     * Desliga o túnel e espera a rede da VPN sumir para o Android (até 3 s): sem isso, a conferência de
     * "outra VPN ligada" logo em seguida enxerga a do próprio Vitro, ainda sendo desmontada.
     */
    static void down(Context c) {
        Tunnel t;
        synchronized (LOCK) {
            t = live;
        }
        if (t != null) t.close();
        c.stopService(new Intent(c, Tunnel.class));
        long end = SystemClock.uptimeMillis() + 3000L;
        while (network(c) != null && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (LOCK) {
            if (live == this && tun != null) return START_NOT_STICKY;
        }
        try {
            Builder b = new Builder()
                    .setSession("Vitro: ADB da própria TV")
                    .addAddress(LOCAL, 30)
                    .addRoute("10.111.222.0", 30)
                    .setMtu(1500);
            // Só a sub-rede privada 10.111.222.0/30 passa pelo túnel; nenhum outro tráfego da TV é tocado.
            // Sem restringir a um app: a resposta vem do adbd (outro processo) e precisa voltar pelo túnel.
            ParcelFileDescriptor fd = b.establish();
            if (fd == null) {
                Log.w(TAG, "túnel: a VPN local do Vitro não está autorizada");
                stopSelf();
                return START_NOT_STICKY;
            }
            pumping = true;
            synchronized (LOCK) {
                tun = fd;
                live = this;
                LOCK.notifyAll();
            }
            Thread t = new Thread(() -> pump(fd), "vitro-tunel");
            t.setDaemon(true);
            t.start();
        } catch (Exception x) {
            Log.w(TAG, "túnel não abriu: " + x);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    /** Devolve cada pacote IPv4 para a TV com origem e destino trocados. */
    private void pump(ParcelFileDescriptor fd) {
        FileInputStream in = new FileInputStream(fd.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(fd.getFileDescriptor());
        byte[] p = new byte[32767];
        try {
            while (pumping) {
                // Leitura sem bloqueio: 0 = nenhum pacote agora. O túnel vive cerca de 1 s.
                int n = in.read(p);
                if (n <= 0) {
                    SystemClock.sleep(2);
                    continue;
                }
                if (n < 20 || (p[0] & 0xf0) != 0x40) continue;   // só IPv4
                for (int k = 12; k < 16; k++) {
                    byte t = p[k];
                    p[k] = p[k + 4];
                    p[k + 4] = t;
                }
                out.write(p, 0, n);
            }
        } catch (IOException x) {
            if (pumping) Log.w(TAG, "túnel: " + x);
        }
    }

    private void close() {
        pumping = false;
        ParcelFileDescriptor fd;
        synchronized (LOCK) {
            fd = tun;
            tun = null;
            if (live == this) live = null;
        }
        if (fd != null) {
            SystemClock.sleep(5);   // deixa a bomba sair do laço antes de fechar o descritor
            try { fd.close(); } catch (IOException ignored) { }
        }
    }

    @Override public void onRevoke() {
        close();
        stopSelf();
    }

    @Override public void onDestroy() {
        close();
        super.onDestroy();
    }
}
