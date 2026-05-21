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

  const setup = () => {
    keycloakMock = {
      login: vi.fn(),
      logout: vi.fn(),
      hasRealmRole: vi.fn().mockReturnValue(false),
      authenticated: false,
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
});
