package com.mobility.core.corporate.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.corporate.AccountStatus;
import com.mobility.core.corporate.account.CorporateAccount.AccountDetails;
import com.mobility.core.identity.CurrentUserProvider;
import com.mobility.core.shared.web.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CorporateAccountService {

	/** Input from the API; normalized and validated here. */
	public record AccountInput(String legalName, String displayName, String taxId, Integer paymentTermsDays,
			Long creditLimitMinor, String currency, BigDecimal serviceFeePct) {
	}

	private final CorporateAccountRepository accounts;

	private final AccountStatusHistoryRepository history;

	private final AuditLog auditLog;

	private final CurrentUserProvider currentUser;

	private final Clock clock;

	CorporateAccountService(CorporateAccountRepository accounts, AccountStatusHistoryRepository history,
			AuditLog auditLog, CurrentUserProvider currentUser, Clock clock) {
		this.accounts = accounts;
		this.history = history;
		this.auditLog = auditLog;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	@Transactional
	public CorporateAccount create(AccountInput input) {
		AccountDetails details = validate(input);
		if (details.taxId() != null && accounts.existsByTaxId(details.taxId())) {
			throw new ApiException(HttpStatus.CONFLICT, "corporate.tax-id-taken");
		}
		CorporateAccount account = accounts.saveAndFlush(new CorporateAccount(details));
		recordHistory(account.getId(), null, AccountStatus.ACTIVE, null);
		auditLog.record("corporate.account.created", "corporate_account", account.getId(),
				Map.of("currency", account.getCurrency()));
		return account;
	}

	@Transactional
	public CorporateAccount update(UUID id, AccountInput input, long expectedVersion) {
		CorporateAccount account = get(id);
		if (!Objects.equals(account.getVersion(), expectedVersion)) {
			throw new ApiException(HttpStatus.CONFLICT, "concurrent-update");
		}
		AccountDetails details = validate(input);
		if (details.taxId() != null && accounts.existsByTaxIdAndIdNot(details.taxId(), id)) {
			throw new ApiException(HttpStatus.CONFLICT, "corporate.tax-id-taken");
		}
		account.apply(details);
		accounts.saveAndFlush(account);
		auditLog.record("corporate.account.updated", "corporate_account", id);
		return account;
	}

	@Transactional
	public CorporateAccount suspend(UUID id, String reason) {
		CorporateAccount account = get(id);
		String valid = account.suspend(reason, currentUser.require().userId(), clock.instant());
		accounts.saveAndFlush(account);
		recordHistory(id, AccountStatus.ACTIVE, AccountStatus.SUSPENDED, valid);
		// The reason stays in the status history; the audit log holds no free text.
		auditLog.record("corporate.account.suspended", "corporate_account", id,
				Map.of("from", AccountStatus.ACTIVE, "to", AccountStatus.SUSPENDED));
		return account;
	}

	@Transactional
	public CorporateAccount reactivate(UUID id, String reason) {
		CorporateAccount account = get(id);
		String valid = account.reactivate(reason);
		accounts.saveAndFlush(account);
		recordHistory(id, AccountStatus.SUSPENDED, AccountStatus.ACTIVE, valid);
		auditLog.record("corporate.account.reactivated", "corporate_account", id,
				Map.of("from", AccountStatus.SUSPENDED, "to", AccountStatus.ACTIVE));
		return account;
	}

	@Transactional(readOnly = true)
	public CorporateAccount get(UUID id) {
		return accounts.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "corporate.not-found"));
	}

	/** For changes to members and cost centres: the account must exist and be active. */
	@Transactional(readOnly = true)
	public CorporateAccount requireActive(UUID id) {
		CorporateAccount account = get(id);
		if (account.getStatus() != AccountStatus.ACTIVE) {
			throw new ApiException(HttpStatus.CONFLICT, "corporate.suspended");
		}
		return account;
	}

	@Transactional(readOnly = true)
	public Page<CorporateAccount> list(String q, AccountStatus status, int page, int size) {
		String query = q == null ? "" : q.strip();
		String pattern = "%" + query.toLowerCase(Locale.ROOT) + "%";
		String taxId = query.isEmpty() ? "" : normalizeTaxId(query);
		List<AccountStatus> statuses = status == null ? List.copyOf(EnumSet.allOf(AccountStatus.class)) : List.of(status);
		return accounts.search(pattern, taxId, statuses,
				PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by("legalName")));
	}

	@Transactional(readOnly = true)
	public List<AccountStatusHistory> history(UUID id) {
		return history.findByAccountIdOrderByOccurredAtAsc(id);
	}

	private void recordHistory(UUID accountId, AccountStatus from, AccountStatus to, String reason) {
		history.save(new AccountStatusHistory(accountId, from, to, reason, currentUser.current().map(u -> u.userId()).orElse(null),
				clock.instant()));
	}

	private static AccountDetails validate(AccountInput in) {
		String legalName = in.legalName() == null ? "" : in.legalName().strip();
		if (legalName.isEmpty()) {
			throw invalid("legalName");
		}
		String currency = in.currency() == null ? "USD" : in.currency().strip().toUpperCase(Locale.ROOT);
		if (!List.of("USD", "KHR").contains(currency)) {
			throw invalid("currency");
		}
		int terms = in.paymentTermsDays() == null ? 30 : in.paymentTermsDays();
		if (terms < 0 || terms > 120) {
			throw invalid("paymentTermsDays");
		}
		long limit = in.creditLimitMinor() == null ? 0 : in.creditLimitMinor();
		if (limit < 0) {
			throw invalid("creditLimitMinor");
		}
		BigDecimal fee = in.serviceFeePct() == null ? new BigDecimal("8.00") : in.serviceFeePct();
		if (fee.signum() < 0 || fee.compareTo(BigDecimal.valueOf(100)) > 0 || fee.scale() > 2) {
			throw invalid("serviceFeePct");
		}
		String display = in.displayName() == null || in.displayName().isBlank() ? null : in.displayName().strip();
		String taxId = in.taxId() == null || in.taxId().isBlank() ? null : normalizeTaxId(in.taxId());
		return new AccountDetails(legalName, display, taxId, terms, limit, currency, fee.setScale(2, RoundingMode.UNNECESSARY));
	}

	/** {@code "k001 - 901234567"} → {@code "K001-901234567"}. */
	static String normalizeTaxId(String raw) {
		return raw.strip().toUpperCase(Locale.ROOT).replaceAll("\\s+", "").replaceAll("-+", "-");
	}

	private static ApiException invalid(String field) {
		return new ApiException(HttpStatus.BAD_REQUEST, "corporate.invalid-field").withProperty("field", field);
	}
}
