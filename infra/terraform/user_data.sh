#!/bin/bash
# CCPIP-23: prepara la instancia con Docker y Docker Compose (solo en el primer arranque).
set -euo pipefail

dnf update -y
dnf install -y docker
systemctl enable --now docker
usermod -aG docker ec2-user

COMPOSE_VERSION=v5.6.0
mkdir -p /usr/libexec/docker/cli-plugins
curl -fsSL "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-x86_64" \
  -o /usr/libexec/docker/cli-plugins/docker-compose
chmod +x /usr/libexec/docker/cli-plugins/docker-compose

mkdir -p /opt/ccpip
chown ec2-user:ec2-user /opt/ccpip
