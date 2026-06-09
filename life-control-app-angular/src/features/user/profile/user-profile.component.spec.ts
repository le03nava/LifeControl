import { TestBed } from '@angular/core/testing';
import Keycloak from 'keycloak-js';
import { UserProfileComponent } from './user-profile.component';

describe('UserProfileComponent', () => {
  const setup = (tokenParsed?: Keycloak['tokenParsed']) => {
    const keycloakMock: Partial<Keycloak> = { tokenParsed };

    TestBed.configureTestingModule({
      providers: [{ provide: Keycloak, useValue: keycloakMock }],
    });

    const fixture = TestBed.createComponent(UserProfileComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    return { fixture, component };
  };

  describe('authenticated user with complete token', () => {
    it('should display the full name from tokenParsed', () => {
      const { fixture } = setup({
        name: 'John Doe',
        email: 'john@example.com',
        preferred_username: 'johndoe',
      });

      expect(fixture.nativeElement.textContent).toContain('John Doe');
    });

    it('should display the email from tokenParsed', () => {
      const { fixture } = setup({
        name: 'John Doe',
        email: 'john@example.com',
        preferred_username: 'johndoe',
      });

      expect(fixture.nativeElement.textContent).toContain('john@example.com');
    });

    it('should display the username from tokenParsed', () => {
      const { fixture } = setup({
        name: 'John Doe',
        email: 'john@example.com',
        preferred_username: 'johndoe',
      });

      expect(fixture.nativeElement.textContent).toContain('johndoe');
    });
  });

  describe('authenticated user with partial token', () => {
    it('should fall back to preferred_username when name is missing', () => {
      const { fixture } = setup({
        name: undefined,
        email: 'test@example.com',
        preferred_username: 'testuser',
      } as Keycloak['tokenParsed']);

      // displayName falls back to preferred_username, not 'Not available'
      expect(fixture.nativeElement.textContent).toContain('testuser');
      expect(fixture.nativeElement.textContent).not.toContain('Not available');
    });

    it('should show fallback when email is missing', () => {
      const { fixture } = setup({
        name: 'Test User',
        email: undefined,
        preferred_username: 'testuser',
      } as Keycloak['tokenParsed']);

      expect(fixture.nativeElement.textContent).toContain('Not available');
    });

    it('should show fallback when preferred_username is missing', () => {
      const { fixture } = setup({
        name: 'Test User',
        email: 'test@example.com',
        preferred_username: undefined,
      } as Keycloak['tokenParsed']);

      expect(fixture.nativeElement.textContent).toContain('Not available');
    });

    it('should still render the card when all optional fields are absent', () => {
      const { fixture } = setup({
        name: undefined,
        email: undefined,
        preferred_username: undefined,
      } as Keycloak['tokenParsed']);

      const card = fixture.nativeElement.querySelector('.profile-card');
      expect(card).toBeTruthy();
      // All three fields should show fallback
      const occurrences = (fixture.nativeElement.textContent.match(/Not available/g) || []).length;
      expect(occurrences).toBeGreaterThanOrEqual(3);
    });
  });

  describe('unauthenticated user (missing token)', () => {
    it('should render without crashing when tokenParsed is undefined', () => {
      const { fixture } = setup(undefined);

      const card = fixture.nativeElement.querySelector('.profile-card');
      expect(card).toBeTruthy();
    });

    it('should show fallback text for all fields when token is missing', () => {
      const { fixture } = setup(undefined);

      expect(fixture.nativeElement.textContent).toContain('Not available');
      const occurrences = (fixture.nativeElement.textContent.match(/Not available/g) || []).length;
      expect(occurrences).toBeGreaterThanOrEqual(3);
    });
  });

  describe('component structure', () => {
    it('should render a Material card', () => {
      const { fixture } = setup({
        name: 'Jane',
        email: 'jane@example.com',
        preferred_username: 'jane',
      });

      const card = fixture.nativeElement.querySelector('mat-card');
      expect(card).toBeTruthy();
    });

    it('should display a profile icon', () => {
      const { fixture } = setup({
        name: 'Jane',
        email: 'jane@example.com',
        preferred_username: 'jane',
      });

      const icon = fixture.nativeElement.querySelector('mat-icon');
      expect(icon).toBeTruthy();
      expect(icon.textContent).toContain('account_circle');
    });
  });
});
