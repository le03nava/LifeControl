#!/bin/bash
# ============================================
# LifeControl - Deployment Script
# ============================================

set -e

# Docker directory
DOCKER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_status() { echo -e "${BLUE}[INFO]${NC} $1"; }
print_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
print_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
print_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Use docker compose v2 (docker-compose v1 not available in WSL2)
DOCKER_COMPOSE="docker compose"

# Default environment
ENV=${1:-dev}
COMPOSE_FILES="-f $DOCKER_DIR/docker-compose.yml"

# Add override for development
if [ "$ENV" = "dev" ] || [ "$ENV" = "development" ]; then
	COMPOSE_FILES="$COMPOSE_FILES -f $DOCKER_DIR/docker-compose.override.yml"
fi

# Add production compose file
if [ "$ENV" = "prod" ] || [ "$ENV" = "production" ]; then
	COMPOSE_FILES="$COMPOSE_FILES -f $DOCKER_DIR/docker-compose.prod.yml"
fi

# Environment file
ENV_FILE="$DOCKER_DIR/.env.$ENV"

usage() {
	echo "Usage: $0 [dev|staging|prod] [start|stop|restart|logs|status]"
	echo ""
	echo "Environments:"
	echo "  dev, development    - Development environment"
	echo "  staging, stg        - Staging environment"
	echo "  prod, production   - Production environment"
	echo ""
	echo "Commands:"
	echo "  start              - Start services"
	echo "  stop               - Stop services"
	echo "  restart            - Restart services"
	echo "  logs               - Show logs"
	echo "  status             - Show status"
	echo "  clean              - Stop and remove volumes"
	exit 1
}

check_requirements() {
	print_status "Checking requirements..."

	if [ ! -f "$ENV_FILE" ]; then
		print_error "Environment file $ENV_FILE not found!"
		print_status "Run: ./docker/scripts/setup-env.sh $ENV"
		exit 1
	fi

	if ! docker info >/dev/null 2>&1; then
		print_error "Docker is not running!"
		exit 1
	fi

	print_success "Requirements OK"
}

start_services() {
	print_status "Starting services for $ENV environment..."

	$DOCKER_COMPOSE $COMPOSE_FILES --env-file "$ENV_FILE" up -d

	print_success "Services started!"
	show_status
}

stop_services() {
	print_status "Stopping services..."
	$DOCKER_COMPOSE $COMPOSE_FILES --env-file "$ENV_FILE" down
	print_success "Services stopped!"
}

restart_services() {
	stop_services
	sleep 2
	start_services
}

show_status() {
	echo ""
	print_status "Service Status:"
	$DOCKER_COMPOSE $COMPOSE_FILES --env-file "$ENV_FILE" ps

	echo ""
	print_status "Service URLs:"
	echo "=========================================="

	case "$ENV" in
	dev | development)
		echo -e "${GREEN}API Gateway:${NC}      http://localhost:9000"
		echo -e "${GREEN}API Gateway Act:${NC} http://localhost:9001"
		echo -e "${GREEN}Keycloak:${NC}         http://localhost:8181"
		echo -e "${GREEN}Grafana:${NC}          http://localhost:3000"
		echo -e "${GREEN}Prometheus:${NC}       http://localhost:9090"
		echo -e "${GREEN}Loki:${NC}            http://localhost:3100"
		echo -e "${GREEN}Tempo:${NC}           http://localhost:3110"
		echo -e "${GREEN}Kafka UI:${NC}         http://localhost:8086"
		echo -e "${GREEN}MongoDB:${NC}         localhost:27017"
		echo -e "${GREEN}MySQL:${NC}           localhost:3307"
		;;
	staging | stg)
		echo -e "${GREEN}API Gateway:${NC}      http://localhost:9100"
		echo -e "${GREEN}API Gateway Act:${NC} http://localhost:9101"
		echo -e "${GREEN}Keycloak:${NC}         http://localhost:8281"
		echo -e "${GREEN}Grafana:${NC}          http://localhost:3100"
		echo -e "${GREEN}Prometheus:${NC}       http://localhost:9190"
		;;
	prod | production)
		echo -e "${GREEN}API Gateway:${NC}      http://localhost:9200"
		echo -e "${GREEN}API Gateway Act:${NC} http://localhost:9201"
		echo -e "${GREEN}Keycloak:${NC}         http://localhost:8381"
		echo -e "${GREEN}Grafana:${NC}          http://localhost:3200"
		echo -e "${GREEN}Prometheus:${NC}       http://localhost:9290"
		;;
	esac
	echo "=========================================="
}

show_logs() {
	SERVICE=${2:-}
	if [ -n "$SERVICE" ]; then
		$DOCKER_COMPOSE $COMPOSE_FILES --env-file "$ENV_FILE" logs -f "$SERVICE"
	else
		$DOCKER_COMPOSE $COMPOSE_FILES --env-file "$ENV_FILE" logs -f
	fi
}

clean_services() {
	print_warning "This will remove all volumes and data!"
	read -p "Are you sure? (yes/no): " -n 1 -r
	echo
	if [[ $REPLY =~ ^[Yy][Ee][Ss]$ ]]; then
		$DOCKER_COMPOSE $COMPOSE_FILES --env-file "$ENV_FILE" down -v
		print_success "Services and volumes cleaned!"
	else
		print_status "Cancelled"
	fi
}

health_check() {
	print_status "Checking health..."

	MAX_ATTEMPTS=30
	ATTEMPT=1

	while [ $ATTEMPT -le $MAX_ATTEMPTS ]; do
		if curl -sf http://localhost:9000/actuator/health >/dev/null 2>&1; then
			print_success "API Gateway is healthy!"
			return 0
		fi
		print_status "Attempt $ATTEMPT/$MAX_ATTEMPTS: Waiting for API Gateway..."
		sleep 5
		((ATTEMPT++))
	done

	print_error "Health check failed!"
	return 1
}

# Main
COMMAND=${2:-start}

case "$COMMAND" in
start)
	check_requirements
	start_services
	health_check
	;;
stop)
	stop_services
	;;
restart)
	restart_services
	;;
logs)
	show_logs "${@:3}"
	;;
status)
	show_status
	;;
clean)
	clean_services
	;;
health)
	health_check
	;;
*)
	usage
	;;
esac
