package com.casiq.workaccount.core.persistence;

import com.casiq.usermanagement.persistence.TenantEntity;
import com.casiq.workitem.persistence.WorkItemDefinitionEntity;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "work_account", uniqueConstraints = @UniqueConstraint(
        name = "uq_work_account_tenant_email", columnNames = {"tenant_id", "normalized_email_id"}))
public class WorkAccountEntity extends PanacheEntityBase {
    @Id @GeneratedValue public UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tenant_id") public TenantEntity tenant;
    @Column(name = "email_id", nullable = false, length = 320) public String emailId;
    @Column(name = "normalized_email_id", nullable = false, length = 320) public String normalizedEmailId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "work_item_definition_id") public WorkItemDefinitionEntity workItemDefinition;
    @Column(name = "work_item", nullable = false, length = 64) public String legacyWorkItemType;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "provider_code") public EmailProviderEntity provider;
    @Column(name = "refresh_token", columnDefinition = "TEXT") public String refreshToken;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
