# CCPIP-23: build de Vite y servidor Caddy (HTTPS automatico + proxy de /api)
FROM node:26-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM caddy:2-alpine
# Caddy sin root: el binario ya trae cap_net_bind_service para usar 80/443.
# Certificados y config en rutas propias (montadas como volumenes en deploy/docker-compose.yml)
ENV XDG_DATA_HOME=/caddy/data \
    XDG_CONFIG_HOME=/caddy/config
RUN addgroup -S web && adduser -S web -G web \
    && mkdir -p /caddy/data /caddy/config \
    && chown -R web:web /caddy
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist /srv
USER web
