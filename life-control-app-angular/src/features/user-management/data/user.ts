import { Injectable, signal, computed } from '@angular/core';
import { User } from '@shared/data/auth';

export interface UserFilters {
  search: string;
  role: 'all' | 'admin' | 'user';
  status: 'all' | 'active' | 'inactive';
}

/**
 * User service demonstrating Resource API patterns (mock implementation)
 * In real application, this would use resource() for HTTP operations
 */
@Injectable({
  providedIn: 'root',
})
export class UserService {
  private users = signal<User[]>([
    {
      id: '1',
      email: 'admin@example.com',
      name: 'Admin User',
      role: 'admin',
      avatar: '👨‍💼',
    },
    {
      id: '2',
      email: 'john@example.com',
      name: 'John Doe',
      role: 'user',
      avatar: '👤',
    },
    {
      id: '3',
      email: 'jane@example.com',
      name: 'Jane Smith',
      role: 'user',
      avatar: '👩',
    },
    {
      id: '4',
      email: 'bob@example.com',
      name: 'Bob Wilson',
      role: 'user',
      avatar: '🧑',
    },
  ]);

  private filters = signal<UserFilters>({
    search: '',
    role: 'all',
    status: 'all',
  });

  // Computed signal for filtered users
  filteredUsers = computed(() => {
    const users = this.users();
    const { search, role } = this.filters();

    return users.filter((user) => {
      const matchesSearch =
        !search ||
        user.name.toLowerCase().includes(search.toLowerCase()) ||
        user.email.toLowerCase().includes(search.toLowerCase());

      const matchesRole = role === 'all' || user.role === role;

      return matchesSearch && matchesRole;
    });
  });

  // Read-only access to filters
  currentFilters = this.filters.asReadonly();

  /**
   * Update search filter
   */
  updateSearch(search: string): void {
    this.filters.update((filters) => ({ ...filters, search }));
  }

  /**
   * Update role filter
   */
  updateRoleFilter(role: UserFilters['role']): void {
    this.filters.update((filters) => ({ ...filters, role }));
  }

  /**
   * Get user by ID
   */
  getUserById(id: string): User | undefined {
    return this.users().find((user) => user.id === id);
  }

  /**
   * Create new user
   */
  createUser(userData: Omit<User, 'id'>): Promise<User> {
    return new Promise((resolve) => {
      setTimeout(() => {
        const newUser: User = {
          ...userData,
          id: Math.random().toString(36).substring(2),
        };

        this.users.update((users) => [...users, newUser]);
        resolve(newUser);
      }, 500);
    });
  }

  /**
   * Update existing user
   */
  updateUser(id: string, updates: Partial<User>): Promise<User> {
    return new Promise((resolve, reject) => {
      setTimeout(() => {
        const userIndex = this.users().findIndex((user) => user.id === id);

        if (userIndex === -1) {
          reject(new Error('User not found'));
          return;
        }

        this.users.update((users) => {
          const updatedUsers = [...users];
          updatedUsers[userIndex] = { ...updatedUsers[userIndex], ...updates };
          return updatedUsers;
        });

        resolve(this.users()[userIndex]);
      }, 500);
    });
  }

  /**
   * Delete user
   */
  deleteUser(id: string): Promise<void> {
    return new Promise((resolve, reject) => {
      setTimeout(() => {
        const userExists = this.users().some((user) => user.id === id);

        if (!userExists) {
          reject(new Error('User not found'));
          return;
        }

        this.users.update((users) => users.filter((user) => user.id !== id));
        resolve();
      }, 500);
    });
  }
}
