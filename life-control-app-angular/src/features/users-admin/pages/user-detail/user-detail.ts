import { Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCardModule } from '@angular/material/card';
import { MatDialogModule } from '@angular/material/dialog';
import { FormsModule } from '@angular/forms';
import { UsersAdminService } from '../../services/users-admin.service';
import { NotificationService } from '@shared/data/notification';
import { Role, RoleAssignmentRequest, RoleScope } from '../../models/users-admin.models';

@Component({
  standalone: true,
  imports: [
    MatTabsModule,
    MatTableModule,
    MatIconModule,
    MatButtonModule,
    MatTooltipModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCardModule,
    MatDialogModule,
    RouterLink,
    FormsModule,
  ],
  templateUrl: './user-detail.html',
  styleUrl: './user-detail.scss',
})
export class UserDetail implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly service = inject(UsersAdminService);
  private readonly notification = inject(NotificationService);

  readonly userId = signal<string>('');
  readonly username = signal<string>('');

  readonly userRoles = signal<Role[]>([]);
  readonly userAttributes = signal<Record<string, string[]>>({});
  readonly loadingRoles = signal(false);
  readonly loadingAttributes = signal(false);

  // Assign role form
  readonly assignScope = signal<RoleScope>('realm');
  readonly assignRoleName = signal('');
  readonly assignClientId = signal('');
  readonly assigning = signal(false);

  // Attribute form
  readonly attrKey = signal('');
  readonly attrValue = signal('');
  readonly editingAttr = signal(false);
  readonly savingAttr = signal(false);

  readonly roleDisplayedColumns = ['name', 'scope', 'clientId', 'actions'];
  readonly attrDisplayedColumns = ['key', 'value', 'actions'];

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.router.navigate(['../'], { relativeTo: this.route });
      return;
    }
    this.userId.set(id);
    this.username.set(id); // Display ID until we get real data
    this.loadData();
  }

  private loadData(): void {
    this.loadRoles();
    this.loadAttributes();
  }

  // ─── Roles ────────────────────────────────────────────────

  loadRoles(): void {
    this.loadingRoles.set(true);
    this.service.getUserRoles(this.userId()).subscribe({
      next: (roles) => {
        this.userRoles.set(roles);
        this.loadingRoles.set(false);
      },
      error: (err) => {
        this.notification.showError(err?.error?.message ?? 'Failed to load user roles');
        this.loadingRoles.set(false);
      },
    });
  }

  onAssignRole(): void {
    const name = this.assignRoleName().trim();
    if (!name || this.assigning()) return;

    const request: RoleAssignmentRequest = {
      roleName: name,
      scope: this.assignScope(),
      clientId: this.assignScope() === 'client' ? this.assignClientId().trim() || undefined : undefined,
    };

    this.assigning.set(true);

    if (this.assignScope() === 'realm') {
      this.service.assignRealmRole(this.userId(), request).subscribe({
        next: () => {
          this.notification.showSuccess(`Role "${name}" assigned`);
          this.assignRoleName.set('');
          this.loadRoles();
          this.assigning.set(false);
        },
        error: (err) => {
          this.notification.showError(err?.error?.message ?? `Failed to assign role "${name}"`);
          this.assigning.set(false);
        },
      });
    } else {
      const clientId = request.clientId!;
      this.service.assignClientRole(this.userId(), clientId, request).subscribe({
        next: () => {
          this.notification.showSuccess(`Client role "${name}" assigned`);
          this.assignRoleName.set('');
          this.assignClientId.set('');
          this.loadRoles();
          this.assigning.set(false);
        },
        error: (err) => {
          this.notification.showError(err?.error?.message ?? `Failed to assign client role "${name}"`);
          this.assigning.set(false);
        },
      });
    }
  }

  onRemoveRole(role: Role): void {
    const confirmed = confirm(
      `Remove role "${role.name}" (${role.scope}) from user?\n\nThis cannot be undone.`,
    );
    if (!confirmed) return;

    if (role.scope === 'realm') {
      this.service.removeRealmRole(this.userId(), role.name).subscribe({
        next: () => {
          this.notification.showSuccess(`Role "${role.name}" removed`);
          this.loadRoles();
        },
        error: (err) => {
          this.notification.showError(err?.error?.message ?? `Failed to remove role "${role.name}"`);
        },
      });
    } else if (role.clientId) {
      this.service.removeClientRole(this.userId(), role.clientId, role.name).subscribe({
        next: () => {
          this.notification.showSuccess(`Client role "${role.name}" removed`);
          this.loadRoles();
        },
        error: (err) => {
          this.notification.showError(err?.error?.message ?? `Failed to remove client role "${role.name}"`);
        },
      });
    }
  }

  // ─── Attributes ───────────────────────────────────────────

  loadAttributes(): void {
    this.loadingAttributes.set(true);
    this.service.getUserAttributes(this.userId()).subscribe({
      next: (attrs) => {
        this.userAttributes.set(attrs);
        this.loadingAttributes.set(false);
      },
      error: (err) => {
        this.notification.showError(err?.error?.message ?? 'Failed to load attributes');
        this.loadingAttributes.set(false);
      },
    });
  }

  getAttributesAsList(): { key: string; values: string[] }[] {
    return Object.entries(this.userAttributes()).map(([key, values]) => ({ key, values }));
  }

  valuesAsString(values: string[]): string {
    return values.join(', ');
  }

  onEditAttribute(key: string, values: string[]): void {
    this.attrKey.set(key);
    this.attrValue.set(values.join(', '));
    this.editingAttr.set(true);
  }

  onNewAttribute(): void {
    this.attrKey.set('');
    this.attrValue.set('');
    this.editingAttr.set(false);
  }

  onSaveAttribute(): void {
    const key = this.attrKey().trim();
    const rawValue = this.attrValue().trim();
    if (!key || this.savingAttr()) return;

    // Split by comma for multi-value
    const values = rawValue
      .split(',')
      .map((v) => v.trim())
      .filter((v) => v.length > 0);

    if (values.length === 0) {
      this.notification.showWarning('At least one value is required');
      return;
    }

    this.savingAttr.set(true);
    this.service.updateUserAttribute(this.userId(), key, values).subscribe({
      next: () => {
        this.notification.showSuccess(`Attribute "${key}" saved`);
        this.attrKey.set('');
        this.attrValue.set('');
        this.editingAttr.set(false);
        this.savingAttr.set(false);
        this.loadAttributes();
      },
      error: (err) => {
        this.notification.showError(err?.error?.message ?? `Failed to save attribute "${key}"`);
        this.savingAttr.set(false);
      },
    });
  }

  onDeleteAttribute(key: string): void {
    const confirmed = confirm(`Delete attribute "${key}"?`);
    if (!confirmed) return;

    this.service.deleteUserAttribute(this.userId(), key).subscribe({
      next: () => {
        this.notification.showSuccess(`Attribute "${key}" deleted`);
        this.loadAttributes();
      },
      error: (err) => {
        this.notification.showError(err?.error?.message ?? `Failed to delete attribute "${key}"`);
      },
    });
  }

  cancelAttributeEdit(): void {
    this.attrKey.set('');
    this.attrValue.set('');
    this.editingAttr.set(false);
  }
}
