# CCPIP-23: build de Vite y servidor Caddy (HTTPS automatico + proxy de /api)
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# Caddy compilado con Go y x/net parchados. La imagen oficial caddy:2.11.7 trae Go 1.26.8 y
# x/net 0.59 (CVE-2026-78667, 78669 y 97031, HIGH). Mismo Caddy y mismos modulos estandar;
# volver a FROM caddy:2-alpine solo cuando la imagen oficial ya traiga Go >= 1.26.9.
FROM golang:1.26-alpine AS caddy
ARG CADDY_VERSION=v2.11.7
WORKDIR /src
RUN go mod init ccpip-caddy \
    && printf 'package main\n\nimport (\n\tcaddycmd "github.com/caddyserver/caddy/v2/cmd"\n\t_ "github.com/caddyserver/caddy/v2/modules/standard"\n)\n\nfunc main() { caddycmd.Main() }\n' > main.go \
    && go get github.com/caddyserver/caddy/v2@${CADDY_VERSION} golang.org/x/net@v0.60.0 \
    && go mod tidy \
    && CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o /caddy .

FROM caddy:2-alpine
COPY --from=caddy /caddy /usr/bin/caddy
# El binario oficial trae cap_net_bind_service; al reemplazarlo hay que volver a darsela
RUN apk add --no-cache libcap \
    && setcap cap_net_bind_service=+ep /usr/bin/caddy \
    && apk del libcap
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
