import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter, RouterLink } from '@angular/router';
import { PageHeader } from '@shared/ui';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { KEYCLOAK_EVENT_SIGNAL, KeycloakEventType } from 'keycloak-angular';
import Keycloak from 'keycloak-js';
import { CompaniesAdminComponent } from './companies-admin.component';

describe('CompaniesAdminComponent', () => {
  let component: CompaniesAdminComponent;
  let fixture: ComponentFixture<CompaniesAdminComponent>;

  /**
   * Set up the CompaniesAdminComponent with specific Keycloak client roles.
   * @param clientRoles - roles inside resource_access['life-control-client'].roles
   */
  const setupWithRoles = (clientRoles: string[] = []) => {
    const tokenParsed = clientRoles.length > 0
      ? {
          resource_access: { 'life-control-client': { roles: clientRoles } },
        }
      : undefined;

    const keycloakMock: Partial<Keycloak> = {
      tokenParsed: tokenParsed as Keycloak['tokenParsed'],
      authenticated: clientRoles.length > 0,
      hasRealmRole: vi.fn().mockReturnValue(false),
      login: vi.fn(),
      logout: vi.fn(),
    };

    const keycloakEventSignal = signal({
      type: KeycloakEventType.Ready,
      token: null,
    });

    TestBed.configureTestingModule({
      imports: [CompaniesAdminComponent, PageHeader, MatCardModule, MatIconModule, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        { provide: Keycloak, useValue: keycloakMock },
        { provide: KEYCLOAK_EVENT_SIGNAL, useValue: keycloakEventSignal },
      ],
    });

    const f = TestBed.createComponent(CompaniesAdminComponent);
    const c = f.componentInstance;
    f.detectChanges();
    f.detectChanges(); // trigger effect that depends on KEYCLOAK_EVENT_SIGNAL

    return { fixture: f, component: c };
  };

  // ─── Default view (no roles) ──────────────────────────────────

  describe('default view (no roles)', () => {
    beforeEach(async () => {
      ({ fixture, component } = setupWithRoles([]));
    });

    it('should create', () => {
      expect(component).toBeTruthy();
    });

    it('should return empty cards array when user has no roles', () => {
      expect(component.cards()).toHaveLength(0);
    });

    it('should render no dashboard cards in the DOM', () => {
      const cards = fixture.debugElement.queryAll(By.css('.dashboard-card'));
      expect(cards).toHaveLength(0);
    });
  });

  // ─── Admin user (lc-admin) ────────────────────────────────────

  describe('admin user (lc-admin)', () => {
    beforeEach(async () => {
      ({ fixture, component } = setupWithRoles(['lc-admin']));
    });

    it('should render all 5 cards enabled', () => {
      const cards = component.cards();
      expect(cards).toHaveLength(5);
      for (const card of cards) {
        expect(card.disabled).toBe(false);
      }
      const dashboardCards = fixture.debugElement.queryAll(By.css('.dashboard-card'));
      expect(dashboardCards).toHaveLength(5);
    });

    it('should render active cards with "Manage" call-to-action text', () => {
      const actionElements = fixture.nativeElement.querySelectorAll('.card-action');
      expect(actionElements).toHaveLength(5);
      expect(actionElements[0].textContent.trim()).toContain('Manage Companies');
      expect(actionElements[1].textContent.trim()).toContain('Manage Countries');
    });
  });

  // ─── Region-only user (lc-company-region) ─────────────────────

  describe('region-only user (lc-company-region)', () => {
    beforeEach(async () => {
      ({ fixture, component } = setupWithRoles(['lc-company-region']));
    });

    it('should enable Regions, Zones, Stores and disable Companies, Countries', () => {
      const cards = component.cards();
      const titles = cards.map((c) => c.title);

      expect(titles).toEqual(['Regions', 'Zones', 'Stores']);
      expect(cards).toHaveLength(3);
      for (const card of cards) {
        expect(card.disabled).toBe(false);
      }
    });

    it('should render 3 cards in the DOM', () => {
      const dashboardCards = fixture.debugElement.queryAll(By.css('.dashboard-card'));
      expect(dashboardCards).toHaveLength(3);
    });
  });

  // ─── Zone-only user (lc-company-zone) ─────────────────────────

  describe('zone-only user (lc-company-zone)', () => {
    beforeEach(async () => {
      ({ fixture, component } = setupWithRoles(['lc-company-zone']));
    });

    it('should enable Zones, Stores and disable Companies, Countries, Regions', () => {
      const cards = component.cards();
      const titles = cards.map((c) => c.title);

      expect(titles).toEqual(['Zones', 'Stores']);
      expect(cards).toHaveLength(2);
      for (const card of cards) {
        expect(card.disabled).toBe(false);
      }
    });

    it('should render 2 cards in the DOM', () => {
      const dashboardCards = fixture.debugElement.queryAll(By.css('.dashboard-card'));
      expect(dashboardCards).toHaveLength(2);
    });
  });

  // ─── Store-only user (lc-company-store) ───────────────────────

  describe('store-only user (lc-company-store)', () => {
    beforeEach(async () => {
      ({ fixture, component } = setupWithRoles(['lc-company-store']));
    });

    it('should enable only Stores and disable all others', () => {
      const cards = component.cards();
      const titles = cards.map((c) => c.title);

      expect(titles).toEqual(['Stores']);
      expect(cards).toHaveLength(1);
      expect(cards[0].disabled).toBe(false);
    });

    it('should render 1 card in the DOM', () => {
      const dashboardCards = fixture.debugElement.queryAll(By.css('.dashboard-card'));
      expect(dashboardCards).toHaveLength(1);
    });
  });

  // ─── Basic structure tests (no roles required for UI) ──────────

  describe('basic structure', () => {
    beforeEach(async () => {
      ({ fixture, component } = setupWithRoles(['lc-admin']));
    });

    it('should render the page header with title and subtitle', () => {
      const title = fixture.nativeElement.querySelector('.page-title');
      const subtitle = fixture.nativeElement.querySelector('.page-subtitle');
      expect(title).toBeTruthy();
      expect(title.textContent.trim()).toBe('Companies Administration');
      expect(subtitle).toBeTruthy();
      expect(subtitle.textContent.trim()).toBe(
        'Manage companies, countries, regions, zones, and stores',
      );
    });

    it('should render each dashboard card with correct title', () => {
      const cardTitles: NodeListOf<Element> = fixture.nativeElement.querySelectorAll('.card-title');
      const titles = Array.from(cardTitles).map((el: Element) => el.textContent?.trim() ?? '');

      expect(titles).toEqual(['Companies', 'Countries', 'Regions', 'Zones', 'Stores']);
    });

    it('should render card icons with correct Material icon names', () => {
      const icons: NodeListOf<Element> = fixture.nativeElement.querySelectorAll('.card-icon mat-icon');
      expect(icons).toHaveLength(5);

      const iconNames = Array.from(icons).map((el: Element) =>
        el.getAttribute('data-mat-icon-name'),
      );
      expect(iconNames).toContain('business');
      expect(iconNames).toContain('public');
      expect(iconNames).toContain('location_on');
      expect(iconNames).toContain('map');
      expect(iconNames).toContain('store');
    });
  });
});
