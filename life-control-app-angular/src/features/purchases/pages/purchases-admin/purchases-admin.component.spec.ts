import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import Keycloak from 'keycloak-js';
import { PurchasesAdminComponent } from './purchases-admin.component';

describe('PurchasesAdminComponent', () => {
  let component: PurchasesAdminComponent;
  let fixture: ComponentFixture<PurchasesAdminComponent>;

  /**
   * Set up the PurchasesAdminComponent with specific Keycloak client roles.
   * @param clientRoles - roles inside resource_access['life-control-client'].roles
   */
  const setupWithRoles = (clientRoles: string[] = []) => {
    const tokenParsed =
      clientRoles.length > 0
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

    TestBed.configureTestingModule({
      imports: [PurchasesAdminComponent, NoopAnimationsModule],
      providers: [provideRouter([]), { provide: Keycloak, useValue: keycloakMock }],
    });

    const f = TestBed.createComponent(PurchasesAdminComponent);
    const c = f.componentInstance;
    f.detectChanges();

    return { fixture: f, component: c };
  };

  // ─── Admin user (lc-admin) ────────────────────────────────────

  describe('admin user (lc-admin)', () => {
    beforeEach(() => {
      ({ fixture, component } = setupWithRoles(['lc-admin']));
    });

    it('should expose both the Purchase Orders and Receipts cards', () => {
      expect(component.cards.map((card) => card.title)).toEqual(['Purchase Orders', 'Receipts']);
      expect(fixture.debugElement.queryAll(By.css('.dashboard-card'))).toHaveLength(2);
    });
  });

  // ─── Receiving-only user (lc-receiving) ───────────────────────

  describe('receiving-only user (lc-receiving)', () => {
    beforeEach(() => {
      ({ fixture, component } = setupWithRoles(['lc-receiving']));
    });

    it('should expose only the Receipts card', () => {
      expect(component.cards.map((card) => card.title)).toEqual(['Receipts']);
      expect(fixture.debugElement.queryAll(By.css('.dashboard-card'))).toHaveLength(1);
    });

    it('should render the Receipts card and not the Purchase Orders card in the DOM', () => {
      const titles: NodeListOf<Element> = fixture.nativeElement.querySelectorAll('.card-title');
      const rendered = Array.from(titles).map((el: Element) => el.textContent?.trim() ?? '');

      expect(rendered).toEqual(['Receipts']);
      expect(fixture.nativeElement.textContent).not.toContain('Purchase Orders');
    });
  });

  // ─── User without purchase roles ──────────────────────────────

  describe('user with no purchase roles', () => {
    beforeEach(() => {
      ({ fixture, component } = setupWithRoles([]));
    });

    it('should expose no cards', () => {
      expect(component.cards).toHaveLength(0);
      expect(fixture.debugElement.queryAll(By.css('.dashboard-card'))).toHaveLength(0);
    });
  });
});
