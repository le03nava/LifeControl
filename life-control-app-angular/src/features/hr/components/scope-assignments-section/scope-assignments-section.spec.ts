/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ScopeAssignmentsSection } from './scope-assignments-section';
import { ScopeAssignment, ScopeAssignmentDerivedScope } from '../../models/scope-assignment.models';

describe('ScopeAssignmentsSection', () => {
  let fixture: ComponentFixture<ScopeAssignmentsSection>;
  let component: ScopeAssignmentsSection;

  const scope = (
    overrides: Partial<ScopeAssignmentDerivedScope> = {},
  ): ScopeAssignmentDerivedScope => ({
    companyId: 'company-1',
    companyName: 'Acme Corp',
    companyCountryId: 'company-country-1',
    companyCountryName: 'México',
    companyRegionId: 'region-1',
    companyRegionName: 'Centro',
    companyZoneId: 'zone-1',
    companyZoneName: 'Zona Norte',
    ...overrides,
  });

  const assignment = (overrides: Partial<ScopeAssignment> = {}): ScopeAssignment => ({
    id: 'assignment-1',
    level: 'ZONE',
    companyCountryId: 'company-country-1',
    companyRegionId: 'region-1',
    companyZoneId: 'zone-1',
    companyStoreId: null,
    nodeName: 'Zona Norte',
    validFrom: '2026-01-01',
    validTo: null,
    enabled: true,
    derived: scope(),
    ...overrides,
  });

  interface SetupOptions {
    assignments?: ScopeAssignment[];
    loading?: boolean;
    error?: string | null;
    canWrite?: boolean;
    /** The derived company root's name; `null` while the page has not resolved the company. */
    companyName?: string | null;
  }

  /**
   * Mounts the component with **no** `HttpClient` and **no** `MatDialog` provider: the section
   * renders the list and emits its intents, so the page owns both the reads and the dialogs. A
   * hidden dependency would fail here with a `NullInjectorError` instead of passing silently.
   */
  async function setup(options: SetupOptions = {}): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ imports: [ScopeAssignmentsSection] });

    fixture = TestBed.createComponent(ScopeAssignmentsSection);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('assignments', options.assignments ?? []);
    fixture.componentRef.setInput('loading', options.loading ?? false);
    fixture.componentRef.setInput('error', options.error ?? null);
    fixture.componentRef.setInput('canWrite', options.canWrite ?? false);
    fixture.componentRef.setInput('companyName', options.companyName ?? 'Acme Corp');
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

  function buttons(): HTMLButtonElement[] {
    return Array.from(element().querySelectorAll('button'));
  }

  function button(label: string): HTMLButtonElement | undefined {
    return buttons().find((candidate) => candidate.textContent?.trim() === label);
  }

  function cellTextsOf(columnIndex: number): string[] {
    return Array.from(
      element().querySelectorAll(`td.mat-mdc-cell:nth-child(${columnIndex + 1})`),
    ).map((td) => td.textContent?.trim() ?? '');
  }

  /** The derived company root row above the table. */
  function companyRoot(): HTMLElement | null {
    return element().querySelector<HTMLElement>('.company-root');
  }

  it('should create and render without an HTTP client or a dialog provider', async () => {
    await setup({ assignments: [assignment()] });

    expect(component).toBeTruthy();
  });

  it('should render the row with its level, node, validity range, derived chain and status', async () => {
    await setup({ assignments: [assignment()] });

    expect(text()).toContain('Zona');
    expect(text()).toContain('Zona Norte');
    expect(text()).toContain('2026-01-01 – Sin fecha de fin');
    expect(text()).toContain('Acme Corp / México / Centro / Zona Norte');
    expect(text()).toContain('Abierta');
  });

  it('should name the level of each row, so a country and a zone row are distinguishable', async () => {
    await setup({
      assignments: [
        assignment({
          id: 'assignment-region',
          level: 'REGION',
          companyZoneId: null,
          nodeName: 'Centro',
          validFrom: '2026-01-01',
        }),
        assignment({ id: 'assignment-country', level: 'COUNTRY', nodeName: 'México' }),
      ],
    });

    expect(cellTextsOf(0)).toEqual(['Región', 'País']);
    expect(cellTextsOf(1)).toEqual(['Centro', 'México']);
  });

  it('should label a store row as Tienda and render the store as the node', async () => {
    await setup({
      assignments: [
        assignment({
          id: 'assignment-store',
          level: 'STORE',
          companyStoreId: 'store-1',
          nodeName: 'Tienda Centro',
        }),
      ],
    });

    expect(cellTextsOf(0)).toEqual(['Tienda']);
    expect(cellTextsOf(1)).toEqual(['Tienda Centro']);
    expect(text()).toContain('Acme Corp / México / Centro / Zona Norte');
  });

  it('should render the four assignable levels in one table', async () => {
    await setup({
      assignments: [
        assignment({ id: 'a-country', level: 'COUNTRY', nodeName: 'México' }),
        assignment({ id: 'a-region', level: 'REGION', nodeName: 'Centro' }),
        assignment({ id: 'a-zone', level: 'ZONE', nodeName: 'Zona Norte' }),
        assignment({
          id: 'a-store',
          level: 'STORE',
          companyStoreId: 'store-1',
          nodeName: 'Tienda Centro',
        }),
      ],
    });

    expect(cellTextsOf(0)).toEqual(['País', 'Región', 'Zona', 'Tienda']);
  });

  it('should render the company as the derived root row above the table', async () => {
    await setup({ assignments: [assignment()], companyName: 'Acme Corp' });

    const root = companyRoot();
    expect(root).not.toBeNull();
    expect(root?.textContent).toContain('Empresa');
    expect(root?.textContent).toContain('Acme Corp');
    // S3: the root is derived from the employee, so the screen renders no control for it.
    expect(root?.textContent).toContain('no es una asignación');
    expect(root?.querySelectorAll('button')).toHaveLength(0);
  });

  it('should render the company root even when nothing is assigned yet', async () => {
    // The root is what makes "the tree starts at the company" and "nothing was assigned yet" stop
    // looking the same, so the empty state must not drop it.
    await setup({ assignments: [], companyName: 'Acme Corp' });

    expect(companyRoot()).not.toBeNull();
    expect(text()).toContain('Acme Corp');
    expect(text()).toContain('ningún nivel de alcance asignado');
  });

  it('should state that the assignment bounds the scope and the role is what enables access', async () => {
    // S4: the tab shows a tenancy, not a permission. The section says so once, not in every cell.
    await setup({ assignments: [assignment()] });

    expect(text()).toContain(
      'La asignación limita el alcance del empleado; es el rol el que habilita el acceso',
    );
  });

  it('should render the rows in the order they arrive, without re-sorting them', async () => {
    // The endpoint returns newest first; the input is deliberately given oldest first, so a
    // component that re-sorted would produce the other order and fail here.
    await setup({
      assignments: [
        assignment({ id: 'assignment-old', nodeName: 'Sur', validFrom: '2024-01-01' }),
        assignment({ id: 'assignment-new', nodeName: 'Norte', validFrom: '2026-01-01' }),
      ],
    });

    expect(cellTextsOf(1)).toEqual(['Sur', 'Norte']);
  });

  it('should show the covered end date of a closed row', async () => {
    await setup({ assignments: [assignment({ validTo: '2026-06-30' })] });

    expect(text()).toContain('2026-01-01 – 2026-06-30');
    expect(text()).toContain('Cerrada');
  });

  it('should state that a soft-deleted row is disabled', async () => {
    await setup({ assignments: [assignment({ enabled: false })] });

    expect(text()).toContain('Deshabilitada');
  });

  it('should keep an unresolved chain level in place instead of shifting it', async () => {
    await setup({
      assignments: [
        assignment({
          derived: scope({
            companyCountryId: null,
            companyCountryName: null,
            companyRegionId: null,
            companyRegionName: null,
          }),
        }),
      ],
    });

    expect(text()).toContain('Acme Corp / — / — / Zona Norte');
  });

  it('should render the loading state instead of the table', async () => {
    await setup({ assignments: [assignment()], loading: true });

    expect(text()).toContain('Cargando alcance asignado…');
    expect(text()).not.toContain('Zona Norte');
  });

  it('should say that no level is assigned yet and that a scoped role then sees nothing', async () => {
    await setup({ assignments: [] });

    expect(text()).toContain('ningún nivel de alcance asignado');
    // The empty state names the consequence instead of only the empty table.
    expect(text()).toContain('un rol de alcance no ve ningún dato del árbol');
    // ...and it must not turn the derived company root into a source of access.
    expect(text()).toContain('no otorga acceso por sí sola');
  });

  it('should render the error state and emit retry', async () => {
    await setup({ error: 'Error al cargar el alcance asignado' });

    expect(text()).toContain('Error al cargar el alcance asignado');
    expect(text()).not.toContain('ningún nivel de alcance asignado');

    const retry = vi.fn();
    component.retry.subscribe(retry);
    button('Reintentar')?.click();

    expect(retry).toHaveBeenCalledTimes(1);
  });

  it('should offer no write control to a caller without a write role', async () => {
    await setup({ assignments: [assignment()], canWrite: false });

    expect(text()).not.toContain('Asignar alcance');
    expect(text()).not.toContain('Cerrar');
  });

  it('should offer the assign action and the close action of an open row to a write role', async () => {
    await setup({ assignments: [assignment()], canWrite: true });

    expect(button('Asignar alcance')).toBeDefined();
    expect(button('Cerrar')).toBeDefined();
  });

  it('should not offer to close a row the API would refuse, which is already closed', async () => {
    await setup({ assignments: [assignment({ validTo: '2026-06-30' })], canWrite: true });

    expect(button('Cerrar')).toBeUndefined();
    // The assign action stays: a second node is a new assignment.
    expect(button('Asignar alcance')).toBeDefined();
  });

  it('should not offer to close a disabled row', async () => {
    await setup({ assignments: [assignment({ enabled: false })], canWrite: true });

    expect(button('Cerrar')).toBeUndefined();
  });

  it('should emit assign', async () => {
    await setup({ assignments: [assignment()], canWrite: true });

    const emitted = vi.fn();
    component.assign.subscribe(emitted);
    button('Asignar alcance')?.click();

    expect(emitted).toHaveBeenCalledTimes(1);
  });

  it('should emit the close intent with the row it belongs to', async () => {
    await setup({ assignments: [assignment({ id: 'assignment-9' })], canWrite: true });

    const emitted = vi.fn();
    component.closeRequested.subscribe(emitted);
    button('Cerrar')?.click();

    expect(emitted).toHaveBeenCalledTimes(1);
    expect(emitted).toHaveBeenCalledWith(expect.objectContaining({ id: 'assignment-9' }));
  });
});
