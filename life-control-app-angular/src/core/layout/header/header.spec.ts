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

  const setup = (realmRoles: string[] = []) => {
    keycloakMock = {
      login: vi.fn(),
      logout: vi.fn(),
      hasRealmRole: vi.fn().mockImplementation((role: string) => realmRoles.includes(role)),
      authenticated: realmRoles.length > 0,
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

    it('should include companies menu item', () => {
      const { component } = setup();
      const items = component.items();
      const companiesItem = items.find((item) => item.routeLink === '/companies');
      expect(companiesItem).toBeDefined();
      expect(companiesItem?.textLink).toBe('Companies');
    });
  });

  describe('menu items count without admin role', () => {
    it('should have exactly 2 menu items when user is not admin', () => {
      const { component } = setup();
      expect(component.items().length).toBe(2);
    });
  });

  describe('role visibility', () => {
    it('should show Users Admin nav and company-selector for admin role', () => {
      const { component, fixture } = setup(['life-control-admin']);
      expect(component.isAdmin()).toBe(true);
      expect(component.isCompanyRole()).toBe(true);
      const items = component.items();
      expect(items.some((i) => i.routeLink === '/users-admin')).toBe(true);
      expect(items.length).toBe(3); // Home + Companies + Users Admin
      const companySelector = fixture.nativeElement.querySelector('app-company-selector');
      expect(companySelector).toBeTruthy();
    });

    it('should show company-selector but NOT Users Admin for country role', () => {
      const { component, fixture } = setup(['life-control-country']);
      expect(component.isAdmin()).toBe(false);
      expect(component.isCompanyRole()).toBe(true);
      const items = component.items();
      expect(items.some((i) => i.routeLink === '/users-admin')).toBe(false);
      expect(items.length).toBe(2); // Home + Companies only
      const companySelector = fixture.nativeElement.querySelector('app-company-selector');
      expect(companySelector).toBeTruthy();
    });

    it('should hide both Users Admin and company-selector for neither role', () => {
      const { component, fixture } = setup(['some-other-role']);
      expect(component.isAdmin()).toBe(false);
      expect(component.isCompanyRole()).toBe(false);
      const items = component.items();
      expect(items.some((i) => i.routeLink === '/users-admin')).toBe(false);
      expect(items.length).toBe(2); // Home + Companies only
      const companySelector = fixture.nativeElement.querySelector('app-company-selector');
      expect(companySelector).toBeFalsy();
    });
  });
});
