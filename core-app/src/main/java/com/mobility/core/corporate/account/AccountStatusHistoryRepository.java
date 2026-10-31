package com.mobility.core.corporate.account;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AccountStatusHistoryRepository extends JpaRepository<AccountStatusHistory, UUID> {

	List<AccountStatusHistory> findByAccountIdOrderByOccurredAtAsc(UUID accountId);
}
