import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AbstractControl, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '@shared/data/auth';
import { NotificationService } from '@shared/data/notification';
import { Button } from '@shared/ui/button/button';

/**
 * Login component demonstrating modern Angular 20 patterns
 * Features:
 * - Signal-based reactive forms
 * - Functional validation
 * - Proper error handling
 * - Accessibility features
 */
@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, Button],
  template: `
    <div class="login-page">
      <div class="login-container">
        <div class="login-header">
          <h1 class="login-title">Welcome Back</h1>
          <p class="login-subtitle">Sign in to your account</p>
        </div>

        {{loginForm.valid  ? 'Form is valid' : 'Form is invalid'}}
        {{loginForm.touched  ? 'Form is touched' : 'Form is not touched'}}
        <form [formGroup]="loginForm" (ngSubmit)="handleSubmit()" class="login-form">

          <div class="form-actions">
            <button
              app-button
              type="submit"
              variant="primary"
              size="large"
              class="submit-button"
              [disabled]="isSubmitting()"
            >
              @if (isSubmitting()) {
                Signing in...
              } @else {
                Sign In
              }
            </button>
          </div>

          <div class="demo-credentials">
            <p class="demo-title">Demo Credentials:</p>
            <p class="demo-text">Email: <code>admin@example.com</code> or <code>user@example.com</code></p>
            <p class="demo-text">Password: <code>password</code></p>
          </div>
        </form>
      </div>
    </div>
  `,
  styles: [
    `
      .login-page {
        min-height: 100vh;
        display: flex;
        align-items: center;
        justify-content: center;
        background: linear-gradient(135deg, var(--color-primary) 0%, #1e40af 100%);
        padding: var(--space-md);
      }

      .login-container {
        background: white;
        border-radius: var(--radius-lg);
        box-shadow: var(--shadow-lg);
        padding: var(--space-2xl);
        width: 100%;
        max-width: 400px;
      }

      .login-header {
        text-align: center;
        margin-bottom: var(--space-2xl);
      }

      .login-title {
        margin: 0 0 var(--space-sm);
        font-size: 2rem;
        font-weight: 700;
        color: var(--color-gray-900);
      }

      .login-subtitle {
        margin: 0;
        color: var(--color-gray-600);
        font-size: 1rem;
      }

      .login-form {
        display: flex;
        flex-direction: column;
        gap: var(--space-lg);
      }

      .form-actions {
        margin-top: var(--space-md);
      }

      .submit-button {
        width: 100%;
      }

      .demo-credentials {
        margin-top: var(--space-lg);
        padding: var(--space-md);
        background-color: var(--color-gray-50);
        border-radius: var(--radius-md);
        border: 1px solid var(--color-gray-200);
      }

      .demo-title {
        margin: 0 0 var(--space-sm);
        font-weight: 600;
        color: var(--color-gray-700);
        font-size: 0.875rem;
      }

      .demo-text {
        margin: 0 0 var(--space-xs);
        font-size: 0.875rem;
        color: var(--color-gray-600);
      }

      code {
        background-color: var(--color-gray-200);
        padding: 2px 4px;
        border-radius: var(--radius-sm);
        font-family: var(--font-family-mono);
        font-size: 0.8rem;
      }
    `,
  ],
})
export class Login {
  private fb = inject(FormBuilder);
  private authService = inject(AuthService);
  private router = inject(Router);
  private notificationService = inject(NotificationService);

  isSubmitting = signal(false);

  loginForm = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(6)]],
  });

  changeValue(val: string, control: AbstractControl): void {
    control.setValue(val);
    control.markAsTouched();
  }

  async handleSubmit(): Promise<void> {
    if (this.loginForm.invalid || this.isSubmitting()) {
      this.loginForm.markAllAsTouched();
      return;
    }

    this.isSubmitting.set(true);

    try {
      const { email, password } = this.loginForm.value;
      const success = await this.authService.login(email!, password!);

      if (success) {
        this.notificationService.showSuccess('Welcome back!');
        this.router.navigate(['/']);
      } else {
        this.notificationService.showError('Invalid email or password');
      }
    } catch (_error) {
      this.notificationService.showError('Login failed. Please try again.');
    } finally {
      this.isSubmitting.set(false);
    }
  }

  getFieldError(fieldName: string): string | undefined {
    const field = this.loginForm.get(fieldName);

    if (field?.errors && field.touched) {
      if (field.errors['required']) {
        return `${fieldName.charAt(0).toUpperCase() + fieldName.slice(1)} is required`;
      }
      if (field.errors['email']) {
        return 'Please enter a valid email address';
      }
      if (field.errors['minlength']) {
        return `${fieldName.charAt(0).toUpperCase() + fieldName.slice(1)} must be at least ${field.errors['minlength'].requiredLength} characters`;
      }
    }

    return undefined;
  }
}
