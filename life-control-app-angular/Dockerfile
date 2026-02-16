FROM oven/bun:1-alpine AS builder

WORKDIR /app

COPY package.json bun.lock ./
RUN bun install

COPY . .

RUN bun run build

FROM oven/bun:1-alpine AS runner

WORKDIR /app

ENV NODE_ENV=production

COPY --from=builder /app/dist/life-control-app-angular/browser /app/public
COPY --from=builder /app/package.json ./
COPY entrypoint.sh /entrypoint.sh

RUN chmod +x /entrypoint.sh

EXPOSE 3000

ENTRYPOINT ["/entrypoint.sh"]
CMD ["bun", "x", "serve", "-s", "public", "-l", "3000"]
