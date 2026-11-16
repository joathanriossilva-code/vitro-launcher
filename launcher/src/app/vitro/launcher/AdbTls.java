package app.vitro.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.RSAPublicKeySpec;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * "Depuração sem fio" do Android 11 em diante, para as TVs em que o ADB não escuta na porta 5555 (Android/Google TV
 * 13 e 14). Depois de pareado uma vez (ver AdbPairing), o Vitro conecta no ADB da própria TV por TLS:
 *
 *   - a porta muda a cada vez que a depuração sem fio liga, e a TV a anuncia na rede local (mDNS,
 *     "_adb-tls-connect._tcp"); o Vitro procura só o anúncio do próprio aparelho;
 *   - a conexão começa como a de sempre (CNXN), o ADB responde STLS e os dois passam para TLS 1.3; o Vitro se
 *     apresenta com um certificado feito da mesma chave RSA do ADB (ver Adb), que a TV conhece desde o pareamento;
 *   - a depuração sem fio desliga quando a TV reinicia: com a permissão de mudar ajustes, o Vitro a religa
 *     (adb_wifi_enabled = 1). Na rede em que a pessoa marcou "Sempre permitir", o Android religa sem perguntar.
 */
final class AdbTls {
    private static final String TAG = "VitroHome";
    static final String PAIRED = "adbPareado", PORT = "adbPortaSemFio";
    static final String CONNECT_SERVICE = "_adb-tls-connect._tcp", PAIRING_SERVICE = "_adb-tls-pairing._tcp";
    /** Endereço em que o serviço de pareamento foi anunciado (o da própria TV na rede). */
    private static volatile String pairingHost;

    static String pairingHost() {
        String h = pairingHost;
        return h == null ? HomeService.DIRECT_HOST : h;
    }

    private AdbTls() { }

    static boolean paired(Context c) {
        return prefs(c).getBoolean(PAIRED, false);
    }

    static void setPaired(Context c, boolean on) {
        prefs(c).edit().putBoolean(PAIRED, on).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("vitro", Context.MODE_PRIVATE);
    }

    // ---- Conexão TLS ------------------------------------------------------------------------

    /** Passa uma conexão já aberta para TLS 1.3, apresentando o certificado do Vitro. */
    static SSLSocket wrap(Context c, Socket plain, String host, int port) throws Exception {
        final PrivateKey key = Adb.privateKey(c);
        final X509Certificate cert = certificate(c);
        X509KeyManager km = new X509KeyManager() {
            @Override public String[] getClientAliases(String type, java.security.Principal[] issuers) { return new String[] {"vitro"}; }
            @Override public String chooseClientAlias(String[] type, java.security.Principal[] issuers, Socket s) { return "vitro"; }
            @Override public String[] getServerAliases(String type, java.security.Principal[] issuers) { return null; }
            @Override public String chooseServerAlias(String type, java.security.Principal[] issuers, Socket s) { return null; }
            @Override public X509Certificate[] getCertificateChain(String alias) { return new X509Certificate[] {cert}; }
            @Override public PrivateKey getPrivateKey(String alias) { return key; }
        };
        // O certificado da TV é autoassinado e muda; quem garante a TV é o pareamento (e a conexão é local).
        X509TrustManager any = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String auth) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String auth) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[] {km}, new TrustManager[] {any}, new SecureRandom());
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(plain, host, port, true);
        s.setUseClientMode(true);
        try {
            s.setEnabledProtocols(new String[] {"TLSv1.3"});
        } catch (IllegalArgumentException x) {
            throw new IOException("este Android não tem TLS 1.3");
        }
        s.startHandshake();
        return s;
    }

    /**
     * Porta da depuração sem fio deste aparelho, ou -1. Religa a depuração se preciso (com a permissão de mudar
     * ajustes), tenta a última porta usada e, se ela não responder, procura o anúncio na rede local.
     */
    static int connectPort(Context c) {
        ensureOn(c);
        int last = prefs(c).getInt(PORT, -1);
        if (last > 0 && open(last)) return last;
        int port = discover(c, CONNECT_SERVICE, 8000L);
        if (port > 0) prefs(c).edit().putInt(PORT, port).apply();
        return port;
    }

    /** Religa a depuração sem fio (ela desliga quando a TV reinicia). */
    static void ensureOn(Context c) {
        try {
            if (Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) == 1) return;
            if (!Repair.has(c, Repair.WSS)) return;
            Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 1);
            Log.i(TAG, "depuração sem fio religada");
            SystemClock.sleep(2500L);
        } catch (RuntimeException x) {
            Log.w(TAG, "não consegui religar a depuração sem fio (" + x + ")");
        }
    }

    private static boolean open(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HomeService.DIRECT_HOST, port), 600);
            return true;
        } catch (IOException x) {
            return false;
        }
    }

    /**
     * Procura na rede local o anúncio de um serviço do ADB (conexão ou pareamento) feito por este aparelho e devolve a
     * porta, ou -1 se não aparecer a tempo. Os anúncios de outros aparelhos da casa são ignorados.
     */
    static int discover(Context c, String type, long timeoutMs) {
        NsdManager nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return -1;
        final AtomicInteger port = new AtomicInteger(-1);
        final CountDownLatch done = new CountDownLatch(1);
        final java.util.Set<String> mine = ownAddresses();
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onStartDiscoveryFailed(String t, int e) { done.countDown(); }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onServiceLost(NsdServiceInfo s) { }
            @Override public void onServiceFound(NsdServiceInfo s) {
                try {
                    nsd.resolveService(s, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo i, int e) { }
                        @Override public void onServiceResolved(NsdServiceInfo i) {
                            InetAddress h = i.getHost();
                            if (h != null && (mine.contains(h.getHostAddress()) || h.isLoopbackAddress())) {
                                if (PAIRING_SERVICE.equals(type)) pairingHost = h.getHostAddress();
                                port.compareAndSet(-1, i.getPort());
                                done.countDown();
                            }
                        }
                    });
                } catch (RuntimeException ignored) { }
            }
        };
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception x) {
            Log.w(TAG, "procura de " + type + " falhou (" + x + ")");
        } finally {
            try { nsd.stopServiceDiscovery(listener); } catch (RuntimeException ignored) { }
        }
        if (port.get() > 0) Log.i(TAG, type + " deste aparelho na porta " + port.get());
        return port.get();
    }

    private static java.util.Set<String> ownAddresses() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) out.add(a.getHostAddress());
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ---- Certificado ---------------------------------------------------------------------------

    private static File certFile(Context c) { return new File(c.getFilesDir(), "adbcert.der"); }

    /** Certificado X.509 autoassinado com a chave RSA do ADB do Vitro (criado uma vez e guardado). */
    static synchronized X509Certificate certificate(Context c) throws Exception {
        File f = certFile(c);
        byte[] der = f.exists() ? readAll(f) : null;
        if (der == null) {
            der = build(Adb.privateKey(c), Adb.publicKeyText(c));
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(der); }
        }
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der));
    }

    static byte[] build(PrivateKey key, String adbPublicKey) throws Exception {
        // A chave privada do Android (Conscrypt) não entrega o expoente público; ele sai da chave pública no formato do
        // adb (ver Adb.publicKeyBytes): tamanho, n0inv, módulo e R² em little-endian, expoente.
        byte[] raw = android.util.Base64.decode(adbPublicKey.substring(0, adbPublicKey.indexOf(' ')), android.util.Base64.DEFAULT);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int words = b.getInt();
        b.getInt();
        byte[] modLe = new byte[words * 4];
        b.get(modLe);
        b.position(b.position() + words * 4);
        int exponent = b.getInt();
        byte[] modBe = new byte[modLe.length];
        for (int i = 0; i < modLe.length; i++) modBe[i] = modLe[modLe.length - 1 - i];
        PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(new BigInteger(1, modBe), BigInteger.valueOf(exponent)));
        byte[] sigAlg = seq(cat(new byte[] {0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b},
                new byte[] {0x05, 0x00}));
        byte[] name = seq(tlv(0x31, seq(cat(new byte[] {0x06, 0x03, 0x55, 0x04, 0x03}, tlv(0x0c, "Vitro".getBytes("UTF-8"))))));
        byte[] validity = seq(cat(tlv(0x17, "250101000000Z".getBytes("US-ASCII")), tlv(0x18, "20550101000000Z".getBytes("US-ASCII"))));
        byte[] serial = new byte[8];
        new SecureRandom().nextBytes(serial);
        serial[0] &= 0x7f;
        byte[] tbs = seq(cat(new byte[] {(byte) 0xa0, 0x03, 0x02, 0x01, 0x02}, tlv(0x02, new BigInteger(1, serial).toByteArray()),
                sigAlg, name, validity, name, pub.getEncoded()));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(tbs);
        byte[] signature = sig.sign();
        return seq(cat(tbs, sigAlg, tlv(0x03, cat(new byte[] {0}, signature))));
    }

    private static byte[] seq(byte[] content) { return tlv(0x30, content); }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = content.length;
        if (n < 0x80) {
            o.write(n);
        } else if (n < 0x100) {
            o.write(0x81); o.write(n);
        } else if (n < 0x10000) {
            o.write(0x82); o.write(n >> 8); o.write(n & 0xff);
        } else {
            o.write(0x83); o.write(n >> 16); o.write((n >> 8) & 0xff); o.write(n & 0xff);
        }
        o.write(content, 0, n);
        return o.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
            return o.toByteArray();
        }
    }
}
