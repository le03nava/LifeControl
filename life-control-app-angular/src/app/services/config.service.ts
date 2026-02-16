import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { AppConfig } from '../models/app-config.model';
import { firstValueFrom } from 'rxjs';

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

  constructor(private http: HttpClient) {}

  async loadConfig(): Promise<void> {
    try {
      const config = await firstValueFrom(
        this.http.get<AppConfig>('/assets/config.json')
      );
      this.config.set(config);
    } catch (error) {
      console.warn(
        'Failed to load config.json, using default values:',
        error
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
