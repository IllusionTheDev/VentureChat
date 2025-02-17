package mineverse.Aust1n46.chat.crypto;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public final class ChatEncryption {

    private static Cipher encryptCipher;
    private static Cipher decryptCipher;

    public static void init(SecretKey key)
        throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeyException {
        encryptCipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        decryptCipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        encryptCipher.init(Cipher.ENCRYPT_MODE, key);
        decryptCipher.init(Cipher.DECRYPT_MODE, key);
    }

    public static String encodeKey(SecretKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static SecretKey decodeKey(String key) {
        return new SecretKeySpec(Base64.getDecoder().decode(key), "AES");
    }

    public static String generateKey() {
        return encodeKey(generateKey(256));
    }

    public static SecretKey generateKey(int keySize) {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(keySize);
            return keyGenerator.generateKey();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static byte[] encrypt(byte[] data) throws IllegalBlockSizeException, BadPaddingException {
        return encryptCipher.doFinal(data);
    }

    public static byte[] decrypt(byte[] data) {
        try {
            return decryptCipher.doFinal(data);
        } catch (Exception e) {
            return null;
        }
    }
}
