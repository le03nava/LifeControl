import {
  Component,
  inject,
  signal,
  input,
  output,
  effect,
} from '@angular/core';
import {
  FormBuilder,
  FormGroup,
  Validators,
  ReactiveFormsModule,
  FormControl,
} from '@angular/forms';
import { User } from '../../models/user.models';
import { UserControl } from '../../models/user.models';
import { Field } from '@shared/ui';

@Component({
  selector: 'app-users-form',
  standalone: true,
  imports: [ReactiveFormsModule, Field],
  templateUrl: './users-form.html',
  styleUrl: './users-form.scss',
})
export class UsersForm {
  formGroup = input.required<FormGroup<UserControl>>();
  saveUser = output<User>();
  cancelForm = output<void>();

  isEditMode = signal(false);

  constructor() {
    effect(() => {
      const form = this.formGroup();
      if (form.get('id')?.value) {
        this.isEditMode.set(true);
      }
    });
  }

  onSave(): void {
    if (this.formGroup().valid) {
      const formData = this.formGroup();

      const userData: User = {
        id: formData.get('id')!.value,
        username: formData.get('username')!.value,
        email: formData.get('email')!.value,
        password: formData.get('password')!.value,
        name: formData.get('name')!.value,
        lastname: formData.get('lastname')!.value,
        phone: formData.get('phone')!.value,
        enabled: formData.get('enabled')!.value,
      };
      this.saveUser.emit(userData);
    }
  }

  onCancel(): void {
    this.cancelForm.emit();
  }

  getControl(controlName: keyof UserControl): FormControl<string | boolean | null> {
    return this.formGroup().controls[controlName] as FormControl<any | null>;
  }
}
