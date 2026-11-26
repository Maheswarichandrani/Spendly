package com.sm.spendly.common.security;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * The RSA key pair that signs (private) and checks (public) our tokens.
 *
 * <p>Only the private key is configured. An RSA private key file also stores the two
 * numbers that make up the public key (the modulus and the public exponent), so we
 * rebuild the public key from it. One secret instead of two, and they can't mismatch.
 */
public record JwtKeys(RSAPrivateCrtKey privateKey, RSAPublicKey publicKey) {

	/**
	 * @param base64Pkcs8 the private key as one line of base64 (a PEM file without its
	 *                    "-----BEGIN/END PRIVATE KEY-----" lines and line breaks)
	 * @throws IllegalStateException if the value is not a valid RSA private key. The
	 *                               message never contains the value itself.
	 */
	public static JwtKeys fromBase64PrivateKey(String base64Pkcs8) {
		try {
			// MIME decoder: tolerates stray spaces or line breaks pasted into the env var.
			byte[] der = Base64.getMimeDecoder().decode(base64Pkcs8);
			KeyFactory rsa = KeyFactory.getInstance("RSA");

			if (!(rsa.generatePrivate(new PKCS8EncodedKeySpec(der)) instanceof RSAPrivateCrtKey privateKey)) {
				throw new IllegalStateException("spendly.jwt.private-key is not an RSA private key in PKCS#8 format.");
			}
			BigInteger modulus = privateKey.getModulus();
			BigInteger publicExponent = privateKey.getPublicExponent();
			RSAPublicKey publicKey = (RSAPublicKey) rsa.generatePublic(new RSAPublicKeySpec(modulus, publicExponent));

			if (modulus.bitLength() < 2048) {
				throw new IllegalStateException("spendly.jwt.private-key must be at least 2048 bits.");
			}
			return new JwtKeys(privateKey, publicKey);
		}
		catch (IllegalArgumentException | GeneralSecurityException ex) {
			// Don't chain "ex": some providers put parts of the input in their messages.
			throw new IllegalStateException(
					"spendly.jwt.private-key could not be read. Expected one line of base64 PKCS#8 (see .env.example).");
		}
	}

	/** Keys are secrets: never print them. */
	@Override
	public String toString() {
		return "JwtKeys[***]";
	}

}
