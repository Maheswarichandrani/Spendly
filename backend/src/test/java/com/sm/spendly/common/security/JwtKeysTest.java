package com.sm.spendly.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class JwtKeysTest {

	@Test
	void parsesPrivateKeyAndDerivesMatchingPublicKey() throws Exception {
		JwtKeys keys = JwtKeys.fromBase64PrivateKey(TestKeys.newBase64PrivateKey());
		byte[] data = "hello".getBytes(StandardCharsets.UTF_8);

		// Sign with the private key...
		Signature signer = Signature.getInstance("SHA256withRSA");
		signer.initSign(keys.privateKey());
		signer.update(data);
		byte[] signature = signer.sign();

		// ...and the public key we derived must accept that signature.
		Signature verifier = Signature.getInstance("SHA256withRSA");
		verifier.initVerify(keys.publicKey());
		verifier.update(data);
		assertThat(verifier.verify(signature)).isTrue();
	}

	@Test
	void rejectsGarbageKey() {
		String garbage = "this-is-not-a-key-SECRET123";

		assertThatThrownBy(() -> JwtKeys.fromBase64PrivateKey(garbage))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("spendly.jwt.private-key")
			// The value itself must never appear in an error message (it could be a real key).
			.hasMessageNotContaining("SECRET123")
			.hasNoCause();
	}

	@Test
	void rejectsKeyShorterThan2048Bits() {
		String shortKey = Base64.getEncoder().encodeToString(TestKeys.newRsaKeyPair(1024).getPrivate().getEncoded());

		assertThatThrownBy(() -> JwtKeys.fromBase64PrivateKey(shortKey))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("2048");
	}

	@Test
	void toStringHidesKeys() {
		JwtKeys keys = JwtKeys.fromBase64PrivateKey(TestKeys.newBase64PrivateKey());

		assertThat(keys.toString()).isEqualTo("JwtKeys[***]");
	}

}
