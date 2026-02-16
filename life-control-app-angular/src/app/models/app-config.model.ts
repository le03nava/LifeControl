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
