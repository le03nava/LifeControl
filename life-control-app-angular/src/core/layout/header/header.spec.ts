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
      expect(items.length).toBe(4); // Companies + Products + Purchases + Users Admin
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

  // ─── Admin role visibility (client roles) — company-selector removed ───

  describe('admin role visibility', () => {
    it('should show Users Admin nav for lc-admin role', () => {
      const { component } = setup(['lc-admin']);
      expect(component.isAdmin()).toBe(true);
      expect(component.isCompanyRole()).toBe(true);
      const items = component.items();
      expect(items.some((i) => i.routeLink === '/users-admin')).toBe(true);
      expect(items.length).toBe(4); // Companies + Products + Purchases + Users Admin
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
          { provide: Keycloak, useValue: {
            login: vi.fn(),
            logout: vi.fn(),
            accountManagement: vi.fn(),
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
