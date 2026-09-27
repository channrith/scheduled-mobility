package com.mobility.core.identity.user;

import java.time.Instant;
import java.util.UUID;

import com.mobility.core.identity.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

/** A role granted to a user, optionally scoped (corporate roles carry the corporate_id). */
@Entity
@Table(schema = "identity", name = "user_roles")
public class UserRole {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id")
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Column(name = "scope_id")
	private UUID scopeId;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected UserRole() {
	}

	UserRole(User user, Role role, UUID scopeId) {
		if (role.isCorporate() != (scopeId != null)) {
			throw new IllegalArgumentException(role.isCorporate()
					? "Corporate role " + role + " requires a corporate scope"
					: "Role " + role + " must not have a scope");
		}
		this.id = UUID.randomUUID();
		this.user = user;
		this.role = role;
		this.scopeId = scopeId;
	}

	public Role getRole() {
		return role;
	}

	public UUID getScopeId() {
		return scopeId;
	}

	boolean matches(Role role, UUID scopeId) {
		return this.role == role && java.util.Objects.equals(this.scopeId, scopeId);
	}
}
