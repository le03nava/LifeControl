# ============================================
# Dockerfile - LifeControl Angular App
# Multi-stage: node build -> nginx runtime (non-root)
# ============================================

# ---------- Imágenes base ----------
# Las dos son keys de `docker/.env.template`, el compose las pasa como build arg y
# `docker/scripts/tests/image-pins.test.sh` falla si un FROM, la key y el default del compose
# divergen. Un ARG declarado antes del primer FROM queda fuera de todo stage, que es justo lo que lo
# hace valer también para el segundo; el default mantiene `docker build .` funcionando fuera de compose.
ARG NODE_BASE_IMAGE=node:24.15-alpine
ARG NGINX_BASE_IMAGE=nginx:alpine

# ---------- Stage 1: Build ----------
FROM ${NODE_BASE_IMAGE} AS build

WORKDIR /app

# Cache dependencies layer.
COPY package.json package-lock.json ./
RUN npm ci

# Build (defaultConfiguration es production)
COPY . .
RUN npm run build

# ---------- Stage 2: Runtime ----------
FROM ${NGINX_BASE_IMAGE}

# ARG es por stage: debe declararse en el stage de runtime (stage 2) para que
# la revisión git quede en la imagen final y no en el stage de build descartado.
ARG GIT_COMMIT=unknown
LABEL org.opencontainers.image.revision="${GIT_COMMIT}"

COPY --from=build /app/dist/life-control-app-angular/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/nginx.conf
COPY entrypoint.sh /entrypoint.sh

# nginx corre como non-root (USER 1000:1000 abajo), por lo que:
# - /usr/share/nginx/html debe ser escribible por el entrypoint (genera env.js + patch de index.html)
# - /var/cache/nginx debe ser escribible para los temp files de nginx
RUN chmod +x /entrypoint.sh \
  && chown -R 1000:1000 /usr/share/nginx/html /var/cache/nginx

USER 1000:1000

EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=3s --retries=3 --start-period=10s \
  CMD wget -qO- http://127.0.0.1:3000/healthz >/dev/null || exit 1

ENTRYPOINT ["/entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
