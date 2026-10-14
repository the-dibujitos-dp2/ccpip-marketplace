# Guia de contribucion

## Antes de empezar

1. Revisa que exista una historia o tarea en Jira (proyecto CCPIP) para lo que vas a hacer. Si no existe, creala antes de escribir codigo.
2. Asignate la historia en Jira y muevela a "In Progress".

## Flujo de trabajo

1. Actualiza tu `main` local:
   ```
   git checkout main
   git pull
   ```
2. Crea tu rama desde `main`, siguiendo la convencion:
   ```
   git checkout -b feature/<area>-CCPIP-<numero>-<descripcion-corta>
   ```
   Areas usadas: `be` (backend), `fe` (frontend), `qa`, `docs`.
3. Haz commits pequenos y descriptivos. Formato sugerido:
   ```
   CCPIP-26: agregar validacion de correo unico en registro
   ```
4. Antes de abrir el Pull Request, verifica que tu codigo compile y que las pruebas existentes pasen.
5. Abre el Pull Request hacia `main` usando la plantilla del repositorio. El titulo debe incluir el numero de historia, por ejemplo:
   ```
   CCPIP-26: Registrar consumidor final
   ```
6. Pide revision a alguien del equipo. Las PR sobre `backend/` o `frontend/` piden revision automatica segun el archivo CODEOWNERS.
7. GitHub solo deja fusionar con 1 aprobacion de un code owner, los 6 checks del CI en verde y la rama al dia con `main`; el unico metodo es "Squash and merge" y la rama se borra sola. Detalle en [docs/devsecops.md](docs/devsecops.md#4-proteger-main-ccpip-22).

## Permisos

Todos los miembros del equipo tienen acceso de escritura al repositorio. Nadie hace push directo a `main`, todo pasa por Pull Request.

## Reportar un problema

Si encuentras un bug o algo que no coincide con lo definido en Jira, crea una Task en Jira describiendo el problema antes de corregirlo, para mantener trazabilidad.
