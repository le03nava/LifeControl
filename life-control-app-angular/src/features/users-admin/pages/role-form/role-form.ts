import { Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { UsersAdminService } from '../../services/users-admin.service';
import { NotificationService } from '@shared/data/notification';
import { RoleRequest } from '../../models/users-admin.models';

@Component({
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatCheckboxModule,
    MatIconModule,
    RouterLink,
  ],
  templateUrl: './role-form.html',
  styleUrl: './role-form.scss',
})
export class RoleForm implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly service = inject(UsersAdminService);
  private readonly notification = inject(NotificationService);
  private readonly fb = inject(NonNullableFormBuilder);

  readonly isEdit = signal(false);
  readonly existingName = signal<string | null>(null);
  readonly saving = signal(false);

  readonly form = this.fb.group({
    name: this.fb.control('', [Validators.required, Validators.minLength(2)]),
    description: this.fb.control(''),
    composite: this.fb.control(false),
  });

  ngOnInit(): void {
    const name = this.route.snapshot.paramMap.get('name');
    if (name) {
      this.isEdit.set(true);
      this.existingName.set(name);
      this.loadRole(name);
    }
  }

  private loadRole(name: string): void {
    this.service.getRealmRole(name).subscribe({
      next: (role) => {
        this.form.patchValue({
          name: role.name,
          description: role.description ?? '',
          composite: role.composite,
        });
      },
      error: (err) => {
        this.notification.showError(err?.error?.message ?? 'Failed to load role');
        this.router.navigate(['../'], { relativeTo: this.route });
      },
    });
  }

  onSubmit(): void {
    if (this.form.invalid || this.saving()) return;

    const request: RoleRequest = {
      name: this.form.controls.name.value,
      description: this.form.controls.description.value || undefined,
      composite: this.form.controls.composite.value,
    };

    this.saving.set(true);

    if (this.isEdit()) {
      const name = this.existingName()!;
      this.service.updateRealmRole(name, request).subscribe({
        next: () => {
          this.notification.showSuccess(`Role "${request.name}" updated`);
          this.router.navigate(['../'], { relativeTo: this.route });
        },
        error: (err) => {
          this.notification.showError(err?.error?.message ?? 'Failed to update role');
          this.saving.set(false);
        },
      });
    } else {
      this.service.createRealmRole(request).subscribe({
        next: () => {
          this.notification.showSuccess(`Role "${request.name}" created`);
          this.router.navigate(['../'], { relativeTo: this.route });
        },
        error: (err) => {
          this.notification.showError(err?.error?.message ?? 'Failed to create role');
          this.saving.set(false);
        },
      });
    }
  }
}
