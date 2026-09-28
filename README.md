# CCPIP Marketplace

Plataforma de comercio digital para la Camara de Comercio de los Pueblos Indigenas del Peru (CCPIP). Conecta a comunidades productoras de la amazonia peruana con consumidores finales, con foco en trazabilidad, historia del producto y comercio justo.

## Stack

- Frontend: React
- Backend: Java (Spring Boot)
- Base de datos: PostgreSQL

## Estructura del repositorio

```
ccpip-marketplace/
├── frontend/          React
├── backend/           Spring Boot
├── docs/              Modelo de datos, decisiones tecnicas, actas
├── .github/           Plantilla de PR, workflows de CI, CODEOWNERS
```

## Equipos

El proyecto se organiza en subequipos, pero cualquier persona puede apoyar en cualquier area:

- Backend
- Frontend
- QA
- Documentacion

## Como empezar

### Backend

```
cd backend
./mvnw spring-boot:run
```

Requiere Java 17 o superior y PostgreSQL corriendo localmente. Copia `backend/.env.example` a `backend/.env` y completa las variables.

### Frontend

```
cd frontend
npm install
npm run dev
```

Copia `frontend/.env.example` a `frontend/.env` y completa las variables (URL del backend, etc).

## Convencion de ramas

- `main`: siempre desplegable.
- `feature/<area>-CCPIP-<numero>-<descripcion-corta>`: una rama por historia de Jira.
  - Ejemplos: `feature/be-CCPIP-19-modelo-datos`, `feature/fe-CCPIP-26-registro-consumidor`
- `fix/<area>-CCPIP-<numero>-<descripcion-corta>`: para correcciones.

Cada rama debe abrir un Pull Request hacia `main` usando la plantilla de este repositorio, referenciando el numero de historia de Jira en el titulo.

## Contribuir

Ver [CONTRIBUTING.md](CONTRIBUTING.md).
