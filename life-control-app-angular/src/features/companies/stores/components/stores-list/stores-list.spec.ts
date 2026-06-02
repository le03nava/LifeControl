import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { StoresList } from './stores-list';
import { CompanyStore } from '../../models/store.models';

describe('StoresList', () => {
  let component: StoresList;
  let fixture: ComponentFixture<StoresList>;

  const mockStores: CompanyStore[] = [
    {
      id: 'store-1', companyId: 'comp-1', companyCountryId: 'cc-1', regionId: 'reg-1', zoneId: 'zone-1',
      storeName: 'Tienda Central', email: 'central@store.com', phoneNumber: '+525512345678',
      street: 'Av. Reforma', streetNumber: '222', neighborhood: 'Juárez', zipCode: '06600',
      city: 'CDMX', state: 'CDMX', countryId: 'MX',
      enabled: true, createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
    {
      id: 'store-2', companyId: 'comp-1', companyCountryId: 'cc-1', regionId: 'reg-1', zoneId: 'zone-1',
      storeName: 'Tienda Norte', email: 'norte@store.com', enabled: false,
      createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
    {
      id: 'store-3', companyId: 'comp-1', companyCountryId: 'cc-1', regionId: 'reg-1', zoneId: 'zone-1',
      storeName: 'Tienda Sur', email: '', phoneNumber: '', enabled: true,
      createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
  ];

  function setupInputs(overrides?: {
    stores?: CompanyStore[];
    loading?: boolean;
    error?: string | null;
  }): void {
    fixture.componentRef.setInput('stores', overrides?.stores ?? []);
    fixture.componentRef.setInput('loading', overrides?.loading ?? false);
    fixture.componentRef.setInput('error', overrides?.error ?? null);
    fixture.detectChanges();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [StoresList, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(StoresList);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    setupInputs();
    expect(component).toBeTruthy();
  });

  // ─── Table rendering ──────────────────────────────────────────
  describe('table rendering', () => {
    beforeEach(() => {
      setupInputs({ stores: mockStores });
    });

    it('should display enabled stores in table rows by default', () => {
      const rows = fixture.nativeElement.querySelectorAll('tr.mat-mdc-row');
      // Default showDisabled=false → only enabled stores (store-2 is disabled)
      expect(rows.length).toBe(2);
    });

    it('should show storeName, email, phone, city, state and status in each row', () => {
      const rows = fixture.nativeElement.querySelectorAll('tr.mat-mdc-row');
      expect(rows[0].textContent).toContain('Tienda Central');
      expect(rows[0].textContent).toContain('central@store.com');
      expect(rows[0].textContent).toContain('+525512345678');
      expect(rows[0].textContent).toContain('CDMX');
      expect(rows[0].textContent).toContain('CDMX');
    });

    it('should show header columns: Nombre, Email, Teléfono, Ciudad, Estado, Acciones', () => {
      const headerRow = fixture.nativeElement.querySelector('tr.mat-mdc-header-row');
      expect(headerRow).toBeTruthy();
      expect(headerRow!.textContent).toContain('Nombre');
      expect(headerRow!.textContent).toContain('Email');
      expect(headerRow!.textContent).toContain('Teléfono');
      expect(headerRow!.textContent).toContain('Ciudad');
      expect(headerRow!.textContent).toContain('Estado');
      expect(headerRow!.textContent).toContain('Acciones');
    });

    it('should render mat-slide-toggle for each store row', () => {
      const toggles = fixture.nativeElement.querySelectorAll('mat-slide-toggle');
      expect(toggles.length).toBeGreaterThanOrEqual(2);
    });

    it('should have edit buttons for each visible row', () => {
      const allButtons = fixture.nativeElement.querySelectorAll('.action-buttons button');
      expect(allButtons.length).toBe(2);

      const btnTexts = [...allButtons].map((b: Element) => b.textContent?.trim() ?? '');
      const editCount = btnTexts.filter(t => t === 'Editar').length;
      expect(editCount).toBe(2);
    });

    it('should show —— when email is empty', () => {
      const rows = fixture.nativeElement.querySelectorAll('tr.mat-mdc-row');
      expect(rows[1].textContent).toContain('——');
    });
  });

  // ─── Empty state ──────────────────────────────────────────────
  describe('empty state', () => {
    it('should show empty state message when no stores', () => {
      setupInputs({ stores: [] });
      const emptyEl = fixture.nativeElement.querySelector('.empty-state');
      expect(emptyEl).toBeTruthy();
      expect(emptyEl.textContent).toContain('No hay tiendas registradas');
    });

    it('should NOT show empty state when loading', () => {
      setupInputs({ stores: [], loading: true });
      const emptyEl = fixture.nativeElement.querySelector('.empty-state');
      expect(emptyEl).toBeNull();
    });
  });

  // ─── Loading state ────────────────────────────────────────────
  describe('loading state', () => {
    it('should show loading indicator when loading is true', () => {
      setupInputs({ stores: mockStores, loading: true });
      const loadingEl = fixture.nativeElement.querySelector('.loading-text');
      expect(loadingEl).toBeTruthy();
      expect(loadingEl.textContent).toContain('Cargando tiendas');
    });

    it('should NOT show table when loading', () => {
      setupInputs({ stores: mockStores, loading: true });
      const table = fixture.nativeElement.querySelector('table');
      expect(table).toBeNull();
    });
  });

  // ─── Error state ──────────────────────────────────────────────
  describe('error state', () => {
    it('should display error message when error input is set', () => {
      setupInputs({ stores: mockStores, error: 'Error al cargar las tiendas' });
      const errorEl = fixture.nativeElement.querySelector('.error-message');
      expect(errorEl).toBeTruthy();
      expect(errorEl.textContent).toContain('Error al cargar las tiendas');
    });
  });

  // ─── Outputs ──────────────────────────────────────────────────
  describe('outputs', () => {
    beforeEach(() => {
      setupInputs({ stores: mockStores });
    });

    it('should emit the store ID when edit is called', async () => {
      const emittedId = await new Promise<string>((resolve) => {
        component.edit.subscribe(resolve);
        component.onEdit('store-1');
      });
      expect(emittedId).toBe('store-1');
    });

    it('should emit toggle when toggle is called', async () => {
      const emitted = await new Promise<{ id: string; enable: boolean }>((resolve) => {
        component.toggle.subscribe(resolve);
        component.onToggle(mockStores[0]);
      });
      expect(emitted.id).toBe('store-1');
      expect(emitted.enable).toBe(false);
    });

    it('should emit enable=true when toggling disabled store', async () => {
      const emitted = await new Promise<{ id: string; enable: boolean }>((resolve) => {
        component.toggle.subscribe(resolve);
        component.onToggle(mockStores[1]); // enabled: false
      });
      expect(emitted.id).toBe('store-2');
      expect(emitted.enable).toBe(true);
    });
  });

  // ─── showDisabled toggle ──────────────────────────────────────
  describe('showDisabled toggle', () => {
    beforeEach(() => {
      setupInputs({ stores: mockStores });
    });

    it('should show only enabled stores by default', () => {
      const rows = fixture.nativeElement.querySelectorAll('tr.mat-mdc-row');
      expect(rows.length).toBe(2);
    });

    it('should show all stores when showDisabled is true', () => {
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
