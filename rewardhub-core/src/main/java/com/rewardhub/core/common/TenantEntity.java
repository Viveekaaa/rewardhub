package com.rewardhub.core.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

/**
 * Base for tenant-scoped entities. Every row carries the owning organization's id;
 * queries against these tables must always be filtered by {@code orgId}.
 */
@Getter
@MappedSuperclass
public abstract class TenantEntity extends BaseEntity {

    @Setter
    @Column(name = "org_id", nullable = false, updatable = false)
    private Long orgId;
}
