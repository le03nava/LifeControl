import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { RegionManager } from './region-manager';
import { CompanyRegion, CompanyRegionRequest } from '../../models/company.models';

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

    it('should show empty state when no regions and not loading', () => {
      setInputs({ regions: [] });
      const emptyEl = fixture.nativeElement.querySelector('.empty-state');
      expect(emptyEl).toBeTruthy();
      expect(emptyEl.textContent).toContain('No hay regiones registradas');
    });

    it('should NOT show empty state when loading', () => {
      setInputs({ regions: [], loading: true });
      const emptyEl = fixture.nativeElement.querySelector('.empty-state');
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

  // ---------- Table rendering ----------
  describe('table', () => {
    it('should display enabled regions in table rows', () => {
      setInputs();
      const rows = fixture.nativeElement.querySelectorAll('tr.mat-mdc-row');
      expect(rows.length).toBe(2);

      expect(rows[0].textContent).toContain('US-CA');
      expect(rows[0].textContent).toContain('California');
    });

    it('should show header columns: Código, Nombre, Estado, Acciones', () => {
      setInputs();
      const headerRow = fixture.nativeElement.querySelector('tr.mat-mdc-header-row');
      expect(headerRow).toBeTruthy();
      expect(headerRow!.textContent).toContain('Código');
      expect(headerRow!.textContent).toContain('Nombre');
      expect(headerRow!.textContent).toContain('Estado');
      expect(headerRow!.textContent).toContain('Acciones');
    });

    it('should render an interactive slide-toggle for each region row', () => {
      setInputs();
      const toggles = fixture.nativeElement.querySelectorAll('mat-slide-toggle');
      expect(toggles.length).toBe(3);
      // All toggles should be enabled (both enable and disable are supported now)
      toggles.forEach((toggle: Element) => {
        expect(toggle.hasAttribute('ng-reflect-disabled')).toBe(false);
      });
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

  // ---------- Edit mode ----------
  describe('edit mode', () => {
    it('should enter edit mode on startEdit and show Save/Cancel buttons', () => {
      setInputs();
      component.onStartEdit(mockRegions[0]);
      fixture.detectChanges();

      expect(component.editMode()['reg-1']).toBe(true);
      expect(component.editForms['reg-1'].value).toEqual({ regionCode: 'US-CA', regionName: 'California' });

      const actionButtons = fixture.nativeElement.querySelectorAll('.action-buttons button');
      expect(actionButtons.length).toBe(4);

      const btnTexts = [...actionButtons].map(b => b.textContent?.trim() ?? '');
      expect(btnTexts).toContain('Guardar');
      expect(btnTexts).toContain('Cancelar');
      expect(btnTexts).toContain('Editar');
      expect(btnTexts).toContain('Eliminar');
    });

    it('should exit edit mode on cancelEdit', () => {
      setInputs();
      component.onStartEdit(mockRegions[0]);
      component.onCancelEdit('reg-1');
      fixture.detectChanges();

      expect(component.editMode()['reg-1']).toBeFalsy();
      expect(component.editForms['reg-1']).toBeUndefined();
    });

    it('should emit updateRegion on saveEdit', async () => {
      setInputs();
      component.onStartEdit(mockRegions[0]);
      component.editForms['reg-1'].patchValue({ regionCode: 'US-CA-UPD', regionName: 'California Updated' });

      const result = await new Promise<{ id: string; data: CompanyRegionRequest }>((resolve) => {
        component.updateRegion.subscribe(resolve);
        component.onSaveEdit('reg-1');
      });

      expect(result.id).toBe('reg-1');
      expect(result.data.regionCode).toBe('US-CA-UPD');
      expect(result.data.regionName).toBe('California Updated');
    });

    it('should exit edit mode and clean up form after saveEdit', () => {
      setInputs();
      component.onStartEdit(mockRegions[0]);
      component.onSaveEdit('reg-1');

      expect(component.editMode()['reg-1']).toBeFalsy();
      expect(component.editForms['reg-1']).toBeUndefined();
    });

    it('should NOT emit updateRegion when form is invalid', () => {
      setInputs();
      component.onStartEdit(mockRegions[0]);
      component.editForms['reg-1'].controls.regionCode.setValue('');

      let emitted = false;
      component.updateRegion.subscribe(() => { emitted = true; });
      component.onSaveEdit('reg-1');

      expect(emitted).toBe(false);
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
