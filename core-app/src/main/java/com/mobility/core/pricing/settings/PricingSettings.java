package com.mobility.core.pricing.settings;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.identity.CurrentUserProvider;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fuel index and exchange rate: the newest change wins; before the first change the configured default. */
@Service
public class PricingSettings implements ExchangeRateProvider {

	/** @param changedBy null for the configured default */
	public record CurrentValue(Setting setting, BigDecimal value, UUID changedBy, Instant changedAt, boolean configuredDefault) {
	}

	private static final BigDecimal FUEL_MIN = new BigDecimal("0.5");

	private static final BigDecimal FUEL_MAX = new BigDecimal("3");

	private static final BigDecimal RATE_MIN = new BigDecimal("1000");

	private static final BigDecimal RATE_MAX = new BigDecimal("10000");

	private final SettingChangeRepository changes;

	private final PricingProperties properties;

	private final AuditLog auditLog;

	private final CurrentUserProvider currentUser;

	private final Clock clock;

	PricingSettings(SettingChangeRepository changes, PricingProperties properties, AuditLog auditLog,
			CurrentUserProvider currentUser, Clock clock) {
		this.changes = changes;
		this.properties = properties;
		this.auditLog = auditLog;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public CurrentValue current(Setting setting) {
		return changes.findFirstBySettingOrderBySeqDesc(setting)
			.map(c -> new CurrentValue(setting, c.getValue(), c.getChangedBy(), c.getChangedAt(), false))
			.orElseGet(() -> new CurrentValue(setting, defaultValue(setting), null, null, true));
	}

	@Transactional(readOnly = true)
	public List<SettingChange> history(Setting setting) {
		return changes.findTop50BySettingOrderBySeqDesc(setting);
	}

	@Transactional
	public CurrentValue change(Setting setting, BigDecimal value) {
		BigDecimal valid = validate(setting, value);
		SettingChange change = changes.save(new SettingChange(setting, valid, currentUser.require().userId(), clock.instant()));
		auditLog.record("pricing.setting.changed", "pricing_setting", setting.name(),
				Map.of("setting", setting.name(), "value", valid.toPlainString()));
		return new CurrentValue(setting, change.getValue(), change.getChangedBy(), change.getChangedAt(), false);
	}

	@Override
	public BigDecimal usdToKhr() {
		return current(Setting.USD_KHR_RATE).value();
	}

	public BigDecimal fuelIndex() {
		return current(Setting.FUEL_INDEX).value();
	}

	private BigDecimal defaultValue(Setting setting) {
		return switch (setting) {
			case FUEL_INDEX -> properties.defaultFuelIndex();
			case USD_KHR_RATE -> properties.defaultUsdKhrRate();
		};
	}

	private static BigDecimal validate(Setting setting, BigDecimal value) {
		if (value == null) {
			throw invalid(setting);
		}
		BigDecimal v = value.stripTrailingZeros();
		boolean ok = switch (setting) {
			case FUEL_INDEX -> v.scale() <= 3 && v.compareTo(FUEL_MIN) >= 0 && v.compareTo(FUEL_MAX) <= 0;
			case USD_KHR_RATE -> v.scale() <= 2 && v.compareTo(RATE_MIN) >= 0 && v.compareTo(RATE_MAX) <= 0;
		};
		if (!ok) {
			throw invalid(setting);
		}
		return v.scale() < 0 ? v.setScale(0) : v;
	}

	private static ApiException invalid(Setting setting) {
		return new ApiException(HttpStatus.BAD_REQUEST, "pricing.setting-invalid").withProperty("setting", setting.name());
	}
}
