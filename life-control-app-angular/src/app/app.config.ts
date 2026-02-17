import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideZonelessChangeDetection } from '@angular/core';
import { provideClientHydration, withIncrementalHydration } from '@angular/platform-browser';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideKeycloak } from '@core/config/keycloak';
import { errorInterceptor } from '@shared/data/error-interceptor';
import { includeBearerTokenInterceptor } from 'keycloak-angular';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideHttpClient(withInterceptors([errorInterceptor, includeBearerTokenInterceptor])),
    provideKeycloak({
      config: {
        url: (window as any).env.KEYCLOAK_URL || 'http://localhost:8181',
        realm: (window as any).env.KEYCLOAK_REALM || 'life-control-realm',
        clientId: (window as any).env.KEYCLOAK_CLIENT_ID || 'life-control-client',
      },
      initOptions: {
        onLoad: 'check-sso',
        silentCheckSsoRedirectUri: window.location.origin + '/assets/silent-check-sso.html',
      },
    }),
    provideZonelessChangeDetection(),
    provideRouter(routes, withComponentInputBinding()),
    provideClientHydration(withIncrementalHydration()),
  ],
};
