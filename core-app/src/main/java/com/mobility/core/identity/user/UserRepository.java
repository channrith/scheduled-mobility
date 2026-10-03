package com.mobility.core.identity.user;

import java.util.Optional;
import java.util.UUID;

import com.mobility.core.identity.Role;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

	@EntityGraph(attributePaths = "roles")
	Optional<User> findByPhoneE164(String phoneE164);

	@EntityGraph(attributePaths = "roles")
	Optional<User> findWithRolesById(UUID id);

	boolean existsByRolesRole(Role role);
}
