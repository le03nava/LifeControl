import {
  ApplicationConfig,
  DEFAULT_CURRENCY_CODE,
  inject,
  LOCALE_ID,
  provideAppInitializer,
  provideZonelessChangeDetection,
} from '@angular/core';
import { registerLocaleData } from '@angular/common';
import localeEsMx from '@angular/common/locales/es-MX';
import { provideHttpClient, withInterceptors, withFetch } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideKeycloak } from '@core/config/keycloak';
import { errorInterceptor } from '@shared/data/error-interceptor';
import { loadingInterceptor } from '@shared/data/loading-interceptor';
import { bearerTokenInterceptor } from '@core/interceptors/bearer-token.interceptor';
import { routes } from './app.routes';
import { ConfigService } from '@app/services/config.service';

const initializeApp = () => {
  const configService = inject(ConfigService);
  return configService.loadConfig();
};

// Register the Mexican locale so `currency`, `date` and `number` pipes format
// values the way the business expects (es-MX).
registerLocaleData(localeEsMx);

export const appConfig: ApplicationConfig = {
  providers: [
    // 0. Localización (MXN / es-MX)
    { provide: LOCALE_ID, useValue: 'es-MX' },
    { provide: DEFAULT_CURRENCY_CODE, useValue: 'MXN' },

    // Animaciones (requerido por Angular Material)
    provideAnimationsAsync(),

    // 1. Core providers - HttpClient con interceptores personalizados
    provideHttpClient(
      withFetch(),
      withInterceptors([loadingInterceptor, bearerTokenInterceptor, errorInterceptor]),
    ),

    // 2. Change detection
    provideZonelessChangeDetection(),

    // 3. Router
    provideRouter(routes, withComponentInputBinding()),

    // 4. App initializer
    provideAppInitializer(initializeApp),
    ConfigService,

    // 5. Keycloak (después de inicialización)
    provideKeycloak({
      config: {
        url: window.env?.KEYCLOAK_URL || 'http://localhost:8181',
        realm: window.env?.KEYCLOAK_REALM || 'life-control-realm',
        clientId: window.env?.KEYCLOAK_CLIENT_ID || 'life-control-client',
      },
      initOptions: {
        onLoad: 'check-sso',
        silentCheckSsoRedirectUri: window.location.origin + '/assets/silent-check-sso.html',
      },
    }),
  ],
};
