import { Component } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { By } from '@angular/platform-browser';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Input } from './input';

/* eslint-disable-next-line @angular-eslint/prefer-on-push-component-change-detection -- test host */
@Component({
  standalone: true,
  imports: [ReactiveFormsModule, Input],
  template: `
    <form [formGroup]="form">
      <app-input
        [type]="type"
        [placeholder]="placeholder"
        [hasError]="hasError"
        [id]="id"
        [name]="name"
        [autocomplete]="autocomplete"
        [ariaDescribedBy]="ariaDescribedBy"
        [value]="value"
      />
    </form>
  `,
})
class InputHost {
  form = new FormGroup({ '': new FormControl('') });
  type: 'text' | 'email' | 'password' | 'number' | 'tel' | 'url' = 'text';
  placeholder = '';
  hasError = false;
  id?: string;
  name?: string;
  autocomplete = 'off';
  ariaDescribedBy?: string;
  value = '';
}

describe('Input', () => {
  let fixture: ComponentFixture<InputHost>;
  let host: InputHost;
  let component: Input;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Input, InputHost],
    }).compileComponents();

    fixture = TestBed.createComponent(InputHost);
    host = fixture.componentInstance;
    fixture.detectChanges();

    component = fixture.debugElement.query(By.directive(Input)).componentInstance;
  });

  function nativeInput(): HTMLInputElement {
    return fixture.nativeElement.querySelector('input');
  }

  // ---------- Creation ----------
  describe('creation', () => {
    it('should be created', () => {
      expect(component).toBeTruthy();
    });

    it('should render a native input element', () => {
      expect(nativeInput()).toBeTruthy();
    });
  });

  // ---------- ControlValueAccessor ----------
  describe('ControlValueAccessor', () => {
    it('should update internalValue with writeValue', () => {
      component.writeValue('initial');
      expect(component.internalValue).toBe('initial');
    });

    it('should normalize empty values on writeValue', () => {
      component.writeValue('');
      expect(component.internalValue).toBe('');
    });

    it('should call onChange and emit inputChange on handleInput', () => {
      const onChange = vi.fn();
      component.registerOnChange(onChange);

      let emitted: string | undefined;
      component.inputChange.subscribe((value: string) => {
        emitted = value;
      });

      component.handleInput({ target: { value: 'abc' } } as unknown as Event);

      expect(onChange).toHaveBeenCalledWith('abc');
      expect(component.internalValue).toBe('abc');
      expect(emitted).toBe('abc');
    });

    it('should call onTouched and emit inputBlur on handleBlur', () => {
      const onTouched = vi.fn();
      component.registerOnTouched(onTouched);

      let blurEmitted = false;
      component.inputBlur.subscribe(() => {
        blurEmitted = true;
      });

      component.handleBlur();

      expect(onTouched).toHaveBeenCalled();
      expect(blurEmitted).toBe(true);
    });

    it('should emit inputFocus on handleFocus', () => {
      let focusEmitted = false;
      component.inputFocus.subscribe(() => {
        focusEmitted = true;
      });

      component.handleFocus();

      expect(focusEmitted).toBe(true);
    });

    it('should not throw when setDisabledState is called', () => {
      expect(() => component.setDisabledState(true)).not.toThrow();
    });
  });

  // ---------- Form binding ----------
  describe('form binding', () => {
    it('should update the bound form control when the user types', () => {
      const input = nativeInput();
      input.value = 'typed value';
      input.dispatchEvent(new Event('input'));

      expect(host.form.get('')?.value).toBe('typed value');
    });
  });

  // ---------- Validation states ----------
  describe('validation states', () => {
    it('should set aria-invalid to true when hasError is true', () => {
      host.hasError = true;
      fixture.detectChanges();
      expect(nativeInput().getAttribute('aria-invalid')).toBe('true');
    });
  });

  // ---------- HTML attributes ----------
  describe('HTML attributes', () => {
    it('should render the type attribute', () => {
      host.type = 'email';
      fixture.detectChanges();
      expect(nativeInput().getAttribute('type')).toBe('email');
    });

    it('should render the placeholder attribute', () => {
      host.placeholder = 'Ingrese email';
      fixture.detectChanges();
      expect(nativeInput().getAttribute('placeholder')).toBe('Ingrese email');
    });

    it('should render the autocomplete attribute', () => {
      host.autocomplete = 'on';
      fixture.detectChanges();
      expect(nativeInput().getAttribute('autocomplete')).toBe('on');
    });

    it('should render the id attribute when provided', () => {
      host.id = 'email-field';
      fixture.detectChanges();
      expect(nativeInput().getAttribute('id')).toBe('email-field');
    });

    it('should render the name attribute when provided', () => {
      host.name = 'email';
      fixture.detectChanges();
      expect(nativeInput().getAttribute('name')).toBe('email');
    });

    it('should render aria-describedby when provided', () => {
      host.ariaDescribedBy = 'help-text';
      fixture.detectChanges();
      expect(nativeInput().getAttribute('aria-describedby')).toBe('help-text');
    });
  });

  // ---------- Value input ----------
  describe('value input', () => {
    it('should render the value input over internalValue', () => {
      component.writeValue('internal');
      host.value = 'input-value';
      fixture.detectChanges();
      expect(nativeInput().value).toBe('input-value');
    });
  });
});
