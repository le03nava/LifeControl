import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { CountrySelector } from './country-selector';
import { Country, CompanyCountry, CompanyCountryRequest } from '../../models/country.models';

describe('CountrySelector', () => {
  let component: CountrySelector;
  let fixture: ComponentFixture<CountrySelector>;

  const mockCountries: Country[] = [
    { id: '1', countryCode: 'MX', countryName: 'Mexico', enabled: true, createdAt: '2024-01-01', updatedAt: '2024-01-01' },
    { id: '2', countryCode: 'CO', countryName: 'Colombia', enabled: true, createdAt: '2024-01-01', updatedAt: '2024-01-01' },
    { id: '3', countryCode: 'BR', countryName: 'Brazil', enabled: true, createdAt: '2024-01-01', updatedAt: '2024-01-01' },
  ];

  const mockAssigned: CompanyCountry[] = [
    { id: 'cc-1', companyId: 'abc', countryId: '1', countryCode: 'MX', countryName: 'Mexico', localAlias: 'Oficina CDMX', createdAt: '2024-01-01', updatedAt: '2024-01-01' },
    { id: 'cc-2', companyId: 'abc', countryId: '2', countryCode: 'CO', countryName: 'Colombia', localAlias: null, createdAt: '2024-01-01', updatedAt: '2024-01-01' },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CountrySelector, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(CountrySelector);
    component = fixture.componentInstance;
  });

  function setInputs(overrides?: {
    countries?: Country[];
    assignedCountries?: CompanyCountry[];
    loading?: boolean;
    errorMessage?: string | null;
  }): void {
    fixture.componentRef.setInput('countries', overrides?.countries ?? mockCountries);
    fixture.componentRef.setInput('assignedCountries', overrides?.assignedCountries ?? mockAssigned);
    fixture.componentRef.setInput('loading', overrides?.loading ?? false);
    fixture.componentRef.setInput('errorMessage', overrides?.errorMessage ?? null);
    fixture.detectChanges();
  }

  // ---------- onAdd ----------
  describe('onAdd', () => {
    it('should emit addCountry with countryCode and localAlias when country is selected', async () => {
      setInputs();
      component.selectedCountryCode.set('BR');
      component.localAlias.set('Sucursal Sao Paulo');

      const req = await new Promise<CompanyCountryRequest>((resolve) => {
        component.addCountry.subscribe(resolve);
        component.onAdd();
      });

      expect(req.countryCode).toBe('BR');
      expect(req.localAlias).toBe('Sucursal Sao Paulo');
    });

    it('should emit addCountry without localAlias when alias is empty', async () => {
      setInputs();
      component.selectedCountryCode.set('BR');
      component.localAlias.set('');

      const req = await new Promise<CompanyCountryRequest>((resolve) => {
        component.addCountry.subscribe(resolve);
        component.onAdd();
      });

      expect(req.countryCode).toBe('BR');
      expect(req.localAlias).toBeUndefined();
    });

    it('should NOT emit addCountry when no country is selected', () => {
      setInputs();
      component.selectedCountryCode.set('');
      let emitted = false;

      component.addCountry.subscribe(() => { emitted = true; });
      component.onAdd();

      expect(emitted).toBe(false);
    });

    it('should reset selectedCountryCode and localAlias after emitting', () => {
      setInputs();
      component.selectedCountryCode.set('BR');
      component.localAlias.set('test');

      component.onAdd();

      expect(component.selectedCountryCode()).toBe('');
      expect(component.localAlias()).toBe('');
    });
  });

  // ---------- onRemove ----------
  describe('onRemove', () => {
    it('should emit removeCountry with the given companyCountryId', async () => {
      setInputs();

      const id = await new Promise<string>((resolve) => {
        component.removeCountry.subscribe(resolve);
        component.onRemove('cc-1');
      });

      expect(id).toBe('cc-1');
    });
  });

  // ---------- Rendering ----------
  describe('rendering', () => {
    it('should render assigned countries as chips with countryName, countryCode, and alias', () => {
      setInputs();
      const chips = fixture.nativeElement.querySelectorAll('mat-chip');
      expect(chips.length).toBe(2);

      // First chip: Mexico with alias
      expect(chips[0].textContent).toContain('Mexico');
      expect(chips[0].textContent).toContain('(MX)');
      expect(chips[0].textContent).toContain('Oficina CDMX');

      // Second chip: Colombia without alias
      expect(chips[1].textContent).toContain('Colombia');
      expect(chips[1].textContent).toContain('(CO)');
    });

    it('should show "no countries" message when assignedCountries is empty and not loading', () => {
      setInputs({ assignedCountries: [] });
      const noCountries = fixture.nativeElement.querySelector('.no-countries');
      expect(noCountries).toBeTruthy();
      expect(noCountries.textContent).toContain('No hay países asignados');
    });

    it('should NOT show "no countries" message when loading', () => {
      setInputs({ assignedCountries: [], loading: true });
      const noCountries = fixture.nativeElement.querySelector('.no-countries');
      expect(noCountries).toBeNull();
    });

    it('should display error message when errorMessage input is provided', () => {
      setInputs({ errorMessage: 'Este país ya está asignado a esta empresa' });
      const errorEl = fixture.nativeElement.querySelector('.error-message');
      expect(errorEl).toBeTruthy();
      expect(errorEl.textContent).toContain('Este país ya está asignado');
    });

    it('should display loading text when loading is true', () => {
      setInputs({ loading: true });
      const loadingEl = fixture.nativeElement.querySelector('.loading-text');
      expect(loadingEl).toBeTruthy();
      expect(loadingEl.textContent).toContain('Cargando países');
    });

    it('should disable add button when no country is selected', () => {
      setInputs();
      component.selectedCountryCode.set('');
      fixture.detectChanges();

      const addBtn = fixture.nativeElement.querySelector('button[mat-raised-button]') as HTMLButtonElement;
      expect(addBtn.disabled).toBe(true);
    });

    it('should disable add button when loading is true', () => {
      setInputs({ loading: true });
      component.selectedCountryCode.set('BR');
      fixture.detectChanges();

      const addBtn = fixture.nativeElement.querySelector('button[mat-raised-button]') as HTMLButtonElement;
      expect(addBtn.disabled).toBe(true);
    });

    it('should render a remove button inside each chip', () => {
      setInputs();
      const chips = fixture.nativeElement.querySelectorAll('mat-chip');
      expect(chips.length).toBe(2);
      chips.forEach((chip: HTMLElement) => {
        const removeBtn = chip.querySelector('button');
        expect(removeBtn).toBeTruthy();
        expect(removeBtn?.textContent?.trim()).toBe('×');
      });
    });

    it('should not show chips when assignedCountries is empty', () => {
      setInputs({ assignedCountries: [] });
      const chips = fixture.nativeElement.querySelectorAll('mat-chip');
      expect(chips.length).toBe(0);
    });
  });
});
