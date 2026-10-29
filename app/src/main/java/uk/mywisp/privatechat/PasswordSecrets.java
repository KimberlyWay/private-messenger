package uk.mywisp.privatechat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Existing account-compatible PBKDF2 authentication derivative. No message/recovery keys. */
final class PasswordSecrets implements AutoCloseable {
    static final int ITERATIONS = 600_000;
    final String authentication;
    static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static byte[] decode(String text) { return Base64.getUrlDecoder().decode(text); }
    static String randomSalt() { byte[] b = new byte[32]; new SecureRandom().nextBytes(b); return encode(b); }
    private static byte[] hmac(byte[] key, String label) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
    }
    PasswordSecrets(String password, String salt, String server, String login) throws Exception {
        byte[] random = decode(salt);
        if (random.length != 32) throw new IllegalArgumentException("Invalid credential salt");
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); digest.update(random);
        byte[] kdfSalt = digest.digest(("PrivateMessenger/password-v2\n" + server + "\n" + login).getBytes(StandardCharsets.UTF_8));
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), kdfSalt, ITERATIONS, 256);
        byte[] master;
        try { master = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
        try {
            authentication = "pm2_" + encode(hmac(master, "PrivateMessenger/authentication/v2"));
        } finally { Arrays.fill(master, (byte) 0); Arrays.fill(kdfSalt, (byte) 0); }
    }
    @Override public void close() { /* KDF temporary buffers are wiped in the constructor. */ }
}
