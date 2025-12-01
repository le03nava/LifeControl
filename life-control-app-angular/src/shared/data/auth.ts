import { Injectable, signal, effect, computed } from '@angular/core';

export interface User {
  id: string;
  email: string;
  name: string;
  role: 'admin' | 'user';
  avatar?: string;
}

/**
 * Authentication service using signals
 * Manages user authentication state reactively
 */
@Injectable({
  providedIn: 'root',
})
export class AuthService {
  private currentUser = signal<User | null>(null);
  private token = signal<string | null>(null);

  /**
   * Read-only computed signals for reactive access
   */
  user = this.currentUser.asReadonly();
  isAuthenticated = computed(() => !!this.currentUser());
  isAdmin = computed(() => this.currentUser()?.role === 'admin');

  constructor() {
    // Load authentication state from storage on init
    this.loadAuthState();

    // Persist authentication state changes
    effect(() => {
      const user = this.currentUser();
      const token = this.token();

      if (user && token) {
        localStorage.setItem('auth_user', JSON.stringify(user));
        localStorage.setItem('auth_token', token);
      } else {
        localStorage.removeItem('auth_user');
        localStorage.removeItem('auth_token');
      }
    });
  }

  /**
   * Login with email and password
   */
  async login(email: string, _password: string): Promise<boolean> {
    try {
      // Simulate API call
      await new Promise((resolve) => setTimeout(resolve, 1000));

      // Mock successful login
      const user: User = {
        id: '1',
        email,
        name: email.split('@')[0],
        role: email.includes('admin') ? 'admin' : 'user',
      };

      const token = 'mock-jwt-token';

      this.currentUser.set(user);
      this.token.set(token);

      return true;
    } catch {
      return false;
    }
  }

  /**
   * Logout and clear authentication state
   */
  logout(): void {
    this.currentUser.set(null);
    this.token.set(null);
  }

  /**
   * Update current user profile
   */
  updateUser(updates: Partial<User>): void {
    const current = this.currentUser();
    if (current) {
      this.currentUser.set({ ...current, ...updates });
    }
  }

  /**
   * Get current authentication token
   */
  getToken(): string | null {
    return this.token();
  }

  private loadAuthState(): void {
    try {
      const storedUser = localStorage.getItem('auth_user');
      const storedToken = localStorage.getItem('auth_token');

      if (storedUser && storedToken) {
        this.currentUser.set(JSON.parse(storedUser));
        this.token.set(storedToken);
      }
    } catch {
      // Ignore localStorage errors
    }
  }
}
