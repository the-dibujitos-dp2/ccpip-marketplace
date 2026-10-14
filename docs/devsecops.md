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

**Excepciones de CVEs:** si Trivy marca un CVE sin parche en nuestra linea de versiones y el equipo confirma que no nos afecta, se agrega a `.trivyignore` con la justificacion y una fecha de vencimiento (`exp:AAAA-MM-DD`). Al vencer, Trivy vuelve a bloquear. Hoy: dos CVEs de Spring Framework 6.2 (XsltView y SSE, que no usamos), hasta migrar a Spring Boot 4.

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

**Sin esa opcion** (no se pudo conseguir el plan), `main` queda vigilada por el [guardian](#guardian-de-main-ccpip-22).

## Guardian de main (CCPIP-22)

Mientras el repo no pueda aplicar `docs/ruleset-main.json` (ver "Proteger `main`"), nada impide
fusionar un PR con checks en rojo. El **guardian** hace lo que esa regla haria, despues del merge:
[`guardian-main.yml`](../.github/workflows/guardian-main.yml) corre al fusionar un PR a `main` y
comprueba que cumplia los **6 checks obligatorios** de `docs/ruleset-main.json` (la unica fuente de
verdad: si cambia la lista, cambia el guardian). Un check incumple si fallo, sigue pendiente o no existe;
si se re-ejecuto, vale la ultima ejecucion.

### Modos (variable del repo `GUARDIAN_MODO`)

| Valor | Que hace |
|---|---|
| `avisar` (por defecto) | Comenta en el PR la tabla de checks incumplidos y abre un issue. **No toca el codigo.** El job queda en rojo y GitHub avisa a quien fusiono. |
| `revertir` | Ademas hace `git revert` del merge y lo publica en `main`. |
| `apagado` | No hace nada. Es el interruptor de emergencia. |

Se cambia en **Settings > Secrets and variables > Actions > Variables**, o con
`gh variable set GUARDIAN_MODO --body revertir`. No hace falta tocar el codigo.

### Salvaguardas del modo `revertir`

- Solo `git revert`: agrega un commit y conserva el historial. Nunca `--force`, nunca borra ramas.
  Un revert equivocado se corrige revirtiendo el revert.
- **Solo revierte si el merge sigue siendo la punta de `main`.** Si alguien fusiono algo encima, revertir sin
  pasar por el CI podria dejar `main` rota sin que se note: en ese caso solo avisa.
- **Nunca revierte el commit inicial** (borraria todo el contenido del repositorio).
- **Antes de publicar comprueba que el contenido queda IDENTICO al de antes del PR** (el arbol de git del revert es el del
  primer padre del merge); si no, no publica nada.
- **No revierte un `rebase and merge` de varios commits**: revertir solo el ultimo dejaria el PR a medias. Solo revierte un
  merge commit, un PR de un commit o un squash (commit con `(#N)` del mismo PR).
- Si el revert no se puede aplicar, o el push es rechazado (alguien se adelanto, o el PR tocaba workflows), no hace nada mas que avisar.
- Antes de revertir deja la etiqueta `respaldo/main-<sha>` en el commit que se revierte.
- Un merge commit se revierte contra su primer padre (`-m 1`); un squash, directo.
- **Valida todo lo que recibe**: la rama (`main`, o `prueba/*` en un simulacro, y debe ser un nombre de rama valido de git) y los
  SHAs, el numero de PR y el de commits (que terminan como argumentos de `git` y `gh`).
- **Si la API de GitHub falla, reintenta** (4 intentos, esperando 3, 6 y 9 segundos); si sigue fallando, termina en error sin actuar.
- Dejar pasar un PR a proposito: ponle la etiqueta **`forzar-merge`** (sin distinguir mayusculas) antes de fusionar.

### Limites (hay que conocerlos)

- No impide el boton de merge: el codigo con checks fallidos **llega a `main` unos segundos** y luego se avisa o se revierte.
- El push del guardian usa el `GITHUB_TOKEN`, que **no dispara** el CI ni el deploy. Lo normal es que no haga falta:
  un PR con tests rotos tambien falla el CI de `deploy.yml` sobre `main` (`ci` -> `images` -> `deploy`, cada paso exige el
  anterior), asi que **nunca se despliega** y el servidor sigue con la version anterior. La excepcion es un PR que el
  guardian marca (por ejemplo, se fusiono con un check aun pendiente que luego pasa) pero cuyo CI en `main` si pasa: se despliega
  y luego se revierte. Por eso, **tras cada revert el guardian pide un despliegue de `main`** (`gh workflow run deploy.yml`,
  que ya admite `workflow_dispatch`): el servidor queda igual que `main` y el CI corre sobre el revert. Si no puede pedirlo, lo
  dice en el aviso y el revert se mantiene; entonces hay que ejecutar *Deploy* a mano.
- Si el PR tocaba `.github/workflows/`, el token no puede escribir esos archivos: el revert falla y solo se avisa.
- Un PR que borre `guardian-main.yml` lo desactiva. Es la misma debilidad de cualquier regla sin proteccion real.
- Es un respaldo, no una sustitucion: con GitHub Team o repo publico, aplica el ruleset y apaga el guardian.

### Pruebas

Todo se ejecuta **sin escribir en GitHub**. Hace falta bash, git y Python 3 (y `gh` con sesion para la parte de la API real).

| Que | Como | Que prueba |
|---|---|---|
| Escenarios | `bash .github/guardian/probar.sh` | Repos de juguete y un `gh` falso: 34 escenarios, 229 comprobaciones. Checks pendientes, cancelados y re-ejecutados; modos; merge commit y squash; commits posteriores; push rechazado; **dos guardianes a la vez** y **otro PR fusionandose justo antes del push**; **ramas, SHAs, numeros y titulos de commit hostiles** (inyeccion); el commit inicial; PRs vacios; tipos de cambio (binarios, renombres, permisos, CRLF); etiquetas; locales; reintentos de la API; ensayo en seco; seguridad estatica del workflow; coherencia con `ci.yml` y el ruleset; `.gitattributes` con `core.autocrlf=true` |
| Unitarias | `python3 -m unittest discover -s .github/guardian -v` | 24 pruebas del evaluador de checks, con **todas** las combinaciones de estado y conclusion que GitHub puede informar |
| Historial real | `bash .github/guardian/probar-historial.sh` | **Parte 1 (solo git):** revierte cada commit real de `main` (46 hoy, squash y merge commits, el de 199 archivos y los 8 que tocan workflows) y comprueba que queda el contenido exacto de antes. **Parte 2 (API de GitHub, solo lectura):** corre el guardian real en seco sobre los 40 PRs fusionados y sobre commits que fallaron de verdad (PR #37 y #39) |
| Linux | las mismas ordenes dentro de un contenedor `ubuntu:24.04` (el sistema de `ubuntu-latest`) con git y python3 | Sin las rarezas de Git Bash en Windows |
| Linters | `actionlint`, `shellcheck`, `pyflakes` | Sintaxis del workflow y de los scripts |

`--sin-red` y `--solo-red` limitan `probar-historial.sh` a su parte 1 o 2. En la parte 2 un `gh` envoltorio **bloquea** cualquier
llamada que no sea leer check-runs, de modo que ni un error del guardian podria escribir en GitHub.

- **Ensayo en seco** (`GUARDIAN_SECO=1`): el guardian lee de GitHub y prueba el revert en local, pero no escribe nada (ni comentarios,
  issues, etiquetas, pushes ni despliegues); imprime lo que haria.
- **Simulacro en GitHub:** Actions > *Guardian de main* > *Run workflow*, con una rama `prueba/*`. Finge checks incumplidos
  sobre esa rama, sin PR, issues ni despliegues, y en modo `revertir` revierte **solo esa rama**. El workflow se niega a
  actuar sobre `main` y sobre cualquier rama que no empiece por `prueba/`.

**Lo que mostraron los datos reales** (13 de octubre de 2026): de los 40 PRs fusionados, los 28 desde el #16 (cuando el CI ya tenia los 6 checks)
cumplen los 6, asi que el guardian no habria marcado ninguno; los 12 anteriores no, porque el CI de entonces no tenia todos los checks.
Los fallos reales se detectan: el primer commit de los PRs #37 y #38 (backend en rojo por la zona horaria, Sonar omitido) y el primero
del #39 (Sonar en rojo por duplicacion), no sus commits correctivos.

### Evidencia para cerrar CCPIP-22

La historia pide que "un fallo bloquee la integracion" y que no se afirme una implementacion que no existe. Con el guardian
se puede afirmar lo siguiente, y solo cuando cada punto este comprobado:

1. **El CI verifica** construccion, analisis y pruebas en cada PR (`ci.yml`): los 6 checks.
2. **El guardian esta probado** sin tocar GitHub: `probar.sh`, las unitarias y `probar-historial.sh`, en Windows y en Linux.
3. **Un PR fusionado sin cumplirlos se detecta** (modo `avisar`): comentario e issue en el PR. Evidencia en vivo: un PR de prueba con un test roto.
4. **Se deshace** (modo `revertir`): el merge se revierte, queda el respaldo y se redespliega `main`. Evidencia en vivo: el mismo PR de prueba en ese modo.
5. **Lo que no se puede**: impedir el boton de merge (requiere plan de pago). Si algun dia se consigue, aplicar `docs/ruleset-main.json` y apagar el guardian.

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
