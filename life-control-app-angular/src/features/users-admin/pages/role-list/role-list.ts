import { Component, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { FormsModule } from '@angular/forms';
import { UsersAdminService } from '../../services/users-admin.service';
import { NotificationService } from '@shared/data/notification';
import { Role } from '../../models/users-admin.models';

@Component({
  standalone: true,
  imports: [
    MatTableModule,
    MatIconModule,
    MatButtonModule,
    MatTooltipModule,
    MatSlideToggleModule,
    MatFormFieldModule,
    MatInputModule,
    MatDialogModule,
    FormsModule,
  ],
  templateUrl: './role-list.html',
  styleUrl: './role-list.scss',
})
export class RoleList {
  private readonly service = inject(UsersAdminService);
  private readonly router = inject(Router);
  private readonly notification = inject(NotificationService);
  private readonly dialog = inject(MatDialog);

  readonly roles = this.service.roles;
  readonly loading = this.service.loading;

  showClientRoles = signal(false);
  clientId = signal('');
  filter = signal('');

  displayedColumns = ['name', 'description', 'composite', 'scope', 'actions'];

  constructor() {
    this.service.loadRealmRoles();

    effect(() => {
      this.filter();
      this.showClientRoles();
      this.clientId();
    });
  }

  get filteredRoles(): Role[] {
    const query = this.filter().toLowerCase();
    return this.roles().filter(
      (role) =>
        role.name.toLowerCase().includes(query) ||
        (role.description?.toLowerCase().includes(query) ?? false),
    );
  }

  onToggleClientRoles(): void {
    if (this.showClientRoles()) {
      const id = this.clientId().trim();
      if (id) {
        this.service.loadClientRoles(id);
      } else {
        this.notification.showWarning('Enter a client ID first');
        this.showClientRoles.set(false);
      }
    } else {
      this.service.loadRealmRoles();
    }
  }

  onCreateRole(): void {
    this.router.navigate(['/users-admin/roles/create']);
  }

  onEditRole(role: Role): void {
    this.router.navigate(['/users-admin/roles/edit', role.name]);
  }

  onDeleteRole(role: Role): void {
    const scope = role.scope;
    const idLabel = scope === 'client' ? `client ${role.clientId}` : 'realm';
    const confirmed = confirm(
      `Delete "${role.name}" (${idLabel}) role?\n\nThis cannot be undone. The role must not be assigned to any user.`,
    );
    if (!confirmed) return;

    if (scope === 'realm') {
      this.service.deleteRealmRole(role.name).subscribe({
        next: () => {
          this.notification.showSuccess(`Role "${role.name}" deleted`);
          this.service.loadRealmRoles();
        },
        error: (err) => {
          this.notification.showError(
            err?.error?.message ?? `Failed to delete role "${role.name}"`,
          );
        },
      });
    }
  }

}
