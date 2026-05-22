import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { AbstractControl } from '@angular/forms';
import { MatInputModule } from '@angular/material/input';

@Component({
  selector: 'app-form-error',
  standalone: true,
  imports: [MatInputModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (mensaje) {
      <mat-error>{{ mensaje }}</mat-error>
    }
  `,
})
export class FormErrorComponent {
  control = input.required<AbstractControl | null>();
  customMessages = input<Record<string, (error: any) => string>>({});

  private readonly mensajesError: Record<string, (error: any) => string> = {
    required: () => 'Este campo es obligatorio.',
    email: () => 'El formato del correo electrónico no es válido.',
    minlength: (err) =>
      `Debe tener al menos ${err.requiredLength} caracteres (llevas ${err.actualLength}).`,
    maxlength: (err) =>
      `No puede superar los ${err.requiredLength} caracteres.`,
    pattern: () => 'El formato ingresado no es válido.',
  };

  get mensaje(): string | null {
    const ctrl = this.control();
    if (!ctrl || !ctrl.errors || !ctrl.touched) {
      return null;
    }

    const primerErrorKey = Object.keys(ctrl.errors)[0];
    const errorDetalle = ctrl.errors[primerErrorKey];

    const allMessages = { ...this.mensajesError, ...this.customMessages() };
    if (allMessages[primerErrorKey]) {
      return allMessages[primerErrorKey](errorDetalle);
    }

    return 'Campo inválido.';
  }
}
