import { Injectable, signal } from '@angular/core';
import { AppConfig } from '../models/app-config.model';

const DEFAULT_CONFIG: AppConfig = {
  keycloak: {
    url: 'http://localhost:8181',
    realm: 'life-control-realm',
    clientId: 'life-control-client',
  },
  apiGateway: {
    url: 'http://localhost:9000',
    basePath: '/api/product',
  },
};

@Injectable({
  providedIn: 'root',
})
export class ConfigService {
  private config = signal<AppConfig | null>(null);

  readonly config$ = this.config.asReadonly();

  constructor() {}

  async loadConfig(): Promise<void> {
    const envConfig = (window as any).ENV;
    
    if (envConfig) {
      this.config.set(envConfig as AppConfig);
    } else {
      console.warn(
        'window.ENV not found, using default values'
      );
      this.config.set(DEFAULT_CONFIG);
    }
  }

  get keycloakUrl(): string {
    return this.config()?.keycloak.url ?? DEFAULT_CONFIG.keycloak.url;
  }

  get keycloakRealm(): string {
    return this.config()?.keycloak.realm ?? DEFAULT_CONFIG.keycloak.realm;
  }

  get keycloakClientId(): string {
    return this.config()?.keycloak.clientId ?? DEFAULT_CONFIG.keycloak.clientId;
  }

  get apiGatewayUrl(): string {
    return this.config()?.apiGateway.url ?? DEFAULT_CONFIG.apiGateway.url;
  }

  get apiBasePath(): string {
    return this.config()?.apiGateway.basePath ?? DEFAULT_CONFIG.apiGateway.basePath;
  }

  get apiUrl(): string {
    return `${this.apiGatewayUrl}${this.apiBasePath}`;
  }
}
