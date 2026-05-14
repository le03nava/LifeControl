import { Component, DestroyRef, effect, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Button, Modal, PageHeader } from '@shared/ui';
import { UserService } from '@features/users/data/user.service';
import { UsersCard } from '@features/users/components';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-user-list',
  imports: [RouterLink, Button, Modal, PageHeader, UsersCard, MatIconModule],
  templateUrl: './user-list.html',
  styleUrl: './user-list.scss',
})
export class UserList {
  userService = inject(UserService);
  private router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  showDeleteModal = signal(false);
  userToDelete = signal<{ id: string; name: string } | null>(null);
  isDeleting = signal(false);

  // Search
  readonly searchQuery = signal('');
  private readonly _debouncedSearch = signal('');

  // Responsive: mobile detection via matchMedia
  readonly isMobile = signal(false);

  // Users desde el servicio
  users = this.userService.users;

  constructor() {
    // Debounce search con 300ms
    effect((onCleanup) => {
      const query = this.searchQuery();
      const timer = setTimeout(() => {
        this._debouncedSearch.set(query);
      }, 300);
      onCleanup(() => clearTimeout(timer));
    });

    // Efecto para recargar cuando cambia la búsqueda debounced
    effect(() => {
      this._debouncedSearch();
      this.userService.getUsers();
    });

    // MatchMedia for responsive detection
    if (typeof window !== 'undefined') {
      const mql = window.matchMedia('(max-width: 575.98px)');
      this.isMobile.set(mql.matches);
      mql.addEventListener('change', (e) => this.isMobile.set(e.matches));
    }
  }

  editUser(id: string): void {
    this.router.navigate([`/users/edit/${id}`]);
  }

  confirmDelete(userInfo: { id: string; name: string }): void {
    this.userToDelete.set({ id: userInfo.id, name: userInfo.name });
    this.showDeleteModal.set(true);
  }

  cancelDelete(): void {
    this.showDeleteModal.set(false);
    this.userToDelete.set(null);
  }

  clearSearch(): void {
    this.searchQuery.set('');
  }

  async executeDelete(): Promise<void> {
    const user = this.userToDelete();
    if (!user || this.isDeleting()) return;

    this.isDeleting.set(true);
    this.userService.deleteUser(user.id).subscribe({
      next: () => {
        this.isDeleting.set(false);
        this.showDeleteModal.set(false);
        this.userService.getUserList();
      },
      error: () => {
        this.isDeleting.set(false);
      },
    });
  }
}
