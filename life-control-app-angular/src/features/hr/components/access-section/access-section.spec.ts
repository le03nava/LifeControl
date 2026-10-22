/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AccessSection } from './access-section';
import {
  AccessProvisioningOverview,
  AccessProvisioningTaskView,
} from '../../models/access-provisioning.models';

describe('AccessSection', () => {
  let fixture: ComponentFixture<AccessSection>;
  let component: AccessSection;

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
    requestedByName: 'Ada Lovelace',
    requestedAt: '2026-10-14T10:00:00',
    decidedBy: null,
    decidedAt: null,
    enabled: true,
    ...overrides,
  });

  const overview = (
    overrides: Partial<AccessProvisioningOverview> = {},
  ): AccessProvisioningOverview => ({
    keycloakUserId: 'keycloak-user-1',
    accountLinked: true,
    requiredRoles: ['lc-sales'],
    currentRoles: ['lc-hr-read'],
    roleDiff: { added: ['lc-live-added'], removed: ['lc-live-removed'] },
    frozenRoleDiff: { added: ['lc-frozen-added'], removed: ['lc-frozen-removed'] },
    openTask: null,
    history: [],
    claims: {
      companyId: ['company-1'],
      companyCountryId: ['country-1'],
      companyRegionId: ['region-1'],
      companyZoneId: ['zone-1'],
      companyStoreId: ['store-1'],
    },
    workerEnabled: true,
    ...overrides,
  });

  interface SetupOptions {
    overview?: AccessProvisioningOverview | null;
    loading?: boolean;
    error?: string | null;
    canWrite?: boolean;
    currentUserId?: string | null;
    employeeStatusName?: string | null;
    companyEmailDomain?: string | null;
  }

  /**
   * Mounts the component with **no** `HttpClient` and **no** `MatDialog` provider: the section
   * renders the overview and emits its intents, so the page owns both the reads and the dialogs. A
   * hidden dependency would fail here with a `NullInjectorError` instead of passing silently.
   */
  async function setup(options: SetupOptions = {}): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ imports: [AccessSection] });

    fixture = TestBed.createComponent(AccessSection);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('overview', options.overview ?? null);
    fixture.componentRef.setInput('loading', options.loading ?? false);
    fixture.componentRef.setInput('error', options.error ?? null);
    fixture.componentRef.setInput('canWrite', options.canWrite ?? false);
    fixture.componentRef.setInput('currentUserId', options.currentUserId ?? null);
    fixture.componentRef.setInput('employeeStatusName', options.employeeStatusName ?? null);
    fixture.componentRef.setInput('companyEmailDomain', options.companyEmailDomain);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function element(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    return element().textContent ?? '';
  }

  function blockText(selector: string): string {
    return element().querySelector(selector)?.textContent ?? '';
  }

  function buttons(): HTMLButtonElement[] {
    return Array.from(element().querySelectorAll('button'));
  }

  function button(label: string): HTMLButtonElement | undefined {
    return buttons().find((candidate) => candidate.textContent?.trim() === label);
  }

  it('should create and render without an HTTP client or a dialog provider', async () => {
    await setup({ overview: overview() });

    expect(component).toBeTruthy();
  });

  it('should render the loading state instead of the overview', async () => {
    await setup({ overview: overview(), loading: true });

    expect(text()).toContain('Cargando el acceso…');
    expect(text()).not.toContain('Cuenta vinculada');
  });

  it('should render the error state and emit retry', async () => {
    await setup({ error: 'Error al cargar el acceso' });

    expect(text()).toContain('Error al cargar el acceso');

    const retry = vi.fn();
    component.retry.subscribe(retry);
    button('Reintentar')?.click();

    expect(retry).toHaveBeenCalledTimes(1);
  });

  it('should render the empty state when there is no overview yet', async () => {
    await setup({ overview: null });

    expect(text()).toContain('No hay información de acceso para este empleado');
  });

  it('should render the linked account state with its account id', async () => {
    await setup({ overview: overview() });

    expect(text()).toContain('Cuenta vinculada');
    expect(text()).toContain('keycloak-user-1');
  });

  it('should say the account is not linked when there is none, and render no account id', async () => {
    await setup({
      overview: overview({ accountLinked: false, keycloakUserId: null }),
    });

    expect(text()).toContain('Cuenta no vinculada');
    expect(text()).not.toContain('Cuenta vinculada');
  });

  it('should render the required roles against the current ones with the live diff', async () => {
    await setup({ overview: overview() });

    expect(text()).toContain('Requeridos');
    expect(text()).toContain('lc-sales');
    expect(text()).toContain('Actuales');
    expect(text()).toContain('lc-hr-read');
    expect(blockText('.live-diff')).toContain('lc-live-added');
    expect(blockText('.live-diff')).toContain('lc-live-removed');
  });

  it('should render the frozen set as the set the reviewer decides on, apart from the live diff', async () => {
    await setup({ overview: overview() });

    expect(text()).toContain('Conjunto congelado');
    // The frozen block carries the frozen set and never the live one...
    expect(blockText('.frozen-diff')).toContain('lc-frozen-added');
    expect(blockText('.frozen-diff')).toContain('lc-frozen-removed');
    expect(blockText('.frozen-diff')).not.toContain('lc-live-added');
    expect(blockText('.frozen-diff')).not.toContain('lc-live-removed');
    // ...and the live diff is still rendered in its own block.
    expect(blockText('.live-diff')).toContain('lc-live-added');
    expect(blockText('.live-diff')).not.toContain('lc-frozen-added');
  });

  it('should say there is no frozen set when frozenRoleDiff is null, and never draw an empty set', async () => {
    await setup({ overview: overview({ frozenRoleDiff: null }) });

    expect(blockText('.frozen-diff')).toContain('Sin conjunto congelado');
    expect(blockText('.frozen-diff')).not.toContain('Agregar');
    expect(blockText('.frozen-diff')).not.toContain('Quitar');
    // The live diff is a different fact and stays rendered.
    expect(blockText('.live-diff')).toContain('lc-live-added');
  });

  it('should render an empty role set as an em dash instead of an empty string', async () => {
    await setup({
      overview: overview({
        requiredRoles: [],
        roleDiff: { added: [], removed: [] },
      }),
    });

    const roleValues = Array.from(element().querySelectorAll('.role-values')).map(
      (node) => node.textContent?.trim() ?? '',
    );
    expect(roleValues).toContain('—');
  });

  it('should render the five claim attributes as the token would carry them', async () => {
    await setup({ overview: overview() });

    const names = Array.from(element().querySelectorAll('.claim-name')).map(
      (node) => node.textContent?.trim() ?? '',
    );
    expect(names).toEqual([
      'company_id',
      'company_country_id',
      'company_region_id',
      'company_zone_id',
      'company_store_id',
    ]);
    expect(blockText('.claims-block')).toContain('store-1');
  });

  it('should render a claim level that is not held as an em dash', async () => {
    await setup({
      overview: overview({
        claims: {
          companyId: ['company-1'],
          companyCountryId: [],
          companyRegionId: [],
          companyZoneId: [],
          companyStoreId: [],
        },
      }),
    });

    const values = Array.from(element().querySelectorAll('.claim-values')).map(
      (node) => node.textContent?.trim() ?? '',
    );
    expect(values[0]).toBe('company-1');
    expect(values.slice(1)).toEqual(['—', '—', '—', '—']);
  });

  it('should render the open task with its state, reason and attempts against the ceiling', async () => {
    await setup({
      overview: overview({
        openTask: task({
          status: 'FAILED',
          attempts: 2,
          maxAttempts: 3,
          lastError: 'Keycloak 500',
        }),
      }),
    });

    expect(text()).toContain('Fallida');
    expect(text()).toContain('Keycloak 500');
    expect(text()).toContain('2 / 3');
  });

  it('should render the history of the employee', async () => {
    await setup({
      overview: overview({
        history: [
          task({ id: 'task-new', kind: 'ACTIVATE', status: 'APPLIED' }),
          task({ id: 'task-old', kind: 'DEACTIVATE', status: 'REJECTED' }),
        ],
      }),
    });

    expect(blockText('.history-block')).toContain('Activación');
    expect(blockText('.history-block')).toContain('Desactivación');
    expect(blockText('.history-block')).toContain('Aplicada');
    expect(blockText('.history-block')).toContain('Rechazada');
  });

  it('should offer no write control to a caller without a write role', async () => {
    await setup({ overview: overview(), canWrite: false });

    expect(button('Solicitar acceso')).toBeUndefined();
    expect(button('Deshabilitar')).toBeUndefined();
    expect(button('Reenviar invitación')).toBeUndefined();
    expect(button('Revisar solicitud')).toBeUndefined();
  });

  it('should offer apply, deactivate and resend to a write role when no task is open', async () => {
    await setup({ overview: overview(), canWrite: true });

    expect(button('Solicitar acceso')).toBeDefined();
    expect(button('Deshabilitar')).toBeDefined();
    expect(button('Reenviar invitación')).toBeDefined();
  });

  it('should hide apply and resend and state the reason when the employee is terminated (T84)', async () => {
    await setup({
      overview: overview(),
      canWrite: true,
      employeeStatusName: 'Terminated',
      companyEmailDomain: 'acme.example',
    });

    // Absent, not disabled, and the operator is told which path applies.
    expect(button('Solicitar acceso')).toBeUndefined();
    expect(button('Reenviar invitación')).toBeUndefined();
    // Deactivate is not refused for a terminated employee, so it stays.
    expect(button('Deshabilitar')).toBeDefined();
    expect(text()).toContain('dado de baja');
  });

  it('should hide apply and resend and state the reason when the company has no email domain (T68/T85)', async () => {
    await setup({
      overview: overview(),
      canWrite: true,
      employeeStatusName: 'Active',
      companyEmailDomain: null,
    });

    expect(button('Solicitar acceso')).toBeUndefined();
    expect(button('Reenviar invitación')).toBeUndefined();
    expect(button('Deshabilitar')).toBeDefined();
    expect(text()).toContain('dominio de correo');
    expect(text()).not.toContain('dado de baja');
  });

  it('should treat a blank email domain as no domain', async () => {
    await setup({
      overview: overview(),
      canWrite: true,
      employeeStatusName: 'Active',
      companyEmailDomain: '   ',
    });

    expect(button('Solicitar acceso')).toBeUndefined();
    expect(text()).toContain('dominio de correo');
  });

  it('should offer apply and resend in the ordinary case: not terminated and a company domain', async () => {
    await setup({
      overview: overview(),
      canWrite: true,
      employeeStatusName: 'Active',
      companyEmailDomain: 'acme.example',
    });

    expect(button('Solicitar acceso')).toBeDefined();
    expect(button('Reenviar invitación')).toBeDefined();
    expect(text()).not.toContain('dado de baja');
    expect(text()).not.toContain('dominio de correo');
  });

  it('should gate on termination independently of a present company domain', async () => {
    await setup({
      overview: overview(),
      canWrite: true,
      employeeStatusName: 'Terminated',
      companyEmailDomain: 'acme.example',
    });

    expect(button('Solicitar acceso')).toBeUndefined();
    expect(text()).toContain('dado de baja');
    expect(text()).not.toContain('dominio de correo');
  });

  it('should gate on the missing domain independently of a non-terminated employee', async () => {
    await setup({
      overview: overview(),
      canWrite: true,
      employeeStatusName: 'Active',
      companyEmailDomain: null,
    });

    expect(button('Solicitar acceso')).toBeUndefined();
    expect(text()).toContain('dominio de correo');
    expect(text()).not.toContain('dado de baja');
  });

  it('should match the Terminated name case-insensitively, as the server does', async () => {
    await setup({
      overview: overview(),
      canWrite: true,
      employeeStatusName: 'terminated',
      companyEmailDomain: 'acme.example',
    });

    expect(button('Solicitar acceso')).toBeUndefined();
    expect(text()).toContain('dado de baja');
  });

  it('should not fire the domain gate while the company is not resolved yet', async () => {
    // `undefined` is "unknown", not "missing": the companies read is independent and may still be
    // in flight, and hiding a valid control on missing data would be its own defect.
    await setup({
      overview: overview(),
      canWrite: true,
      employeeStatusName: 'Active',
      companyEmailDomain: undefined,
    });

    expect(button('Solicitar acceso')).toBeDefined();
    expect(button('Reenviar invitación')).toBeDefined();
  });

  it('should emit apply and deactivate from their controls', async () => {
    await setup({ overview: overview(), canWrite: true });

    const applied = vi.fn();
    const deactivated = vi.fn();
    component.apply.subscribe(applied);
    component.deactivate.subscribe(deactivated);
    button('Solicitar acceso')?.click();
    button('Deshabilitar')?.click();

    expect(applied).toHaveBeenCalledTimes(1);
    expect(deactivated).toHaveBeenCalledTimes(1);
  });

  it('should not offer apply or deactivate while a task is open, because the API answers 409', async () => {
    await setup({ overview: overview({ openTask: task() }), canWrite: true });

    expect(button('Solicitar acceso')).toBeUndefined();
    expect(button('Deshabilitar')).toBeUndefined();
  });

  it('should offer retry only for a failed open task', async () => {
    await setup({ overview: overview({ openTask: task({ status: 'PENDING' }) }), canWrite: true });
    expect(button('Reintentar solicitud')).toBeUndefined();

    await setup({ overview: overview({ openTask: task({ status: 'FAILED' }) }), canWrite: true });
    expect(button('Reintentar solicitud')).toBeDefined();

    const emitted = vi.fn();
    component.retryRequest.subscribe(emitted);
    button('Reintentar solicitud')?.click();
    expect(emitted).toHaveBeenCalledTimes(1);
  });

  it('should not offer resend when no account is linked, because the API answers 409', async () => {
    await setup({
      overview: overview({ accountLinked: false, keycloakUserId: null }),
      canWrite: true,
    });

    expect(button('Reenviar invitación')).toBeUndefined();
  });

  it('should emit resend from its control', async () => {
    await setup({ overview: overview(), canWrite: true });

    const emitted = vi.fn();
    component.resend.subscribe(emitted);
    button('Reenviar invitación')?.click();

    expect(emitted).toHaveBeenCalledTimes(1);
  });

  it('should offer the decision control for an approval-pending task and emit the task', async () => {
    await setup({
      overview: overview({ openTask: task({ id: 'task-42', status: 'APPROVAL_PENDING' }) }),
      canWrite: true,
    });

    const emitted = vi.fn();
    component.decisionRequested.subscribe(emitted);
    button('Revisar solicitud')?.click();

    expect(emitted).toHaveBeenCalledTimes(1);
    expect(emitted).toHaveBeenCalledWith(expect.objectContaining({ id: 'task-42' }));
  });

  it('should not offer the decision control for a task that is not awaiting approval', async () => {
    await setup({ overview: overview({ openTask: task({ status: 'RUNNING' }) }), canWrite: true });

    expect(button('Revisar solicitud')).toBeUndefined();
  });

  it('should hide the decision control when the caller requested the task themselves (O3)', async () => {
    await setup({
      overview: overview({ openTask: task({ requestedBy: 'caller-sub' }) }),
      canWrite: true,
      currentUserId: 'caller-sub',
    });

    expect(button('Revisar solicitud')).toBeUndefined();
    expect(text()).toContain('No podés decidir tu propia solicitud');
  });

  it('should offer the decision control when the requester is someone else', async () => {
    await setup({
      overview: overview({ openTask: task({ requestedBy: 'someone-else' }) }),
      canWrite: true,
      currentUserId: 'caller-sub',
    });

    expect(button('Revisar solicitud')).toBeDefined();
    expect(text()).not.toContain('No podés decidir tu propia solicitud');
  });

  it('should offer the decision control when the caller identity is unknown', async () => {
    // With no `sub` the section cannot prove a self-request, and the API stays the authority.
    await setup({
      overview: overview({ openTask: task({ requestedBy: 'someone-else' }) }),
      canWrite: true,
      currentUserId: null,
    });

    expect(button('Revisar solicitud')).toBeDefined();
  });

  it('should show the disabled-worker notice when the API reports the worker off and no request is open (T5)', async () => {
    await setup({ overview: overview({ workerEnabled: false }) });

    // The notice is the section's own promise: with the worker off, a request it would enqueue
    // is never applied, so it is stated before any request exists.
    expect(blockText('.worker-off-notice')).toContain('deshabilitado');
    expect(blockText('.worker-off-notice')).toContain('no se aplica por sí sola');
    expect(blockText('.worker-off-notice')).toContain('WORKER_ENABLED=true');
  });

  it('should show the disabled-worker notice while a request is open too, because it will not advance on its own (T5)', async () => {
    await setup({
      overview: overview({ workerEnabled: false, openTask: task({ status: 'PENDING' }) }),
    });

    // A `Pendiente` task and a task nobody will ever pick up look identical: the notice is what
    // tells them apart, and it must not be gated on there being no open task.
    expect(button('Solicitar acceso')).toBeUndefined();
    expect(blockText('.worker-off-notice')).toContain('no se aplica por sí sola');
  });

  it('should not show the worker notice when the API reports the worker enabled', async () => {
    await setup({ overview: overview({ workerEnabled: true }) });

    expect(element().querySelector('.worker-off-notice')).toBeNull();
  });

  it('should treat an absent workerEnabled as unknown and show no notice', async () => {
    // A backend older than the additive field answers no `workerEnabled`: unknown, never disabled.
    await setup({ overview: overview({ workerEnabled: undefined }) });

    expect(element().querySelector('.worker-off-notice')).toBeNull();
  });
});
