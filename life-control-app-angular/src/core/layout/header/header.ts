import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { CommonModule } from '@angular/common';
import { Component, computed, effect, inject, OnInit, signal } from '@angular/core';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { Router, RouterModule } from '@angular/router';
import { Button, Hyperlink } from '@shared/ui';
import { CompanyContextService } from '@shared/data/company-context.service';
import Keycloak from 'keycloak-js';
import { KEYCLOAK_EVENT_SIGNAL, KeycloakEventType } from 'keycloak-angular';

/**
 * Application header component
 * Features:
 * - Responsive navigation
 * - User authentication state
 * - User menu dropdown (MatMenu) with profile, preferences, logout
 * - Signal-based reactivity
 * - Breakpoint-aware menu visibility
 */
@Component({
  selector: 'header[app-header]',
  imports: [
    CommonModule,
    Button,
    Hyperlink,
    MatDividerModule,
    MatIconModule,
    MatMenuModule,
    RouterModule,
  ],
  templateUrl: `header.html`,
  styleUrl: `header.scss`,
})
export class Header implements OnInit {
  private breakpointObserver = inject(BreakpointObserver);
  private keycloak = inject(Keycloak);
  private keycloakSignal = inject(KEYCLOAK_EVENT_SIGNAL);
  private companyContext = inject(CompanyContextService);
  private router = inject(Router);

  // Signals
  private showMenu = signal(false);
  private isSmallScreen = signal(false);
  isCompanyRole = signal(false);
  isAdmin = signal(false);

  /** User display name from Keycloak token — signal for template reactivity */
  userName = signal('');

  // Computed properties
  isMenuOpen = computed(() => {
    const isSmall = this.isSmallScreen();
    const menuOpen = this.showMenu();
    return isSmall ? menuOpen : true;
  });

  items = computed(() => {
    const menuItems = [
      { id: '1', routeLink: '/home', textLink: 'Home', icon: 'home' },
    ];

    if (this.isCompanyRole()) {
      menuItems.push(
        { id: '2', routeLink: '/companies', textLink: 'Companies', icon: 'business' },
      );
    }

    if (this.isAdmin()) {
      menuItems.push(
        { id: '3', routeLink: '/products', textLink: 'Products', icon: 'inventory_2' },
        { id: '4', routeLink: '/purchases', textLink: 'Compras', icon: 'shopping_cart' },
        { id: '5', routeLink: '/users-admin', textLink: 'Users Admin', icon: 'admin_panel_settings' },
      );
    }

    return menuItems;
  });

  authenticated = false;

  ngOnInit(): void {
    this.companyContext.loadCompanies();
  }

  constructor() {
    // Observe breakpoint changes
    this.breakpointObserver.observe([Breakpoints.Small, Breakpoints.XSmall]).subscribe((result) => {
      this.isSmallScreen.set(result.matches);
    });

    // Subscribe to keycloak events
    const keycloakEvent = this.keycloakSignal();
    if (keycloakEvent?.type === KeycloakEventType.Ready) {
      this.authenticated = this.keycloak.authenticated ?? false;
      this.updateUserFromToken();
    }

    effect(() => {
      const event = this.keycloakSignal();
      if (event?.type === KeycloakEventType.Ready) {
        this.authenticated = this.keycloak.authenticated ?? false;
        const token = this.keycloak.tokenParsed;
        const clientRoles: string[] = token?.resource_access?.['life-control-client']?.roles ?? [];
        this.isAdmin.set(clientRoles.includes('lc-admin'));
        this.isCompanyRole.set(
          clientRoles.includes('lc-admin') ||
          clientRoles.includes('lc-company') ||
          clientRoles.includes('lc-company-country') ||
          clientRoles.includes('lc-company-region') ||
          clientRoles.includes('lc-company-zone') ||
          clientRoles.includes('lc-company-store'),
        );
        this.updateUserFromToken();
      }
      if (event?.type === KeycloakEventType.AuthLogout) {
        this.authenticated = false;
        this.isAdmin.set(false);
        this.isCompanyRole.set(false);
        this.userName.set('');
      }
    });
  }

  /** Extract user display name from Keycloak token: name → preferred_username → empty */
  private updateUserFromToken(): void {
    const token = this.keycloak.tokenParsed;
    const name = (token?.['name'] as string) || (token?.['preferred_username'] as string) || '';
    this.userName.set(name);
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

  /** Navigate to the user profile page */
  viewProfile(): void {
    this.router.navigate(['/profile']);
  }

  /** Open Keycloak account management UI in a new tab */
  editPreferences(): void {
    this.keycloak.accountManagement();
  }
}
