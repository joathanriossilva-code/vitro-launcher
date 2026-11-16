package app.vitro.launcher;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * SPAKE2 sobre a curva Ed25519, igual ao da BoringSSL (crypto/curve25519/spake25519.cc), que é o que o adb usa para
 * parear pela "Depuração sem fio" (pairing_auth.cpp): o código de 6 dígitos que a TV mostra, somado à chave exportada
 * da conexão TLS, vira uma chave secreta comum aos dois lados sem que o código passe pela rede.
 *
 * Feito com BigInteger: lento (dezenas de milissegundos), mas roda uma vez só, no pareamento. Sem nada do Android:
 * testado do computador (os dois papéis, cliente e servidor, precisam chegar à mesma chave).
 *
 * Detalhes que precisam bater com a BoringSSL:
 *   - os nomes vão com o NUL do fim ("adb pair client\0", "adb pair server\0");
 *   - a chave privada é (aleatório de 64 bytes mod l) × 8;
 *   - o escalar da senha é SHA-512(senha) mod l, mais l, 2l e 4l conforme os três bits de baixo (o "password scalar
 *     hack"), e multiplica os pontos M (cliente) e N (servidor) por inteiro, sem reduzir;
 *   - a transcrição é SHA-512 de cada campo com o tamanho em 8 bytes little-endian na frente.
 */
final class Spake2 {
    static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    static final BigInteger L = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger D2 = D.add(D).mod(P);
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);

    /** Pontos da BoringSSL: M para o cliente (alice), N para o servidor (bob). */
    static final byte[] M_ENC = hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e");
    static final byte[] N_ENC = hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778");

    static final byte[] CLIENT_NAME = "adb pair client\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    static final byte[] SERVER_NAME = "adb pair server\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** Ponto em coordenadas estendidas (X:Y:Z:T), x = X/Z, y = Y/Z, xy = T/Z. */
    static final class Pt {
        final BigInteger x, y, z, t;
        Pt(BigInteger x, BigInteger y, BigInteger z, BigInteger t) { this.x = x; this.y = y; this.z = z; this.t = t; }
    }

    static final Pt ZERO = new Pt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);
    static final Pt BASE;

    static {
        BigInteger by = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
        byte[] enc = le(by, 32);
        BASE = decode(enc);
    }

    private final boolean client;
    private final byte[] myName, theirName;
    private BigInteger priv, pwScalar;
    private byte[] pwHash, myMsg;

    Spake2(boolean client) {
        this.client = client;
        myName = client ? CLIENT_NAME : SERVER_NAME;
        theirName = client ? SERVER_NAME : CLIENT_NAME;
    }

    /** A mensagem a mandar (32 bytes). */
    byte[] start(byte[] password, SecureRandom random) throws Exception {
        byte[] r = new byte[64];
        random.nextBytes(r);
        priv = fromLe(r).mod(L).shiftLeft(3);
        Pt p = mul(priv, BASE);

        pwHash = MessageDigest.getInstance("SHA-512").digest(password);
        BigInteger s = fromLe(pwHash).mod(L);
        if (s.testBit(0)) s = s.add(L);
        if (s.testBit(1)) s = s.add(L.shiftLeft(1));
        if (s.testBit(2)) s = s.add(L.shiftLeft(2));
        pwScalar = s;

        Pt mask = mul(pwScalar, decode(client ? M_ENC : N_ENC));
        myMsg = encode(add(p, mask));
        return myMsg.clone();
    }

    /** Com a mensagem do outro lado, a chave comum (64 bytes). Lança exceção se o ponto recebido não é da curva. */
    byte[] finish(byte[] theirMsg) throws Exception {
        if (theirMsg.length != 32) throw new IllegalArgumentException("mensagem SPAKE2 com tamanho errado");
        Pt q = decode(theirMsg);
        Pt peersMask = mul(pwScalar, decode(client ? N_ENC : M_ENC));
        Pt unmasked = add(q, negate(peersMask));
        byte[] dh = encode(mul(priv, unmasked));
        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        if (client) {
            lp(sha, myName); lp(sha, theirName); lp(sha, myMsg); lp(sha, theirMsg);
        } else {
            lp(sha, theirName); lp(sha, myName); lp(sha, theirMsg); lp(sha, myMsg);
        }
        lp(sha, dh);
        lp(sha, pwHash);
        return sha.digest();
    }

    private static void lp(MessageDigest sha, byte[] data) {
        long n = data.length;
        byte[] len = new byte[8];
        for (int i = 0; i < 8; i++) { len[i] = (byte) (n & 0xff); n >>= 8; }
        sha.update(len);
        sha.update(data);
    }

    // ---- Curva (Edwards torcida, a = -1) --------------------------------------------------------

    static Pt add(Pt p, Pt q) {
        BigInteger a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P);
        BigInteger b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P);
        BigInteger c = p.t.multiply(D2).multiply(q.t).mod(P);
        BigInteger d = p.z.shiftLeft(1).multiply(q.z).mod(P);
        BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
        return new Pt(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P));
    }

    static Pt negate(Pt p) {
        return new Pt(P.subtract(p.x).mod(P), p.y, p.z, P.subtract(p.t).mod(P));
    }

    static Pt mul(BigInteger k, Pt p) {
        Pt q = ZERO;
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            q = add(q, q);
            if (k.testBit(i)) q = add(q, p);
        }
        return q;
    }

    static byte[] encode(Pt p) {
        BigInteger zi = p.z.modInverse(P);
        BigInteger x = p.x.multiply(zi).mod(P), y = p.y.multiply(zi).mod(P);
        byte[] out = le(y, 32);
        if (x.testBit(0)) out[31] |= (byte) 0x80;
        return out;
    }

    static Pt decode(byte[] s) {
        if (s.length != 32) throw new IllegalArgumentException("ponto com tamanho errado");
        byte[] c = s.clone();
        int sign = (c[31] >> 7) & 1;
        c[31] &= 0x7f;
        BigInteger y = fromLe(c);
        if (y.compareTo(P) >= 0) throw new IllegalArgumentException("ponto fora da curva");
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P), v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        BigInteger x2 = u.multiply(v.modInverse(P)).mod(P);
        BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (!x.multiply(x).subtract(x2).mod(P).equals(BigInteger.ZERO)) x = x.multiply(SQRT_M1).mod(P);
        if (!x.multiply(x).subtract(x2).mod(P).equals(BigInteger.ZERO)) throw new IllegalArgumentException("ponto fora da curva");
        if (x.signum() == 0 && sign == 1) throw new IllegalArgumentException("ponto inválido");
        if ((x.testBit(0) ? 1 : 0) != sign) x = P.subtract(x);
        return new Pt(x, y, BigInteger.ONE, x.multiply(y).mod(P));
    }

    // ---- Bytes --------------------------------------------------------------------------------

    static BigInteger fromLe(byte[] b) {
        byte[] be = new byte[b.length];
        for (int i = 0; i < b.length; i++) be[i] = b[b.length - 1 - i];
        return new BigInteger(1, be);
    }

    static byte[] le(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    static byte[] hex(String h) {
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
