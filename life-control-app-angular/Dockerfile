# ============================================
# Dockerfile - LifeControl Angular App
# ============================================
# Este Dockerfile usa los archivos ya construidos localmente por deploy.sh
# El build se realiza fuera del contenedor para mayor control

FROM oven/bun:1-alpine AS runner

WORKDIR /app

ENV NODE_ENV=production

# Copiar archivos del build local (realizado por deploy.sh)
COPY dist/life-control-app-angular/browser /app/public
COPY package.json ./
COPY entrypoint.sh /entrypoint.sh

RUN chmod +x /entrypoint.sh

EXPOSE 3000

ENTRYPOINT ["/entrypoint.sh"]
CMD ["bun", "x", "serve", "-s", "public", "-l", "3000"]
