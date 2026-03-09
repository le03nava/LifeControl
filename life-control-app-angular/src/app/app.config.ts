import { ApplicationConfig, inject, provideAppInitializer, provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient, withInterceptors, withFetch } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideKeycloak } from '@core/config/keycloak';
import { errorInterceptor } from '@shared/data/error-interceptor';
import { bearerTokenInterceptor } from '@core/interceptors/bearer-token.interceptor';
import { routes } from './app.routes';
import { ConfigService } from '@app/services/config.service';

const initializeApp = () => {
  const configService = inject(ConfigService);
  return configService.loadConfig();
};

export const appConfig: ApplicationConfig = {
  providers: [
    // 1. Core providers - HttpClient con interceptores personalizados
    provideHttpClient(
      withFetch(),
      withInterceptors([errorInterceptor, bearerTokenInterceptor])
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
        url: (window as any).env.KEYCLOAK_URL || 'http://localhost:8181',
        realm: (window as any).env.KEYCLOAK_REALM || 'life-control-realm',
        clientId: (window as any).env.KEYCLOAK_CLIENT_ID || 'life-control-client',
      },
      initOptions: {
        onLoad: 'check-sso',
        silentCheckSsoRedirectUri: window.location.origin + '/assets/silent-check-sso.html',
      },
    }),
  ],
};
