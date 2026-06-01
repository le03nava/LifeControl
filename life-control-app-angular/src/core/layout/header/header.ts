import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { CommonModule } from '@angular/common';
import { Component, computed, effect, inject, OnInit, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterModule } from '@angular/router';
import { Button, Hyperlink, CompanySelector } from '@shared/ui';
import { CompanyContextService } from '@shared/data/company-context.service';
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
  imports: [CommonModule, Button, Hyperlink, MatIconModule, RouterModule, CompanySelector],
  templateUrl: `header.html`,
  styleUrl: `header.scss`,
})
export class Header implements OnInit {
  private breakpointObserver = inject(BreakpointObserver);
  private keycloak = inject(Keycloak);
  private keycloakSignal = inject(KEYCLOAK_EVENT_SIGNAL);
  private companyContext = inject(CompanyContextService);

  // Signals
  private showMenu = signal(false);
  private isSmallScreen = signal(false);
  isCompanyRole = signal(false);
  isAdmin = signal(false);

  // Computed properties
  isMenuOpen = computed(() => {
    const isSmall = this.isSmallScreen();
    const menuOpen = this.showMenu();
    return isSmall ? menuOpen : true;
  });

  items = computed(() => {
    const menuItems = [
      { id: '1', routeLink: '/home', textLink: 'Home', icon: 'home' },
      { id: '2', routeLink: '/companies', textLink: 'Companies', icon: 'business' },
    ];

    if (this.isAdmin()) {
      menuItems.push(
        { id: '3', routeLink: '/products', textLink: 'Products', icon: 'inventory_2' },
        { id: '4', routeLink: '/users-admin', textLink: 'Users Admin', icon: 'admin_panel_settings' },
      );
    }

    return menuItems;
  });

  authenticated = false;

  ngOnInit(): void {
    this.companyContext.loadCompanies();
  }

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
        this.isAdmin.set(this.keycloak.hasRealmRole('life-control-admin'));
        this.isCompanyRole.set(
          this.keycloak.hasRealmRole('life-control-admin') ||
          this.keycloak.hasRealmRole('life-control-country')
        );
      }
      if (event?.type === KeycloakEventType.AuthLogout) {
        this.authenticated = false;
        this.isAdmin.set(false);
        this.isCompanyRole.set(false);
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
