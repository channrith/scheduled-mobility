package com.mobility.core.corporate.member;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mobility.core.corporate.MemberRole;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CorporateMemberRepository extends JpaRepository<CorporateMember, UUID> {

	List<CorporateMember> findByAccountIdAndStatusOrderByCreatedAtAsc(UUID accountId, MemberStatus status);

	List<CorporateMember> findByAccountIdOrderByCreatedAtAsc(UUID accountId);

	/** Every lookup is scoped to the account, so another company's id is simply not found. */
	Optional<CorporateMember> findByIdAndAccountId(UUID id, UUID accountId);

	Optional<CorporateMember> findByAccountIdAndUserId(UUID accountId, UUID userId);

	long countByAccountIdAndRoleAndStatus(UUID accountId, MemberRole role, MemberStatus status);

	long countByAccountIdAndStatus(UUID accountId, MemberStatus status);
}
