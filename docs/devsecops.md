# DevSecOps y ambientes (CCPIP-23)

## Ambientes

| Ambiente | Donde corre | Base de datos | Se actualiza |
|---|---|---|---|
| **local** | Laptop de cada integrante (`./mvnw spring-boot:run` + `npm run dev`) | PostgreSQL y S3Mock de `docker-compose.yml` en la raiz (`ccpip_dev`) | A mano |
| **e2e** | `e2e/docker-compose.yml`, local o en CI | PostgreSQL en memoria (tmpfs) y S3Mock, se borran al bajar el stack | En cada corrida |
| **ci** | Runners de GitHub Actions, efimero | Ninguna persistente | En cada PR |
| **prod** (demo para cliente y jefes de laboratorio) | EC2 en AWS Academy Learner Lab, `https://<ip-con-guiones>.sslip.io` | PostgreSQL en contenedor dentro de la EC2 (`ccpip`), volumen propio | Automatico en cada merge a `main` |

Las bases estan separadas: la de prod solo es accesible desde la red interna de Docker en la EC2 (no publica puerto) y su clave vive en el secreto `DB_PASSWORD`.

Usuarios de demostracion (uno por rol, clave `Demo1234!`): `consumidor@`, `productor@`, `sudo@`, `admin_acopio@`, `sub_admin@`, `distribuidor@` con dominio `ccpip.test`.
`productor_nuevo@ccpip.test` tiene la tienda vacia, para probar que una comunidad la arma desde cero (PW-08).
Ademas `sin_confirmar@ccpip.test` tiene el correo sin confirmar, para probar que el inicio de sesion lo rechaza (CCPIP-27).

## Arquitectura de prod

```
Internet ──443──▶ web (Caddy: HTTPS automatico, archivos de React, cabeceras de seguridad)
                    └── /api/* ──▶ backend (Spring Boot, perfil prod) ──▶ db (PostgreSQL 17)
                                        └──▶ S3 (bucket privado de imagenes, con el rol de la EC2)
```

### Imagenes (CCPIP-30, CCPIP-44)

- Las fotos van a un bucket S3 **privado** (`ccpip-imagenes-<cuenta>`, cifrado, versionado). El
  navegador nunca ve el bucket: el backend las sirve en `/api/imagenes/{id}` con cache de un ano.
- El backend usa las credenciales del `LabInstanceProfile` de la EC2 (no hay llaves en el codigo
  ni en secretos). Para que los contenedores lleguen a esas credenciales, la instancia tiene
  `http_put_response_hop_limit = 2` en Terraform.
- Cada foto se valida (JPG, PNG o WebP, maximo 10 MB y 40 MP), se endereza segun su EXIF y se
  guarda en 1600 px mas una miniatura de 480 px.
- En desarrollo y en e2e se usa **S3Mock** (`adobe/s3mock`), que imita la API de S3.

### Redaccion asistida por IA (CCPIP-48, CCPIP-49)

El backend ya tiene el punto de enchufe (`AsistenteRedaccion`) y la pantalla completa. Se elige con
`CCPIP_IA_PROVEEDOR`: `ninguno` en produccion (responde `503 IA_NO_DISPONIBLE` y el productor
escribe a mano) y `simulado` solo en pruebas. El modelo real se agrega como otro proveedor; su
llave ira como secreto del servidor, nunca al navegador.

- `infra/terraform/`: EC2 (Amazon Linux 2023, IMDSv2, disco cifrado), Security Group, Elastic IP.
- `deploy/docker-compose.yml`: los tres contenedores.
- Las imagenes se publican en GitHub Container Registry (`ghcr.io/the-dibujitos-dp2/ccpip-backend|frontend`).

**Por que asi y no ECS/EKS/OIDC:** Learner Lab no permite crear roles IAM ni proveedores OIDC y sus credenciales vencen cada ~4 h. Por eso el despliegue usa SSH con la llave `vockey` (no vence) y GHCR con el `GITHUB_TOKEN` del propio workflow. No hace falta tocar secretos de AWS para desplegar, solo para cambiar infraestructura.

## Pipeline

| Workflow | Cuando | Que hace |
|---|---|---|
| `ci.yml` | Cada PR a `main`, antes de cada deploy, y los lunes | Tests del backend (JaCoCo) y del frontend (Vitest) con cobertura, SonarQube Cloud (Quality Gate con ambas coberturas), build del frontend, `npm audit`, TruffleHog (secretos), Trivy (CVEs en dependencias, Dockerfiles y Terraform), **e2e con Playwright** sobre las imagenes de produccion |
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
4. Crea el bucket de imagenes (una sola vez). Terraform no puede crearlo: AWS Academy niega
   `s3:GetBucketObjectLockConfiguration`, que el recurso `aws_s3_bucket` consulta en cada plan.
   Terraform lo lee y administra su bloqueo publico, cifrado, versionado y limpieza:
   ```
   aws s3api create-bucket --bucket ccpip-imagenes-$(aws sts get-caller-identity --query Account --output text) --region us-east-1
   ```
5. Crea la infraestructura (local o con el workflow *Terraform → apply*):
   ```
   cd infra/terraform
   terraform init -backend-config="bucket=ccpip-tfstate-<algo-unico>"
   terraform apply
   ```
6. Anota las salidas `app_domain` (ej. `3-91-10-20.sslip.io`) e `images_bucket`.

### 2. Secretos y variables de GitHub

*Settings → Secrets and variables → Actions*

| Nombre | Tipo | De donde sale |
|---|---|---|
| `EC2_SSH_KEY` | Secret | Learner Lab → **AWS Details** → **SSH key: Download PEM** (`labsuser.pem`). Pega el contenido completo, incluidas las lineas `BEGIN`/`END`. |
| `DB_PASSWORD` | Secret | Inventala: `openssl rand -base64 24`. Si la cambias despues, hay que borrar el volumen `ccpip_pgdata`. |
| `SONAR_TOKEN` | Secret | sonarcloud.io → *My Account → Security → Generate Token*. Si falta, CI salta el analisis sin fallar. |
| `DEPLOY_HOST` | Variable | Salida `app_domain` de Terraform. |
| `TF_STATE_BUCKET` | Variable | Nombre del bucket del paso 1.3 (solo para el workflow de Terraform). |
| `IMAGES_BUCKET` | Variable | Salida `images_bucket` de Terraform. Sin ella el deploy se detiene antes de tocar el servidor. |
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

## Pruebas de punta a punta (Playwright)

`e2e/` levanta el stack con **las mismas imagenes que van a produccion** (backend con perfil
`prod`, Caddy con su CSP) mas PostgreSQL y S3Mock, y recorre el flujo del prototipo como una
persona: la comunidad arma su tienda y publica un producto con fotos, historia y lote; el
comprador lo busca, filtra y lee su historia; edicion, despublicar y volver a publicar; reglas
de seguridad (otro productor recibe 404, no se publica sin lote vendible, no se borra);
sesion (CCPIP-27); accesibilidad con axe (WCAG 2.1 AA) y celular a 375 px sin scroll horizontal.

```
cd e2e
npm ci && npx playwright install chromium
npm run stack          # construye y levanta el stack en http://localhost:4173
npx playwright test    # ~3 min (incluye esperar la expiracion de sesion de 2 min)
npm run stack:down     # borra todo; el flujo necesita una base nueva en cada corrida
```

Para comparar pantallas con el Figma: `CAPTURAS=1 npx playwright test tests/capturas.spec.js`
guarda capturas en `e2e/capturas/`.

## Operacion diaria

- **La app solo esta en linea mientras haya una sesion de Learner Lab activa**: al terminar la sesion AWS apaga la EC2; al volver a darle *Start Lab* arranca sola y Docker levanta los contenedores (`restart: unless-stopped`). Antes de una revision de sprint, inicia el lab ~5 min antes.
- Si un merge llega con el lab apagado, el deploy falla en *Preparar SSH*. Inicia el lab y usa **Re-run failed jobs**.
- Entrar al servidor: `ssh -i labsuser.pem ec2-user@<DEPLOY_HOST>` y `cd /opt/ccpip && docker compose ps` / `docker compose logs -f backend`.
- Desplegar a mano la ultima version de `main`: *Actions → Deploy → Run workflow*.
