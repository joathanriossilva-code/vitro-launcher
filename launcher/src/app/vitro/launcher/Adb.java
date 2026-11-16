package app.vitro.launcher;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Cliente mínimo do ADB da própria TV, para rodar um comando com o privilégio do shell. O Fire OS recusa
 * conexões ADB que começam na própria TV, então a conexão passa pelo túnel local do Vitro (ver Tunnel).
 *
 * O Fire OS não deixa apps encerrarem processos da Amazon; o shell do ADB pode. Com a depuração ADB
 * ligada, o Vitro gera, na própria TV, uma chave RSA-2048 que nunca sai dela; na primeira conexão a TV pergunta
 * na tela se autoriza essa chave, e só com o "Sempre permitir" de quem usa a TV o Vitro passa a conseguir rodar
 * comandos. Os comandos são fixos, no código.
 *
 * Protocolo (o mesmo do adb do computador): mensagens com cabeçalho de 24 bytes em little-endian
 * (comando, arg0, arg1, tamanho, soma dos bytes, comando ^ 0xffffffff) seguidas do conteúdo.
 * CNXN apresenta; AUTH responde ao desafio da TV assinando-o com a chave (ou, se ela não for conhecida,
 * oferece a chave pública, e a TV mostra o pedido de autorização); OPEN abre "shell:comando"; WRTE traz a
 * saída; CLSE fecha.
 *
 * Revogar: Configurações → Minha Fire TV → Opções do desenvolvedor (revogar autorizações de depuração),
 * ou desligar a depuração ADB.
 */
final class Adb {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    /** A TV pede TLS (depuração sem fio do Android 11 em diante; ver AdbTls). */
    private static final int STLS = 0x534c5453, STLS_VERSION = 0x01000000;
    private static final int VERSION = 0x01000000, MAX_PAYLOAD = 4096;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** Prefixo DigestInfo do SHA-1 (PKCS#1): a TV confere a assinatura como se o desafio fosse um SHA-1. */
    private static final byte[] SHA1_INFO = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
            0x05, 0x00, 0x04, 0x14};
    private static final String KEY_NAME = " vitro@firetv";

    /** A TV não conhece a chave do Vitro (ou ninguém autorizou a tempo). */
    static final class NotAuthorized extends IOException {
        NotAuthorized(String m) { super(m); }
    }

    private Adb() {}

    // ---- No aparelho -------------------------------------------------------------------------

    /**
     * Roda um comando de shell na própria TV e devolve a saída. Com offerKey, se a TV ainda não conhece a
     * chave, mostra o pedido de autorização na tela e espera a resposta por até approvalMs.
     */
    static String run(Context c, String host, android.net.Network net, String command, boolean offerKey,
            long approvalMs) throws IOException, GeneralSecurityException {
        return run(c, host, 5555, net, command, offerKey, approvalMs);
    }

    /** Como o de cima, numa porta escolhida (a da depuração sem fio, por exemplo; ver AdbTls). */
    static String run(Context c, String host, int port, android.net.Network net, String command, boolean offerKey,
            long approvalMs) throws IOException, GeneralSecurityException {
        PrivateKey key = privateKey(c);
        byte[] pub = (publicKeyText(c) + "\0").getBytes("UTF-8");
        IOException last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            Socket s = new Socket();
            try {
                // Presa à rede do túnel: sem isso, logo depois de a VPN subir a conexão pode sair pela rede comum.
                if (net != null && android.os.Build.VERSION.SDK_INT >= 23) net.bindSocket(s);
                s.connect(new InetSocketAddress(host, port), 3000);
            } catch (IOException x) {
                try { s.close(); } catch (IOException ignored) { }
                last = x;
                // Nos primeiros instantes da VPN a rota dela ainda não existe (ENETUNREACH): espera e tenta de novo.
                String m = String.valueOf(x.getMessage());
                if (m.contains("ENETUNREACH") || m.contains("unreachable")) {
                    android.os.SystemClock.sleep(200);
                    continue;
                }
                throw x;
            }
            try (Socket conn = s) {
                Conn k = handshake(c, conn, host, port, key, offerKey ? pub : null, approvalMs);
                try {
                    return shell(k, command);
                } finally {
                    if (k.socket != conn) try { k.socket.close(); } catch (IOException ignored) { }
                }
            }
        }
        throw last;
    }

    /** Recebe, linha a linha, a saída de um comando que não termina sozinho (o logcat). */
    interface Lines {
        void line(String s);
    }

    /**
     * Roda no shell da TV um comando longo (o logcat) e entrega cada linha da saída, até o comando acabar ou quem
     * chamou fechar a conexão (o Socket fica em holder para isso). Só com a chave já autorizada: não mostra pedido
     * na tela. Usado do Android 13 em diante, onde um app em segundo plano não lê mais o registro do sistema por
     * conta própria, mas o shell do ADB lê (ver HomeService.watchLog).
     */
    static void stream(Context c, String host, int port, android.net.Network net, String command, Lines out,
            java.util.concurrent.atomic.AtomicReference<Socket> holder) throws IOException, GeneralSecurityException {
        PrivateKey key = privateKey(c);
        Socket s = new Socket();
        holder.set(s);
        Conn k = null;
        try (Socket conn = s) {
            if (net != null && android.os.Build.VERSION.SDK_INT >= 23) net.bindSocket(conn);
            conn.connect(new InetSocketAddress(host, port), 3000);
            k = handshake(c, conn, host, port, key, null, 0L);
            holder.set(k.socket);
            k.socket.setSoTimeout(0);   // o logcat pode passar muito tempo calado
            final int local = 1;
            send(k.out, OPEN, local, 0, ("shell:" + command + "\0").getBytes("UTF-8"));
            StringBuilder pending = new StringBuilder();
            while (true) {
                Msg m = read(k.in);
                if (m.cmd == WRTE) {
                    send(k.out, OKAY, local, m.arg0, new byte[0]);
                    pending.append(new String(m.data, "UTF-8"));
                    for (int nl; (nl = pending.indexOf("\n")) >= 0; ) {
                        String line = pending.substring(0, nl);
                        pending.delete(0, nl + 1);
                        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                        out.line(line);
                    }
                } else if (m.cmd == CLSE) {
                    return;
                } else if (m.cmd != OKAY) {
                    throw new IOException("resposta inesperada da TV");
                }
            }
        } finally {
            if (k != null && k.socket != s) try { k.socket.close(); } catch (IOException ignored) { }
            holder.set(null);
        }
    }

    /** As duas pontas da conexão depois da apresentação: viram TLS se a TV pedir (depuração sem fio). */
    private static final class Conn {
        Socket socket;
        DataInputStream in;
        OutputStream out;
    }

    /**
     * Apresenta-se e autentica. Na porta 5555, a TV manda um desafio (AUTH) que o Vitro assina com a chave; se ela
     * não conhecer a chave e pubKeyPayload vier, o Vitro a oferece e a TV mostra o pedido de autorização. Na porta da
     * depuração sem fio, a TV responde STLS: os dois passam para TLS 1.3 e o certificado do Vitro (da chave pareada)
     * substitui o desafio.
     */
    private static Conn handshake(Context c, Socket s, String host, int port, PrivateKey key, byte[] pubKeyPayload,
            long approvalMs) throws IOException, GeneralSecurityException {
        s.setSoTimeout(5000);
        Conn k = new Conn();
        k.socket = s;
        k.in = new DataInputStream(s.getInputStream());
        k.out = s.getOutputStream();
        send(k.out, CNXN, VERSION, MAX_PAYLOAD, "host::\0".getBytes("UTF-8"));
        boolean signed = false, offered = false, tls = false;
        while (true) {
            Msg m;
            try {
                m = read(k.in);
            } catch (javax.net.ssl.SSLException x) {
                if (tls) throw new NotAuthorized("a TV não reconhece o pareamento do Vitro (" + x.getMessage() + ")");
                throw x;
            }
            if (m.cmd == CNXN) break;
            if (m.cmd == STLS && !tls) {
                send(k.out, STLS, STLS_VERSION, 0, new byte[0]);
                javax.net.ssl.SSLSocket t;
                try {
                    t = AdbTls.wrap(c, s, host, port);
                } catch (javax.net.ssl.SSLException x) {
                    throw new NotAuthorized("a TV não reconhece o pareamento do Vitro (" + x.getMessage() + ")");
                } catch (IOException | GeneralSecurityException x) {
                    throw x;
                } catch (Exception x) {
                    throw new IOException(x);
                }
                t.setSoTimeout(5000);
                k.socket = t;
                k.in = new DataInputStream(t.getInputStream());
                k.out = t.getOutputStream();
                tls = true;
                continue;
            }
            if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("resposta inesperada da TV");
            if (!signed) {
                send(k.out, AUTH, AUTH_SIGNATURE, 0, sign(key, m.data));
                signed = true;
            } else if (pubKeyPayload != null && !offered) {
                // A TV não conhece a chave: mostra o pedido de autorização e espera a resposta.
                send(k.out, AUTH, AUTH_RSAPUBLICKEY, 0, pubKeyPayload);
                offered = true;
                k.socket.setSoTimeout((int) Math.max(5000L, approvalMs));
            } else {
                throw new NotAuthorized("a TV não autorizou a chave do Vitro");
            }
        }
        return k;
    }

    private static String shell(Conn k, String command) throws IOException {
        k.socket.setSoTimeout(15000);
        final int local = 1;
        send(k.out, OPEN, local, 0, ("shell:" + command + "\0").getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        while (true) {
            Msg m = read(k.in);
            if (m.cmd == WRTE) {
                sb.append(new String(m.data, "UTF-8"));
                send(k.out, OKAY, local, m.arg0, new byte[0]);
            } else if (m.cmd == CLSE) {
                send(k.out, CLSE, local, m.arg0, new byte[0]);
                return sb.toString();
            } else if (m.cmd != OKAY) {
                throw new IOException("resposta inesperada da TV");
            }
        }
    }

    /** Impressão digital que a TV mostra no pedido de autorização (MD5 da chave, em hexadecimal). */
    static String fingerprint(Context c) throws IOException, GeneralSecurityException {
        String text = publicKeyText(c);
        byte[] raw = Base64.decode(text.substring(0, text.indexOf(' ')), Base64.DEFAULT);
        byte[] md5 = MessageDigest.getInstance("MD5").digest(raw);
        StringBuilder sb = new StringBuilder();
        for (byte b : md5) {
            if (sb.length() > 0) sb.append(':');
            sb.append(String.format("%02X", b & 0xff));
        }
        return sb.toString();
    }

    private static File privFile(Context c) { return new File(c.getFilesDir(), "adbkey"); }
    private static File pubFile(Context c) { return new File(c.getFilesDir(), "adbkey.pub"); }

    /** Chave do Vitro: criada na primeira vez (leva uns segundos) e guardada só no armazenamento do app. */
    static synchronized PrivateKey privateKey(Context c) throws IOException, GeneralSecurityException {
        File priv = privFile(c), pub = pubFile(c);
        if (!priv.exists() || !pub.exists()) {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            String text = Base64.encodeToString(publicKeyBytes((RSAPublicKey) kp.getPublic()), Base64.NO_WRAP) + KEY_NAME;
            write(pub, text.getBytes("UTF-8"));
            write(priv, kp.getPrivate().getEncoded());
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(readAll(priv)));
    }

    static synchronized String publicKeyText(Context c) throws IOException, GeneralSecurityException {
        if (!pubFile(c).exists()) privateKey(c);
        return new String(readAll(pubFile(c)), "UTF-8").trim();
    }

    private static void write(File f, byte[] data) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(data);
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    // ---- Protocolo (sem nada do Android: testado do computador contra a TV) --------------------

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        b.getInt();
        if (b.getInt() != ~m.cmd) throw new IOException("cabeçalho inválido");
        if (len < 0 || len > (1 << 20)) throw new IOException("tamanho inválido");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    /** Assinatura PKCS#1 v1.5 do desafio, tratado como um resumo SHA-1 (é como o adbd confere). */
    static byte[] sign(PrivateKey key, byte[] token) throws GeneralSecurityException {
        byte[] m = new byte[256];
        int ps = m.length - 3 - SHA1_INFO.length - token.length;
        m[0] = 0;
        m[1] = 1;
        for (int i = 0; i < ps; i++) m[2 + i] = (byte) 0xff;
        m[2 + ps] = 0;
        System.arraycopy(SHA1_INFO, 0, m, 3 + ps, SHA1_INFO.length);
        System.arraycopy(token, 0, m, 3 + ps + SHA1_INFO.length, token.length);
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key);
        return c.doFinal(m);
    }

    /**
     * Chave pública no formato do adb (a estrutura RSAPublicKey do Android, 524 bytes): tamanho em palavras,
     * n0inv = -1/n mod 2^32, módulo e R² mod n (R = 2^2048) em little-endian, expoente.
     */
    static byte[] publicKeyBytes(RSAPublicKey k) {
        BigInteger n = k.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.putInt(n0inv.intValue());
        b.put(littleEndian(n, 256));
        b.put(littleEndian(rr, 256));
        b.putInt(k.getPublicExponent().intValue());
        return b.array();
    }

    private static byte[] littleEndian(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] le = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) le[i] = be[be.length - 1 - i];
        return le;
    }
}
