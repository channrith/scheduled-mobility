package com.mobility.core.pricing.settings;

import java.math.BigDecimal;

/** USD→KHR rate. Set by an admin for now; a bank or central-bank feed can implement this later. */
public interface ExchangeRateProvider {

	/** Riel per US dollar. */
	BigDecimal usdToKhr();
}
