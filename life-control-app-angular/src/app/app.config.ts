import { ApplicationConfig, provideZonelessChangeDetection, APP_INITIALIZER } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withInterceptors, withFetch } from '@angular/common/http';
import { provideClientHydration, withIncrementalHydration } from '@angular/platform-browser';
import { provideKeycloakAngular } from '@core/config/keycloak';
import { routes } from './app.routes';
import { errorInterceptor } from '@shared/data/error-interceptor';
import { loadingInterceptor } from '@shared/data/loading-interceptor';
import { includeBearerTokenInterceptor } from 'keycloak-angular';
import { ConfigService } from './services/config.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideHttpClient(
      withInterceptors([
        errorInterceptor,
        includeBearerTokenInterceptor,
      ]),
    ),
    {
      provide: APP_INITIALIZER,
      useFactory: (configService: ConfigService) => () => configService.loadConfig(),
      deps: [ConfigService],
      multi: true,
    },
    provideKeycloakAngular(),
    provideZonelessChangeDetection(),
    provideRouter(routes, withComponentInputBinding()),
    provideClientHydration(withIncrementalHydration()),
  ],
};
