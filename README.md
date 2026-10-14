# CCPIP Marketplace

Plataforma de comercio digital para la Cámara de Comercio de los Pueblos Indígenas del Perú (CCPIP). Conecta a comunidades productoras de la amazonía peruana con consumidores finales, con foco en trazabilidad, historia del producto y comercio justo.

## Stack

- Frontend: React
- Backend: Java (Spring Boot)
- Base de datos: PostgreSQL

## Estructura del repositorio

```
ccpip-marketplace/
├── frontend/          React
├── backend/           Spring Boot
├── docs/              Modelo de datos, decisiones técnicas, actas
├── infra/terraform/   Infraestructura en AWS Academy
├── deploy/            docker-compose del ambiente desplegado
├── .github/           Plantilla de PR, workflows de CI, CODEOWNERS
```

## Equipos

El proyecto se organiza en subequipos, pero cualquier persona puede apoyar en cualquier area:

- Backend
- Frontend
- QA
- Documentacion
- DevSecOps

## Como empezar

### Backend

```
cd backend
./mvnw spring-boot:run
```

Requiere Java 21 y Docker Desktop abierto. Antes de arrancar el backend, desde la raíz del repo:

```
docker compose up -d
```

Eso levanta PostgreSQL (`localhost:5432`), S3Mock (`localhost:9090`), que reemplaza a S3 para las imágenes en local, y Mailpit, que atrapa los correos del backend: los enlaces de verificación de las cuentas nuevas se leen en http://localhost:8025. **No hace falta ningún `.env` ni cuenta de AWS**: todo tiene valores por defecto en `backend/src/main/resources/application.properties`. Al arrancar, el backend crea las tablas, carga los datos de prueba y crea el bucket de imágenes.

Usuarios de prueba (clave `Demo1234!`): `consumidor@ccpip.test`, `productor@ccpip.test` y `productor_nuevo@ccpip.test` (tienda vacía para armarla desde cero). Ver [docs/devsecops.md](docs/devsecops.md) para los demás roles.

Problemas comunes:
- `Connection to localhost:5432 refused` o `No se pudo conectar al almacenamiento de imagenes`: Docker Desktop está cerrado o falta `docker compose up -d`.
- Fallan las migraciones tras cambiar de rama: borra la base local con `docker compose down -v` y vuelve a levantarla.

### Frontend

```
cd frontend
npm install
npm run dev
```

Abre http://localhost:5173. Las llamadas a `/api` se redirigen al backend en `localhost:8080` (configurado en `vite.config.js`), así que tampoco hace falta un `.env`.

## Convencion de ramas

- `main`: siempre desplegable.
- `<area>/CCPIP-<numero>-<palabra_significativa>`: una rama por historia de Jira.
  - Ejemplos: `be/CCPIP-19-modelo-datos`, `fe/CCPIP-26-registro-consumidor`
- `fix-<area>/CCPIP-<numero>-<descripcion-corta>`: para correcciones.

Cada rama debe abrir un Pull Request hacia `main` usando la plantilla de este repositorio, referenciando el número de historia de Jira en el título.

## Contribuir

Ver [CONTRIBUTING.md](CONTRIBUTING.md).

## Ambientes y despliegue

Cada merge a `main` se despliega automáticamente tras pasar las pruebas y los análisis de seguridad. Ver [docs/devsecops.md](docs/devsecops.md).
