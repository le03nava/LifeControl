#!/bin/sh
set -e

echo "========================================="
echo "LifeControl - Loading Configuration"
echo "========================================="

echo "[INFO] Replacing configuration placeholders..."

# Keycloak config
KEYCLOAK_URL_VAL="${KEYCLOAK_URL:-http://localhost:8181}"
KEYCLOAK_REALM_VAL="${KEYCLOAK_REALM:-life-control-realm}"
KEYCLOAK_CLIENT_ID_VAL="${KEYCLOAK_CLIENT_ID:-life-control-client}"

# API Gateway config
API_GATEWAY_URL_VAL="${API_GATEWAY_URL:-http://localhost:9000}"
API_BASE_PATH_VAL="${API_BASE_PATH:-/api/product}"

echo "[INFO] Keycloak URL: $KEYCLOAK_URL_VAL"
echo "[INFO] Keycloak Realm: $KEYCLOAK_REALM_VAL"
echo "[INFO] Keycloak Client ID: $KEYCLOAK_CLIENT_ID_VAL"
echo "[INFO] API Gateway URL: $API_GATEWAY_URL_VAL"
echo "[INFO] API Base Path: $API_BASE_PATH_VAL"

# Reemplazar placeholders
sed -i "s|KEYCLOAK_URL_PLACEHOLDER|$KEYCLOAK_URL_VAL|g" /app/public/assets/config.json
sed -i "s|KEYCLOAK_REALM_PLACEHOLDER|$KEYCLOAK_REALM_VAL|g" /app/public/assets/config.json
sed -i "s|KEYCLOAK_CLIENT_ID_PLACEHOLDER|$KEYCLOAK_CLIENT_ID_VAL|g" /app/public/assets/config.json
sed -i "s|API_GATEWAY_URL_PLACEHOLDER|$API_GATEWAY_URL_VAL|g" /app/public/assets/config.json
sed -i "s|API_BASE_PATH_PLACEHOLDER|$API_BASE_PATH_VAL|g" /app/public/assets/config.json

echo "[INFO] Configuration file contents:"
cat /app/public/assets/config.json
echo "========================================="

exec "$@"
