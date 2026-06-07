import { provideLocationMocks } from '@angular/common/testing';
import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
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
  const setup = (
    clientRoles: string[] = [],
    extraTokenParsed: Record<string, unknown> = {},
  ) => {
    const tokenParsed = clientRoles.length > 0 || Object.keys(extraTokenParsed).length > 0
      ? {
          ...extraTokenParsed,
          resource_access: {
            'life-control-client': { roles: clientRoles },
          },
        }
      : undefined;

    keycloakMock = {
      login: vi.fn(),
      logout: vi.fn(),
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

    it('should include home menu item', () => {
      const { component } = setup();
      const items = component.items();
      const homeItem = items.find((item) => item.routeLink === '/home');
      expect(homeItem).toBeDefined();
      expect(homeItem?.textLink).toBe('Home');
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
      expect(items.length).toBe(2); // Home + Companies only
    });

    it('should show Companies menu when user has lc-company-country client role', () => {
      const { component } = setup(['lc-company-country']);
      expect(component.isCompanyRole()).toBe(true);
      expect(component.isAdmin()).toBe(false);
      const items = component.items();
      const companiesItem = items.find((i) => i.routeLink === '/companies');
      expect(companiesItem).toBeDefined();
      expect(companiesItem?.textLink).toBe('Companies');
      expect(items.length).toBe(2); // Home + Companies only
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
      expect(items.length).toBe(5); // Home + Companies + Products + Purchases + Users Admin
    });

    it('should NOT show Companies menu when user has no company client roles', () => {
      const { component } = setup([]);
      expect(component.isCompanyRole()).toBe(false);
      expect(component.isAdmin()).toBe(false);
      const items = component.items();
      const companiesItem = items.find((i) => i.routeLink === '/companies');
      expect(companiesItem).toBeUndefined();
      expect(items.length).toBe(1); // Home only
    });
  });

  // ─── Admin role visibility (client roles) ─────────────────────

  describe('admin role visibility', () => {
    it('should show Users Admin nav and company-selector for lc-admin role', () => {
      const { component, fixture } = setup(['lc-admin']);
      expect(component.isAdmin()).toBe(true);
      expect(component.isCompanyRole()).toBe(true);
      const items = component.items();
      expect(items.some((i) => i.routeLink === '/users-admin')).toBe(true);
      expect(items.length).toBe(5); // Home + Companies + Products + Purchases + Users Admin
      const companySelector = fixture.nativeElement.querySelector('app-company-selector');
      expect(companySelector).toBeTruthy();
    });

    it('should show company-selector but NOT Users Admin for lc-company role', () => {
      const { component, fixture } = setup(['lc-company']);
      expect(component.isAdmin()).toBe(false);
      expect(component.isCompanyRole()).toBe(true);
      const items = component.items();
      expect(items.some((i) => i.routeLink === '/users-admin')).toBe(false);
      expect(items.length).toBe(2); // Home + Companies only
      const companySelector = fixture.nativeElement.querySelector('app-company-selector');
      expect(companySelector).toBeTruthy();
    });

    it('should hide both Users Admin and company-selector with no client roles', () => {
      const { component, fixture } = setup([]);
      expect(component.isAdmin()).toBe(false);
      expect(component.isCompanyRole()).toBe(false);
      const items = component.items();
      expect(items.some((i) => i.routeLink === '/users-admin')).toBe(false);
      expect(items.length).toBe(1); // Home only
      const companySelector = fixture.nativeElement.querySelector('app-company-selector');
      expect(companySelector).toBeFalsy();
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
      expect(items.length).toBe(1); // Home only
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
      expect(items.length).toBe(2); // Home + Companies only
    });

    it('should show Companies menu when user has only lc-company-zone role', () => {
      const { component } = setup(['lc-company-zone']);
      expect(component.isCompanyRole()).toBe(true);
      expect(component.isAdmin()).toBe(false);
      const items = component.items();
      const companiesItem = items.find((i) => i.routeLink === '/companies');
      expect(companiesItem).toBeDefined();
      expect(companiesItem?.textLink).toBe('Companies');
      expect(items.length).toBe(2); // Home + Companies only
    });

    it('should show Companies menu when user has only lc-company-store role', () => {
      const { component } = setup(['lc-company-store']);
      expect(component.isCompanyRole()).toBe(true);
      expect(component.isAdmin()).toBe(false);
      const items = component.items();
      const companiesItem = items.find((i) => i.routeLink === '/companies');
      expect(companiesItem).toBeDefined();
      expect(companiesItem?.textLink).toBe('Companies');
      expect(items.length).toBe(2); // Home + Companies only
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
          { provide: Keycloak, useValue: {
            login: vi.fn(),
            logout: vi.fn(),
            hasRealmRole: vi.fn().mockReturnValue(false),
            tokenParsed: {
              resource_access: { 'life-control-client': { roles: ['lc-company-region'] } },
            },
            authenticated: true,
          } as Partial<Keycloak> },
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
});
