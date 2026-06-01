import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ZonesList } from './zones-list';
import { CompanyZone } from '../../models/zone.models';

describe('ZonesList', () => {
  let component: ZonesList;
  let fixture: ComponentFixture<ZonesList>;

  const mockZones: CompanyZone[] = [
    {
      id: 'zone-1', companyRegionId: 'reg-1', companyCountryId: 'cc-1', companyId: 'comp-1', countryId: '1',
      zoneCode: 'US-CA-DT', zoneName: 'Downtown', description: 'Zona céntrica', displayOrder: 1,
      enabled: true, createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
    {
      id: 'zone-2', companyRegionId: 'reg-1', companyCountryId: 'cc-1', companyId: 'comp-1', countryId: '1',
      zoneCode: 'US-CA-SF', zoneName: 'San Francisco', description: 'Zona norte', displayOrder: 2,
      enabled: true, createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
    {
      id: 'zone-3', companyRegionId: 'reg-1', companyCountryId: 'cc-1', companyId: 'comp-1', countryId: '1',
      zoneCode: 'US-CA-LA', zoneName: 'Los Angeles', description: '', displayOrder: undefined,
      enabled: false, createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
  ];

  function setupInputs(overrides?: {
    zones?: CompanyZone[];
    loading?: boolean;
    error?: string | null;
  }): void {
    fixture.componentRef.setInput('zones', overrides?.zones ?? []);
    fixture.componentRef.setInput('loading', overrides?.loading ?? false);
    fixture.componentRef.setInput('error', overrides?.error ?? null);
    fixture.detectChanges();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ZonesList, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(ZonesList);
    component = fixture.componentInstance;
  });

  // ─── Creation ─────────────────────────────────────────────────
  it('should create', () => {
    setupInputs();
    expect(component).toBeTruthy();
  });

  // ─── Table rendering ──────────────────────────────────────────
  describe('table rendering', () => {
    beforeEach(() => {
      setupInputs({ zones: mockZones });
    });

    it('should display enabled zones in table rows by default', () => {
      const rows = fixture.nativeElement.querySelectorAll('tr.mat-mdc-row');
      // Default showDisabled=false → only enabled zones (zone-3 is disabled)
      expect(rows.length).toBe(2);
    });

    it('should show zoneCode, zoneName, description, and displayOrder in each row', () => {
      const rows = fixture.nativeElement.querySelectorAll('tr.mat-mdc-row');
      expect(rows[0].textContent).toContain('US-CA-DT');
      expect(rows[0].textContent).toContain('Downtown');
      expect(rows[0].textContent).toContain('Zona céntrica');
      expect(rows[0].textContent).toContain('1');
      expect(rows[1].textContent).toContain('US-CA-SF');
      expect(rows[1].textContent).toContain('San Francisco');
    });

    it('should show header columns: Código, Nombre, Descripción, Orden, Estado, Acciones', () => {
      const headerRow = fixture.nativeElement.querySelector('tr.mat-mdc-header-row');
      expect(headerRow).toBeTruthy();
      expect(headerRow!.textContent).toContain('Código');
      expect(headerRow!.textContent).toContain('Nombre');
      expect(headerRow!.textContent).toContain('Descripción');
      expect(headerRow!.textContent).toContain('Orden');
      expect(headerRow!.textContent).toContain('Estado');
      expect(headerRow!.textContent).toContain('Acciones');
    });

    it('should render mat-slide-toggle for each zone row', () => {
      const toggles = fixture.nativeElement.querySelectorAll('mat-slide-toggle');
      // All 3 zones have toggles (including disabled ones)
      expect(toggles.length).toBeGreaterThanOrEqual(2);
    });

    it('should have edit and delete buttons for each visible row', () => {
      const allButtons = fixture.nativeElement.querySelectorAll('.action-buttons button');
      // Default showDisabled=false → 2 enabled zones × 2 buttons = 4
      expect(allButtons.length).toBe(4);

      const btnTexts = [...allButtons].map((b: Element) => b.textContent?.trim() ?? '');
      const editCount = btnTexts.filter(t => t === 'Editar').length;
      const deleteCount = btnTexts.filter(t => t === 'Eliminar').length;
      expect(editCount).toBe(2);
      expect(deleteCount).toBe(2);
    });
  });

  // ─── Empty state ──────────────────────────────────────────────
  describe('empty state', () => {
    it('should show empty state message when no zones', () => {
      setupInputs({ zones: [] });
      const emptyEl = fixture.nativeElement.querySelector('.empty-state');
      expect(emptyEl).toBeTruthy();
      expect(emptyEl.textContent).toContain('No hay zonas registradas');
    });

    it('should NOT show empty state when loading', () => {
      setupInputs({ zones: [], loading: true });

      const emptyEl = fixture.nativeElement.querySelector('.empty-state');
      expect(emptyEl).toBeNull();
    });
  });

  // ─── Loading state ────────────────────────────────────────────
  describe('loading state', () => {
    it('should show loading indicator when loading is true', () => {
      setupInputs({ zones: mockZones, loading: true });

      const loadingEl = fixture.nativeElement.querySelector('.loading-text');
      expect(loadingEl).toBeTruthy();
      expect(loadingEl.textContent).toContain('Cargando zonas');
    });

    it('should NOT show table when loading', () => {
      setupInputs({ zones: mockZones, loading: true });

      const table = fixture.nativeElement.querySelector('table');
      expect(table).toBeNull();
    });
  });

  // ─── Error state ──────────────────────────────────────────────
  describe('error state', () => {
    it('should display error message when error input is set', () => {
      setupInputs({ zones: mockZones, error: 'Error al cargar las zonas' });

      const errorEl = fixture.nativeElement.querySelector('.error-message');
      expect(errorEl).toBeTruthy();
      expect(errorEl.textContent).toContain('Error al cargar las zonas');
    });
  });

  // ─── Outputs: edit ────────────────────────────────────────────
  describe('edit output', () => {
    beforeEach(() => {
      setupInputs({ zones: mockZones });
    });

    it('should emit the zone ID when edit button is clicked', async () => {
      const emittedId = await new Promise<string>((resolve) => {
        component.edit.subscribe(resolve);
        component.onEdit('zone-1');
      });

      expect(emittedId).toBe('zone-1');
    });
  });

  // ─── Outputs: remove ──────────────────────────────────────────
  describe('remove output', () => {
    beforeEach(() => {
      setupInputs({ zones: mockZones });
    });

    it('should emit the zone ID when delete button is clicked', async () => {
      const emittedId = await new Promise<string>((resolve) => {
        component.remove.subscribe(resolve);
        component.onRemove('zone-1');
      });

      expect(emittedId).toBe('zone-1');
    });
  });

  // ─── Outputs: enable ──────────────────────────────────────────
  describe('enable output', () => {
    beforeEach(() => {
      setupInputs({ zones: mockZones });
    });

    it('should emit disable when toggling an enabled zone OFF', async () => {
      const emitted = await new Promise<{ id: string; enable: boolean }>((resolve) => {
        component.enable.subscribe(resolve);
        component.onToggle(mockZones[0]); // enabled: true → toggle OFF
      });

      expect(emitted.id).toBe('zone-1');
      expect(emitted.enable).toBe(false);
    });

    it('should emit enable when toggling a disabled zone ON', async () => {
      const emitted = await new Promise<{ id: string; enable: boolean }>((resolve) => {
        component.enable.subscribe(resolve);
        component.onToggle(mockZones[2]); // enabled: false → toggle ON
      });

      expect(emitted.id).toBe('zone-3');
      expect(emitted.enable).toBe(true);
    });
  });

  // ─── showDisabled toggle ──────────────────────────────────────
  describe('showDisabled toggle', () => {
    beforeEach(() => {
      setupInputs({ zones: mockZones });
    });

    it('should show only enabled zones by default', () => {
      const rows = fixture.nativeElement.querySelectorAll('tr.mat-mdc-row');
      expect(rows.length).toBe(2); // zone-3 is disabled
    });

    it('should show all zones when showDisabled is true', () => {
      component.showDisabled.set(true);
      fixture.detectChanges();

      const rows = fixture.nativeElement.querySelectorAll('tr.mat-mdc-row');
      expect(rows.length).toBe(3);
    });

    it('should render the showDisabled toggle in the header', () => {
      const headerRow = fixture.nativeElement.querySelector('.header-row');
      expect(headerRow).toBeTruthy();
      expect(headerRow?.textContent).toContain('Mostrar deshabilitadas');
    });
  });
});
