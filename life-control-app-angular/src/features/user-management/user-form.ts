import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { UserService } from './data/user';
import { NotificationService } from '@shared/data/notification';
import { Button } from '@shared/ui';

/**
 * User form component for creating and editing users
 * Demonstrates modern reactive forms with signals
 */
@Component({
  selector: 'app-user-form',
  imports: [ReactiveFormsModule, Button],
  template: `
    <div class="user-form-page">
      <header class="page-header">
        <h1 class="page-title">
          {{ isEditMode() ? 'Edit User' : 'Create New User' }}
        </h1>
        <p class="page-subtitle">
          {{ isEditMode() ? 'Update user information' : 'Add a new user to the system' }}
        </p>
      </header>

      <form [formGroup]="userForm" (ngSubmit)="handleSubmit()" class="user-form">
        <div class="form-section">
          <h2 class="section-title">Basic Information</h2>

          <div class="form-grid"></div>

          <div class="form-grid">
            <div class="field">
              <label for="role" class="label"> Role <span class="required">*</span> </label>
              <select
                id="role"
                formControlName="role"
                class="select-input"
                [class.error]="userForm.get('role')?.errors && userForm.get('role')?.touched"
              >
                <option value="">Select a role</option>
                <option value="user">User</option>
                <option value="admin">Admin</option>
              </select>
              @if (getFieldError('role')) {
                <div class="error-message">{{ getFieldError('role') }}</div>
              }
            </div>
          </div>
        </div>

        <div class="form-actions">
          <button app-button type="button" variant="secondary" (buttonClick)="goBack()">
            Cancel
          </button>

          <button
            app-button
            type="submit"
            variant="primary"
            [disabled]="userForm.invalid || isSubmitting()"
          >
            @if (isSubmitting()) {
              {{ isEditMode() ? 'Updating...' : 'Creating...' }}
            } @else {
              {{ isEditMode() ? 'Update User' : 'Create User' }}
            }
          </button>
        </div>
      </form>
    </div>
  `,
  styles: [
    `
      .user-form-page {
        max-width: 800px;
        margin: 0 auto;
      }

      .page-header {
        margin-bottom: var(--space-2xl);
      }

      .page-title {
        margin: 0 0 var(--space-sm);
        font-size: 2.5rem;
        font-weight: 700;
        color: var(--color-gray-900);
      }

      .page-subtitle {
        margin: 0;
        font-size: 1.125rem;
        color: var(--color-gray-600);
      }

      .user-form {
        background: white;
        border: 1px solid var(--color-gray-200);
        border-radius: var(--radius-lg);
        padding: var(--space-2xl);
      }

      .form-section {
        margin-bottom: var(--space-2xl);
      }

      .section-title {
        margin: 0 0 var(--space-lg);
        font-size: 1.25rem;
        font-weight: 600;
        color: var(--color-gray-800);
        padding-bottom: var(--space-sm);
        border-bottom: 1px solid var(--color-gray-200);
      }

      .form-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
        gap: var(--space-lg);
        margin-bottom: var(--space-lg);
      }

      .field {
        display: flex;
        flex-direction: column;
        gap: var(--space-xs);
      }

      .label {
        font-weight: 500;
        color: var(--color-gray-700);
        font-size: 0.875rem;
      }

      .required {
        color: var(--color-error);
        margin-left: var(--space-xs);
      }

      .select-input {
        padding: var(--space-sm) var(--space-md);
        border: 1px solid var(--color-gray-300);
        border-radius: var(--radius-md);
        font-size: 1rem;
        background: white;
        transition: border-color 0.2s ease;
      }

      .select-input:focus {
        outline: none;
        border-color: var(--color-primary);
        box-shadow: 0 0 0 3px rgb(37 99 235 / 0.1);
      }

      .select-input.error {
        border-color: var(--color-error);
      }

      .error-message {
        color: var(--color-error);
        font-size: 0.875rem;
        font-weight: 500;
      }

      .form-actions {
        display: flex;
        gap: var(--space-md);
        justify-content: flex-end;
        padding-top: var(--space-lg);
        border-top: 1px solid var(--color-gray-200);
      }

      @media (max-width: 768px) {
        .form-grid {
          grid-template-columns: 1fr;
        }

        .form-actions {
          flex-direction: column-reverse;
        }
      }
    `,
  ],
})
export class UserForm {
  private fb = inject(FormBuilder);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private userService = inject(UserService);
  private notificationService = inject(NotificationService);

  isEditMode = signal(false);
  isSubmitting = signal(false);
  userId = signal<string | null>(null);

  userForm = this.fb.group({
    name: ['', [Validators.required, Validators.minLength(2)]],
    email: ['', [Validators.required, Validators.email]],
    role: ['', [Validators.required]],
    avatar: ['👤'],
  });

  constructor() {
    // Check if we're in edit mode
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.isEditMode.set(true);
      this.userId.set(id);
      this.loadUser(id);
    }
  }

  private loadUser(id: string): void {
    const user = this.userService.getUserById(id);
    if (user) {
      this.userForm.patchValue({
        name: user.name,
        email: user.email,
        role: user.role,
        avatar: user.avatar || '👤',
      });
    }
  }

  async handleSubmit(): Promise<void> {
    if (this.userForm.invalid || this.isSubmitting()) {
      return;
    }

    this.isSubmitting.set(true);

    try {
      const formValue = this.userForm.value;
      const userData = {
        name: formValue.name!,
        email: formValue.email!,
        role: formValue.role! as 'admin' | 'user',
        avatar: formValue.avatar || '👤',
      };

      if (this.isEditMode()) {
        await this.userService.updateUser(this.userId()!, userData);
        this.notificationService.showSuccess('User updated successfully');
      } else {
        await this.userService.createUser(userData);
        this.notificationService.showSuccess('User created successfully');
      }

      this.router.navigate(['/users']);
    } catch (_error) {
      this.notificationService.showError(
        this.isEditMode() ? 'Failed to update user' : 'Failed to create user',
      );
    } finally {
      this.isSubmitting.set(false);
    }
  }

  getFieldError(fieldName: string): string | undefined {
    const field = this.userForm.get(fieldName);

    if (field?.errors && field.touched) {
      if (field.errors['required']) {
        return `${this.getFieldLabel(fieldName)} is required`;
      }
      if (field.errors['email']) {
        return 'Please enter a valid email address';
      }
      if (field.errors['minlength']) {
        return `${this.getFieldLabel(fieldName)} must be at least ${field.errors['minlength'].requiredLength} characters`;
      }
    }

    return undefined;
  }

  private getFieldLabel(fieldName: string): string {
    const labels: Record<string, string> = {
      name: 'Name',
      email: 'Email',
      role: 'Role',
      avatar: 'Avatar',
    };
    return labels[fieldName] || fieldName;
  }

  goBack(): void {
    this.router.navigate(['/users']);
  }
}
