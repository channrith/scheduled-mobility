package com.mobility.core.corporate.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.mobility.core.corporate.AccountStatus;
import com.mobility.core.shared.web.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.springframework.http.HttpStatus;

/** A corporate client. Money is in minor units of {@link #getCurrency()}. */
@Entity
@Table(schema = "corporate", name = "corporate_accounts")
public class CorporateAccount {

	static final int MIN_REASON_LENGTH = 10;

	@Id
	private UUID id;

	@Column(name = "legal_name", nullable = false)
	private String legalName;

	@Column(name = "display_name")
	private String displayName;

	@Column(name = "tax_id")
	private String taxId;

	@Column(name = "payment_terms_days", nullable = false)
	private int paymentTermsDays;

	@Column(name = "credit_limit_minor", nullable = false)
	private long creditLimitMinor;

	/** CHAR(3) per the money convention (USD or KHR). */
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(nullable = false, length = 3)
	private String currency;

	@Column(name = "service_fee_pct", nullable = false, precision = 5, scale = 2)
	private BigDecimal serviceFeePct;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AccountStatus status;

	@Column(name = "suspension_reason")
	private String suspensionReason;

	@Column(name = "suspended_at")
	private Instant suspendedAt;

	@Column(name = "suspended_by")
	private UUID suspendedBy;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected CorporateAccount() {
	}

	CorporateAccount(AccountDetails details) {
		this.id = UUID.randomUUID();
		this.status = AccountStatus.ACTIVE;
		apply(details);
	}

	/** Editable fields, already validated and normalized. */
	record AccountDetails(String legalName, String displayName, String taxId, int paymentTermsDays,
			long creditLimitMinor, String currency, BigDecimal serviceFeePct) {
	}

	void apply(AccountDetails d) {
		this.legalName = d.legalName();
		this.displayName = d.displayName();
		this.taxId = d.taxId();
		this.paymentTermsDays = d.paymentTermsDays();
		this.creditLimitMinor = d.creditLimitMinor();
		this.currency = d.currency();
		this.serviceFeePct = d.serviceFeePct();
	}

	/** @return the reason, trimmed */
	String suspend(String reason, UUID actor, Instant now) {
		requireStatus(AccountStatus.ACTIVE, AccountStatus.SUSPENDED);
		String valid = requireReason(reason);
		this.status = AccountStatus.SUSPENDED;
		this.suspensionReason = valid;
		this.suspendedAt = now;
		this.suspendedBy = actor;
		return valid;
	}

	String reactivate(String reason) {
		requireStatus(AccountStatus.SUSPENDED, AccountStatus.ACTIVE);
		String valid = requireReason(reason);
		this.status = AccountStatus.ACTIVE;
		this.suspensionReason = null;
		this.suspendedAt = null;
		this.suspendedBy = null;
		return valid;
	}

	private void requireStatus(AccountStatus from, AccountStatus to) {
		if (status != from) {
			throw new ApiException(HttpStatus.CONFLICT, "corporate.invalid-transition", status, to)
				.withProperty("from", status)
				.withProperty("to", to);
		}
	}

	private static String requireReason(String reason) {
		String trimmed = reason == null ? "" : reason.strip();
		if (trimmed.length() < MIN_REASON_LENGTH) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "corporate.reason-required", MIN_REASON_LENGTH);
		}
		return trimmed;
	}

	public UUID getId() {
		return id;
	}

	public String getLegalName() {
		return legalName;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getTaxId() {
		return taxId;
	}

	public int getPaymentTermsDays() {
		return paymentTermsDays;
	}

	public long getCreditLimitMinor() {
		return creditLimitMinor;
	}

	public String getCurrency() {
		return currency;
	}

	public BigDecimal getServiceFeePct() {
		return serviceFeePct;
	}

	public AccountStatus getStatus() {
		return status;
	}

	public String getSuspensionReason() {
		return suspensionReason;
	}

	public Instant getSuspendedAt() {
		return suspendedAt;
	}

	public UUID getSuspendedBy() {
		return suspendedBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Long getVersion() {
		return version;
	}
}
