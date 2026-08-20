package com.casiq.workitem.service;

import com.casiq.usermanagement.persistence.TenantEntity;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import org.jboss.logging.Logger;
import org.slf4j.MDC;

@ApplicationScoped
public class WorkItemNumberService {
    private static final long FIRST_WORK_ITEM_NUMBER = 100000L;
    private static final Logger LOG = Logger.getLogger(WorkItemNumberService.class);

    public long next(TenantEntity tenant) {
        MDC.put("tenantCode", String.valueOf(tenant.id));
        try {
            LOG.debugf("Allocating next work-item number tenantId=%s", tenant.id);
            var entityManager = Panache.getEntityManager();
            TenantEntity lockedTenant = entityManager.find(
                    TenantEntity.class, tenant.id, LockModeType.PESSIMISTIC_WRITE);
            if (lockedTenant == null) {
                throw new IllegalStateException("Tenant no longer exists");
            }

            Number currentMaximum = (Number) entityManager.createNativeQuery("""
                            SELECT MAX(work_item_number)
                            FROM work_item_execution
                            WHERE tenant_id = ?1
                            """)
                    .setParameter(1, tenant.id)
                    .getSingleResult();
            long nextNumber = currentMaximum == null
                    ? FIRST_WORK_ITEM_NUMBER
                    : currentMaximum.longValue() + 1L;
            LOG.debugf("Allocated tenant work-item number tenantId=%s workItemNumber=%d",
                    (Object) tenant.id, nextNumber);
            return nextNumber;
        } catch (RuntimeException | Error e) {
            LOG.errorf("Error allocating work-item number tenantId=%s", tenant.id, e);
            throw e;
        } finally {
            MDC.remove("tenantCode");
        }
    }
}
