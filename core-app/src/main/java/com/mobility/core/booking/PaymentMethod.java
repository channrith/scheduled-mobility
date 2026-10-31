package com.mobility.core.booking;

public enum PaymentMethod {
	CASH,
	/** Bank QR payment, confirmed by the gateway's webhook (payment module, not built yet). */
	KHQR,
	/** Charged to the company and paid on its monthly invoice. */
	CORPORATE_INVOICE
}
