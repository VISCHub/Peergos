package peergos.crypto.symmetric;

import peergos.crypto.*;
import peergos.crypto.random.*;

import java.util.*;

public class TweetNaClKey implements SymmetricKey
{
    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 24;

    private final byte[] secretKey;
    private final boolean isDirty;
    private final Salsa20Poly1305 implementation;
    private final SafeRandom random;

    public TweetNaClKey(byte[] secretKey, boolean isDirty, Salsa20Poly1305 implementation, SafeRandom random)
    {
        if (secretKey.length != TweetNaCl.SECRETBOX_KEY_BYTES)
            throw new IllegalStateException("Incorrect key size! ("+secretKey.length+")");
        this.secretKey = secretKey;
        this.isDirty = isDirty;
        this.implementation = implementation;
        this.random = random;
    }

    public TweetNaClKey(byte[] encoded, Salsa20Poly1305 implementation, SafeRandom random)
    {
        this(Arrays.copyOfRange(encoded, 0, encoded.length - 1), encoded[encoded.length - 1] != 0, implementation, random);
    }

    public Type type() {
        return Type.TweetNaCl;
    }

    public byte[] getKey()
    {
        return secretKey;
    }

    public boolean isDirty() {
        return isDirty;
    }

    public SymmetricKey toDirty() {
        byte[] combined = Arrays.copyOfRange(secretKey, 0, secretKey.length + 1);
        combined[combined.length - 1] = (byte)1;
        return new TweetNaClKey(combined, implementation, random);
    }

    public byte[] encrypt(byte[] data, byte[] nonce)
    {
        return encrypt(secretKey, data, nonce, implementation);
    }

    public byte[] decrypt(byte[] data, byte[] nonce)
    {
        return decrypt(secretKey, data, nonce, implementation);
    }

    public static byte[] encrypt(byte[] key, byte[] data, byte[] nonce, Salsa20Poly1305 implementation)
    {
        return implementation.secretbox(data, nonce, key);
    }

    public static byte[] decrypt(byte[] key, byte[] cipher, byte[] nonce, Salsa20Poly1305 implementation)
    {
        return implementation.secretbox_open(cipher, nonce, key);
    }

    public byte[] createNonce()
    {
        byte[] res = new byte[NONCE_BYTES];
        random.randombytes(res, 0, res.length);
        return res;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        TweetNaClKey that = (TweetNaClKey) o;

        if (isDirty != that.isDirty) return false;
        return Arrays.equals(secretKey, that.secretKey);

    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(secretKey);
        result = 31 * result + (isDirty ? 1 : 0);
        return result;
    }

    public static TweetNaClKey random(Salsa20Poly1305 provider, SafeRandom random)
    {
        byte[] key = new byte[KEY_BYTES + 1];
        random.randombytes(key, 0, KEY_BYTES);
        return new TweetNaClKey(key, provider, random);
    }
}