package com.taskforge.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

// AES-256-GCM at-rest encryption for column values that must be read back in
// plaintext by the application (unlike SecureTokenGenerator.hash(), which is
// for values only ever compared, never decrypted). Applied explicitly per
// field via @Convert - not autoApply - so encrypting a column stays a
// deliberate choice at the entity, not something that silently applies to
// every String field.
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final int IV_LENGTH_BYTES = 12;
	private static final int TAG_LENGTH_BITS = 128;

	private static final SecureRandom secureRandom = new SecureRandom();

	private final SecretKeySpec key;

	public EncryptedStringConverter(@Value("${app.encryption-key}") String base64Key) {
		this.key = new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES");
	}

	@Override
	public String convertToDatabaseColumn(String attribute) {
		if (attribute == null) {
			return null;
		}
		try {
			byte[] iv = new byte[IV_LENGTH_BYTES];
			secureRandom.nextBytes(iv);

			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
			byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

			ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
			buffer.put(iv).put(ciphertext);
			return Base64.getEncoder().encodeToString(buffer.array());
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Failed to encrypt column value", e);
		}
	}

	@Override
	public String convertToEntityAttribute(String dbData) {
		if (dbData == null) {
			return null;
		}
		try {
			byte[] decoded = Base64.getDecoder().decode(dbData);
			byte[] iv = Arrays.copyOfRange(decoded, 0, IV_LENGTH_BYTES);
			byte[] ciphertext = Arrays.copyOfRange(decoded, IV_LENGTH_BYTES, decoded.length);

			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
			return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Failed to decrypt column value", e);
		}
	}

}
