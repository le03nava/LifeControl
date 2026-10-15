import { TestBed } from '@angular/core/testing';
import Keycloak from 'keycloak-js';
import { CurrentUser, subFromToken } from './current-user';

describe('current user sub', () => {
  describe('subFromToken', () => {
    it('should read the sub claim of a parsed token', () => {
      const token = { sub: 'user-123' } as unknown as Keycloak['tokenParsed'];

      expect(subFromToken(token)).toBe('user-123');
    });

    it('should return null when there is no token', () => {
      expect(subFromToken(undefined)).toBeNull();
    });

    it('should return null when the token carries no sub', () => {
      const token = { preferred_username: 'operator' } as unknown as Keycloak['tokenParsed'];

      expect(subFromToken(token)).toBeNull();
    });

    it('should return null for a blank sub', () => {
      const token = { sub: '   ' } as unknown as Keycloak['tokenParsed'];

      expect(subFromToken(token)).toBeNull();
    });
  });

  describe('CurrentUser', () => {
    let keycloakMock: Partial<Keycloak>;

    beforeEach(() => {
      keycloakMock = { tokenParsed: undefined };
      TestBed.configureTestingModule({
        providers: [{ provide: Keycloak, useValue: keycloakMock }],
      });
    });

    it('should expose the caller’s own sub from the injected token', () => {
      keycloakMock.tokenParsed = {
        sub: 'user-456',
      } as unknown as Keycloak['tokenParsed'];

      const sub = TestBed.runInInjectionContext(() => TestBed.inject(CurrentUser).sub);

      expect(sub).toBe('user-456');
    });

    it('should return null when the session carries no token', () => {
      const sub = TestBed.runInInjectionContext(() => TestBed.inject(CurrentUser).sub);

      expect(sub).toBeNull();
    });
  });
});
