package com.mobility.core.identity.otp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumbersTest {

	@ParameterizedTest
	@CsvSource({
			"012 345 678,      +85512345678",
			"012-345-678,      +85512345678",
			"0973456789,       +855973456789",
			"855 12 345 678,   +85512345678",
			"+855 12 345 678,  +85512345678",
			"00855 12345678,   +85512345678",
			"+66 81 234 5678,  +66812345678",
			"+1 (202) 555-0147, +12025550147" })
	void normalizesToE164(String raw, String expected) {
		assertThat(PhoneNumbers.normalize(raw)).contains(expected);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "abc", "12345", "012 34", "+855 0 12 345 678", "+855 12 345 678 999", "12345678" })
	void rejectsInvalidNumbers(String raw) {
		assertThat(PhoneNumbers.normalize(raw)).isEmpty();
	}

	@org.junit.jupiter.api.Test
	void masksForLogs() {
		assertThat(PhoneNumbers.mask("+85512345678")).isEqualTo("+855*****678");
	}
}
