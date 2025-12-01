import { ApplicationConfig, provideZonelessChangeDetection } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withInterceptors, withFetch } from '@angular/common/http';
import { provideClientHydration, withIncrementalHydration } from '@angular/platform-browser';
import { provideKeycloakAngular } from '@core/config/keycloak';
import { routes } from './app.routes';
import { errorInterceptor } from '@shared/data/error-interceptor';
import { loadingInterceptor } from '@shared/data/loading-interceptor';
import { includeBearerTokenInterceptor } from 'keycloak-angular';

export const appConfig: ApplicationConfig = {
  providers: [
    provideKeycloakAngular(),
    // Enable zoneless change detection for optimal performance (Angular v20)
    provideZonelessChangeDetection(),

    // Router with modern features
    provideRouter(routes, withComponentInputBinding()),

    // HTTP client with functional interceptors
    provideHttpClient(
      // withFetch(),
      withInterceptors([
        errorInterceptor,
        //loadingInterceptor,
        includeBearerTokenInterceptor,
      ]),
    ),

    // Client-side hydration with incremental hydration (Angular v20 stable)
    provideClientHydration(withIncrementalHydration()),
  ],
};
