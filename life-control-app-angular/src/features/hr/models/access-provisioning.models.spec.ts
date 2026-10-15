/// <reference types="vitest/globals" />
import {
  ACCESS_CLAIM_FIELDS,
  ACCESS_TASK_KIND_LABELS,
  ACCESS_TASK_STATUS_LABELS,
  AccessProvisioningTaskKind,
  AccessProvisioningTaskStatus,
  accessTaskKindLabel,
  accessTaskStatusLabel,
} from './access-provisioning.models';

describe('access provisioning models', () => {
  describe('ACCESS_TASK_STATUS_LABELS', () => {
    it('should map the seven statuses of AccessProvisioningTaskStatus to their Spanish labels', () => {
      // Literal pin: these seven are the Java enum `AccessProvisioningTaskStatus`, and this map is
      // the only place the UI turns them into Spanish copy.
      expect(ACCESS_TASK_STATUS_LABELS).toEqual({
        PENDING: 'Pendiente',
        APPROVAL_PENDING: 'Esperando aprobación',
        RUNNING: 'En ejecución',
        APPLIED: 'Aplicada',
        FAILED: 'Fallida',
        REJECTED: 'Rechazada',
        SUPERSEDED: 'Reemplazada',
      });
    });

    it('should carry every status the wire can produce and no extra key', () => {
      expect(Object.keys(ACCESS_TASK_STATUS_LABELS)).toHaveLength(7);
    });
  });

  describe('ACCESS_TASK_KIND_LABELS', () => {
    it('should map the three kinds of AccessProvisioningTaskKind to their Spanish labels', () => {
      expect(ACCESS_TASK_KIND_LABELS).toEqual({
        ACTIVATE: 'Activación',
        DEACTIVATE: 'Desactivación',
        RECONCILE: 'Reconciliación',
      });
    });
  });

  describe('accessTaskStatusLabel', () => {
    it('should translate every status', () => {
      const statuses: AccessProvisioningTaskStatus[] = [
        'PENDING',
        'APPROVAL_PENDING',
        'RUNNING',
        'APPLIED',
        'FAILED',
        'REJECTED',
        'SUPERSEDED',
      ];
      for (const status of statuses) {
        expect(accessTaskStatusLabel(status)).toBe(ACCESS_TASK_STATUS_LABELS[status]);
      }
    });

    it('should fall back to the raw value for an unknown status', () => {
      expect(accessTaskStatusLabel('CANCELLED' as AccessProvisioningTaskStatus)).toBe('CANCELLED');
    });

    it('should not resolve a prototype member', () => {
      expect(accessTaskStatusLabel('constructor' as AccessProvisioningTaskStatus)).toBe(
        'constructor',
      );
    });
  });

  describe('accessTaskKindLabel', () => {
    it('should translate every kind', () => {
      const kinds: AccessProvisioningTaskKind[] = ['ACTIVATE', 'DEACTIVATE', 'RECONCILE'];
      for (const kind of kinds) {
        expect(accessTaskKindLabel(kind)).toBe(ACCESS_TASK_KIND_LABELS[kind]);
      }
    });

    it('should fall back to the raw value for an unknown kind', () => {
      expect(accessTaskKindLabel('REVOKE' as AccessProvisioningTaskKind)).toBe('REVOKE');
    });

    it('should not resolve a prototype member', () => {
      expect(accessTaskKindLabel('toString' as AccessProvisioningTaskKind)).toBe('toString');
    });
  });

  describe('ACCESS_CLAIM_FIELDS', () => {
    it('should name the five claim attributes in the order the token carries them', () => {
      // Literal pin: the names are the JWT claim names the realm's protocol mappers emit
      // (company_id, company_country_id, …), not the DTO's camelCase field names.
      expect(ACCESS_CLAIM_FIELDS).toEqual([
        { field: 'companyId', claim: 'company_id' },
        { field: 'companyCountryId', claim: 'company_country_id' },
        { field: 'companyRegionId', claim: 'company_region_id' },
        { field: 'companyZoneId', claim: 'company_zone_id' },
        { field: 'companyStoreId', claim: 'company_store_id' },
      ]);
    });
  });
});
