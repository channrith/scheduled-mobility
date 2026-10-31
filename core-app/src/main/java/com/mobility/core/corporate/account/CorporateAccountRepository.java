package com.mobility.core.corporate.account;

import java.util.List;
import java.util.UUID;

import com.mobility.core.corporate.AccountStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CorporateAccountRepository extends JpaRepository<CorporateAccount, UUID> {

	boolean existsByTaxId(String taxId);

	boolean existsByTaxIdAndIdNot(String taxId, UUID id);

	/** {@code pattern} is a lowercase LIKE pattern ("%" for all); statuses filters by status. */
	@Query("""
			select a from CorporateAccount a
			where a.status in :statuses
			  and (lower(a.legalName) like :pattern or lower(coalesce(a.displayName, '')) like :pattern or a.taxId = :taxId)""")
	Page<CorporateAccount> search(String pattern, String taxId, List<AccountStatus> statuses, Pageable pageable);
}
