package com.mobility.core.corporate.member;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.corporate.account.CorporateAccountService;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CostCenterService {

	private final CostCenterRepository costCenters;

	private final CorporateAccountService accounts;

	private final AuditLog auditLog;

	CostCenterService(CostCenterRepository costCenters, CorporateAccountService accounts, AuditLog auditLog) {
		this.costCenters = costCenters;
		this.accounts = accounts;
		this.auditLog = auditLog;
	}

	@Transactional(readOnly = true)
	public List<CostCenter> list(UUID corporateId) {
		accounts.get(corporateId);
		return costCenters.findByAccountIdOrderByCode(corporateId);
	}

	@Transactional
	public CostCenter create(UUID corporateId, String code, String name) {
		accounts.requireActive(corporateId);
		String normalized = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
		if (normalized.isEmpty() || normalized.length() > 30 || name == null || name.isBlank()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation");
		}
		if (costCenters.existsByAccountIdAndCode(corporateId, normalized)) {
			throw new ApiException(HttpStatus.CONFLICT, "corporate.cost-center-code-taken");
		}
		CostCenter costCenter = costCenters.saveAndFlush(new CostCenter(corporateId, normalized, name.strip()));
		auditLog.record("corporate.cost-center.created", "cost_center", costCenter.getId(),
				Map.of("corporateId", corporateId));
		return costCenter;
	}

	@Transactional
	public CostCenter update(UUID corporateId, UUID costCenterId, String name, boolean active, long expectedVersion) {
		accounts.requireActive(corporateId);
		CostCenter costCenter = get(corporateId, costCenterId);
		if (!Objects.equals(costCenter.getVersion(), expectedVersion)) {
			throw new ApiException(HttpStatus.CONFLICT, "concurrent-update");
		}
		if (name == null || name.isBlank()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation");
		}
		costCenter.update(name.strip(), active);
		costCenters.saveAndFlush(costCenter);
		auditLog.record("corporate.cost-center.updated", "cost_center", costCenterId,
				Map.of("corporateId", corporateId, "active", active));
		return costCenter;
	}

	/** @throws ApiException 404 also when the cost centre belongs to another company */
	@Transactional(readOnly = true)
	public CostCenter get(UUID corporateId, UUID costCenterId) {
		return costCenters.findByIdAndAccountId(costCenterId, corporateId)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "corporate.cost-center-not-found"));
	}
}
