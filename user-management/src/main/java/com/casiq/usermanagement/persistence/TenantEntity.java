package com.casiq.usermanagement.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant")
public class TenantEntity extends PanacheEntityBase {
    @Id @GeneratedValue public UUID id;
    @Column(name = "company_code", nullable = false, length = 64) public String companyCode;
    @Column(name = "normalized_company_code", nullable = false, unique = true, length = 64) public String normalizedCompanyCode;
    @Column(name = "display_name", nullable = false, length = 160) public String displayName;
    @Column(nullable = false) public boolean active;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
