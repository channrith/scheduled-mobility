package com.mobility.core.identity.user;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.mobility.core.identity.Role;
import com.mobility.core.shared.i18n.Language;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(schema = "identity", name = "users")
public class User {

	@Id
	private UUID id;

	@Column(name = "phone_e164", nullable = false, unique = true, length = 16)
	private String phoneE164;

	@Column(name = "telegram_id")
	private Long telegramId;

	@Column(name = "full_name")
	private String fullName;

	@Column(name = "preferred_lang", nullable = false)
	private Language preferredLang;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private UserStatus status;

	@OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
	private Set<UserRole> roles = new HashSet<>();

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected User() {
	}

	private User(String phoneE164, Language preferredLang) {
		this.id = UUID.randomUUID();
		this.phoneE164 = phoneE164;
		this.preferredLang = preferredLang;
		this.status = UserStatus.ACTIVE;
	}

	/** Self-registration through phone OTP always creates a passenger. */
	public static User registerPassenger(String phoneE164, Language preferredLang) {
		User user = new User(phoneE164, preferredLang);
		user.grant(Role.PASSENGER, null);
		return user;
	}

	public void grant(Role role, UUID scopeId) {
		if (roles.stream().noneMatch(r -> r.matches(role, scopeId))) {
			roles.add(new UserRole(this, role, scopeId));
		}
	}

	public void revoke(Role role, UUID scopeId) {
		roles.removeIf(r -> r.matches(role, scopeId));
	}

	public void changeStatus(UserStatus status) {
		this.status = status;
	}

	public boolean isActive() {
		return status == UserStatus.ACTIVE;
	}

	public UUID getId() {
		return id;
	}

	public String getPhoneE164() {
		return phoneE164;
	}

	public Long getTelegramId() {
		return telegramId;
	}

	public String getFullName() {
		return fullName;
	}

	public Language getPreferredLang() {
		return preferredLang;
	}

	public UserStatus getStatus() {
		return status;
	}

	public Set<UserRole> getRoles() {
		return Collections.unmodifiableSet(roles);
	}
}
