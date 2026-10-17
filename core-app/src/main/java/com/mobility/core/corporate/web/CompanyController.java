package com.mobility.core.corporate.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.mobility.core.corporate.MemberPolicy;
import com.mobility.core.corporate.MemberRole;
import com.mobility.core.corporate.account.CorporateAccountService;
import com.mobility.core.corporate.member.CostCenterService;
import com.mobility.core.corporate.member.MemberService;
import com.mobility.core.corporate.member.MemberService.MemberInput;
import com.mobility.core.corporate.web.CorporateViews.AccountView;
import com.mobility.core.corporate.web.CorporateViews.CostCenterView;
import com.mobility.core.corporate.web.CorporateViews.MemberView;
import com.mobility.core.identity.Authz;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * One company's members, cost centres and policies. Changes: that company's CORPORATE_ADMIN or a platform
 * ADMIN. Reads: also staff. Every lookup is additionally filtered by {@code corporateId}, so ids of another
 * company are "not found".
 */
@RestController
@RequestMapping("/api/v1/corporates/{corporateId}")
@Tag(name = "Company", description = "A corporate client's members, cost centres and booking policies")
class CompanyController {

	static final String READ = "@authz.canManageCorporate(#corporateId) or hasAnyRole('ADMIN', 'SUPPORT', 'DISPATCHER', 'SAFETY_OFFICER')";

	static final String WRITE = "@authz.canManageCorporate(#corporateId)";

	private final CorporateAccountService accounts;

	private final MemberService members;

	private final CostCenterService costCenters;

	private final CorporateViews views;

	private final Authz authz;

	CompanyController(CorporateAccountService accounts, MemberService members, CostCenterService costCenters,
			CorporateViews views, Authz authz) {
		this.accounts = accounts;
		this.members = members;
		this.costCenters = costCenters;
		this.views = views;
		this.authz = authz;
	}

	@Operation(summary = "The company's account (read-only)")
	@GetMapping
	@PreAuthorize(READ)
	AccountView account(@PathVariable UUID corporateId) {
		return CorporateViews.account(accounts.get(corporateId), authz.isStaff());
	}

	// --- Members -------------------------------------------------------------------------------

	@Operation(summary = "Members", description = "Active members; `includeRemoved=true` adds removed ones.")
	@GetMapping("/members")
	@PreAuthorize(READ)
	List<MemberView> members(@PathVariable UUID corporateId, @RequestParam(defaultValue = "false") boolean includeRemoved) {
		return views.members(members.list(corporateId, includeRemoved), costCenters.list(corporateId));
	}

	@Operation(summary = "Add a member",
			description = "By phone; an existing user is reused. CORPORATE_ADMIN and CORPORATE_BOOKER get that role "
					+ "for this company; a RIDER rides on the account with a normal passenger login. `policy` limits "
					+ "what the member may book (missing fields = no limit).")
	@PostMapping("/members")
	@PreAuthorize(WRITE)
	ResponseEntity<MemberView> addMember(@PathVariable UUID corporateId, @Valid @RequestBody AddMemberRequest request) {
		var member = members.add(corporateId, request.phone(), request.fullName().strip(), request.input());
		return ResponseEntity.created(URI.create("/api/v1/corporates/" + corporateId + "/members/" + member.getId()))
			.body(views.members(List.of(member), costCenters.list(corporateId)).getFirst());
	}

	@Operation(summary = "Edit a member", description = "Role, cost centre, employee reference and policy; `version` must match. The last admin cannot be demoted.")
	@PutMapping("/members/{memberId}")
	@PreAuthorize(WRITE)
	MemberView updateMember(@PathVariable UUID corporateId, @PathVariable UUID memberId,
			@Valid @RequestBody UpdateMemberRequest request) {
		var member = members.update(corporateId, memberId, request.input(), request.version());
		return views.members(List.of(member), costCenters.list(corporateId)).getFirst();
	}

	@Operation(summary = "Remove a member", description = "Revokes their role for this company. The last admin cannot be removed.")
	@DeleteMapping("/members/{memberId}")
	@PreAuthorize(WRITE)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void removeMember(@PathVariable UUID corporateId, @PathVariable UUID memberId) {
		members.remove(corporateId, memberId);
	}

	// --- Cost centres --------------------------------------------------------------------------

	@Operation(summary = "Cost centres")
	@GetMapping("/cost-centers")
	@PreAuthorize(READ)
	List<CostCenterView> costCenters(@PathVariable UUID corporateId) {
		return costCenters.list(corporateId).stream().map(CorporateViews::costCenter).toList();
	}

	@Operation(summary = "Add a cost centre", description = "`code` is unique within the company (stored uppercase).")
	@PostMapping("/cost-centers")
	@PreAuthorize(WRITE)
	ResponseEntity<CostCenterView> addCostCenter(@PathVariable UUID corporateId,
			@Valid @RequestBody CostCenterRequest request) {
		var costCenter = costCenters.create(corporateId, request.code(), request.name());
		return ResponseEntity.status(HttpStatus.CREATED).body(CorporateViews.costCenter(costCenter));
	}

	@Operation(summary = "Rename or (de)activate a cost centre")
	@PutMapping("/cost-centers/{costCenterId}")
	@PreAuthorize(WRITE)
	CostCenterView updateCostCenter(@PathVariable UUID corporateId, @PathVariable UUID costCenterId,
			@Valid @RequestBody UpdateCostCenterRequest request) {
		return CorporateViews.costCenter(
				costCenters.update(corporateId, costCenterId, request.name(), request.active(), request.version()));
	}

	record AddMemberRequest(@NotBlank String phone, @NotBlank @Size(max = 200) String fullName, @NotNull MemberRole role,
			UUID costCenterId, @Size(max = 50) String employeeRef, MemberPolicy policy) {

		MemberInput input() {
			return new MemberInput(role, costCenterId, employeeRef, policy);
		}
	}

	record UpdateMemberRequest(@NotNull Long version, @NotNull MemberRole role, UUID costCenterId,
			@Size(max = 50) String employeeRef, MemberPolicy policy) {

		MemberInput input() {
			return new MemberInput(role, costCenterId, employeeRef, policy);
		}
	}

	record CostCenterRequest(@NotBlank @Size(max = 30) String code, @NotBlank @Size(max = 100) String name) {
	}

	record UpdateCostCenterRequest(@NotNull Long version, @NotBlank @Size(max = 100) String name, boolean active) {
	}
}
