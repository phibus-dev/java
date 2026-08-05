package dev.phibus.s3.settings;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class BootstrapSecretCodec {
    private static final String ENV_KEY = "S3_PERF_BOOTSTRAP_KEY";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public BootstrapSecretCodec() {
        String passphrase = System.getenv(ENV_KEY);
        if (passphrase == null || passphrase.isBlank()) {
            key = null;
        } else {
            try {
                key = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                        .digest(passphrase.getBytes(StandardCharsets.UTF_8)), "AES");
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Cannot initialize bootstrap encryption", e);
            }
        }
    }

    public boolean available() {
        return key != null;
    }

    public String encrypt(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        requireKey();
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt bootstrap secret", e);
        }
    }

    public String decrypt(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        requireKey();
        try {
            byte[] combined = Base64.getDecoder().decode(value);
            byte[] iv = new byte[12];
            byte[] encrypted = new byte[combined.length - iv.length];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot decrypt bootstrap secret", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("Environment variable " + ENV_KEY + " must be set before saving secrets");
        }
    }
}
