import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { CommonModule } from '@angular/common';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterModule } from '@angular/router';
import { Button, Hyperlink } from '@shared/ui';
import Keycloak from 'keycloak-js';
import { KEYCLOAK_EVENT_SIGNAL, KeycloakEventType } from 'keycloak-angular';

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
  private keycloak = inject(Keycloak);
  private keycloakSignal = inject(KEYCLOAK_EVENT_SIGNAL);

  // Signals
  private showMenu = signal(false);
  private isSmallScreen = signal(false);

  // Computed properties
  isMenuOpen = computed(() => {
    const isSmall = this.isSmallScreen();
    const menuOpen = this.showMenu();
    return isSmall ? menuOpen : true;
  });

  items = computed(() => [
    { id: '1', routeLink: '/home', textLink: 'Home', icon: 'home' },
    { id: '2', routeLink: '/products', textLink: 'products', icon: 'inventory_2' },
    { id: '3', routeLink: '/expressions', textLink: 'expressions', icon: 'fact_check' },
    { id: '4', routeLink: '/companies', textLink: 'Companies', icon: 'business' },
  ]);

  authenticated = false;
  constructor() {
    // Observar cambios de breakpoint
    this.breakpointObserver.observe([Breakpoints.Small, Breakpoints.XSmall]).subscribe((result) => {
      this.isSmallScreen.set(result.matches);
    });

    // Subscribe to keycloak events
    const keycloakEvent = this.keycloakSignal();
    if (keycloakEvent?.type === KeycloakEventType.Ready) {
      this.authenticated = this.keycloak.authenticated ?? false;
    }

    effect(() => {
      const event = this.keycloakSignal();
      if (event?.type === KeycloakEventType.Ready) {
        this.authenticated = this.keycloak.authenticated ?? false;
      }
      if (event?.type === KeycloakEventType.AuthLogout) {
        this.authenticated = false;
      }
    });
  }

  toggleMenu(): void {
    this.showMenu.update((value) => !value);
  }

  login() {
    this.keycloak.login();
  }

  logout() {
    this.keycloak.logout();
  }
}
