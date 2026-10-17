package com.mobility.core.corporate.member;

import java.time.Instant;
import java.util.UUID;

import com.mobility.core.corporate.MemberPolicy;
import com.mobility.core.corporate.MemberRole;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(schema = "corporate", name = "corporate_members")
public class CorporateMember {

	@Id
	private UUID id;

	@Column(name = "account_id", nullable = false)
	private UUID accountId;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MemberRole role;

	@Column(name = "cost_center_id")
	private UUID costCenterId;

	@Column(name = "employee_ref")
	private String employeeRef;

	@Convert(converter = MemberPolicyConverter.class)
	@ColumnTransformer(write = "?::jsonb")
	@Column(nullable = false, columnDefinition = "jsonb")
	private MemberPolicy policy;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MemberStatus status;

	@Column(name = "removed_at")
	private Instant removedAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected CorporateMember() {
	}

	CorporateMember(UUID accountId, UUID userId) {
		this.id = UUID.randomUUID();
		this.accountId = accountId;
		this.userId = userId;
		this.status = MemberStatus.ACTIVE;
		this.policy = MemberPolicy.UNRESTRICTED;
	}

	void apply(MemberRole role, UUID costCenterId, String employeeRef, MemberPolicy policy) {
		this.role = role;
		this.costCenterId = costCenterId;
		this.employeeRef = employeeRef;
		this.policy = policy == null ? MemberPolicy.UNRESTRICTED : policy;
	}

	void remove(Instant now) {
		this.status = MemberStatus.REMOVED;
		this.removedAt = now;
	}

	void restore() {
		this.status = MemberStatus.ACTIVE;
		this.removedAt = null;
	}

	public boolean isActive() {
		return status == MemberStatus.ACTIVE;
	}

	public UUID getId() {
		return id;
	}

	public UUID getAccountId() {
		return accountId;
	}

	public UUID getUserId() {
		return userId;
	}

	public MemberRole getRole() {
		return role;
	}

	public UUID getCostCenterId() {
		return costCenterId;
	}

	public String getEmployeeRef() {
		return employeeRef;
	}

	public MemberPolicy getPolicy() {
		return policy;
	}

	public MemberStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Long getVersion() {
		return version;
	}
}
