import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter, RouterLink } from '@angular/router';
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
      imports: [CompaniesAdminComponent, MatCardModule, MatIconModule, NoopAnimationsModule],
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

    it('should render all 5 cards as disabled when user has no roles', () => {
      const cards = component.cards();
      for (const card of cards) {
        expect(card.disabled).toBe(true);
      }
      const disabledCards = fixture.debugElement.queryAll(
        By.css('.dashboard-card.card--disabled'),
      );
      expect(disabledCards).toHaveLength(5);
    });

    it('should display "Coming soon" badges on all disabled cards', () => {
      const comingSoonBadges = fixture.nativeElement.querySelectorAll('.card-badge--coming-soon');
      expect(comingSoonBadges).toHaveLength(5);
    });
  });

  // ─── Admin user (lc-admin) ────────────────────────────────────

  describe('admin user (lc-admin)', () => {
    beforeEach(async () => {
      ({ fixture, component } = setupWithRoles(['lc-admin']));
    });

    it('should render all 5 cards enabled', () => {
      const cards = component.cards();
      for (const card of cards) {
        expect(card.disabled).toBe(false);
      }
      const disabledCards = fixture.debugElement.queryAll(
        By.css('.dashboard-card.card--disabled'),
      );
      expect(disabledCards).toHaveLength(0);
    });

    it('should render active cards with "Manage" call-to-action text', () => {
      const actionElements = fixture.nativeElement.querySelectorAll('.card-action');
      expect(actionElements).toHaveLength(5);
      expect(actionElements[0].textContent.trim()).toContain('Manage Companies');
      expect(actionElements[1].textContent.trim()).toContain('Manage Countries');
    });

    it('should render no "Coming soon" badges', () => {
      const comingSoonBadges = fixture.nativeElement.querySelectorAll('.card-badge--coming-soon');
      expect(comingSoonBadges).toHaveLength(0);
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

      const getCard = (title: string) => cards[titles.indexOf(title)];

      expect(getCard('Companies').disabled).toBe(true);
      expect(getCard('Countries').disabled).toBe(true);
      expect(getCard('Regions').disabled).toBe(false);
      expect(getCard('Zones').disabled).toBe(false);
      expect(getCard('Stores').disabled).toBe(false);
    });

    it('should render 3 enabled and 2 disabled cards in the DOM', () => {
      const enabledCards = fixture.debugElement.queryAll(
        By.css('.dashboard-card:not(.card--disabled)'),
      );
      const disabledCards = fixture.debugElement.queryAll(
        By.css('.dashboard-card.card--disabled'),
      );
      expect(enabledCards).toHaveLength(3);
      expect(disabledCards).toHaveLength(2);
    });

    it('should show "Coming soon" badges on disabled cards only', () => {
      const comingSoonBadges = fixture.nativeElement.querySelectorAll('.card-badge--coming-soon');
      expect(comingSoonBadges).toHaveLength(2);
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

      const getCard = (title: string) => cards[titles.indexOf(title)];

      expect(getCard('Companies').disabled).toBe(true);
      expect(getCard('Countries').disabled).toBe(true);
      expect(getCard('Regions').disabled).toBe(true);
      expect(getCard('Zones').disabled).toBe(false);
      expect(getCard('Stores').disabled).toBe(false);
    });

    it('should render 2 enabled and 3 disabled cards in the DOM', () => {
      const enabledCards = fixture.debugElement.queryAll(
        By.css('.dashboard-card:not(.card--disabled)'),
      );
      const disabledCards = fixture.debugElement.queryAll(
        By.css('.dashboard-card.card--disabled'),
      );
      expect(enabledCards).toHaveLength(2);
      expect(disabledCards).toHaveLength(3);
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

      const getCard = (title: string) => cards[titles.indexOf(title)];

      expect(getCard('Companies').disabled).toBe(true);
      expect(getCard('Countries').disabled).toBe(true);
      expect(getCard('Regions').disabled).toBe(true);
      expect(getCard('Zones').disabled).toBe(true);
      expect(getCard('Stores').disabled).toBe(false);
    });

    it('should render 1 enabled and 4 disabled cards in the DOM', () => {
      const enabledCards = fixture.debugElement.queryAll(
        By.css('.dashboard-card:not(.card--disabled)'),
      );
      const disabledCards = fixture.debugElement.queryAll(
        By.css('.dashboard-card.card--disabled'),
      );
      expect(enabledCards).toHaveLength(1);
      expect(disabledCards).toHaveLength(4);
    });
  });

  // ─── Basic structure tests (no roles required) ─────────────────

  describe('basic structure', () => {
    beforeEach(async () => {
      ({ fixture, component } = setupWithRoles(['lc-admin']));
    });

    it('should render each placeholder card with correct title from data', () => {
      const cardTitles: NodeListOf<Element> = fixture.nativeElement.querySelectorAll('.card-title');
      const titles = Array.from(cardTitles).map((el: Element) => el.textContent?.trim() ?? '');

      expect(titles).toContain('Companies');
      expect(titles).toContain('Countries');
      expect(titles).toContain('Regions');
      expect(titles).toContain('Zones');
      expect(titles).toContain('Stores');
    });

    it('should render the dashboard subtitle', () => {
      const subtitle = fixture.nativeElement.querySelector('.dashboard-subtitle');
      expect(subtitle).toBeTruthy();
      expect(subtitle.textContent.trim()).toBe(
        'Manage companies, countries, regions, zones, and stores',
      );
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

    it('should render dashboard title "Companies Administration"', () => {
      const title = fixture.nativeElement.querySelector('.dashboard-title');
      expect(title).toBeTruthy();
      expect(title.textContent.trim()).toBe('Companies Administration');
    });
  });
});
