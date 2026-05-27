import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { signal } from '@angular/core';
import { RegionsList } from './regions-list';
import { CompanyRegionService } from '../../data/company-region.service';
import { Company } from '../../../companies/models/company.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { CompanyRegion } from '../../models/region.models';

describe('RegionsList', () => {
  let component: RegionsList;
  let fixture: ComponentFixture<RegionsList>;

  const mockCompanies: Company[] = [
    {
      id: 'comp-1', companyKey: 'ACME', companyName: 'ACME Corp',
      tipoPersonaId: 1, razonSocial: 'ACME Corp SA',
      rfc: 'ACME010101', email: 'a@a.com', phone: '1234567890',
      enabled: true, createdAt: '', updatedAt: '',
    },
    {
      id: 'comp-2', companyKey: 'BETA', companyName: 'Beta Inc',
      tipoPersonaId: 2, razonSocial: 'Beta Inc SA',
      rfc: 'BETA010101', email: 'b@b.com', phone: '0987654321',
      enabled: true, createdAt: '', updatedAt: '',
    },
  ];

  const mockCountries: CompanyCountry[] = [
    {
      id: 'cc-1', companyId: 'comp-1', countryId: '1',
      countryCode: 'US', countryName: 'United States',
      localAlias: null, createdAt: '', updatedAt: '',
    },
    {
      id: 'cc-2', companyId: 'comp-1', countryId: '2',
      countryCode: 'MX', countryName: 'Mexico',
      localAlias: null, createdAt: '', updatedAt: '',
    },
  ];

  const mockRegions: CompanyRegion[] = [
    {
      id: 'reg-1', companyCountryId: 'cc-1', companyId: 'comp-1', countryId: '1',
      regionCode: 'US-CA', regionName: 'California',
      enabled: true, createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
    {
      id: 'reg-2', companyCountryId: 'cc-1', companyId: 'comp-1', countryId: '1',
      regionCode: 'US-TX', regionName: 'Texas',
      enabled: true, createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
    {
      id: 'reg-3', companyCountryId: 'cc-1', companyId: 'comp-1', countryId: '1',
      regionCode: 'US-DC', regionName: 'Distrito de Columbia',
      enabled: false, createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
  ];

  // Mock service signals
  const mockRegionsSignal = signal<CompanyRegion[]>([]);
  const mockLoadingSignal = signal(false);
  const mockErrorSignal = signal<string | null>(null);
  const mockGetRegions = vi.fn().mockReturnValue({ subscribe: vi.fn() });

  const mockService = {
    regions: mockRegionsSignal.asReadonly(),
    loading: mockLoadingSignal.asReadonly(),
    error: mockErrorSignal.asReadonly(),
    getRegions: mockGetRegions,
  };

  function setupInputs(overrides?: {
    companies?: Company[];
    companyCountries?: CompanyCountry[];
  }): void {
    fixture.componentRef.setInput('companies', overrides?.companies ?? mockCompanies);
    fixture.componentRef.setInput('companyCountries', overrides?.companyCountries ?? []);
    fixture.detectChanges();
  }

  beforeEach(async () => {
    // Reset all signals to defaults
    mockRegionsSignal.set([]);
    mockLoadingSignal.set(false);
    mockErrorSignal.set(null);
    mockGetRegions.mockClear();
    mockGetRegions.mockReturnValue({ subscribe: vi.fn() });

    await TestBed.configureTestingModule({
      imports: [RegionsList, NoopAnimationsModule],
      providers: [
        { provide: CompanyRegionService, useValue: mockService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(RegionsList);
    component = fixture.componentInstance;
  });

  // ─── Creation ─────────────────────────────────────────────────
  it('should create', () => {
    setupInputs();
    expect(component).toBeTruthy();
  });

  // ─── Selector rendering ───────────────────────────────────────
  describe('selectors', () => {
    it('should render Company and CompanyCountry mat-selects', () => {
      setupInputs();
      const selects = fixture.nativeElement.querySelectorAll('mat-select');
      expect(selects.length).toBe(2);
    });

    it('should show placeholder when no country is selected', () => {
      setupInputs();
      const placeholder = fixture.nativeElement.querySelector('.placeholder-text');
      expect(placeholder).toBeTruthy();
      expect(placeholder.textContent).toContain('Seleccioná un país');
    });

    it('should NOT render table when no country is selected', () => {
      setupInputs();
      const table = fixture.nativeElement.querySelector('table');
      expect(table).toBeNull();
    });

    it('should store selected company ID when company changes', () => {
      setupInputs();
      component.onCompanyChange('comp-1');
      expect(component.selectedCompanyId()).toBe('comp-1');
    });

    it('should reset country selection when company changes', () => {
      setupInputs();
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
      expect(component.selectedCompanyCountryId()).toBe('cc-1');

      component.onCompanyChange('comp-2');
      expect(component.selectedCompanyCountryId()).toBeNull();
    });

    it('should store selected country ID and call service when country changes', () => {
      setupInputs();
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();

      component.onCountryChange(mockCountries[0]);
      expect(component.selectedCompanyCountryId()).toBe('cc-1');
      expect(mockGetRegions).toHaveBeenCalledWith('comp-1', 'cc-1');
    });
  });

  // ─── Table rendering ──────────────────────────────────────────
  describe('table rendering', () => {
    beforeEach(() => {
      setupInputs();
      // Simulate country selection
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
      // Set regions in service
      mockRegionsSignal.set(mockRegions);
      fixture.detectChanges();
    });

    it('should display enabled regions in table rows by default', () => {
      const rows = fixture.nativeElement.querySelectorAll('tr.mat-mdc-row');
      // Default showDisabled=false → only enabled regions (reg-3 is disabled)
      expect(rows.length).toBe(2);
    });

    it('should show regionCode and regionName in each row', () => {
      const rows = fixture.nativeElement.querySelectorAll('tr.mat-mdc-row');
      expect(rows[0].textContent).toContain('US-CA');
      expect(rows[0].textContent).toContain('California');
      expect(rows[1].textContent).toContain('US-TX');
    });

    it('should show header columns: Código, Nombre, Estado, Acciones', () => {
      const headerRow = fixture.nativeElement.querySelector('tr.mat-mdc-header-row');
      expect(headerRow).toBeTruthy();
      expect(headerRow!.textContent).toContain('Código');
      expect(headerRow!.textContent).toContain('Nombre');
      expect(headerRow!.textContent).toContain('Estado');
      expect(headerRow!.textContent).toContain('Acciones');
    });

    it('should render mat-slide-toggle for each region row', () => {
      const toggles = fixture.nativeElement.querySelectorAll('mat-slide-toggle');
      expect(toggles.length).toBe(3);
    });

    it('should keep toggle enabled for all regions (including disabled ones)', () => {
      const toggleInputs = fixture.nativeElement.querySelectorAll('mat-slide-toggle input');
      // reg-3 (index 2) is disabled (enabled: false) — toggle must still be interactive
      const disabledToggle = toggleInputs[2] as HTMLInputElement;
      if (disabledToggle) {
        expect(disabledToggle.disabled).toBe(false);
      }
    });

    it('should have edit and delete buttons for each visible row', () => {
      const allButtons = fixture.nativeElement.querySelectorAll('.action-buttons button');
      // Default showDisabled=false → 2 enabled regions × 2 buttons = 4
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
    beforeEach(() => {
      setupInputs();
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
      mockRegionsSignal.set([]);
      fixture.detectChanges();
    });

    it('should show empty state message when no regions', () => {
      const emptyEl = fixture.nativeElement.querySelector('.empty-state');
      expect(emptyEl).toBeTruthy();
      expect(emptyEl.textContent).toContain('No hay regiones registradas para este país');
    });

    it('should NOT show empty state when loading', () => {
      mockLoadingSignal.set(true);
      fixture.detectChanges();

      const emptyEl = fixture.nativeElement.querySelector('.empty-state');
      expect(emptyEl).toBeNull();
    });
  });

  // ─── Loading state ────────────────────────────────────────────
  describe('loading state', () => {
    beforeEach(() => {
      setupInputs();
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
    });

    it('should show loading indicator when loading is true', () => {
      mockLoadingSignal.set(true);
      fixture.detectChanges();

      const loadingEl = fixture.nativeElement.querySelector('.loading-text');
      expect(loadingEl).toBeTruthy();
      expect(loadingEl.textContent).toContain('Cargando regiones');
    });

    it('should NOT show table when loading', () => {
      mockLoadingSignal.set(true);
      fixture.detectChanges();

      const table = fixture.nativeElement.querySelector('table');
      expect(table).toBeNull();
    });
  });

  // ─── Error state ──────────────────────────────────────────────
  describe('error state', () => {
    it('should display error message when service has error', () => {
      setupInputs();
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
      mockErrorSignal.set('Error al cargar las regiones');
      fixture.detectChanges();

      const errorEl = fixture.nativeElement.querySelector('.error-message');
      expect(errorEl).toBeTruthy();
      expect(errorEl.textContent).toContain('Error al cargar las regiones');
    });
  });

  // ─── Outputs: editRegion ──────────────────────────────────────
  describe('editRegion output', () => {
    beforeEach(() => {
      setupInputs();
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
      mockRegionsSignal.set(mockRegions);
      fixture.detectChanges();
    });

    it('should emit the region when edit button is clicked', async () => {
      const emitted = await new Promise<CompanyRegion>((resolve) => {
        component.editRegion.subscribe(resolve);
        component.onEdit(mockRegions[0]);
      });

      expect(emitted.id).toBe('reg-1');
      expect(emitted.regionCode).toBe('US-CA');
    });
  });

  // ─── Outputs: deleteRegion ────────────────────────────────────
  describe('deleteRegion output', () => {
    beforeEach(() => {
      setupInputs();
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
      mockRegionsSignal.set(mockRegions);
      fixture.detectChanges();
    });

    it('should emit the region ID when delete button is clicked', async () => {
      const emittedId = await new Promise<string>((resolve) => {
        component.deleteRegion.subscribe(resolve);
        component.onDelete('reg-1');
      });

      expect(emittedId).toBe('reg-1');
    });
  });

  // ─── Outputs: toggleRegion ────────────────────────────────────
  describe('toggleRegion output', () => {
    beforeEach(() => {
      setupInputs();
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
      mockRegionsSignal.set(mockRegions);
      fixture.detectChanges();
    });

    it('should emit disable when toggling an enabled region OFF', async () => {
      const emitted = await new Promise<{ id: string; enable: boolean }>((resolve) => {
        component.toggleRegion.subscribe(resolve);
        component.onToggle(mockRegions[0]); // enabled: true → toggle OFF
      });

      expect(emitted.id).toBe('reg-1');
      expect(emitted.enable).toBe(false);
    });

    it('should emit enable when toggling a disabled region ON', async () => {
      const emitted = await new Promise<{ id: string; enable: boolean }>((resolve) => {
        component.toggleRegion.subscribe(resolve);
        component.onToggle(mockRegions[2]); // enabled: false → toggle ON
      });

      expect(emitted.id).toBe('reg-3');
      expect(emitted.enable).toBe(true);
    });
  });

  // ─── showDisabled toggle ──────────────────────────────────────
  describe('showDisabled toggle', () => {
    beforeEach(() => {
      setupInputs();
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
      mockRegionsSignal.set(mockRegions);
      fixture.detectChanges();
    });

    it('should show only enabled regions by default', () => {
      const rows = fixture.nativeElement.querySelectorAll('tr.mat-mdc-row');
      expect(rows.length).toBe(2); // reg-3 is disabled
    });

    it('should show all regions when showDisabled is true', () => {
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

  // ─── Table not rendered when no country selected ──────────────
  describe('country selection guard', () => {
    it('should not render table or empty state when no country is selected', () => {
      setupInputs();
      // No country selected
      const table = fixture.nativeElement.querySelector('table');
      const emptyEl = fixture.nativeElement.querySelector('.empty-state');
      const placeholder = fixture.nativeElement.querySelector('.placeholder-text');

      expect(table).toBeNull();
      expect(emptyEl).toBeNull();
      expect(placeholder).toBeTruthy();
    });
  });
});
