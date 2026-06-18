/**
 * Tenant-aware base convention — <strong>PHASE-2 SEED ONLY</strong> (Backend LLD §3.2/§12).
 *
 * <p>Phase 1 is effectively single-tenant: no {@code tenant_id} column, no tenant filter,
 * no {@code Tenant} entity. This package documents the convention a future
 * {@code @MappedSuperclass TenantAware} base entity would follow so a tenant dimension is
 * cheap to add. The multi-tenancy strategy (shared-schema vs schema-per-tenant vs
 * db-per-tenant) is deliberately undecided until the phase-2 HLD.
 */
package com.allpets.api.common.tenant;
