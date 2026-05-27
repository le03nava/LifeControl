import { By } from '@angular/platform-browser';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { RegionManager } from './region-manager';
import { CompanyRegion, CompanyRegionRequest } from '../../models/region.models';
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

    it('should render add form with code and name fields and add button', () => {
      setInputs();
      const addForm = fixture.nativeElement.querySelector('.add-form-row');
      expect(addForm).toBeTruthy();

      const inputs = addForm!.querySelectorAll('input');
      expect(inputs.length).toBeGreaterThanOrEqual(2);

      const addBtn = addForm!.querySelector('button');
      expect(addBtn).toBeTruthy();
      expect(addBtn?.textContent?.trim()).toContain('Agregar');
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

    it('should not emit removeRegion when card editRegion fires (placeholder)', () => {
      setInputs();

      let emitted = false;
      component.removeRegion.subscribe(() => { emitted = true; });

      const cards = fixture.debugElement.queryAll(By.directive(RegionsCard));
      cards[0].componentInstance.onEdit(new MouseEvent('click'));

      expect(emitted).toBe(false);
    });
  });

  // ---------- onAdd ----------
  describe('onAdd', () => {
    it('should emit addRegion when form is valid', async () => {
      setInputs();
      component.newRegionForm.patchValue({ regionCode: 'US-NY', regionName: 'New York' });

      const req = await new Promise<CompanyRegionRequest>((resolve) => {
        component.addRegion.subscribe(resolve);
        component.onAdd();
      });

      expect(req.regionCode).toBe('US-NY');
      expect(req.regionName).toBe('New York');
    });

    it('should reset the form after emitting', () => {
      setInputs();
      component.newRegionForm.patchValue({ regionCode: 'US-NY', regionName: 'New York' });
      component.onAdd();

      expect(component.newRegionForm.value).toEqual({ regionCode: '', regionName: '' });
    });

    it('should NOT emit addRegion when form is invalid (empty fields)', () => {
      setInputs();
      let emitted = false;
      component.addRegion.subscribe(() => { emitted = true; });
      component.onAdd();

      expect(emitted).toBe(false);
    });

    it('should NOT emit when regionCode fails pattern validation', () => {
      setInputs();
      component.newRegionForm.patchValue({ regionCode: 'INVALID CHARS!!!', regionName: 'Test' });

      let emitted = false;
      component.addRegion.subscribe(() => { emitted = true; });
      component.onAdd();

      expect(component.newRegionForm.controls.regionCode.errors?.['pattern']).toBeTruthy();
      expect(emitted).toBe(false);
    });

    it('should disable add button when form is invalid', () => {
      setInputs();
      fixture.detectChanges();

      const addBtn = fixture.nativeElement.querySelector('.add-form-row button') as HTMLButtonElement;
      expect(addBtn.disabled).toBe(true);
    });

    it('should disable add button when loading', () => {
      setInputs({ loading: true });
      component.newRegionForm.patchValue({ regionCode: 'US-NY', regionName: 'New York' });
      fixture.detectChanges();

      const addBtn = fixture.nativeElement.querySelector('.add-form-row button') as HTMLButtonElement;
      expect(addBtn.disabled).toBe(true);
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

    it('should render the showDisabled toggle in the header', () => {
      setInputs();
      const headerRow = fixture.nativeElement.querySelector('.header-row');
      expect(headerRow).toBeTruthy();
      expect(headerRow?.textContent).toContain('Mostrar deshabilitadas');
    });
  });

  // ---------- Validation ----------
  describe('validation', () => {
    it('should reject regionCode with special characters', () => {
      const control = component.newRegionForm.controls.regionCode;
      control.setValue('INVALID!!!');
      expect(control.errors?.['pattern']).toBeTruthy();
    });

    it('should accept regionCode with letters, numbers, and hyphens', () => {
      const control = component.newRegionForm.controls.regionCode;
      control.setValue('US-CA-2');
      expect(control.valid).toBe(true);
    });

    it('should enforce maxLength 10 on regionCode', () => {
      const control = component.newRegionForm.controls.regionCode;
      control.setValue('ABCDEFGHIJK');
      expect(control.errors?.['maxlength']).toBeTruthy();
    });
  });
});
