package com.casiq.workitem.persistence;

import com.casiq.usermanagement.persistence.ApplicationUserEntity;
import com.casiq.usermanagement.persistence.TenantEntity;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "work_item_internal_note")
public class WorkItemInternalNoteEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY) public Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tenant_id")
    public TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "execution_id")
    public WorkItemExecutionEntity execution;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "author_user_id")
    public ApplicationUserEntity author;
    @Column(nullable = false, columnDefinition = "TEXT") public String content;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
}
