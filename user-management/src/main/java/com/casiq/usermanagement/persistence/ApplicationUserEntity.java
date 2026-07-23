package com.casiq.usermanagement.persistence;

import com.casiq.usermanagement.domain.UserRole;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "application_user", uniqueConstraints =
        @UniqueConstraint(name = "uq_application_user_tenant_username", columnNames = {"tenant_id", "normalized_username"}))
public class ApplicationUserEntity extends PanacheEntityBase {
    @Id @GeneratedValue public UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tenant_id") public TenantEntity tenant;
    @Column(nullable = false, length = 128) public String username;
    @Column(name = "normalized_username", nullable = false, length = 128) public String normalizedUsername;
    @Column(name = "password_hash", nullable = false, length = 100) public String passwordHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) public UserRole role;
    @Column(name = "must_change_password", nullable = false) public boolean mustChangePassword;
    @Column(nullable = false) public boolean active;
    @Column(name = "password_changed_at") public Instant passwordChangedAt;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
