package com.sm.spendly.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.sm.spendly.auth.dto.RegisterRequest;
import com.sm.spendly.common.exception.BadRequestException;
import com.sm.spendly.common.exception.ErrorCode;
import com.sm.spendly.user.service.UserService;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	@Mock
	UserService userService;

	@Mock
	PasswordEncoder passwordEncoder;

	@InjectMocks
	AuthService authService;

	@Test
	void register_normalizesEmailAndName() {
		when(passwordEncoder.encode("correct-horse-42")).thenReturn("hashed");

		authService.register(new RegisterRequest("  Ravi Kumar ", " Ravi@Mail.com ", "correct-horse-42"));

		// Spaces stripped by the record, lowercase applied by the service.
		verify(userService).createUser("ravi@mail.com", "hashed", "Ravi Kumar");
	}

	@Test
	void register_passesHashNotRawPassword() {
		when(passwordEncoder.encode("correct-horse-42")).thenReturn("hashed");
		ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);

		authService.register(new RegisterRequest("Ravi", "ravi@mail.com", "correct-horse-42"));

		verify(userService).createUser(eq("ravi@mail.com"), hash.capture(), eq("Ravi"));
		assertThat(hash.getValue()).isEqualTo("hashed").isNotEqualTo("correct-horse-42");
	}

	@Test
	void register_rejectsPasswordOver72Bytes() {
		// 30 emoji: 60 Java chars (passes @Size max 72) but 120 bytes in UTF-8.
		String emojiPassword = "😀".repeat(30);

		assertThatThrownBy(() -> authService.register(new RegisterRequest("Ravi", "ravi@mail.com", emojiPassword)))
			.isInstanceOf(BadRequestException.class)
			.extracting(ex -> ((BadRequestException) ex).getErrorCode())
			.isEqualTo(ErrorCode.PASSWORD_TOO_LONG);
		verifyNoInteractions(passwordEncoder);
		verify(userService, never()).createUser(anyString(), anyString(), anyString());
	}

	@Test
	void register_accepts72ByteAsciiPassword() {
		String password = "a".repeat(72);
		when(passwordEncoder.encode(password)).thenReturn("hashed");

		authService.register(new RegisterRequest("Ravi", "ravi@mail.com", password));

		verify(userService).createUser(any(), eq("hashed"), any());
	}

}
