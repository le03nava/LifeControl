import { FormControl, Validators } from '@angular/forms';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormInput } from './form-input';

describe('FormInput', () => {
  let fixture: ComponentFixture<FormInput>;
  let component: FormInput;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FormInput],
    }).compileComponents();

    fixture = TestBed.createComponent(FormInput);
    component = fixture.componentInstance;
  });

  function setControl(control: FormControl<unknown>): void {
    fixture.componentRef.setInput('control', control as FormControl<unknown>);
    fixture.detectChanges();
  }

  function nativeInput(): HTMLInputElement {
    return fixture.nativeElement.querySelector('input');
  }

  // ---------- Creation ----------
  describe('creation', () => {
    it('should be created', () => {
      setControl(new FormControl(''));
      expect(component).toBeTruthy();
    });

    it('should render the control value', () => {
      setControl(new FormControl('hola'));
      expect(nativeInput().value).toBe('hola');
    });

    it('should render type and placeholder attributes', () => {
      fixture.componentRef.setInput('type', 'email');
      fixture.componentRef.setInput('placeholder', 'Ingrese email');
      setControl(new FormControl(''));
      expect(nativeInput().getAttribute('type')).toBe('email');
      expect(nativeInput().getAttribute('placeholder')).toBe('Ingrese email');
    });
  });

  // ---------- Form control binding ----------
  describe('form control binding', () => {
    it('should update the FormControl value when the user types', () => {
      const control = new FormControl('');
      setControl(control);

      const input = nativeInput();
      input.value = 'typed';
      input.dispatchEvent(new Event('input'));

      expect(control.value).toBe('typed');
    });
  });

  // ---------- Error display ----------
  describe('errors', () => {
    it('should NOT show an error when the control is invalid but not touched/dirty', () => {
      const control = new FormControl('', Validators.required);
      setControl(control);

      expect(fixture.nativeElement.querySelector('input.is-invalid')).toBeNull();
      expect(fixture.nativeElement.textContent).not.toContain('Este campo es requerido.');
    });

    it('should show the required error message when the control is invalid and touched', () => {
      const control = new FormControl('', Validators.required);
      control.markAsTouched();
      setControl(control);

      expect(nativeInput().classList.contains('is-invalid')).toBe(true);
      expect(fixture.nativeElement.textContent).toContain('Este campo es requerido.');
    });

    it('should show the email error message for an invalid email', () => {
      const control = new FormControl('not-an-email', [Validators.email]);
      control.markAsTouched();
      setControl(control);

      expect(fixture.nativeElement.textContent).toContain('Formato de email inválido.');
    });

    it('should show the minlength error message with the required length', () => {
      const control = new FormControl('ab', Validators.minLength(5));
      control.markAsTouched();
      setControl(control);

      expect(fixture.nativeElement.textContent).toContain('Mínimo 5 caracteres.');
    });

    it('should show a generic message for unknown validators', () => {
      const control = new FormControl('x', () => ({ custom: { valid: false } }));
      control.markAsTouched();
      setControl(control);

      expect(fixture.nativeElement.textContent).toContain('Error de validación.');
    });

    it('should clear the error once the control becomes valid', () => {
      const control = new FormControl('', Validators.required);
      control.markAsTouched();
      setControl(control);
      expect(fixture.nativeElement.textContent).toContain('Este campo es requerido.');

      setControl(new FormControl('valor válido'));

      expect(nativeInput().classList.contains('is-invalid')).toBe(false);
      expect(fixture.nativeElement.textContent).not.toContain('Este campo es requerido.');
    });
  });
});
