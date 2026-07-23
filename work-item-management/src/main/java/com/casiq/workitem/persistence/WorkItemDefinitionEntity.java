package com.casiq.workitem.persistence;

import com.casiq.usermanagement.persistence.TenantEntity;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "work_item_definition", uniqueConstraints = @UniqueConstraint(
        name = "uq_work_item_owner_type", columnNames = {"owner_tenant_id", "normalized_type"}))
public class WorkItemDefinitionEntity extends PanacheEntityBase {
    @Id @GeneratedValue public UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "owner_tenant_id") public TenantEntity ownerTenant;
    @Column(nullable = false, length = 64) public String type;
    @Column(name = "normalized_type", nullable = false, length = 64) public String normalizedType;
    @Column(name = "display_name", nullable = false, length = 160) public String displayName;
    @Column(name = "global_scope", nullable = false) public boolean globalScope;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "overrides_definition_id") public WorkItemDefinitionEntity overridesDefinition;
    @Column(nullable = false) public boolean active;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
