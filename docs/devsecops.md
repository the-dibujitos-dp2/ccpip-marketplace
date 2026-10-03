# DevSecOps y ambientes (CCPIP-23)

## Ambientes

| Ambiente | Donde corre | Base de datos | Se actualiza |
|---|---|---|---|
| **local** | Laptop de cada integrante (`./mvnw spring-boot:run` + `npm run dev`) | PostgreSQL de `docker-compose.yml` en la raiz (`ccpip_dev`) | A mano |
| **ci** | Runners de GitHub Actions, efimero | Ninguna persistente | En cada PR |
| **prod** (demo para cliente y jefes de laboratorio) | EC2 en AWS Academy Learner Lab, `https://<ip-con-guiones>.sslip.io` | PostgreSQL en contenedor dentro de la EC2 (`ccpip`), volumen propio | Automatico en cada merge a `main` |

Las bases estan separadas: la de prod solo es accesible desde la red interna de Docker en la EC2 (no publica puerto) y su clave vive en el secreto `DB_PASSWORD`.

Usuarios de demostracion (uno por rol, clave `Demo1234!`): `consumidor@`, `productor@`, `sudo@`, `admin_acopio@`, `sub_admin@`, `distribuidor@` con dominio `ccpip.test`.
Ademas `sin_confirmar@ccpip.test` tiene el correo sin confirmar, para probar que el inicio de sesion lo rechaza (CCPIP-27).

## Arquitectura de prod

```
Internet ──443──▶ web (Caddy: HTTPS automatico, archivos de React, cabeceras de seguridad)
                    └── /api/* ──▶ backend (Spring Boot, perfil prod) ──▶ db (PostgreSQL 17)
```

- `infra/terraform/`: EC2 (Amazon Linux 2023, IMDSv2, disco cifrado), Security Group, Elastic IP.
- `deploy/docker-compose.yml`: los tres contenedores.
- Las imagenes se publican en GitHub Container Registry (`ghcr.io/the-dibujitos-dp2/ccpip-backend|frontend`).

**Por que asi y no ECS/EKS/OIDC:** Learner Lab no permite crear roles IAM ni proveedores OIDC y sus credenciales vencen cada ~4 h. Por eso el despliegue usa SSH con la llave `vockey` (no vence) y GHCR con el `GITHUB_TOKEN` del propio workflow. No hace falta tocar secretos de AWS para desplegar, solo para cambiar infraestructura.

## Pipeline

| Workflow | Cuando | Que hace |
|---|---|---|
| `ci.yml` | Cada PR a `main`, antes de cada deploy, y los lunes | Tests + cobertura JaCoCo, SonarQube Cloud (Quality Gate), build del frontend, `npm audit`, TruffleHog (secretos), Trivy (CVEs en dependencias, Dockerfiles y Terraform) |
| `deploy.yml` | Push a `main` | Corre CI → construye imagenes → Trivy sobre la imagen (bloquea HIGH/CRITICAL con parche) → publica en GHCR → SSH a la EC2 → `docker compose up` → prueba de humo (home + login de demo) → rollback automatico si falla → OWASP ZAP baseline (reporte en artefactos) |
| `terraform.yml` | PR que toca `infra/terraform/`, o manual | `fmt`, `validate`; manual: `plan`/`apply` con credenciales de Learner Lab |
| `dependabot.yml` | Semanal | PRs de actualizacion de Maven, npm, imagenes Docker, Actions y Terraform |

Todas las Actions estan fijadas por SHA (evita ataques de cadena de suministro por tags movidos); Dependabot las mantiene al dia.

## Configuracion inicial (una sola vez)

### 1. Infraestructura en AWS Academy

1. Entra a AWS Academy → curso → *Learner Lab* → **Start Lab** y espera el circulo verde.
2. Abre **AWS Details** → **AWS CLI: Show** y copia el bloque a `~/.aws/credentials` (o abre la terminal del lab).
3. Crea el bucket del estado de Terraform (nombre unico global):
   ```
   aws s3 mb s3://ccpip-tfstate-<algo-unico> --region us-east-1
   aws s3api put-bucket-versioning --bucket ccpip-tfstate-<algo-unico> --versioning-configuration Status=Enabled
   ```
4. Crea la infraestructura (local o con el workflow *Terraform → apply*):
   ```
   cd infra/terraform
   terraform init -backend-config="bucket=ccpip-tfstate-<algo-unico>"
   terraform apply
   ```
5. Anota la salida `app_domain` (ej. `3-91-10-20.sslip.io`).

### 2. Secretos y variables de GitHub

*Settings → Secrets and variables → Actions*

| Nombre | Tipo | De donde sale |
|---|---|---|
| `EC2_SSH_KEY` | Secret | Learner Lab → **AWS Details** → **SSH key: Download PEM** (`labsuser.pem`). Pega el contenido completo, incluidas las lineas `BEGIN`/`END`. |
| `DB_PASSWORD` | Secret | Inventala: `openssl rand -base64 24`. Si la cambias despues, hay que borrar el volumen `ccpip_pgdata`. |
| `SONAR_TOKEN` | Secret | sonarcloud.io → *My Account → Security → Generate Token*. Si falta, CI salta el analisis sin fallar. |
| `DEPLOY_HOST` | Variable | Salida `app_domain` de Terraform. |
| `TF_STATE_BUCKET` | Variable | Nombre del bucket del paso 1.3 (solo para el workflow de Terraform). |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` | Secret | Learner Lab → **AWS Details** → **AWS CLI: Show**. **Vencen al cerrar la sesion**; solo se usan para `terraform.yml`, actualizalos justo antes de correrlo. |

### 3. SonarQube Cloud

1. Entra a sonarcloud.io con GitHub e importa la organizacion `the-dibujitos-dp2` (plan Free: hasta 50k lineas privadas).
2. Analiza el repo `ccpip-marketplace` eligiendo **With GitHub Actions** (no *Automatic Analysis*, o chocara con el pipeline).
3. Verifica que `sonar.organization` y `sonar.projectKey` de `sonar-project.properties` coincidan con los que muestra Sonar.

### 4. Proteger `main`

El plan Free de GitHub no permite reglas de rama en repos privados. Opciones:

- Pedir **GitHub Team gratis para educacion** (lo solicita el profesor en https://education.github.com) o hacer el repo publico si el curso lo permite. Publico ademas habilita CodeQL, secret scanning con push protection y dependency review gratis.
- Una vez habilitado, un admin aplica la regla con:
  ```
  gh api -X POST repos/the-dibujitos-dp2/ccpip-marketplace/rulesets --input docs/ruleset-main.json
  ```
  Exige PR con 1 aprobacion (CODEOWNERS), checks de CI en verde, historial lineal y bloquea force-push y borrado de `main`.

## Operacion diaria

- **La app solo esta en linea mientras haya una sesion de Learner Lab activa**: al terminar la sesion AWS apaga la EC2; al volver a darle *Start Lab* arranca sola y Docker levanta los contenedores (`restart: unless-stopped`). Antes de una revision de sprint, inicia el lab ~5 min antes.
- Si un merge llega con el lab apagado, el deploy falla en *Preparar SSH*. Inicia el lab y usa **Re-run failed jobs**.
- Entrar al servidor: `ssh -i labsuser.pem ec2-user@<DEPLOY_HOST>` y `cd /opt/ccpip && docker compose ps` / `docker compose logs -f backend`.
- Desplegar a mano la ultima version de `main`: *Actions → Deploy → Run workflow*.
