docker pull mysql:8.3.0
docker pull quay.io/keycloak/keycloak:24.0.1
docker pull grafana/loki:3.7.8
docker pull prom/prometheus:v2.46.0
docker pull grafana/tempo:2.2.2
docker pull grafana/grafana:10.1.0
docker pull saiupadhyayula007/new-api-gateway:latest

kind load docker-image -n microservices mysql:8.3.0
kind load docker-image -n microservices quay.io/keycloak/keycloak:24.0.1
kind load docker-image -n microservices grafana/loki:3.7.8
kind load docker-image -n microservices prom/prometheus:v2.46.0
kind load docker-image -n microservices grafana/tempo:2.2.2
kind load docker-image -n microservices grafana/grafana:10.1.0
kind load docker-image -n microservices saiupadhyayula007/new-api-gateway:latest