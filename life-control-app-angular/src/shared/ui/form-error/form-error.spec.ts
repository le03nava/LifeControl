import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { FormControl, Validators } from '@angular/forms';
import { FormErrorComponent } from './form-error';

describe('FormErrorComponent', () => {
  let component: FormErrorComponent;
  let fixture: ComponentFixture<FormErrorComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FormErrorComponent, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(FormErrorComponent);
    component = fixture.componentInstance;
  });

  function setControl(control: FormControl | null): void {
    fixture.componentRef.setInput('control', control);
    fixture.detectChanges();
  }

  describe('rendering', () => {
    it('should render nothing when control is null', () => {
      setControl(null);
      const matError = fixture.nativeElement.querySelector('mat-error');
      expect(matError).toBeNull();
    });

    it('should render nothing when control is valid', () => {
      const control = new FormControl('valor válido');
      setControl(control);
      const matError = fixture.nativeElement.querySelector('mat-error');
      expect(matError).toBeNull();
    });

    it('should render nothing when control is invalid but untouched', () => {
      const control = new FormControl('', Validators.required);
      setControl(control);
      const matError = fixture.nativeElement.querySelector('mat-error');
      expect(matError).toBeNull();
    });
  });

  describe('error messages', () => {
    it('should show "Este campo es obligatorio." for required error', () => {
      const control = new FormControl('', Validators.required);
      control.markAsTouched();
      setControl(control);

      const matError = fixture.nativeElement.querySelector('mat-error');
      expect(matError).toBeTruthy();
      expect(matError.textContent).toContain('Este campo es obligatorio.');
    });

    it('should show email error message for invalid email', () => {
      const control = new FormControl('correo-invalido', Validators.email);
      control.markAsTouched();
      control.updateValueAndValidity();
      setControl(control);

      const matError = fixture.nativeElement.querySelector('mat-error');
      expect(matError).toBeTruthy();
      expect(matError.textContent).toContain(
        'El formato del correo electrónico no es válido.',
      );
    });

    it('should show minlength error with formatted length values', () => {
      const control = new FormControl('ab', [Validators.minLength(5)]);
      control.markAsTouched();
      control.updateValueAndValidity();
      setControl(control);

      const matError = fixture.nativeElement.querySelector('mat-error');
      expect(matError).toBeTruthy();
      expect(matError.textContent).toContain(
        'Debe tener al menos 5 caracteres (llevas 2).',
      );
    });

    it('should show fallback "Campo inválido." for unknown validator', () => {
      const control = new FormControl('test');
      control.setErrors({ customValidator: true });
      control.markAsTouched();
      setControl(control);

      const matError = fixture.nativeElement.querySelector('mat-error');
      expect(matError).toBeTruthy();
      expect(matError.textContent).toContain('Campo inválido.');
    });

    it('should show only first error when multiple errors exist', () => {
      const control = new FormControl('', [Validators.required, Validators.email]);
      control.markAsTouched();
      control.updateValueAndValidity();
      setControl(control);

      const matError = fixture.nativeElement.querySelector('mat-error');
      expect(matError).toBeTruthy();
      // First error should be 'required' (comes first in validators array)
      expect(matError.textContent).toContain('Este campo es obligatorio.');
      // Should NOT contain the email error message
      expect(matError.textContent).not.toContain('correo electrónico');
    });
  });

  describe('serverError', () => {
    it('should display serverError message verbatim', () => {
      const control = new FormControl('valor');
      control.setErrors({ serverError: 'RFC inválido — debe tener 12-13 caracteres' });
      control.markAsTouched();
      setControl(control);

      const matError = fixture.nativeElement.querySelector('mat-error');
      expect(matError).toBeTruthy();
      expect(matError.textContent).toContain('RFC inválido — debe tener 12-13 caracteres');
    });

    it('should respect first-error-wins when serverError comes after required', () => {
      const control = new FormControl('', Validators.required);
      control.markAsTouched();
      control.updateValueAndValidity();
      // control.errors is now { required: true }
      control.setErrors({ ...control.errors, serverError: 'Mensaje de servidor' });
      setControl(control);

      const matError = fixture.nativeElement.querySelector('mat-error');
      expect(matError).toBeTruthy();
      // required comes first in the errors object
      expect(matError.textContent).toContain('Este campo es obligatorio.');
      expect(matError.textContent).not.toContain('Mensaje de servidor');
    });

    it('should not require customMessages configuration for serverError', () => {
      const control = new FormControl('test');
      control.setErrors({ serverError: 'Error del servidor' });
      control.markAsTouched();
      // Set customMessages that does NOT include serverError
      fixture.componentRef.setInput('customMessages', {
        required: () => 'Personalizado.',
      });
      setControl(control);

      const matError = fixture.nativeElement.querySelector('mat-error');
      expect(matError).toBeTruthy();
      expect(matError.textContent).toContain('Error del servidor');
    });
  });

  describe('customMessages', () => {
    it('should override default message for the same validator key', () => {
      const control = new FormControl('', Validators.required);
      control.markAsTouched();
      fixture.componentRef.setInput('customMessages', {
        required: () => 'Campo obligatorio personalizado.',
      });
      setControl(control);

      const matError = fixture.nativeElement.querySelector('mat-error');
      expect(matError).toBeTruthy();
      expect(matError.textContent).toContain('Campo obligatorio personalizado.');
    });

    it('should fall back to default messages for unspecified keys', () => {
      const control = new FormControl('', Validators.required);
      control.markAsTouched();
      fixture.componentRef.setInput('customMessages', {
        email: () => 'Sobrescribir email.',
      });
      setControl(control);

      const matError = fixture.nativeElement.querySelector('mat-error');
      expect(matError).toBeTruthy();
      expect(matError.textContent).toContain('Este campo es obligatorio.');
    });

    it('should behave identically to defaults when customMessages is empty', () => {
      const control = new FormControl('', Validators.required);
      control.markAsTouched();
      fixture.componentRef.setInput('customMessages', {});
      setControl(control);

      const matError = fixture.nativeElement.querySelector('mat-error');
      expect(matError).toBeTruthy();
      expect(matError.textContent).toContain('Este campo es obligatorio.');
    });

    it('should support multiple instances with different customMessages independently', () => {
      const control1 = new FormControl('', Validators.required);
      control1.markAsTouched();
      fixture.componentRef.setInput('customMessages', {
        required: () => 'Mensaje A.',
      });
      setControl(control1);

      const matError1 = fixture.nativeElement.querySelector('mat-error');
      expect(matError1.textContent).toContain('Mensaje A.');

      const control2 = new FormControl('', Validators.required);
      control2.markAsTouched();
      fixture.componentRef.setInput('customMessages', {
        required: () => 'Mensaje B.',
      });
      setControl(control2);

      const matError2 = fixture.nativeElement.querySelector('mat-error');
      expect(matError2.textContent).toContain('Mensaje B.');
    });
  });
});
