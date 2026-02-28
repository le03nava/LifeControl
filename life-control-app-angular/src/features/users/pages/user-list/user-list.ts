import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Button, Modal } from '@shared/ui';
import { UserService } from '@features/users/data/user.service';
import { UsersCard } from '@features/users/components';

@Component({
  selector: 'app-user-list',
  imports: [RouterLink, Button, Modal, UsersCard],
  templateUrl: './user-list.html',
  styleUrls: ['./user-list.scss'],
})
export class UserList {
  userService = inject(UserService);
  private router = inject(Router);

  showDeleteModal = signal(false);
  userToDelete = signal<{ id: string; name: string } | null>(null);
  isDeleting = signal(false);

  users = this.userService.users;

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
