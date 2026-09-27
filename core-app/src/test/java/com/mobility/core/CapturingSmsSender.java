package com.mobility.core;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mobility.core.identity.otp.SmsSender;

/** Records SMS messages so tests can read the OTP that would have been sent. */
public class CapturingSmsSender implements SmsSender {

	private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

	public record Sms(String phone, String message) {
	}

	private final List<Sms> sent = new CopyOnWriteArrayList<>();

	@Override
	public void send(String phoneE164, String message) {
		sent.add(new Sms(phoneE164, message));
	}

	public Optional<Sms> lastTo(String phoneE164) {
		return sent.reversed().stream().filter(s -> s.phone().equals(phoneE164)).findFirst();
	}

	public String lastCodeTo(String phoneE164) {
		Sms sms = lastTo(phoneE164).orElseThrow(() -> new AssertionError("No SMS sent to " + phoneE164));
		Matcher m = CODE.matcher(sms.message());
		if (!m.find()) {
			throw new AssertionError("No code in SMS: " + sms.message());
		}
		return m.group(1);
	}

	public long countTo(String phoneE164) {
		return sent.stream().filter(s -> s.phone().equals(phoneE164)).count();
	}
}
