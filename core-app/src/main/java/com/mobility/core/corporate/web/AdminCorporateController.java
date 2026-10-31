package com.mobility.core.corporate.web;

import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;

import com.mobility.core.corporate.AccountStatus;
import com.mobility.core.corporate.account.CorporateAccount;
import com.mobility.core.corporate.account.CorporateAccountService;
import com.mobility.core.corporate.account.CorporateAccountService.AccountInput;
import com.mobility.core.corporate.member.CorporateMemberRepository;
import com.mobility.core.corporate.member.CostCenterService;
import com.mobility.core.corporate.member.MemberService;
import com.mobility.core.corporate.member.MemberStatus;
import com.mobility.core.corporate.web.CorporateViews.AccountDetail;
import com.mobility.core.corporate.web.CorporateViews.AccountView;
import com.mobility.core.corporate.web.CorporateViews.MemberView;
import com.mobility.core.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/corporates")
@Tag(name = "Admin · Corporate accounts", description = "Platform management of corporate clients")
class AdminCorporateController {

	static final String VIEW = "hasAnyRole('ADMIN', 'SUPPORT', 'DISPATCHER', 'SAFETY_OFFICER')";

	private final CorporateAccountService accounts;

	private final MemberService members;

	private final CostCenterService costCenters;

	private final CorporateMemberRepository memberRepository;

	private final CorporateViews views;

	AdminCorporateController(CorporateAccountService accounts, MemberService members, CostCenterService costCenters,
			CorporateMemberRepository memberRepository, CorporateViews views) {
		this.accounts = accounts;
		this.members = members;
		this.costCenters = costCenters;
		this.memberRepository = memberRepository;
		this.views = views;
	}

	@Operation(summary = "Create a corporate account",
			description = "Money in minor units of `currency` (USD cents or KHR riel). Defaults: 30 payment days, "
					+ "credit limit 0, service fee 8.00 %. `taxId` (TIN) must be unique.")
	@PostMapping
	@PreAuthorize("hasRole('ADMIN')")
	ResponseEntity<AccountView> create(@Valid @RequestBody AccountRequest request) {
		CorporateAccount account = accounts.create(request.input());
		return ResponseEntity.created(URI.create("/api/v1/admin/corporates/" + account.getId()))
			.body(CorporateViews.account(account, true));
	}

	@Operation(summary = "List corporate accounts", description = "`q` matches legal or display name, or exactly the tax ID.")
	@GetMapping
	@PreAuthorize(VIEW)
	PageResponse<AccountView> list(@RequestParam(required = false) String q,
			@RequestParam(required = false) AccountStatus status, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return PageResponse.of(accounts.list(q, status, page, size), a -> CorporateViews.account(a, true));
	}

	@Operation(summary = "Account detail", description = "With the number of active members and the status history (reasons).")
	@GetMapping("/{corporateId}")
	@PreAuthorize(VIEW)
	AccountDetail get(@PathVariable UUID corporateId) {
		CorporateAccount account = accounts.get(corporateId);
		return new AccountDetail(CorporateViews.account(account, true),
				memberRepository.countByAccountIdAndStatus(corporateId, MemberStatus.ACTIVE),
				CorporateViews.history(accounts.history(corporateId)));
	}

	@Operation(summary = "Edit a corporate account", description = "Replaces the editable fields; `version` must match.")
	@PutMapping("/{corporateId}")
	@PreAuthorize("hasRole('ADMIN')")
	AccountView update(@PathVariable UUID corporateId, @Valid @RequestBody UpdateAccountRequest request) {
		return CorporateViews.account(accounts.update(corporateId, request.input(), request.version()), true);
	}

	@Operation(summary = "Add a company admin",
			description = "Grants CORPORATE_ADMIN for this company to the phone's user (created if new; an existing "
					+ "member is promoted).")
	@PostMapping("/{corporateId}/admins")
	@PreAuthorize("hasRole('ADMIN')")
	MemberView addAdmin(@PathVariable UUID corporateId, @Valid @RequestBody AddAdminRequest request) {
		var member = members.addAdmin(corporateId, request.phone(), request.fullName().strip());
		return views.members(java.util.List.of(member), costCenters.list(corporateId)).getFirst();
	}

	@Operation(summary = "ACTIVE → SUSPENDED", description = "`reason` (≥ 10 characters). Rides can no longer be charged to the account.")
	@PostMapping("/{corporateId}/suspend")
	@PreAuthorize("hasRole('ADMIN')")
	AccountView suspend(@PathVariable UUID corporateId, @RequestBody AccountReasonRequest request) {
		return CorporateViews.account(accounts.suspend(corporateId, request.reason()), true);
	}

	@Operation(summary = "SUSPENDED → ACTIVE", description = "`reason` (≥ 10 characters).")
	@PostMapping("/{corporateId}/reactivate")
	@PreAuthorize("hasRole('ADMIN')")
	AccountView reactivate(@PathVariable UUID corporateId, @RequestBody AccountReasonRequest request) {
		return CorporateViews.account(accounts.reactivate(corporateId, request.reason()), true);
	}

	record AccountRequest(@NotBlank @Size(max = 200) String legalName, @Size(max = 100) String displayName,
			@Size(max = 30) String taxId, Integer paymentTermsDays, Long creditLimitMinor, String currency,
			BigDecimal serviceFeePct) {

		AccountInput input() {
			return new AccountInput(legalName, displayName, taxId, paymentTermsDays, creditLimitMinor, currency, serviceFeePct);
		}
	}

	record UpdateAccountRequest(@NotNull Long version, @NotBlank @Size(max = 200) String legalName,
			@Size(max = 100) String displayName, @Size(max = 30) String taxId, Integer paymentTermsDays,
			Long creditLimitMinor, String currency, BigDecimal serviceFeePct) {

		AccountInput input() {
			return new AccountInput(legalName, displayName, taxId, paymentTermsDays, creditLimitMinor, currency, serviceFeePct);
		}
	}

	record AddAdminRequest(@NotBlank String phone, @NotBlank @Size(max = 200) String fullName) {
	}

	/** Reason length is checked by the account (consistent error code). */
	record AccountReasonRequest(String reason) {
	}
}
