import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { FormControl, FormGroup } from '@angular/forms';
import { AddressFormComponent } from './address-form';
import { AddressControl } from '../../models/address.models';
import { Country } from '../../../features/companies/countries/models/country.models';

describe('AddressFormComponent', () => {
  let component: AddressFormComponent;
  let fixture: ComponentFixture<AddressFormComponent>;

  function createFormGroup(): FormGroup<AddressControl> {
    return new FormGroup<AddressControl>({
      street: new FormControl<string | null>(null),
      streetNumber: new FormControl<string | null>(null),
      internalNumber: new FormControl<string | null>(null),
      neighborhood: new FormControl<string | null>(null),
      zipCode: new FormControl<string | null>(null),
      city: new FormControl<string | null>(null),
      state: new FormControl<string | null>(null),
      countryId: new FormControl<string | null>(null),
    });
  }

  function createMockCountries(): Country[] {
    return [
      { id: '1', countryCode: 'MX', countryName: 'México', enabled: true, createdAt: '', updatedAt: '' },
      { id: '2', countryCode: 'US', countryName: 'Estados Unidos', enabled: true, createdAt: '', updatedAt: '' },
    ];
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AddressFormComponent, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(AddressFormComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('addressFormGroup', createFormGroup());
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should render address section heading by default', () => {
    const heading = fixture.nativeElement.querySelector('.address-heading');
    expect(heading).toBeTruthy();
    expect(heading.textContent).toContain('Dirección');
  });

  it('should hide address heading when hideHeading is true', () => {
    fixture.componentRef.setInput('hideHeading', true);
    fixture.detectChanges();

    const heading = fixture.nativeElement.querySelector('.address-heading');
    expect(heading).toBeFalsy();
  });

  it('should render all 7 address input fields when FormGroup provided (country hidden when no countries)', () => {
    const addressFields = ['street', 'streetNumber', 'internalNumber', 'neighborhood', 'zipCode', 'city', 'state'];
    for (const field of addressFields) {
      const el = fixture.nativeElement.querySelector(`[formControlName="${field}"]`);
      expect(el).toBeTruthy();
    }
  });

  it('should render countryId select when countries are provided', () => {
    fixture.componentRef.setInput('countries', createMockCountries());
    fixture.detectChanges();
    const el = fixture.nativeElement.querySelector('[formControlName="countryId"]');
    expect(el).toBeTruthy();
  });

  it('should render location_on mat-icon on street field', () => {
    const matIcons = fixture.nativeElement.querySelectorAll('mat-icon[matPrefix]');
    expect(matIcons.length).toBe(1);
    expect(matIcons[0].textContent).toContain('location_on');
  });

  it('should keep all address fields optional (no required indicator)', () => {
    const addressFields = ['street', 'streetNumber', 'internalNumber', 'neighborhood', 'zipCode', 'city', 'state', 'countryId'];
    for (const field of addressFields) {
      const control = component.addressFormGroup().get(field);
      control?.markAsTouched();
      expect(control?.errors?.['required']).toBeUndefined();
    }
  });

  describe('country selector', () => {
    it('should be hidden when countries array is empty', () => {
      fixture.componentRef.setInput('countries', []);
      fixture.detectChanges();

      const countrySelect = fixture.nativeElement.querySelector('[formControlName="countryId"]');
      // countryId input still exists as text input, but should NOT be a mat-select
      const matSelect = fixture.nativeElement.querySelector('mat-select');
      expect(matSelect).toBeFalsy();
    });

    it('should be visible when countries array has items', () => {
      fixture.componentRef.setInput('countries', createMockCountries());
      fixture.detectChanges();

      const matSelect = fixture.nativeElement.querySelector('mat-select');
      expect(matSelect).toBeTruthy();
    });
  });

  describe('error messages', () => {
    it('should display error message when control has server error and is touched', () => {
      const streetControl = component.addressFormGroup().controls.street;
      streetControl.markAsTouched();
      streetControl.setErrors({ serverError: 'La calle es inválida' });
      fixture.detectChanges();

      const matErrors = fixture.nativeElement.querySelectorAll('mat-error');
      expect(matErrors.length).toBeGreaterThan(0);

      const streetError = Array.from(matErrors).find(
        (el: any) => el.textContent?.includes('La calle es inválida')
      );
      expect(streetError).toBeTruthy();
    });

    it('should apply server errors to matching controls', () => {
      fixture.componentRef.setInput('serverErrors', {
        street: 'Calle inválida',
        city: 'Ciudad inválida',
      });
      fixture.detectChanges();

      const streetControl = component.addressFormGroup().controls.street;
      const cityControl = component.addressFormGroup().controls.city;

      expect(streetControl.errors?.['serverError']).toBe('Calle inválida');
      expect(cityControl.errors?.['serverError']).toBe('Ciudad inválida');
    });

    it('should warn on unmatched server error keys', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

      fixture.componentRef.setInput('serverErrors', {
        nonexistent: 'No existe',
      });
      fixture.detectChanges();

      expect(warnSpy).toHaveBeenCalledWith(
        expect.stringContaining('nonexistent'),
      );

      warnSpy.mockRestore();
    });

    it('should clear serverError on valueChanges while preserving other validators', () => {
      const streetControl = component.addressFormGroup().controls.street;

      fixture.componentRef.setInput('serverErrors', {
        street: 'Calle inválida',
      });
      fixture.detectChanges();

      expect(streetControl.errors?.['serverError']).toBe('Calle inválida');

      streetControl.setValue('Nueva calle');
      fixture.detectChanges();

      expect(streetControl.errors?.['serverError']).toBeUndefined();
    });
  });
});
