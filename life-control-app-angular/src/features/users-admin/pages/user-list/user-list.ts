import { Component, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { FormsModule } from '@angular/forms';
import { UsersAdminService } from '../../services/users-admin.service';

@Component({
  standalone: true,
  imports: [
    MatTableModule,
    MatIconModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    FormsModule,
  ],
  templateUrl: './user-list.html',
  styleUrl: './user-list.scss',
})
export class UserList {
  private readonly service = inject(UsersAdminService);
  private readonly router = inject(Router);

  readonly users = this.service.users;
  readonly loading = this.service.loading;

  readonly searchQuery = signal('');
  private readonly _debouncedSearch = signal('');

  displayedColumns = ['username', 'email', 'enabled', 'actions'];

  constructor() {
    // Debounce search (300ms)
    effect((onCleanup) => {
      const query = this.searchQuery();
      const timer = setTimeout(() => {
        this._debouncedSearch.set(query);
      }, 300);
      onCleanup(() => clearTimeout(timer));
    });

    // Trigger search when debounced value changes
    effect(() => {
      this.service.searchUsers(this._debouncedSearch());
    });
  }

  clearSearch(): void {
    this.searchQuery.set('');
  }

  onSelectUser(id: string): void {
    this.router.navigate(['/users-admin/users', id]);
  }
}
