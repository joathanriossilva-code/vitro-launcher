package app.vitro.launcher;

import android.content.Context;
import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;

/**
 * Pareamento com a "Depuração sem fio" (Android 11 em diante), igual ao "adb pair" do computador
 * (adb/pairing_connection e adb/pairing_auth):
 *
 *   1. conexão TLS 1.3 com a porta de pareamento que a TV mostra (e anuncia na rede como "_adb-tls-pairing._tcp");
 *   2. senha = o código de 6 dígitos + 64 bytes exportados da conexão TLS ("adb-label\0"), para ninguém no meio
 *      conseguir usar o código;
 *   3. SPAKE2 (ver Spake2): cada lado manda 32 bytes e os dois chegam à mesma chave só se o código bater;
 *   4. dessa chave, HKDF-SHA256 tira uma chave AES-128-GCM ("adb pairing_auth aes-128-gcm key"), e cada lado manda,
 *      cifrado, um PeerInfo de 8192 bytes: tipo 0 e a chave pública do ADB do Vitro (a TV manda a identificação dela).
 *
 * Pacotes: 1 byte de versão (1), 1 de tipo (0 = mensagem SPAKE2, 1 = PeerInfo) e o tamanho em 4 bytes big-endian.
 * Se o código estiver errado, a TV não consegue abrir o PeerInfo do Vitro, e o do Vitro também não abre.
 */
final class AdbPairing {
    private static final String TAG = "VitroHome";
    private static final int VERSION = 1, SPAKE2_MSG = 0, PEER_INFO = 1, PEER_INFO_SIZE = 8192;

    /** O código não bateu (a TV recusou). */
    static final class WrongCode extends IOException {
        WrongCode() { super("o código de pareamento não confere"); }
    }

    private AdbPairing() { }

    static void pair(Context c, String host, int port, String code) throws Exception {
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(host, port), 4000);
            plain.setSoTimeout(15000);
            SSLSocket tls = AdbTls.wrap(c, plain, host, port);
            byte[] ekm = android.net.ssl.SSLSockets.exportKeyingMaterial(tls, "adb-label\0", null, 64);
            byte[] codeBytes = code.trim().getBytes(StandardCharsets.US_ASCII);
            byte[] password = new byte[codeBytes.length + ekm.length];
            System.arraycopy(codeBytes, 0, password, 0, codeBytes.length);
            System.arraycopy(ekm, 0, password, codeBytes.length, ekm.length);

            DataInputStream in = new DataInputStream(tls.getInputStream());
            OutputStream out = tls.getOutputStream();

            Spake2 spake = new Spake2(true);
            write(out, SPAKE2_MSG, spake.start(password, new SecureRandom()));
            byte[] theirMsg = read(in, SPAKE2_MSG);
            byte[] keyMaterial;
            try {
                keyMaterial = spake.finish(theirMsg);
            } catch (IllegalArgumentException x) {
                throw new IOException("mensagem de pareamento inválida");
            }
            SecretKeySpec aes = new SecretKeySpec(hkdf(keyMaterial, "adb pairing_auth aes-128-gcm key", 16), "AES");

            byte[] info = new byte[PEER_INFO_SIZE];
            info[0] = 0;   // ADB_RSA_PUB_KEY
            byte[] pub = Adb.publicKeyText(c).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(pub, 0, info, 1, Math.min(pub.length, PEER_INFO_SIZE - 2));
            write(out, PEER_INFO, gcm(Cipher.ENCRYPT_MODE, aes, 0, info));

            byte[] theirs;
            try {
                theirs = read(in, PEER_INFO);
            } catch (java.io.EOFException x) {
                // A TV fecha sem responder quando não consegue abrir o nosso PeerInfo: o código estava errado.
                throw new WrongCode();
            }
            try {
                byte[] plainInfo = gcm(Cipher.DECRYPT_MODE, aes, 0, theirs);
                Log.i(TAG, "pareado com a depuração sem fio (resposta da TV: tipo " + plainInfo[0] + ")");
            } catch (AEADBadTagException x) {
                throw new WrongCode();
            }
        }
    }

    private static void write(OutputStream out, int type, byte[] payload) throws IOException {
        byte[] h = new byte[6];
        h[0] = (byte) VERSION;
        h[1] = (byte) type;
        int n = payload.length;
        h[2] = (byte) (n >>> 24); h[3] = (byte) (n >>> 16); h[4] = (byte) (n >>> 8); h[5] = (byte) n;
        out.write(h);
        out.write(payload);
        out.flush();
    }

    private static byte[] read(DataInputStream in, int expectedType) throws IOException {
        byte[] h = new byte[6];
        in.readFully(h);
        if (h[0] != VERSION) throw new IOException("versão de pareamento desconhecida: " + h[0]);
        if (h[1] != expectedType) throw new IOException("pacote de pareamento inesperado: " + h[1]);
        int n = ((h[2] & 0xff) << 24) | ((h[3] & 0xff) << 16) | ((h[4] & 0xff) << 8) | (h[5] & 0xff);
        if (n <= 0 || n > 2 * PEER_INFO_SIZE) throw new IOException("tamanho de pacote inválido: " + n);
        byte[] p = new byte[n];
        in.readFully(p);
        return p;
    }

    /** AES-128-GCM com o número de sequência como nonce (8 bytes little-endian e 4 zeros), sem dados extras. */
    private static byte[] gcm(int mode, SecretKeySpec key, long sequence, byte[] data) throws Exception {
        byte[] nonce = new byte[12];
        for (int i = 0; i < 8; i++) nonce[i] = (byte) (sequence >>> (8 * i));
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(128, nonce));
        return c.doFinal(data);
    }

    /** HKDF-SHA256 sem sal (RFC 5869: sal vazio = zeros). */
    static byte[] hkdf(byte[] ikm, String info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] out = new byte[length], t = new byte[0];
        int done = 0;
        for (int i = 1; done < length; i++) {
            mac.update(t);
            mac.update(info.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) i);
            t = mac.doFinal();
            int n = Math.min(t.length, length - done);
            System.arraycopy(t, 0, out, done, n);
            done += n;
        }
        return out;
    }
}
