import { provideLocationMocks } from '@angular/common/testing';
import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { KEYCLOAK_EVENT_SIGNAL, KeycloakEventType } from 'keycloak-angular';
import { Header } from './header';
import Keycloak from 'keycloak-js';

describe('Header', () => {
  let keycloakMock: Partial<Keycloak>;

  /**
   * Set up the Header component with mocked Keycloak tokenParsed.
   * @param clientRoles - roles inside resource_access['life-control-client'].roles
   * @param extraTokenParsed - additional tokenParsed properties (e.g. realm_access for backward-compat tests)
   */
  const setup = (clientRoles: string[] = [], extraTokenParsed: Record<string, unknown> = {}) => {
    const tokenParsed =
      clientRoles.length > 0 || Object.keys(extraTokenParsed).length > 0
        ? {
            ...extraTokenParsed,
            resource_access: {
              ...((extraTokenParsed['resource_access'] as Record<string, unknown>) || {}),
              ...(clientRoles.length > 0 ? { 'life-control-client': { roles: clientRoles } } : {}),
            },
          }
        : undefined;

    keycloakMock = {
      login: vi.fn(),
      logout: vi.fn(),
      accountManagement: vi.fn(),
      hasRealmRole: vi.fn().mockReturnValue(false),
      tokenParsed: tokenParsed as Keycloak['tokenParsed'],
      authenticated: clientRoles.length > 0 || Object.keys(extraTokenParsed).length > 0,
    };

    // Create a keycloak event signal for the test
    const keycloakEventSignal = signal({
      type: KeycloakEventType.Ready,
      token: null,
    });

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideLocationMocks(),
        provideHttpClient(),
        { provide: Keycloak, useValue: keycloakMock },
        { provide: KEYCLOAK_EVENT_SIGNAL, useValue: keycloakEventSignal },
      ],
    });

    const fixture = TestBed.createComponent(Header);
    const component = fixture.componentInstance;

    fixture.detectChanges();

    return { fixture, component };
  };

  // ─── Menu items — basic structure ─────────────────────────────

  describe('menu items', () => {
    it('should not include expressions menu item after deletion', () => {
      const { component } = setup();
      const items = component.items();
      const expressionsItem = items.find((item) => item.routeLink === '/expressions');
      expect(expressionsItem).toBeUndefined();
    });

    it('should render home link in template', () => {
      const { fixture } = setup();
      const homeLink = fixture.nativeElement.querySelector('a[routerLink="/"]');
      expect(homeLink).toBeTruthy();
      expect(homeLink.textContent).toContain('Life Control');
    });
  });

  // ─── Companies menu gating (client roles) ─────────────────────

  describe('companies menu gating', () => {
    it('should show Companies menu when user has lc-admin client role', () => {
      const { component } = setup(['lc-admin']);
      expect(component.isCompanyRole()).toBe(true);
      expect(component.isAdmin()).toBe(true);
      const items = component.items();
      const companiesItem = items.find((i) => i.routeLink === '/companies');
      expect(companiesItem).toBeDefined();
      expect(companiesItem?.textLink).toBe('Companies');
    });

    it('should show Companies menu when user has lc-company client role', () => {
      const { component } = setup(['lc-company']);
      expect(component.isCompanyRole()).toBe(true);
      expect(component.isAdmin()).toBe(false);
      const items = component.items();
      const companiesItem = items.find((i) => i.routeLink === '/companies');
      expect(companiesItem).toBeDefined();
      expect(companiesItem?.textLink).toBe('Companies');
      expect(items.length).toBe(1); // Companies only
    });

    it('should show Companies menu when user has lc-company-country client role', () => {
      const { component } = setup(['lc-company-country']);
      expect(component.isCompanyRole()).toBe(true);
      expect(component.isAdmin()).toBe(false);
      const items = component.items();
      const companiesItem = items.find((i) => i.routeLink === '/companies');
      expect(companiesItem).toBeDefined();
      expect(companiesItem?.textLink).toBe('Companies');
      expect(items.length).toBe(1); // Companies only
    });

    it('should show Companies + admin menus when user has lc-admin + lc-company-country', () => {
      const { component } = setup(['lc-admin', 'lc-company-country']);
      expect(component.isCompanyRole()).toBe(true);
      expect(component.isAdmin()).toBe(true);
      const items = component.items();
      const companiesItem = items.find((i) => i.routeLink === '/companies');
      expect(companiesItem).toBeDefined();
      expect(companiesItem?.textLink).toBe('Companies');
      expect(items.some((i) => i.routeLink === '/users-admin')).toBe(true);
      expect(items.length).toBe(6); // Companies + Sales + Products + Stock by store + Purchases + Users Admin
    });

    it('should NOT show Companies menu when user has no company client roles', () => {
      const { component } = setup([]);
      expect(component.isCompanyRole()).toBe(false);
      expect(component.isAdmin()).toBe(false);
      const items = component.items();
      const companiesItem = items.find((i) => i.routeLink === '/companies');
      expect(companiesItem).toBeUndefined();
      expect(items.length).toBe(0); // No items (Home is rendered directly in template)
    });
  });

  // ─── Companies submenu — Store Zones child ────────────────────

  describe('companies submenu', () => {
    it('should expose a Store Zones child on the Companies item', () => {
      const { component } = setup(['lc-admin']);
      const companiesItem = component.items().find((i) => i.routeLink === '/companies');
      expect(companiesItem?.children).toBeDefined();
      expect(companiesItem?.children).toHaveLength(2);
      expect(companiesItem?.children?.[0]).toEqual({
        id: '2-1',
        routeLink: '/companies/store-zones',
        textLink: 'Store Zones',
        icon: 'grid_view',
      });
      expect(companiesItem?.children?.[1]).toEqual({
        id: '2-2',
        routeLink: '/companies/store-locations',
        textLink: 'Store Locations',
        icon: 'shelves',
      });
    });

    it('should keep the Companies parent navigating to /companies (D7)', () => {
      const { component } = setup(['lc-admin']);
      const companiesItem = component.items().find((i) => i.id === '2');
      expect(companiesItem?.routeLink).toBe('/companies');
    });

    it.each([
      'lc-admin',
      'lc-company',
      'lc-company-country',
      'lc-company-region',
      'lc-company-zone',
      'lc-company-store',
    ])('should expose the Store Zones child under company gating for %s', (role) => {
      const { component } = setup([role]);
      const companiesItem = component.items().find((i) => i.routeLink === '/companies');
      expect(companiesItem).toBeDefined();
      expect(companiesItem?.children?.some((c) => c.routeLink === '/companies/store-zones')).toBe(
        true,
      );
      expect(
        companiesItem?.children?.some((c) => c.routeLink === '/companies/store-locations'),
      ).toBe(true);
    });

    it('should NOT expose the Store Zones child when user has no company role', () => {
      const { component } = setup(['lc-sales']);
      const companiesItem = component.items().find((i) => i.routeLink === '/companies');
      expect(companiesItem).toBeUndefined();
    });

    it('should render the caret trigger only for items that have children', () => {
      const { fixture } = setup(['lc-admin']);
      const triggers = fixture.nativeElement.querySelectorAll('.submenu-trigger');
      // Only the Companies item has children among the 5 admin items
      expect(triggers).toHaveLength(1);
      expect(triggers[0].getAttribute('aria-label')).toBe('Companies submenu');
    });

    it('should NOT render a caret trigger when no item has children', () => {
      const { fixture } = setup(['lc-sales']);
      const triggers = fixture.nativeElement.querySelectorAll('.submenu-trigger');
      expect(triggers).toHaveLength(0);
    });
  });

  // ─── Admin role visibility (client roles) — company-selector removed ───

  describe('admin role visibility', () => {
    it('should show Users Admin nav for lc-admin role', () => {
      const { component } = setup(['lc-admin']);
      expect(component.isAdmin()).toBe(true);
      expect(component.isCompanyRole()).toBe(true);
      const items = component.items();
      expect(items.some((i) => i.routeLink === '/users-admin')).toBe(true);
      expect(items.length).toBe(6); // Companies + Sales + Products + Stock by store + Purchases + Users Admin
    });

    it('should NOT show Users Admin for lc-company role', () => {
      const { component } = setup(['lc-company']);
      expect(component.isAdmin()).toBe(false);
      expect(component.isCompanyRole()).toBe(true);
      const items = component.items();
      expect(items.some((i) => i.routeLink === '/users-admin')).toBe(false);
      expect(items.length).toBe(1); // Companies only
    });

    it('should hide Users Admin with no client roles', () => {
      const { component } = setup([]);
      expect(component.isAdmin()).toBe(false);
      expect(component.isCompanyRole()).toBe(false);
      const items = component.items();
      expect(items.some((i) => i.routeLink === '/users-admin')).toBe(false);
      expect(items.length).toBe(0); // No items (Home is rendered directly in template)
    });
  });

  // ─── No backward compat for Companies (old realm roles ignored) ─

  describe('no backward compat for companies', () => {
    it('should NOT show Companies menu when user has old realm roles but no client roles', () => {
      const { component } = setup([], {
        realm_access: { roles: ['life-control-admin', 'life-control-country'] },
      });
      expect(component.isCompanyRole()).toBe(false);
      expect(component.isAdmin()).toBe(false);
      const items = component.items();
      const companiesItem = items.find((i) => i.routeLink === '/companies');
      expect(companiesItem).toBeUndefined();
      expect(items.length).toBe(0); // No items (Home is rendered directly in template)
    });
  });

  // ─── New company hierarchy roles (lc-company-region, lc-company-zone, lc-company-store) ─

  describe('region/zone/store company roles', () => {
    it('should show Companies menu when user has only lc-company-region role', () => {
      const { component } = setup(['lc-company-region']);
      expect(component.isCompanyRole()).toBe(true);
      expect(component.isAdmin()).toBe(false);
      const items = component.items();
      const companiesItem = items.find((i) => i.routeLink === '/companies');
      expect(companiesItem).toBeDefined();
      expect(companiesItem?.textLink).toBe('Companies');
      expect(items.length).toBe(1); // Companies only
    });

    it('should show Companies menu when user has only lc-company-zone role', () => {
      const { component } = setup(['lc-company-zone']);
      expect(component.isCompanyRole()).toBe(true);
      expect(component.isAdmin()).toBe(false);
      const items = component.items();
      const companiesItem = items.find((i) => i.routeLink === '/companies');
      expect(companiesItem).toBeDefined();
      expect(companiesItem?.textLink).toBe('Companies');
      expect(items.length).toBe(1); // Companies only
    });

    it('should show Companies menu when user has only lc-company-store role', () => {
      const { component } = setup(['lc-company-store']);
      expect(component.isCompanyRole()).toBe(true);
      expect(component.isAdmin()).toBe(false);
      const items = component.items();
      const companiesItem = items.find((i) => i.routeLink === '/companies');
      expect(companiesItem).toBeDefined();
      expect(companiesItem?.textLink).toBe('Companies');
      expect(items.length).toBe(1); // Companies only
    });

    it('should NOT set isCompanyRole when user has no company hierarchy roles', () => {
      const { component } = setup(['lc-user']);
      expect(component.isCompanyRole()).toBe(false);
      expect(component.isAdmin()).toBe(false);
    });

    it('should reset isCompanyRole on AuthLogout event for lc-company-region user', () => {
      const keycloakEventSignal = signal({
        type: KeycloakEventType.Ready,
        token: null,
      });

      TestBed.configureTestingModule({
        providers: [
          provideRouter([]),
          provideLocationMocks(),
          provideHttpClient(),
          {
            provide: Keycloak,
            useValue: {
              login: vi.fn(),
              logout: vi.fn(),
              accountManagement: vi.fn(),
              hasRealmRole: vi.fn().mockReturnValue(false),
              tokenParsed: {
                resource_access: { 'life-control-client': { roles: ['lc-company-region'] } },
              },
              authenticated: true,
            } as Partial<Keycloak>,
          },
          { provide: KEYCLOAK_EVENT_SIGNAL, useValue: keycloakEventSignal },
        ],
      });

      const fixture = TestBed.createComponent(Header);
      const component = fixture.componentInstance;
      fixture.detectChanges();

      // After Ready event, isCompanyRole should be true
      expect(component.isCompanyRole()).toBe(true);

      // Simulate AuthLogout event
      keycloakEventSignal.set({ type: KeycloakEventType.AuthLogout, token: null });
      fixture.detectChanges();

      // After logout, isCompanyRole should be false
      expect(component.isCompanyRole()).toBe(false);
      const items = component.items();
      const companiesItem = items.find((i) => i.routeLink === '/companies');
      expect(companiesItem).toBeUndefined();
    });
  });

  // ─── User menu (MatMenu) — NEW ──────────────────────────────────

  describe('user menu — userName signal', () => {
    it('should display user name from keycloak tokenParsed name', () => {
      const { component } = setup(['lc-admin'], {
        name: 'John Doe',
        preferred_username: 'jdoe',
        email: 'john@test.com',
      });
      expect(component.userName()).toBe('John Doe');
    });

    it('should fall back to preferred_username when name is missing', () => {
      const { component } = setup(['lc-company'], {
        preferred_username: 'jdoe',
        email: 'john@test.com',
      });
      expect(component.userName()).toBe('jdoe');
    });

    it('should show empty userName when not authenticated', () => {
      const { component } = setup([]);
      expect(component.userName()).toBe('');
    });

    it('should reset userName on logout', () => {
      const keycloakEventSignal = signal({
        type: KeycloakEventType.Ready,
        token: null,
      });

      TestBed.configureTestingModule({
        providers: [
          provideRouter([]),
          provideLocationMocks(),
          provideHttpClient(),
          {
            provide: Keycloak,
            useValue: {
              login: vi.fn(),
              logout: vi.fn(),
              accountManagement: vi.fn(),
              hasRealmRole: vi.fn().mockReturnValue(false),
              tokenParsed: {
                name: 'John Doe',
                preferred_username: 'jdoe',
                resource_access: {
                  'life-control-client': { roles: ['lc-admin'] },
                },
              },
              authenticated: true,
            } as Partial<Keycloak>,
          },
          { provide: KEYCLOAK_EVENT_SIGNAL, useValue: keycloakEventSignal },
        ],
      });

      const fixture = TestBed.createComponent(Header);
      const component = fixture.componentInstance;
      fixture.detectChanges();

      // After Ready event, userName should be set
      expect(component.userName()).toBe('John Doe');

      // Simulate AuthLogout event
      keycloakEventSignal.set({ type: KeycloakEventType.AuthLogout, token: null });
      fixture.detectChanges();

      // After logout, userName should be reset
      expect(component.userName()).toBe('');
    });
  });

  describe('user menu — DOM', () => {
    it('should show user menu trigger when authenticated', () => {
      const { fixture } = setup(['lc-admin'], { name: 'John Doe' });
      const trigger = fixture.nativeElement.querySelector('.user-menu-trigger');
      expect(trigger).toBeTruthy();
    });

    it('should show user name in the trigger button', () => {
      const { fixture } = setup(['lc-admin'], { name: 'John Doe' });
      expect(fixture.nativeElement.textContent).toContain('John Doe');
    });

    it('should show Login button when unauthenticated', () => {
      const { fixture } = setup([]);
      const loginButton = fixture.nativeElement.querySelector('button[variant="primary"]');
      expect(loginButton).toBeTruthy();
      expect(fixture.nativeElement.textContent).toContain('Login');
    });

    it('should NOT show user menu trigger when unauthenticated', () => {
      const { fixture } = setup([]);
      const trigger = fixture.nativeElement.querySelector('.user-menu-trigger');
      expect(trigger).toBeFalsy();
    });

    it('should show account_circle icon in menu trigger', () => {
      const { fixture } = setup(['lc-admin'], { name: 'John Doe' });
      const icon = fixture.nativeElement.querySelector('.user-menu-trigger mat-icon');
      expect(icon).toBeTruthy();
    });
  });

  describe('user menu — actions', () => {
    it('viewProfile should navigate to /profile', () => {
      const { component } = setup(['lc-admin']);
      const router = TestBed.inject(Router);
      const navigateSpy = vi.spyOn(router, 'navigate');
      component.viewProfile();
      expect(navigateSpy).toHaveBeenCalledWith(['/profile']);
    });

    it('editPreferences should navigate to /profile?edit=true', () => {
      const { component } = setup(['lc-admin']);
      const router = TestBed.inject(Router);
      const navigateSpy = vi.spyOn(router, 'navigate');
      component.editPreferences();
      expect(navigateSpy).toHaveBeenCalledWith(['/profile'], { queryParams: { edit: true } });
    });

    it('logout should call keycloak.logout', () => {
      const { component } = setup(['lc-admin']);
      component.logout();
      expect(keycloakMock.logout).toHaveBeenCalled();
    });
  });

  // ─── Sales menu gating (lc-sales / lc-admin client roles from life-control-client) ─

  describe('sales menu gating', () => {
    it('should show Sales menu when user has lc-sales client role', () => {
      const { component } = setup(['lc-sales']);
      expect(component.isSalesRole()).toBe(true);
      const items = component.items();
      const salesItem = items.find((i) => i.routeLink === '/sales');
      expect(salesItem).toBeDefined();
      expect(salesItem?.textLink).toBe('Sales');
      expect(salesItem?.icon).toBe('point_of_sale');
    });

    it('should show Sales menu when user has only lc-sales role', () => {
      const { component } = setup(['lc-sales']);
      expect(component.isSalesRole()).toBe(true);
      expect(component.isCompanyRole()).toBe(false);
      expect(component.isAdmin()).toBe(false);
      const items = component.items();
      const salesItem = items.find((i) => i.routeLink === '/sales');
      expect(salesItem).toBeDefined();
      expect(items.length).toBe(2); // Sales + Stock by store
    });

    it('should NOT show Sales menu when user has no lc-sales role', () => {
      const { component } = setup(['lc-company']);
      expect(component.isSalesRole()).toBe(false);
      const items = component.items();
      const salesItem = items.find((i) => i.routeLink === '/sales');
      expect(salesItem).toBeUndefined();
    });

    it('should show Sales menu for lc-admin even without explicit lc-sales role', () => {
      const { component } = setup(['lc-admin'], {
        name: 'Admin User',
      });
      expect(component.isSalesRole()).toBe(true);
      expect(component.isAdmin()).toBe(true);
      const items = component.items();
      const salesItem = items.find((i) => i.routeLink === '/sales');
      expect(salesItem).toBeDefined();
    });

    it('should show Sales menu alongside Companies and Admin menus', () => {
      const { component } = setup(['lc-admin', 'lc-sales']);
      expect(component.isSalesRole()).toBe(true);
      expect(component.isCompanyRole()).toBe(true);
      expect(component.isAdmin()).toBe(true);
      const items = component.items();
      const salesItem = items.find((i) => i.routeLink === '/sales');
      expect(salesItem).toBeDefined();
      expect(items.length).toBe(6); // Companies + Sales + Products + Stock by store + Purchases + Users Admin
    });

    it('should reset isSalesRole on AuthLogout event', () => {
      const keycloakEventSignal = signal({
        type: KeycloakEventType.Ready,
        token: null,
      });

      TestBed.configureTestingModule({
        providers: [
          provideRouter([]),
          provideLocationMocks(),
          provideHttpClient(),
          {
            provide: Keycloak,
            useValue: {
              login: vi.fn(),
              logout: vi.fn(),
              accountManagement: vi.fn(),
              hasRealmRole: vi.fn().mockReturnValue(false),
              tokenParsed: {
                resource_access: {
                  'life-control-client': { roles: ['lc-sales'] },
                },
              },
              authenticated: true,
            } as Partial<Keycloak>,
          },
          { provide: KEYCLOAK_EVENT_SIGNAL, useValue: keycloakEventSignal },
        ],
      });

      const fixture = TestBed.createComponent(Header);
      const component = fixture.componentInstance;
      fixture.detectChanges();

      expect(component.isSalesRole()).toBe(true);

      keycloakEventSignal.set({ type: KeycloakEventType.AuthLogout, token: null });
      fixture.detectChanges();

      expect(component.isSalesRole()).toBe(false);
      const items = component.items();
      const salesItem = items.find((i) => i.routeLink === '/sales');
      expect(salesItem).toBeUndefined();
    });
  });

  // ─── Receiving menu gating (lc-receiving client role) ──────────

  // ─── Store-scoped stock entry (lc-admin / lc-sales, not the company roles) ────

  describe('stock by store menu gating', () => {
    it('should show the store-scoped stock entry for a sales-only user', () => {
      const { component } = setup(['lc-sales']);
      const stockItem = component.items().find((i) => i.routeLink === '/products/variants');

      expect(stockItem).toBeDefined();
      expect(stockItem?.textLink).toBe('Stock por tienda');
      expect(stockItem?.icon).toBe('storefront');
    });

    it('should show the store-scoped stock entry for an admin', () => {
      const { component } = setup(['lc-admin']);

      expect(component.items().some((i) => i.routeLink === '/products/variants')).toBe(true);
    });

    it('should NOT show the store-scoped stock entry to a company role', () => {
      // The company roles reach the store administration pages; the backend only
      // authorises lc-admin and lc-sales on the variant endpoints, so offering them the
      // entry would promise a screen the API refuses.
      const { component } = setup(['lc-company-store']);

      expect(component.items().some((i) => i.routeLink === '/products/variants')).toBe(false);
    });

    it('should NOT show the store-scoped stock entry to a receiving-only user', () => {
      const { component } = setup(['lc-receiving']);

      expect(component.items().some((i) => i.routeLink === '/products/variants')).toBe(false);
    });
  });

  describe('receiving menu gating', () => {
    it('should show only the Compras menu for a receiving-only user', () => {
      const { component } = setup(['lc-receiving']);
      expect(component.isReceiving()).toBe(true);
      expect(component.isAdmin()).toBe(false);

      const items = component.items();
      const comprasItem = items.find((i) => i.routeLink === '/purchases');
      expect(comprasItem).toBeDefined();
      expect(comprasItem?.textLink).toBe('Compras');
      expect(items.some((i) => i.routeLink === '/products')).toBe(false);
      expect(items.some((i) => i.routeLink === '/users-admin')).toBe(false);
      expect(items.length).toBe(1); // Compras only
    });

    it('should keep the admin menu unchanged: Products, Compras, Users Admin', () => {
      const { component } = setup(['lc-admin']);
      expect(component.isReceiving()).toBe(false);

      const routes = component.items().map((i) => i.routeLink);
      expect(routes).toContain('/products');
      expect(routes).toContain('/purchases');
      expect(routes).toContain('/users-admin');
      // Compras stays between Products and Users Admin for an admin.
      expect(routes.indexOf('/products')).toBeLessThan(routes.indexOf('/purchases'));
      expect(routes.indexOf('/purchases')).toBeLessThan(routes.indexOf('/users-admin'));
    });

    it('should reset isReceiving on AuthLogout event', () => {
      const keycloakEventSignal = signal({
        type: KeycloakEventType.Ready,
        token: null,
      });

      TestBed.configureTestingModule({
        providers: [
          provideRouter([]),
          provideLocationMocks(),
          provideHttpClient(),
          {
            provide: Keycloak,
            useValue: {
              login: vi.fn(),
              logout: vi.fn(),
              accountManagement: vi.fn(),
              hasRealmRole: vi.fn().mockReturnValue(false),
              tokenParsed: {
                resource_access: {
                  'life-control-client': { roles: ['lc-receiving'] },
                },
              },
              authenticated: true,
            } as Partial<Keycloak>,
          },
          { provide: KEYCLOAK_EVENT_SIGNAL, useValue: keycloakEventSignal },
        ],
      });

      const fixture = TestBed.createComponent(Header);
      const component = fixture.componentInstance;
      fixture.detectChanges();

      expect(component.isReceiving()).toBe(true);

      keycloakEventSignal.set({ type: KeycloakEventType.AuthLogout, token: null });
      fixture.detectChanges();

      expect(component.isReceiving()).toBe(false);
      const comprasItem = component.items().find((i) => i.routeLink === '/purchases');
      expect(comprasItem).toBeUndefined();
    });
  });

  // ─── Company selector fully removed ────────────────────────────

  describe('company selector removal', () => {
    it('should NOT render app-company-selector in the header DOM', () => {
      const { fixture } = setup(['lc-admin']);
      const companySelector = fixture.nativeElement.querySelector('app-company-selector');
      expect(companySelector).toBeFalsy();
    });

    it('should NOT render company-selector even for lc-company role', () => {
      const { fixture } = setup(['lc-company']);
      const companySelector = fixture.nativeElement.querySelector('app-company-selector');
      expect(companySelector).toBeFalsy();
    });
  });
});
