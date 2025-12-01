import {
  ChangeDetectionStrategy,
  Component,
  forwardRef,
  input,
  OnInit,
  output,
} from '@angular/core';
import {
  ControlContainer,
  ControlValueAccessor,
  FormGroupDirective,
  NG_VALUE_ACCESSOR,
  ReactiveFormsModule,
} from '@angular/forms';

@Component({
  selector: 'app-input',
  imports: [ReactiveFormsModule],
  templateUrl: './input.html',
  styleUrl: './input.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => Input),
      multi: true,
    },
  ],
  viewProviders: [
    {
      provide: ControlContainer,
      useExisting: FormGroupDirective, // Usa el Form Group del padre
    },
  ],
})
export class Input implements ControlValueAccessor, OnInit {
  id = input<string | undefined>(undefined);
  name = input<string | undefined>(undefined);
  type = input<'text' | 'email' | 'password' | 'number' | 'tel' | 'url'>('text');
  placeholder = input<string>('');
  disabled = input(false);
  hasError = input(false);
  ariaDescribedBy = input<string>();
  value = input<string>('');
  autocomplete = input<string>('off');
  formControlName = input<string>('');
  inputChange = output<string>();
  inputBlur = output<void>();
  inputFocus = output<void>();

  internalValue = '';

  ngOnInit(): void {
    // ✅ ¡El lugar correcto! El valor de miDato ya ha sido asignado por el padre.
    console.log('ngOnInit:', this.formControlName());
  }
  private onChange = (_value: string) => {};
  private onTouched = () => {};

  handleInput(event: Event) {
    const target = event.target as HTMLInputElement;
    this.internalValue = target.value;
    this.onChange(this.internalValue);
    this.inputChange.emit(this.internalValue);
  }

  handleBlur() {
    this.onTouched();
    this.inputBlur.emit();
  }

  handleFocus() {
    this.inputFocus.emit();
  }

  // ControlValueAccessor implementation
  writeValue(value: string): void {
    this.internalValue = value || '';
  }

  registerOnChange(fn: (_value: string) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(_isDisabled: boolean): void {
    // Handled by signal input
  }
}
