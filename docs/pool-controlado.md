# CCPIP: política operativa del pool controlado

## Estado, propósito y fuentes de verdad

Esta es la política vigente para ejecutar historias de Jira sin que agentes concurrentes mezclen cambios, inventen requisitos, modifiquen datos, realicen despliegues no autorizados o eludan revisión. **Jira define el alcance y los criterios de aceptación completos y más recientes.** El repositorio, las pruebas y GitHub aportan evidencia de implementación e integración. Figma es una referencia visual que puede estar desactualizada: sirve para inspección de experiencia y consistencia, pero nunca amplía, contradice ni sustituye a Jira. Una regla de producto no presente en Jira solo puede proponerse mediante una decisión humana identificada y enlazada a la historia; un documento redactado por agentes, o sin aprobación humana verificable, no es autoridad.

El pool admite **hasta cinco carriles de implementación independientes y activos**. No es una cola de un único worktree ni de una única historia. La concurrencia solo es válida cuando cada carril tiene una historia distinta, un alcance verificable y ningún solapamiento material de archivos, contratos o migraciones.

- **Orquestador:** `gpt-6.1-sol`.
- **Ejecutor, QA/E2E y revisor independiente:** `gpt-6-luna`.
- **Delegación y observabilidad:** toda tarea de cada carril se inicia mediante `delegate_task` desde la sesión del orquestador; así permanece visible en **Subagent Viewer**. No se sustituyen los carriles por subprocesos autónomos invisibles.
- **Límites no negociables:** ningún agente hace push directo a `main`, realiza despliegues no autorizados, ejecuta Terraform `apply`, borra datos ni cambia automáticamente el estado de Jira, salvo la asignación fallback autorizada descrita en «Automatización recurrente autorizada»; tampoco introduce reglas de producto que no estén en Jira o en una decisión humana aprobada y enlazada a Jira.

## Roles y responsabilidades

| Rol | Modelo | Responsabilidad | Límites y entrega |
|---|---|---|---|
| Orquestador | `gpt-6.1-sol` | Selecciona hasta cinco historias independientes, abre/cierra gates, mantiene el ledger de solapamientos y coordina reemplazos. | No implementa a ciegas. Entrega ficha de historia, decisión de gate y estado del carril. |
| Ejecutor frontend o backend | `gpt-6-luna` | Implementa una sola historia en su worktree aislado. | Solo toca el alcance aprobado. Entrega diff, pruebas y comandos reproducibles. |
| QA/E2E | `gpt-6-luna` | Define y ejecuta casos válidos, inválidos, de permisos y regresión. | No modifica producción fuera del worktree y alcance de la historia. Entrega evidencia de pruebas. |
| Revisor independiente | `gpt-6-luna` | Contrasta diff, criterios Jira, permisos y regresiones. | No revisa su propio cambio. Entrega aprobación o hallazgos accionables. |
| DevSecOps de apoyo | `gpt-6-luna` | Comprueba CI, dependencias e impacto de infraestructura. | No despliega ni aplica infraestructura. Entrega estado de checks y bloqueos. |

Los simuladores de rol, cuando hagan falta, son tareas `gpt-6-luna` delegadas y visibles. Solo prueban los roles presentes en la historia; no crean reglas de negocio.

## Ledger obligatorio de carriles y solapamientos

Antes de abrir un carril, el orquestador mantiene un ledger en la ficha o tablero operativo con una fila por historia activa o reservada:

| Campo | Registro obligatorio |
|---|---|
| Carril | `1` a `5`, o `apoyo` para una tarea corta no implementadora. |
| Historia y estado | Clave Jira, `preparación`, `implementación`, `QA`, `revisión`, `PR`, `bloqueada` o `liberada`. |
| Responsable y tarea | Rol, modelo y referencia de `delegate_task` visible en Subagent Viewer. |
| Base aislada | Rama, worktree y commit/base de `origin/main`. |
| Superficie de cambio | Archivos, directorios, contratos API, migraciones, componentes compartidos y pruebas previstas. |
| Riesgo de solapamiento | `ninguno`, `posible` o `material`, con la historia/carril afectado y la decisión. |
| Gate y evidencia | Gate actual, comandos, resultados, revisión y bloqueo si existe. |

Un carril no empieza implementación con riesgo **material** de solapamiento. Se debe dividir el alcance, secuenciar las historias o resolver explícitamente la dependencia antes de asignarlo. Si dos historias comparten un archivo o componente pero los cambios son realmente independientes, el ledger debe nombrar el archivo, explicar la separación y definir el orden de integración. Una CI verde en una rama hermana no prueba integración conjunta.

## Gates obligatorios

### Gate 0 — Elegibilidad y aislamiento

Una historia entra a un carril solo si:

1. está en Jira como `To Do` o `Ready`, no está retirada, absorbida o duplicada;
2. tiene actor, operación, criterios de aceptación y dependencias verificables;
3. el orquestador revisó ramas y PR existentes y clasificó la relación como independiente, dependiente, duplicada, obsoleta o bloqueada;
4. hay una superficie de cambio y matriz mínima de pruebas identificadas;
5. el ledger no muestra solapamiento material con otro carril activo.

Si el código parece cubrir la historia, se abre una tarea de **reconciliación**, no una reimplementación: cada criterio se mapea a comportamiento y prueba. Jira solo se actualiza con evidencia verificable. En la ejecución recurrente de HUs, implementar un único gap funcional real y continuar la selección criterio por criterio en el ledger; la reconciliación no cuenta como HU implementada. Si no hay HU elegible ni gap verificable, no fabricar documentación, pruebas o PR para llenar la cuota.

### Gate 1 — Ficha de implementación y trazabilidad de diseño

Antes del primer cambio, la ficha registra:

- clave Jira y criterio de aceptación literal;
- archivos, componentes, contratos API, migraciones y datos preservados;
- comportamiento permitido, acciones prohibidas, ambigüedades y decisiones humanas pendientes;
- matriz de pruebas unitarias, API, UI y E2E;
- entrada del ledger de solapamientos;
- para toda historia con interfaz visible, la **página y el frame relevante de Figma**, si están disponibles, como referencia de inspección visual.

Si alguien propone una regla de producto adicional o contradictoria con Jira, la ficha debe citar una decisión humana identificada —comentario, adjunto o decisión enlazada desde Jira— y nombrar a quien la aprobó. Si falta o entra en conflicto con Jira, se bloquea la propuesta; no se bloquea la historia definida por Jira. Un documento de agente o no aprobado no resuelve esa propuesta.

El archivo Figma proporcionado para DP2 es una **referencia visual no autoritativa**. La ficha identifica el frame concreto cuando exista para registrar la comparación visual. Si Figma no contiene el frame, está desactualizado o contradice Jira, se implementa Jira y se registra el drift visual para reconciliación de diseño posterior. No se infieren requisitos de producto a partir de una pantalla.

### Gate 2 — Implementación aislada

- Cada historia tiene rama y worktree propios, creados desde un `origin/main` actualizado y con la convención del repositorio.
- Cada tarea de implementación, QA, revisión o diagnóstico de carril se crea con `delegate_task` desde el orquestador.
- Los cambios se limitan al alcance de la ficha y se registran inmediatamente en el ledger si amplían la superficie de archivos o contratos.
- Los commits llevan la clave Jira y son revisables; no se hace push directo a `main`, despliegue no autorizado ni cambio automático del estado de Jira, salvo la asignación fallback autorizada descrita en «Automatización recurrente autorizada», ni operación destructiva. La integración queda exclusivamente en Gate 4.

### Gate 3 — Calidad y revisión independiente

No se propone una PR sin:

1. pruebas nuevas o ajustadas que demuestren aceptación y rechazos relevantes;
2. pruebas backend, frontend y E2E según el área afectada;
3. verificación de permisos, trazabilidad y no exposición de datos ajenos;
4. revisión independiente de `gpt-6-luna` contra Jira y diff;
5. para historias con interfaz visible, comparación visual documentada contra el frame Figma disponible de Gate 1 en **desktop y mobile**, antes de abrir la PR;
6. registro del drift visual observado. El drift no bloquea ni modifica la historia cuando Figma difiere de Jira: se implementan los criterios Jira y se deja el registro para reconciliación posterior. Una preferencia del implementador tampoco justifica apartarse de Jira.

La falta de Docker permite análisis estático o pruebas que no lo necesiten, pero bloquea la declaración de lista para PR cuando faltan integraciones con Testcontainers. Un fallo de entorno se registra como bloqueo operativo, no como defecto del producto, después de intentar reproducirlo con la precondición correcta.

### Gate 4 — PR, CI e integración

Una PR solo puede abrirse con la evidencia de Gate 3 y debe declarar historia Jira, archivos modificados, pruebas, frame Figma cuando aplique y drift visual observado. Si incluye una regla de producto adicional ajena a Jira, debe adjuntar la decisión humana aprobada y enlazada a la historia; nunca se presenta como una desviación aceptada por el agente.

La integración exige, en este orden:

1. checks requeridos de GitHub terminados correctamente;
2. revisión independiente aprobada y sin hallazgos bloqueantes;
3. ausencia de conflictos y de solapamientos nuevos contra la base actual;
4. para PRs hermanas o apiladas, rebase o integración en orden de dependencia y CI contra la base integrada;
5. lectura final de estado de merge, revisiones y check rollup.

Solo cuando CI requerida esté verde, no haya conflicto ni hallazgo bloqueante de revisión, y el ledger registre la evidencia de los cinco puntos anteriores, el orquestador ejecuta `Squash and merge` conforme a los permisos y la política del repositorio. Esa evidencia es el límite de auditoría del orquestador; ningún otro agente hace merge. El merge no autoriza por sí solo el despliegue ni el cambio automático de estado en Jira. El despliegue por `push` a `main` queda autorizado únicamente bajo la autorización recurrente explícita descrita en «Automatización recurrente autorizada» y solo tras superar todos los gates de integración y producción. Si algún gate falla, la PR vuelve al carril de corrección y el ledger registra el motivo.

### Automatización recurrente autorizada

Existe autorización recurrente explícita de Alonso, concedida en sesión desktop, para revisión, merge y seguimiento del despliegue por `push` cada hora, y para avanzar una historia de usuario (HU) cada dos horas. La autorización no elimina gates ni amplía el alcance de cada HU; no autoriza Terraform `apply`, push directo a `main`, cambios automáticos de estado Jira salvo la asignación fallback autorizada al seleccionar una HU pendiente, ni operaciones destructivas. La autorización de despliegue se limita al flujo verificado de merge y sus controles de producción; no se lanza manualmente un segundo despliegue ni se cancela preventivamente el despliegue autorizado.

- Antes de cada ciclo, usar claims atómicos y ledger persistente con owner/run/session/PID comprobables, head/base, fase, archivos reservados y timestamp. Actualizar checkpoint tras cada fase. Los jobs pueden coexistir, pero no pueden reservar superficies de cambio solapadas: solo hay un escritor de integración. Verificar claims existentes; no robar uno vivo ni borrar trabajo ajeno. Reanudar un claim huérfano desde su checkpoint tras inspeccionar worktree, PR y artefactos. Liberar solo claims propios con evidencia en `runs/`.
- El job horario revisa PRs, ejecuta revisión independiente, integra únicamente al superar los gates obligatorios y sigue el despliegue autorizado hasta verificar merge, `mergedAt`/commit, evento `push` con `headSha` exacto, CI, análisis de ambas imágenes, EC2, smoke y DAST. Inspeccionar advertencias y rollback; merge o inicio de run no equivalen a producción verificada.
- El job cada dos horas trabaja en una sola HU por ejecución: reanuda primero la propia incompleta; si no hay una, prioriza HU pendiente asignada a Alonso y, en su defecto, puede seleccionar una HU pendiente del backlog no iniciada y sin PR abierta, reasignándola a Alonso y leyendo de vuelta el assignee. Comprobar Jira vivo, dependencias, actividad, ramas, worktrees, claims y duplicados. Publica una PR tras sus pruebas, QA y revisión independiente; **no fusiona**. No modifica estados Jira. Si no hay HU elegible o está bloqueada, no inventa alcance ni trabajo para llenar la cuota.
- Todas las tareas de implementación, QA/E2E y revisión se ejecutan como lanes separados con `delegate_task`; orquestador `gpt-6.1-sol`, delegates `gpt-6-luna`. Registrar y evitar riesgos de solapamiento antes de delegar.
- Estos jobs requieren el host despierto, gateway activo y accesos/herramientas disponibles. Una ejecución no realizada o incompleta no se reporta como éxito; conservar el checkpoint y comunicar fallos reales.

### Prevención de despliegue cuando no está autorizado

Antes de cada merge se inspeccionan los triggers reales de `.github/workflows`. Si no existe autorización explícita vigente para el despliegue, la autorización de integración no autoriza los efectos de un workflow disparado por `push` a `main`.

- Si el merge activa `Deploy`, el orquestador debe tener preparado un control acotado a su SHA de merge: identificar y cancelar esa ejecución antes de que empiecen los jobs de imágenes/publicación o EC2. Leer de vuelta su conclusión y los pasos de esos jobs; una solicitud de cancelación por sí sola no prueba que se detuvieron a tiempo.
- Ejecutar después únicamente `ci.yml` mediante `workflow_dispatch` sobre `main`, comprobar que `headSha` coincide con la SHA recién integrada y verificar todos los checks y el análisis Sonar de esa revisión. La cancelación preventiva de `Deploy` no se presenta como CI aprobada ni sustituye ese CI independiente.
- No desactivar workflows, cambiar triggers/protecciones, omitir scanners ni alterar credenciales para lograrlo. Si el control no puede garantizarse o un paso de despliegue ya comenzó, detener nuevas integraciones y comunicar el estado exacto; no iniciar ni revertir despliegues por cuenta propia.
- Guardar por separado las evidencias de PR verde, merge, cancelación preventiva sin pasos de despliegue ejecutados y CI/Sonar posterior. Nunca extrapolar a `main` el resultado de la PR por identidad del árbol.

## Regla de relevo y uso del pool

El pool mantiene hasta cinco carriles implementadores independientes cuando haya historias claras y sin solapamiento material. Las tareas de reconocimiento, CI o revisión son apoyo breve: no deben ocupar la capacidad destinada a historias listas para implementar.

Cuando un trabajador termina, el orquestador debe hacer primero una de estas acciones, en este orden:

1. avanzar su historia al siguiente paso necesario —corrección, QA, revisión o PR— mediante una tarea delegada y visible; o
2. liberar el carril y reemplazarlo de inmediato por la siguiente historia elegible e independiente.

No se mantiene un carril ocioso para abrir exploración adicional mientras exista una historia lista. Un carril bloqueado se registra con el bloqueo concreto; si no puede desbloquearse sin decisión humana, se libera y se sustituye. El orquestador vuelve a evaluar el ledger antes de cada reemplazo.

## Mapa actual de páginas Figma

El siguiente mapa es de navegación y trazabilidad; no añade requisitos funcionales. En Gate 1 se debe identificar la página y el frame concreto aplicable.

| Página Figma | Uso de trazabilidad |
|---|---|
| `00 Portada` | Referencia general del archivo. |
| `01 Design System` | Componentes, tokens y patrones visuales. |
| `02 Consumidor final` | Flujos del consumidor final. |
| `03 Productor` | Flujos del productor. |
| `04 Admin punto de acopio` | Flujos de administración de punto de acopio. |
| `05 Sub administrador` | Flujos de subadministración. |
| `06 Super administrador` | Flujos de superadministración. |
| `07 Distribuidor` | Flujos de distribución. |
| `08 Changelog` | Cambios documentados en diseño. |
| `09 UX` | Personas, journeys y arquitectura UX. |

## Evidencia histórica de línea base — no es instrucción vigente

Esta sección conserva la evidencia observada durante la auditoría del **23 de octubre de 2026**. Sus conteos, estado de PR, ramas y prioridad eran una fotografía de ese momento; no describen el estado actual del backlog ni autorizan seleccionar una “primera” historia o limitar el pool a una implementación.

- Backlog recuperado entonces: **184** ítems en `To Do`: 160 historias, 17 épicas y 7 tareas.
- Se registraron como áreas prioritarias IA de redacción/documentos/imágenes, cumplimiento y moderación, logística de entrega, reportes, analítica y permisos.
- El repositorio se describió como Spring Boot con Java 21, React/Vite, PostgreSQL y S3Mock locales, Playwright E2E y CI en GitHub Actions.
- En esa auditoría no había pull requests ni issues de GitHub abiertos, y una rama de `CCPIP-22` se dejó fuera del pool hasta gestión de su autora o revisor. Ese dato no es una condición operativa actual.
- Tras `npm ci`, frontend registró **12 archivos y 184 pruebas**; `npm run build` y `npm audit --omit=dev --audit-level=high` pasaron.
- La primera ejecución backend se bloqueó por Docker Desktop no disponible; al repetir con Docker disponible, se registraron **725 pruebas** sin fallos ni errores.
- Se identificaron historias `To Do` aparentemente cubiertas por código y ambigüedades de producto para IA de imágenes/documentos. Siguen sujetas a Gate 0 y reconciliación; esta observación no equivale a requisitos aprobados.

### Comandos observados en la línea base

```text
frontend: npm ci
frontend: npm run test:coverage        # 12 archivos, 184 pruebas
frontend: npm run build                # pasó
frontend: npm audit --omit=dev --audit-level=high  # 0 vulnerabilidades
backend: ./mvnw -B test                # 725 pruebas, sin fallos ni errores
e2e: npm ci && npm run stack && npm test && npm run stack:down  # 22 pruebas Playwright, pasó y limpió contenedores
```
