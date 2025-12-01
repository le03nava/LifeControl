import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { CommonModule } from '@angular/common';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterModule } from '@angular/router';
import { Button, Hyperlink } from '@shared/ui';
import { KeyCloakService } from '@shared/data/keycloak.service';

/**
 * Application header component
 * Features:
 * - Responsive navigation
 * - User authentication state
 * - Signal-based reactivity
 * - Breakpoint-aware menu visibility
 */
@Component({
  selector: 'header[app-header]',
  imports: [CommonModule, Button, Hyperlink, MatIconModule, RouterModule],
  templateUrl: `header.html`,
  styleUrl: `header.scss`,
})
export class Header {
  private breakpointObserver = inject(BreakpointObserver);
  // Signals
  private showMenu = signal(false);
  private isSmallScreen = signal(false);

  keyCloakService = inject(KeyCloakService);
  // Computed properties
  isMenuOpen = computed(() => {
    const isSmall = this.isSmallScreen();
    const menuOpen = this.showMenu();
    return isSmall ? menuOpen : true;
  });

  items = computed(() => [
    { id: '1', routeLink: '/home', textLink: 'Home', icon: 'home' },
    { id: '2', routeLink: '/products', textLink: 'products', icon: 'delete' },
  ]);

  authenticated = false;
  constructor() {
    // Observar cambios de breakpoint
    this.breakpointObserver.observe([Breakpoints.Small, Breakpoints.XSmall]).subscribe((result) => {
      this.isSmallScreen.set(result.matches);
    });

    // Cerrar menú automáticamente cuando se sale de small screen
    effect(() => {
      if (!this.isSmallScreen()) {
        this.showMenu.set(false);
      }
      this.authenticated = this.keyCloakService.isAuthenticated();
    });
  }

  toggleMenu(): void {
    this.showMenu.update((value) => !value);
  }

  login() {
    this.keyCloakService.login();
  }

  logout() {
    this.keyCloakService.logout();
  }
}
