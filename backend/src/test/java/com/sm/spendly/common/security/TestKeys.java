package com.sm.spendly.common.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;

import com.sm.spendly.common.config.JwtProperties;

/** Throwaway RSA keys for tests. Each call makes a brand-new key nobody else has. */
public final class TestKeys {

	private TestKeys() {
	}

	public static KeyPair newRsaKeyPair(int bits) {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(bits);
			return generator.generateKeyPair();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Same format as JWT_PRIVATE_KEY: PKCS#8, base64, one line. */
	public static String newBase64PrivateKey() {
		return Base64.getEncoder().encodeToString(newRsaKeyPair(2048).getPrivate().getEncoded());
	}

	public static JwtProperties newProperties() {
		return new JwtProperties("spendly", Duration.ofMinutes(15), "test-key", newBase64PrivateKey());
	}

}
