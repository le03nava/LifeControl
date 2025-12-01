import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { UserService } from './data/user';
import { NotificationService } from '@shared/data/notification';
import { Button } from '@shared/ui/button/button';
import { Input } from '@shared/ui';
import { Modal } from '@shared/ui/modal';

/**
 * User list component demonstrating:
 * - Signal-based data management
 * - Modern template syntax with control flow
 * - Reactive filtering
 * - Confirmation dialogs
 */
@Component({
  selector: 'app-user-list',
  imports: [RouterLink, Button, Input, Modal],
  template: `
    <div class="user-list-page">
      <header class="page-header">
        <div class="header-content">
          <h1 class="page-title">User Management</h1>
          <p class="page-subtitle">Manage system users and their permissions</p>
        </div>
        <div class="header-actions">
          <button app-button variant="primary" routerLink="/users/create">➕ Add User</button>
        </div>
      </header>

      <div class="filters-section">
        <div class="search-filter">
          <app-input
            type="text"
            placeholder="Search users..."
            [value]="userService.currentFilters().search"
            (inputChange)="userService.updateSearch($event)"
          />
        </div>

        <div class="role-filter">
          <select
            [value]="userService.currentFilters().role"
            (change)="handleRoleFilterChange($event)"
            class="role-select"
          >
            <option value="all">All Roles</option>
            <option value="admin">Admin</option>
            <option value="user">User</option>
          </select>
        </div>
      </div>

      <div class="users-grid">
        @for (user of userService.filteredUsers(); track user.id) {
          <div class="user-card">
            <div class="user-avatar">{{ user.avatar || '👤' }}</div>
            <div class="user-info">
              <h3 class="user-name">{{ user.name }}</h3>
              <p class="user-email">{{ user.email }}</p>
              <span class="user-role" [class]="user.role">{{ user.role }}</span>
            </div>
            <div class="user-actions">
              <app-button variant="secondary" size="small" [routerLink]="['/users', user.id]">
                View
              </app-button>
              <app-button
                variant="secondary"
                size="small"
                [routerLink]="['/users', user.id, 'edit']"
              >
                Edit
              </app-button>
              <button
                app-button
                variant="danger"
                size="small"
                (buttonClick)="confirmDelete(user.id, user.name)"
              >
                Delete
              </button>
            </div>
          </div>
        } @empty {
          <div class="empty-state">
            <div class="empty-icon">👥</div>
            <h3 class="empty-title">No users found</h3>
            <p class="empty-description">
              @if (userService.currentFilters().search) {
                No users match your search criteria.
              } @else {
                Get started by adding your first user.
              }
            </p>
            @if (!userService.currentFilters().search) {
              <app-button variant="primary" routerLink="/users/create"> Add First User </app-button>
            }
          </div>
        }
      </div>

      <!-- Delete Confirmation Modal -->
      <app-modal
        [isOpen]="showDeleteModal()"
        title="Confirm Deletion"
        size="small"
        (modalClose)="cancelDelete()"
      >
        <p>
          Are you sure you want to delete <strong>{{ userToDelete()?.name }}</strong
          >?
        </p>
        <p class="warning-text">This action cannot be undone.</p>

        <div slot="footer">
          <button app-button variant="secondary" (buttonClick)="cancelDelete()">Cancel</button>
          <button
            app-button
            variant="danger"
            (buttonClick)="executeDelete()"
            [disabled]="isDeleting()"
          >
            @if (isDeleting()) {
              Deleting...
            } @else {
              Delete User
            }
          </button>
        </div>
      </app-modal>
    </div>
  `,
  styles: [
    `
      .user-list-page {
        max-width: 1200px;
        margin: 0 auto;
      }

      .page-header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        margin-bottom: var(--space-2xl);
        gap: var(--space-lg);
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

      .filters-section {
        display: flex;
        gap: var(--space-lg);
        margin-bottom: var(--space-xl);
        flex-wrap: wrap;
      }

      .search-filter {
        flex: 1;
        min-width: 250px;
      }

      .role-select {
        padding: var(--space-sm) var(--space-md);
        border: 1px solid var(--color-gray-300);
        border-radius: var(--radius-md);
        font-size: 1rem;
        background: white;
        min-width: 150px;
      }

      .users-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(350px, 1fr));
        gap: var(--space-lg);
      }

      .user-card {
        background: white;
        border: 1px solid var(--color-gray-200);
        border-radius: var(--radius-lg);
        padding: var(--space-lg);
        display: flex;
        flex-direction: column;
        gap: var(--space-md);
        transition: box-shadow 0.2s ease;
      }

      .user-card:hover {
        box-shadow: var(--shadow-md);
      }

      .user-avatar {
        font-size: 3rem;
        text-align: center;
        margin-bottom: var(--space-sm);
      }

      .user-info {
        text-align: center;
        flex: 1;
      }

      .user-name {
        margin: 0 0 var(--space-xs);
        font-size: 1.25rem;
        font-weight: 600;
        color: var(--color-gray-900);
      }

      .user-email {
        margin: 0 0 var(--space-sm);
        color: var(--color-gray-600);
        font-size: 0.875rem;
      }

      .user-role {
        display: inline-block;
        padding: var(--space-xs) var(--space-sm);
        border-radius: var(--radius-sm);
        font-size: 0.75rem;
        font-weight: 500;
        text-transform: uppercase;
        letter-spacing: 0.05em;
      }

      .user-role.admin {
        background-color: var(--color-primary);
        color: white;
      }

      .user-role.user {
        background-color: var(--color-gray-200);
        color: var(--color-gray-700);
      }

      .user-actions {
        display: flex;
        gap: var(--space-sm);
        justify-content: center;
      }

      .empty-state {
        grid-column: 1 / -1;
        text-align: center;
        padding: var(--space-2xl);
        color: var(--color-gray-500);
      }

      .empty-icon {
        font-size: 4rem;
        margin-bottom: var(--space-lg);
      }

      .empty-title {
        margin: 0 0 var(--space-md);
        font-size: 1.5rem;
        font-weight: 600;
        color: var(--color-gray-700);
      }

      .empty-description {
        margin: 0 0 var(--space-lg);
        font-size: 1rem;
        max-width: 400px;
        margin-left: auto;
        margin-right: auto;
      }

      .warning-text {
        color: var(--color-error);
        font-size: 0.875rem;
        margin-top: var(--space-sm);
      }

      @media (max-width: 768px) {
        .page-header {
          flex-direction: column;
          align-items: stretch;
        }

        .filters-section {
          flex-direction: column;
        }

        .users-grid {
          grid-template-columns: 1fr;
        }

        .user-actions {
          flex-direction: column;
        }
      }
    `,
  ],
})
export class UserList {
  userService = inject(UserService);
  private notificationService = inject(NotificationService);

  showDeleteModal = signal(false);
  userToDelete = signal<{ id: string; name: string } | null>(null);
  isDeleting = signal(false);

  handleRoleFilterChange(event: Event): void {
    const target = event.target as HTMLSelectElement;
    this.userService.updateRoleFilter(target.value as any);
  }

  confirmDelete(userId: string, userName: string): void {
    this.userToDelete.set({ id: userId, name: userName });
    this.showDeleteModal.set(true);
  }

  cancelDelete(): void {
    this.showDeleteModal.set(false);
    this.userToDelete.set(null);
  }

  async executeDelete(): Promise<void> {
    const user = this.userToDelete();
    if (!user || this.isDeleting()) return;

    this.isDeleting.set(true);

    try {
      await this.userService.deleteUser(user.id);
      this.notificationService.showSuccess(`User ${user.name} deleted successfully`);
      this.showDeleteModal.set(false);
      this.userToDelete.set(null);
    } catch (_error) {
      this.notificationService.showError('Failed to delete user');
    } finally {
      this.isDeleting.set(false);
    }
  }
}
