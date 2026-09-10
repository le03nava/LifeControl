import { HttpHandlerFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import Keycloak from 'keycloak-js';
import { of } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { bearerTokenInterceptor } from './bearer-token.interceptor';

const TEST_API = 'http://localhost:9000/api';

describe('bearerTokenInterceptor', () => {
  let keycloakMock: Partial<Keycloak>;
  let configServiceMock: Partial<ConfigService>;

  beforeEach(() => {
    keycloakMock = {
      authenticated: true,
      token: 'test-token',
    };
    configServiceMock = {
      apiUrl: TEST_API,
    };

    TestBed.configureTestingModule({
      providers: [
        { provide: Keycloak, useValue: keycloakMock },
        { provide: ConfigService, useValue: configServiceMock },
      ],
    });
  });

  function forwardedRequest(url: string): HttpRequest<unknown> {
    let captured!: HttpRequest<unknown>;
    const next: HttpHandlerFn = (req) => {
      captured = req;
      return of(new HttpResponse({ status: 200 }));
    };
    const req = new HttpRequest('GET', url);
    TestBed.runInInjectionContext(() => bearerTokenInterceptor(req, next));
    return captured;
  }

  it('should add the Bearer token to API Gateway requests', () => {
    const forwarded = forwardedRequest(`${TEST_API}/companies`);
    expect(forwarded.headers.get('Authorization')).toBe('Bearer test-token');
  });

  it('should not add the token to external requests', () => {
    const forwarded = forwardedRequest('https://api.third-party.com/data');
    expect(forwarded.headers.has('Authorization')).toBe(false);
  });

  it('should not add the token when Keycloak is not authenticated', () => {
    keycloakMock.authenticated = false;
    const forwarded = forwardedRequest(`${TEST_API}/companies`);
    expect(forwarded.headers.has('Authorization')).toBe(false);
  });

  it('should skip the token when ConfigService.apiUrl is unavailable', () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: Keycloak, useValue: { authenticated: true, token: 'test-token' } },
        {
          provide: ConfigService,
          useValue: {
            get apiUrl() {
              throw new Error('config not loaded');
            },
          },
        },
      ],
    });

    const forwarded = forwardedRequest(`${TEST_API}/companies`);
    expect(forwarded.headers.has('Authorization')).toBe(false);
  });
});
