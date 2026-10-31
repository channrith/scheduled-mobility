package com.mobility.core.corporate.account;

import java.util.Optional;
import java.util.UUID;

import com.mobility.core.corporate.CorporateDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CorporateDirectoryService implements CorporateDirectory {

	private final CorporateAccountRepository accounts;

	CorporateDirectoryService(CorporateAccountRepository accounts) {
		this.accounts = accounts;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CorporateSummary> find(UUID corporateId) {
		return accounts.findById(corporateId)
			.map(a -> new CorporateSummary(a.getId(), a.getLegalName(), a.getDisplayName(), a.getStatus(), a.getCurrency(),
					a.getServiceFeePct()));
	}
}
