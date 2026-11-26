package studio.performance.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Component
public class Vault {
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public Vault(@Value("${studio.encryption-key}") String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length != 32) throw Input.invalid("STUDIO_ENCRYPTION_KEY must be 32 bytes in base64.");
        key = new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(UUID id, String secret) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] ciphertext = transform(Cipher.ENCRYPT_MODE, id, nonce, secret.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + ciphertext.length).put(nonce).put(ciphertext).array());
    }

    public String decrypt(UUID id, String sealed) {
        ByteBuffer bytes = ByteBuffer.wrap(Base64.getDecoder().decode(sealed));
        byte[] nonce = new byte[12];
        bytes.get(nonce);
        byte[] ciphertext = new byte[bytes.remaining()];
        bytes.get(ciphertext);
        return new String(transform(Cipher.DECRYPT_MODE, id, nonce, ciphertext), StandardCharsets.UTF_8);
    }

    private byte[] transform(int mode, UUID id, byte[] nonce, byte[] value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(id.toString().getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(value);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Credential encryption failed.");
        }
    }

    public String randomPassword() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
