package com.rewardhub.core.common;

/**
 * Thread-local holder for the current request's tenant (organization) id.
 *
 * <p>Populated by the web layer from the authenticated principal (API key / JWT),
 * read by services and repositories to scope all access, and always cleared at the
 * end of the request.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT_ORG_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setOrgId(Long orgId) {
        CURRENT_ORG_ID.set(orgId);
    }

    public static Long getOrgId() {
        return CURRENT_ORG_ID.get();
    }

    /** @throws IllegalStateException if no tenant is bound to the current request */
    public static long requireOrgId() {
        Long orgId = CURRENT_ORG_ID.get();
        if (orgId == null) {
            throw new IllegalStateException("No tenant (organization) bound to the current request");
        }
        return orgId;
    }

    public static void clear() {
        CURRENT_ORG_ID.remove();
    }
}
