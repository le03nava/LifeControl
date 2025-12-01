import { ApplicationConfig } from '@angular/core';
import { provideClientHydration, withIncrementalHydration } from '@angular/platform-browser';
import { provideServerRendering } from '@angular/platform-server';
export const appConfig: ApplicationConfig = {
  providers: [
    provideServerRendering(),
    // Client-side hydration with incremental hydration (Angular v20 stable)
    provideClientHydration(withIncrementalHydration()),
  ],
};
