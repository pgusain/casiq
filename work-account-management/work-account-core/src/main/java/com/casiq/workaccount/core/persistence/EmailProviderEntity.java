package com.casiq.workaccount.core.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "email_provider_reference")
public class EmailProviderEntity extends PanacheEntityBase {
    @Id @Column(length = 32) public String code;
    @Column(name = "display_name", nullable = false, length = 80) public String displayName;
    @Column(nullable = false) public boolean active;
    @Column(name = "sort_order", nullable = false) public int sortOrder;
}
