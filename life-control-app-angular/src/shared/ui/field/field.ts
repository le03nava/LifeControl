import { ChangeDetectionStrategy, Component, input, OnInit, output } from '@angular/core';
import { FormControl } from '@angular/forms';
import { FormInput } from '../form-input/form-input';
@Component({
  selector: 'app-field',
  imports: [FormInput],
  templateUrl: './field.html',
  styleUrl: './field.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Field {
  control = input.required<FormControl<any>>();
  fieldId = input.required<string>();
  label = input<string>();
  type = input<'text' | 'email' | 'password' | 'number' | 'tel' | 'url'>('text');
  placeholder = input<string>('');
  disabled = input(false);
  required = input(false);
  errorMessage = input<string>();
  helpText = input<string>();
  autocomplete = input<string>('off');
}
