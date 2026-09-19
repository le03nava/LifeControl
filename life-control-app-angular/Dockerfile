# ============================================
# Dockerfile - LifeControl Angular App
# Multi-stage: node build -> nginx runtime (non-root)
# ============================================

# ---------- Stage 1: Build ----------
FROM node:22-alpine AS build

WORKDIR /app

# Cache dependencies layer.
# --legacy-peer-deps: el proyecto fija zone.js ^0.16.2, fuera del peerOptional
# ~0.15.0 de @angular/core, así que el resolver estricto de npm fallaría.
COPY package.json package-lock.json ./
RUN npm ci --legacy-peer-deps

# Build (defaultConfiguration es production)
COPY . .
RUN npm run build

# ---------- Stage 2: Runtime ----------
FROM nginx:alpine

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
