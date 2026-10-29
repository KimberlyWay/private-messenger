package uk.mywisp.privatechat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Standard primitives only. Human password and recovery key never go to the server. */
final class PasswordSecrets implements AutoCloseable {
    static final int ITERATIONS = 600_000;
    private final byte[] backup;
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
            backup = hmac(master, "PrivateMessenger/recovery-envelope/v1");
        } finally { Arrays.fill(master, (byte) 0); Arrays.fill(kdfSalt, (byte) 0); }
    }
    private static byte[] aad(String server, String user) {
        return ("PrivateMessenger/recovery-envelope/v1\n" + server + "\n" + user).getBytes(StandardCharsets.UTF_8);
    }
    String[] seal(String recoveryKey, String server, String user) throws Exception {
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(backup, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad(server, user));
        return new String[] { encode(nonce), encode(cipher.doFinal(recoveryKey.getBytes(StandardCharsets.UTF_8))) };
    }
    String open(String nonce, String ciphertext, String server, String user) throws Exception {
        byte[] iv = decode(nonce), content = decode(ciphertext);
        if (iv.length != 12 || content.length < 16 || content.length > 4096) throw new IllegalArgumentException("Invalid recovery envelope");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(backup, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(aad(server, user));
        byte[] clear = cipher.doFinal(content);
        try { return new String(clear, StandardCharsets.UTF_8); } finally { Arrays.fill(clear, (byte) 0); }
    }
    @Override public void close() { Arrays.fill(backup, (byte) 0); }
}
