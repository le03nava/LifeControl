import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';

@Component({
  selector: 'app-form-input',
  imports: [ReactiveFormsModule],
  templateUrl: './form-input.html',
  styleUrl: './form-input.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
})
export class FormInput {
  control = input.required<FormControl<any | null>>();
  // Inputs opcionales
  placeholder = input<string>('');
  type = input<'text' | 'email' | 'password' | 'number' | 'tel' | 'url'>('text');

  // Signal Computed para determinar si mostrar error
  hasError = computed(() => {
    const ctrl = this.control();
    // Verifica si es inválido Y ha sido tocado o modificado
    return ctrl.invalid && (ctrl.dirty || ctrl.touched);
  });

  // Signal Computed para determinar el mensaje de error (simple)
  errorMessage = computed(() => {
    const ctrl = this.control();
    if (!ctrl.errors) return '';

    if (ctrl.errors['required']) {
      return 'Este campo es requerido.';
    }
    if (ctrl.errors['email']) {
      return 'Formato de email inválido.';
    }
    if (ctrl.errors['minlength']) {
      const requiredLength = ctrl.errors['minlength'].requiredLength;
      return `Mínimo ${requiredLength} caracteres.`;
    }
    return 'Error de validación.';
  });
}
