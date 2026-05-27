import { By } from '@angular/platform-browser';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { RegionManager } from './region-manager';
import { CompanyRegion } from '../../models/region.models';
import { RegionsCard } from '../regions-card/regions-card';

describe('RegionManager', () => {
  let component: RegionManager;
  let fixture: ComponentFixture<RegionManager>;

  const mockRegions: CompanyRegion[] = [
    {
      id: 'reg-1', companyCountryId: 'cc-1', companyId: 'company-123', countryId: '1',
      regionCode: 'US-CA', regionName: 'California',
      enabled: true, createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
    {
      id: 'reg-2', companyCountryId: 'cc-1', companyId: 'company-123', countryId: '1',
      regionCode: 'US-TX', regionName: 'Texas',
      enabled: true, createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
    {
      id: 'reg-3', companyCountryId: 'cc-1', companyId: 'company-123', countryId: '1',
      regionCode: 'US-DC', regionName: 'Distrito de Columbia',
      enabled: false, createdAt: '2024-01-01', updatedAt: '2024-01-01',
    },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RegionManager, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(RegionManager);
    component = fixture.componentInstance;
  });

  function setInputs(overrides?: {
    regions?: CompanyRegion[];
    companyId?: string;
    countryId?: string;
    loading?: boolean;
    errorMessage?: string | null;
  }): void {
    fixture.componentRef.setInput('regions', overrides?.regions ?? mockRegions);
    fixture.componentRef.setInput('companyId', overrides?.companyId ?? 'company-123');
    fixture.componentRef.setInput('countryId', overrides?.countryId ?? '1');
    fixture.componentRef.setInput('loading', overrides?.loading ?? false);
    fixture.componentRef.setInput('errorMessage', overrides?.errorMessage ?? null);
    fixture.detectChanges();
  }

  // ---------- Rendering: basic states ----------
  describe('rendering', () => {
    it('should show placeholder when no countryId is provided', () => {
      setInputs({ countryId: '', regions: [] });
      const placeholder = fixture.nativeElement.querySelector('.placeholder-text');
      expect(placeholder).toBeTruthy();
      expect(placeholder.textContent).toContain('Seleccioná un país');
    });

    it('should display error message banner when errorMessage is set', () => {
      setInputs({ errorMessage: 'Este código de región ya existe' });
      const errorEl = fixture.nativeElement.querySelector('.error-message');
      expect(errorEl).toBeTruthy();
      expect(errorEl.textContent).toContain('Este código de región ya existe');
    });

    it('should display loading text when loading is true', () => {
      setInputs({ loading: true });
      const loadingEl = fixture.nativeElement.querySelector('.loading-text');
      expect(loadingEl).toBeTruthy();
      expect(loadingEl.textContent).toContain('Cargando regiones');
    });

    it('should show empty message when no regions and not loading', () => {
      setInputs({ regions: [] });
      const emptyEl = fixture.nativeElement.querySelector('.empty-message');
      expect(emptyEl).toBeTruthy();
      expect(emptyEl.textContent).toContain('No hay regiones registradas');
    });

    it('should NOT show empty message when loading', () => {
      setInputs({ regions: [], loading: true });
      const emptyEl = fixture.nativeElement.querySelector('.empty-message');
      expect(emptyEl).toBeNull();
    });
  });

  // ---------- Card grid rendering ----------
  describe('card grid', () => {
    it('should render an app-regions-card for each enabled region', () => {
      setInputs();
      const cards = fixture.nativeElement.querySelectorAll('app-regions-card');
      // 2 enabled regions by default
      expect(cards.length).toBe(2);
    });

    it('should render cards for all regions when showDisabled is on', () => {
      setInputs();
      component.showDisabled.set(true);
      fixture.detectChanges();
      const cards = fixture.nativeElement.querySelectorAll('app-regions-card');
      expect(cards.length).toBe(3);
    });

    it('should pass the correct region data to each card', () => {
      setInputs();
      const cards = fixture.debugElement.queryAll(By.directive(RegionsCard));
      expect(cards.length).toBe(2);
      expect(cards[0].componentInstance.region()).toEqual(mockRegions[0]);
      expect(cards[1].componentInstance.region()).toEqual(mockRegions[1]);
    });

    it('should have a CSS grid container wrapping the cards', () => {
      setInputs();
      const grid = fixture.nativeElement.querySelector('.regions-grid');
      expect(grid).toBeTruthy();
      const cards = grid!.querySelectorAll('app-regions-card');
      expect(cards.length).toBe(2);
    });

    it('should emit removeRegion when card deleteRegion fires', () => {
      setInputs();

      let emittedId: string | undefined;
      component.removeRegion.subscribe((id) => { emittedId = id; });

      // Access the first card component instance and trigger its delete
      const cards = fixture.debugElement.queryAll(By.directive(RegionsCard));
      cards[0].componentInstance.onDelete(new MouseEvent('click'));

      expect(emittedId).toBe('reg-1');
    });

    it('should emit editRegion with full region when card editRegion fires', async () => {
      setInputs();

      const region = await new Promise<CompanyRegion>((resolve) => {
        component.editRegion.subscribe(resolve);

        const cards = fixture.debugElement.queryAll(By.directive(RegionsCard));
        cards[0].componentInstance.onEdit(new MouseEvent('click'));
      });

      expect(region).toEqual(mockRegions[0]);
    });
  });

  // ---------- createRegion output ----------
  describe('createRegion', () => {
    it('should emit createRegion when "Nueva Región" button is clicked', () => {
      setInputs();

      let emitted = false;
      component.createRegion.subscribe(() => { emitted = true; });

      // Find the button with "Nueva Región" text
      const buttons = fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>;
      const nuevoBtn = Array.from(buttons).find(b => b.textContent?.trim().includes('Nueva Región'))!;
      expect(nuevoBtn).toBeTruthy();
      nuevoBtn.click();

      expect(emitted).toBe(true);
    });
  });

  // ---------- onRemove ----------
  describe('onRemove', () => {
    it('should emit removeRegion with the region id', async () => {
      setInputs();

      const id = await new Promise<string>((resolve) => {
        component.removeRegion.subscribe(resolve);
        component.onRemove('reg-1');
      });

      expect(id).toBe('reg-1');
    });
  });

  // ---------- onToggleRegion ----------
  describe('onToggleRegion', () => {
    it('should emit removeRegion when toggling an enabled region OFF', async () => {
      setInputs();

      const id = await new Promise<string>((resolve) => {
        component.removeRegion.subscribe(resolve);
        component.onToggleRegion(mockRegions[0]); // enabled: true
      });

      expect(id).toBe('reg-1');
    });

    it('should emit enableRegion when toggling a disabled region ON', async () => {
      setInputs();

      const id = await new Promise<string>((resolve) => {
        component.enableRegion.subscribe(resolve);
        component.onToggleRegion(mockRegions[2]); // enabled: false
      });

      expect(id).toBe('reg-3');
    });
  });

  // ---------- showDisabled toggle ----------
  describe('showDisabled toggle', () => {
    it('should show only enabled regions by default', () => {
      setInputs();
      expect(component.filteredRegions().length).toBe(2);
      expect(component.filteredRegions().every(r => r.enabled)).toBe(true);
    });

    it('should show all regions when showDisabled is true', () => {
      setInputs();
      component.showDisabled.set(true);
      fixture.detectChanges();

      expect(component.filteredRegions().length).toBe(3);
    });

    it('should render the header with title, toggle, and Nueva Región button', () => {
      setInputs();
      const headerRow = fixture.nativeElement.querySelector('.header-row');
      expect(headerRow).toBeTruthy();
      expect(headerRow?.textContent).toContain('Mostrar deshabilitadas');
      expect(headerRow?.textContent).toContain('Nueva Región');
      const buttons = headerRow?.querySelectorAll('button');
      expect(buttons?.length).toBeGreaterThanOrEqual(1);
    });
  });

  // ---------- onEditRegion ----------
  describe('onEditRegion', () => {
    it('should emit editRegion with found region when regionId exists', async () => {
      setInputs();

      const region = await new Promise<CompanyRegion>((resolve) => {
        component.editRegion.subscribe(resolve);
        component.onEditRegion('reg-2');
      });

      expect(region).toEqual(mockRegions[1]);
    });

    it('should NOT emit editRegion when regionId is not found', () => {
      setInputs();

      let emitted = false;
      component.editRegion.subscribe(() => { emitted = true; });
      component.onEditRegion('non-existent');

      expect(emitted).toBe(false);
    });
  });
});
