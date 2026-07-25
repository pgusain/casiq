package com.casiq.workitem.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "work_item_status", uniqueConstraints = @UniqueConstraint(
        name = "uq_work_item_status_code", columnNames = {"definition_id", "normalized_code"}))
public class WorkItemStatusEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY) public Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "definition_id") public WorkItemDefinitionEntity definition;
    @Column(nullable = false, length = 64) public String code;
    @Column(name = "normalized_code", nullable = false, length = 64) public String normalizedCode;
    @Column(name = "display_name", nullable = false, length = 160) public String displayName;
    @Column(name = "initial_status", nullable = false) public boolean initialStatus;
    @Column(name = "terminal_status", nullable = false) public boolean terminalStatus;
    @Column(name = "sort_order", nullable = false) public int sortOrder;
}
