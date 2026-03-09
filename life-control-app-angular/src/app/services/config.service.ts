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
    basePath: '/api/products',
  },
};

@Injectable({
  providedIn: 'root',
})
export class ConfigService {
  // Inicializar con valores por defecto de forma síncrona
  private config = signal<AppConfig>(DEFAULT_CONFIG);

  readonly config$ = this.config.asReadonly();

  constructor() {}

  async loadConfig(): Promise<void> {
    const envConfig = (window as any).env;
    
    if (envConfig) {
      const runtimeConfig: AppConfig = {
        keycloak: {
          url: envConfig.KEYCLOAK_URL || DEFAULT_CONFIG.keycloak.url,
          realm: envConfig.KEYCLOAK_REALM || DEFAULT_CONFIG.keycloak.realm,
          clientId: envConfig.KEYCLOAK_CLIENT_ID || DEFAULT_CONFIG.keycloak.clientId,
        },
        apiGateway: {
          url: envConfig.API_GATEWAY_URL || DEFAULT_CONFIG.apiGateway.url,
          basePath: envConfig.API_BASE_PATH || DEFAULT_CONFIG.apiGateway.basePath,
        },
      };
      this.config.set(runtimeConfig);
      console.log('[ConfigService] Runtime config loaded:', runtimeConfig);
    } else {
      console.warn('[ConfigService] window.env not found, using default values');
    }
  }

  get keycloakUrl(): string {
    return this.config().keycloak.url;
  }

  get keycloakRealm(): string {
    return this.config().keycloak.realm;
  }

  get keycloakClientId(): string {
    return this.config().keycloak.clientId;
  }

  get apiGatewayUrl(): string {
    return this.config().apiGateway.url;
  }

  get apiBasePath(): string {
    return this.config().apiGateway.basePath;
  }

  get apiUrl(): string {
    const url = `${this.apiGatewayUrl}${this.apiBasePath}`;
    console.log('[ConfigService] apiUrl accessed:', url);
    return url;
  }
}
