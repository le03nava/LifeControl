/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AccessRequestsList } from './access-requests-list';
import { AccessProvisioningQueueEntry } from '../../models/access-provisioning.models';

describe('AccessRequestsList', () => {
  let fixture: ComponentFixture<AccessRequestsList>;
  let component: AccessRequestsList;

  const entry = (
    overrides: Partial<AccessProvisioningQueueEntry> = {},
  ): AccessProvisioningQueueEntry => ({
    taskId: 'task-1',
    kind: 'ACTIVATE',
    status: 'APPROVAL_PENDING',
    requestedBy: 'requester-sub',
    requestedByName: 'Ada Lovelace',
    requestedAt: '2026-10-14T10:00:00',
    employeeId: 'emp-1',
    employeeNumber: 'EMP-001',
    employeeFirstName: 'Ana',
    employeePaternalLastName: 'Gómez',
    employeeMaternalLastName: 'Ruiz',
    ...overrides,
  });

  interface SetupOptions {
    entries?: AccessProvisioningQueueEntry[];
    loading?: boolean;
    error?: string | null;
  }

  /**
   * Mounts the component with **no** `HttpClient` and **no** `MatDialog` provider: the list renders
   * the rows and emits its intents, so the page owns both the reads and the dialog. A hidden
   * dependency would fail here with a `NullInjectorError` instead of passing silently.
   */
  async function setup(options: SetupOptions = {}): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ imports: [AccessRequestsList] });

    fixture = TestBed.createComponent(AccessRequestsList);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('entries', options.entries ?? []);
    fixture.componentRef.setInput('loading', options.loading ?? false);
    fixture.componentRef.setInput('error', options.error ?? null);
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

  function headers(): string[] {
    return Array.from(element().querySelectorAll('th.mat-mdc-header-cell')).map(
      (th) => th.textContent?.trim() ?? '',
    );
  }

  function buttons(): HTMLButtonElement[] {
    return Array.from(element().querySelectorAll('button'));
  }

  function button(label: string): HTMLButtonElement | undefined {
    return buttons().find((candidate) => candidate.textContent?.trim() === label);
  }

  it('should create and render without an HTTP client or a dialog provider', async () => {
    await setup({ entries: [entry()] });

    expect(component).toBeTruthy();
  });

  it('should render the loading state instead of the rows', async () => {
    await setup({ entries: [entry()], loading: true });

    expect(text()).toContain('Cargando solicitudes');
    expect(text()).not.toContain('EMP-001');
  });

  it('should render the error state and emit retry', async () => {
    await setup({ error: 'Error al cargar las solicitudes' });

    expect(text()).toContain('Error al cargar las solicitudes');

    const retry = vi.fn();
    component.retry.subscribe(retry);
    button('Reintentar')?.click();

    expect(retry).toHaveBeenCalledTimes(1);
  });

  it('should render the empty state when there are no waiting decisions', async () => {
    await setup({ entries: [] });

    expect(text()).toContain('No hay solicitudes pendientes de decisión');
    expect(element().querySelector('table')).toBeNull();
  });

  it('should render every required column header', async () => {
    await setup({ entries: [entry()] });

    expect(headers()).toEqual([
      'Empleado',
      'Tipo',
      'Solicitada por',
      'Fecha de solicitud',
      'Acciones',
    ]);
  });

  it('should render the employee number, the composed name, the kind, the requester and the date', async () => {
    await setup({ entries: [entry()] });

    expect(text()).toContain('EMP-001');
    expect(text()).toContain('Ana Gómez Ruiz');
    expect(text()).toContain('emp-1');
    expect(text()).toContain('Activación');
    expect(text()).toContain('Ada Lovelace');
    expect(text()).toContain('2026-10-14T10:00:00');
  });

  it('should compose the name without a trailing gap when there is no maternal last name', async () => {
    await setup({ entries: [entry({ employeeMaternalLastName: null })] });

    expect(text()).toContain('Ana Gómez');
    expect(text()).not.toContain('Ana Gómez ');
  });

  it('should fall back to the requester actor id when the name cannot be resolved (T93/G42)', async () => {
    await setup({ entries: [entry({ requestedByName: null })] });

    expect(text()).toContain('requester-sub');
  });

  it('should render an em dash when neither the requester name nor the actor id resolves', async () => {
    await setup({ entries: [entry({ requestedByName: null, requestedBy: null })] });

    expect(text()).toContain('—');
  });

  it('should emit reviewRequested with the row when Revisar solicitud is clicked', async () => {
    const row = entry();
    await setup({ entries: [row] });

    const requested = vi.fn();
    component.reviewRequested.subscribe(requested);
    button('Revisar solicitud')?.click();

    expect(requested).toHaveBeenCalledWith(row);
  });
});
