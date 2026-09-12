export interface KeycloakConfig {
  url: string;
  realm: string;
  clientId: string;
}

export interface ApiGatewayConfig {
  url: string;
  basePath: string;
}

export interface AppConfig {
  keycloak: KeycloakConfig;
  apiGateway: ApiGatewayConfig;
}

export interface EnvConfig {
  KEYCLOAK_URL?: string;
  KEYCLOAK_REALM?: string;
  KEYCLOAK_CLIENT_ID?: string;
  API_GATEWAY_URL?: string;
  API_BASE_PATH?: string;
}

declare global {
  interface Window {
    env?: EnvConfig;
  }
}
