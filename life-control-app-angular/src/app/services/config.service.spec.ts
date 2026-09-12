import { TestBed } from '@angular/core/testing';
import { ConfigService } from './config.service';
import { AppConfig, EnvConfig } from '../models/app-config.model';

const DEFAULT_CONFIG: AppConfig = {
  keycloak: {
    url: 'http://localhost:8181',
    realm: 'life-control-realm',
    clientId: 'life-control-client',
  },
  apiGateway: {
    url: 'http://localhost:9000',
    basePath: '/api',
  },
};

describe('ConfigService', () => {
  let service: ConfigService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [ConfigService] });
    service = TestBed.inject(ConfigService);
  });

  afterEach(() => {
    delete window.env;
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('loadConfig', () => {
    it('should read window.env and set the config signal', async () => {
      const env: EnvConfig = {
        KEYCLOAK_URL: 'https://kc.example.com',
        KEYCLOAK_REALM: 'custom-realm',
        KEYCLOAK_CLIENT_ID: 'custom-client',
        API_GATEWAY_URL: 'https://api.example.com',
        API_BASE_PATH: '/v2',
      };
      window.env = env;

      await service.loadConfig();

      expect(service.config$()).toEqual({
        keycloak: {
          url: 'https://kc.example.com',
          realm: 'custom-realm',
          clientId: 'custom-client',
        },
        apiGateway: {
          url: 'https://api.example.com',
          basePath: '/v2',
        },
      });
      expect(service.keycloakUrl).toBe('https://kc.example.com');
      expect(service.keycloakRealm).toBe('custom-realm');
      expect(service.keycloakClientId).toBe('custom-client');
      expect(service.apiGatewayUrl).toBe('https://api.example.com');
      expect(service.apiBasePath).toBe('/v2');
    });

    it('should fall back to default values for missing env properties', async () => {
      window.env = {
        API_BASE_PATH: '/custom/api',
      };

      await service.loadConfig();

      expect(service.config$()).toEqual({
        keycloak: DEFAULT_CONFIG.keycloak,
        apiGateway: {
          url: DEFAULT_CONFIG.apiGateway.url,
          basePath: '/custom/api',
        },
      });
    });

    it('should keep default values when window.env is not present', async () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
      delete window.env;

      await service.loadConfig();

      expect(service.config$()).toEqual(DEFAULT_CONFIG);
      expect(warnSpy).toHaveBeenCalledWith(
        '[ConfigService] window.env not found, using default values',
      );
      warnSpy.mockRestore();
    });
  });

  describe('apiUrl', () => {
    it('should concatenate apiGatewayUrl and basePath', () => {
      window.env = {
        API_GATEWAY_URL: 'https://api.example.com',
        API_BASE_PATH: '/api',
      };

      void service.loadConfig();

      expect(service.apiUrl).toBe('https://api.example.com/api');
    });

    it('should use default values when window.env is not present', () => {
      delete window.env;

      expect(service.apiUrl).toBe('http://localhost:9000/api');
    });
  });
});
