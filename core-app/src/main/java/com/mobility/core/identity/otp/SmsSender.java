package com.mobility.core.identity.otp;

/** Outbound SMS. A real gateway implementation replaces {@link LoggingSmsSender} later. */
public interface SmsSender {

	void send(String phoneE164, String message);
}
