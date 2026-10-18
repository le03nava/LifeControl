/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { AccessReviewDialog, AccessReviewDialogData } from './access-review-dialog';
import {
  AccessProvisioningOverview,
  AccessProvisioningRoleDiff,
  AccessProvisioningTaskView,
} from '../../models/access-provisioning.models';

describe('AccessReviewDialog', () => {
  let fixture: ComponentFixture<AccessReviewDialog>;
  let component: AccessReviewDialog;
  let dialogRef: { close: ReturnType<typeof vi.fn> };

  const task = (
    overrides: Partial<AccessProvisioningTaskView> = {},
  ): AccessProvisioningTaskView => ({
    id: 'task-1',
    kind: 'ACTIVATE',
    status: 'APPROVAL_PENDING',
    attempts: 0,
    maxAttempts: 3,
    lastError: null,
    nextAttemptAt: null,
    requestedBy: 'requester-sub',
    requestedByName: 'Marta Revisora',
    requestedAt: '2026-10-14T10:00:00',
    decidedBy: null,
    decidedAt: null,
    enabled: true,
    ...overrides,
  });

  const overview = (
    overrides: Partial<AccessProvisioningOverview> = {},
  ): AccessProvisioningOverview => ({
    keycloakUserId: null,
    accountLinked: false,
    requiredRoles: ['lc-employee', 'lc-salary'],
    currentRoles: ['lc-employee'],
    roleDiff: { added: ['lc-salary'], removed: [] },
    frozenRoleDiff: { added: ['lc-salary'], removed: ['lc-position'] },
    openTask: task(),
    history: [],
    claims: {
      companyId: ['company-1'],
      companyCountryId: [],
      companyRegionId: [],
      companyZoneId: [],
      companyStoreId: [],
    },
    ...overrides,
  });

  interface SetupOptions {
    overview?: Partial<AccessProvisioningOverview>;
    frozenRoleDiff?: AccessProvisioningRoleDiff | null;
    currentUserId?: string | null;
  }

  function setup(options: SetupOptions = {}): void {
    dialogRef = { close: vi.fn() };
    // `null` is a real, distinct value here ("the task froze nothing"), so it must not be replaced
    // by the default: only an absent option falls back.
    const frozenRoleDiff =
      options.frozenRoleDiff === undefined ? overview().frozenRoleDiff : options.frozenRoleDiff;
    const data: AccessReviewDialogData = {
      overview: overview({ ...options.overview, frozenRoleDiff }),
      currentUserId: options.currentUserId ?? 'other-sub',
      employeeNumber: 'EMP-001',
      employeeName: 'Ana Gómez Ruiz',
    };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [AccessReviewDialog],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: data },
      ],
    });

    fixture = TestBed.createComponent(AccessReviewDialog);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    return root().textContent ?? '';
  }

  function button(label: string): HTMLButtonElement | undefined {
    return Array.from(root().querySelectorAll('button')).find(
      (candidate) => candidate.textContent?.trim() === label,
    );
  }

  it('should create', () => {
    setup();
    expect(component).toBeTruthy();
  });

  it('should render the frozen set as roles to grant and roles to revoke (T92)', () => {
    setup({ frozenRoleDiff: { added: ['lc-salary'], removed: ['lc-position'] } });

    expect(text()).toContain('lc-salary');
    expect(text()).toContain('lc-position');
  });

  it('should say there is no frozen set when the task froze nothing, never an empty set (T92/G35)', () => {
    setup({ frozenRoleDiff: null });

    expect(text()).toContain('Sin conjunto congelado');
    expect(text()).not.toContain('Agregar:');
    expect(text()).not.toContain('Quitar:');
  });

  it('should render the requester display name when it is resolvable (T93)', () => {
    setup({ overview: { openTask: task({ requestedByName: 'Marta Revisora' }) } });

    expect(text()).toContain('Marta Revisora');
  });

  it('should fall back to the actor id when the requester name is null (T93/G42)', () => {
    setup({ overview: { openTask: task({ requestedByName: null, requestedBy: 'actor-uuid' }) } });

    expect(text()).toContain('actor-uuid');
  });

  it('should render the employee identity the page supplied', () => {
    setup();

    expect(text()).toContain('EMP-001');
    expect(text()).toContain('Ana Gómez Ruiz');
  });

  it('should offer Aprobar when the requester is somebody else (O3/T95)', () => {
    setup({
      overview: { openTask: task({ requestedBy: 'requester-sub' }) },
      currentUserId: 'other-sub',
    });

    expect(button('Aprobar')).toBeDefined();
  });

  it('should hide Aprobar and state why when the caller requested the task (O3/T95)', () => {
    setup({
      overview: { openTask: task({ requestedBy: 'my-own-sub' }) },
      currentUserId: 'my-own-sub',
    });

    expect(button('Aprobar')).toBeUndefined();
    expect(text()).toContain('No podés aprobar tu propia solicitud');
  });

  it('should keep Rechazar disabled until the reason has non-whitespace content (T95)', () => {
    setup();

    expect(button('Rechazar')?.disabled).toBe(true);

    component.reason.setValue('   ');
    fixture.detectChanges();
    expect(button('Rechazar')?.disabled).toBe(true);

    component.reason.setValue('El puesto no corresponde');
    fixture.detectChanges();
    expect(button('Rechazar')?.disabled).toBe(false);
  });

  it('should not close a rejection while the reason is blank (T95)', () => {
    setup();
    component.reason.setValue('   ');
    fixture.detectChanges();

    button('Rechazar')?.click();

    expect(dialogRef.close).not.toHaveBeenCalled();
  });

  it('should close with the approve result and perform no call', () => {
    setup();
    button('Aprobar')?.click();

    expect(dialogRef.close).toHaveBeenCalledWith({ decision: 'approve' });
  });

  it('should close a rejection with the trimmed reason (T95)', () => {
    setup();
    component.reason.setValue('  El puesto no corresponde  ');
    fixture.detectChanges();

    button('Rechazar')?.click();

    expect(dialogRef.close).toHaveBeenCalledWith({
      decision: 'reject',
      reason: 'El puesto no corresponde',
    });
  });

  it('should close with null on cancel', () => {
    setup();
    button('Cancelar')?.click();

    expect(dialogRef.close).toHaveBeenCalledWith(null);
  });
});
