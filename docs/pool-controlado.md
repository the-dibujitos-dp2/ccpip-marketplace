# CCPIP: pool controlado de agentes

## Propósito

Este documento define cómo se ejecutan las historias de Jira sin convertir el proyecto en un conjunto de agentes que editan, mezclan o despliegan cambios sin control. Jira mantiene el alcance y los criterios de aceptación; GitHub y el código mantienen la evidencia de implementación y pruebas.

**Fuentes auditadas el 23 de octubre de 2026**

- Backlog Jira recuperado: **184** ítems en `To Do`: 160 historias, 17 épicas y 7 tareas.
- Historias de negocio de mayor prioridad: IA de redacción/documentos/imágenes, cumplimiento y moderación, logística de entrega, reportes, analítica y permisos.
- Repositorio: Spring Boot con Java 21, React/Vite, PostgreSQL y S3Mock locales, Playwright E2E y CI en GitHub Actions.
- No hay pull requests ni issues de GitHub abiertos al momento de la auditoría.
- La rama de trabajo encontrada para CCPIP-22 queda fuera de este pool hasta que su autora o revisor la gestione; no se reutiliza como base de nuevas historias.

## Hallazgos que condicionan el trabajo

1. La instalación local del frontend estaba desactualizada respecto de `package-lock.json`. Tras `npm ci`, la suite pasó: **12 archivos y 184 pruebas**; `npm run build` y `npm audit --omit=dev --audit-level=high` también pasaron.
2. La primera ejecución de backend quedó bloqueada porque Docker Desktop no estaba disponible: Testcontainers abortó antes de ejecutar la integración. Tras iniciar Docker Desktop se repitió la suite y pasó con **725 pruebas**, sin fallos ni errores. No se interpreta el primer fallo como defecto funcional del backend.
3. Hay historias `To Do` cuyo flujo ya aparece en código, por ejemplo retiro/publicación lógica de productos y la propuesta/aprobación de historia asistida. Antes de escribir código para esas historias hay que contrastar cada criterio de aceptación con pruebas y actualizar Jira solamente con evidencia verificable.
4. Las historias de IA de imagen y documentos exigen decisiones de diseño que el backlog no resuelve por sí solo: modelo propio para correspondencia de imágenes, tipos de certificados/documentos, límites de archivos, conservación de originales y forma de explicar recomendaciones. No se deben simular esas decisiones como si fueran requisitos aprobados.

## Pool y límites de concurrencia

El pool trabaja por historia, no por módulo genérico. En cada momento puede existir **una sola historia en implementación** y hasta dos trabajos de lectura/verificación paralelos.

| Rol | Modelo objetivo | Permiso | Entrega obligatoria |
|---|---|---|---|
| Orquestador | `gpt-6.1-sol` | Lee Jira/GitHub, selecciona la siguiente historia y abre/cierra gates. No implementa a ciegas. | Ficha de historia y decisión de avanzar, bloquear o reconciliar. |
| Implementador backend o frontend | `gpt-6-luna` | Edita exclusivamente un worktree y una rama de una historia aprobada. | Diff pequeño, pruebas nuevas y comandos reproducibles. |
| QA de API y E2E | `gpt-6-luna` | No cambia producción; puede añadir pruebas en el worktree de la historia. | Casos válidos, inválidos, permisos y regresión. |
| Revisor independiente | `gpt-6-luna` | Solo lectura sobre el diff hasta que el implementador entregue. | Hallazgos priorizados contra criterios Jira. |
| DevSecOps/operaciones | `gpt-6-luna` | Valida CI, dependencias, infraestructura y despliegue; no ejecuta `apply` ni despliega. | Evidencia de checks y bloqueos operativos. |
| Simuladores por rol | `gpt-6-luna` | No escriben producción ni cambian Jira. | Escenarios E2E de productor, consumidor, acopio, distribuidor y administrador, únicamente cuando el rol exista en la historia. |

Los simuladores son críticos para comprobar que cada rol ve solo la información y acciones definidas por Jira; no introducen nuevas reglas de negocio.

## Gates obligatorios

### Gate 0 — Elegibilidad

Una historia entra al pool solo si:

- Está en Jira como `To Do` o `Ready`, no está absorbida, retirada ni duplicada.
- Tiene actor, operación y aceptación concretos.
- Sus dependencias están implementadas o se pueden probar mediante una interfaz existente.
- No existe una rama o PR abierto que cubra la misma historia.
- El orquestador identifica los componentes afectados y los casos de permiso/fracaso.

Si el código ya cubre la historia, no se reimplementa: pasa a **reconciliación** con matriz criterio → evidencia → prueba. Jira solo cambia cuando la evidencia sea suficiente.

### Gate 1 — Diseño por historia

Antes del primer cambio se registra en la ficha:

- clave y criterio de aceptación literal;
- archivos/componentes implicados;
- contrato API o comportamiento de interfaz;
- datos que se preservan y acciones prohibidas;
- pruebas unitarias, de API, UI y E2E necesarias;
- ambigüedades que requieren decisión humana.

### Gate 2 — Implementación aislada

- Un worktree desde `origin/main` y una rama `feature/<area>-CCPIP-<n>-<descripcion>`.
- Commits pequeños con la clave Jira.
- Ningún agente hace merge, push directo a `main`, borrado de datos, Terraform `apply` o despliegue.

### Gate 3 — Calidad

La historia no pasa a PR sin:

1. pruebas nuevas para el cambio y los rechazos relevantes;
2. pruebas backend y frontend según el área afectada;
3. Playwright cuando la historia sea visible para un rol;
4. revisión independiente del diff contra Jira;
5. verificación de permisos, trazabilidad y no exposición de datos ajenos.

Cuando Docker no esté disponible, se permite avanzar hasta revisión local estática y pruebas frontend, pero no se declara lista para PR hasta ejecutar las pruebas de integración que usan Testcontainers. En la auditoría inicial se recuperó Docker Desktop y la suite backend pasó con 725 pruebas.

### Gate 4 — Pull request y decisión humana

El pool puede crear una PR hacia `main` con la plantilla del repositorio y evidencia de pruebas. Cuando todas las validaciones obligatorias de GitHub hayan terminado correctamente, no exista conflicto ni revisión pendiente bloqueante, el orquestador hace `Squash and merge` y elimina la rama. El pool no despliega. Jira recibe comentarios o propuestas de estado; no se mueve automáticamente de estado sin evidencia y una decisión explícita.

## Orden de ejecución

### Carril 0 — Estabilización y trazabilidad (iniciado)

1. Mantener este plan y la matriz de historias como evidencia de `CCPIP-223`.
2. Corregir la capacidad de prueba local: Docker Desktop debe estar activo para backend/E2E.
3. Reconciliar primero historias aparentemente cubiertas por código: `CCPIP-48`, `CCPIP-49`, `CCPIP-279`, `CCPIP-280`, `CCPIP-315` y `CCPIP-316`. Cada una requiere sus pruebas y criterios, no solo coincidencia de nombres.

### Carril 1 — IA, prioridad máxima y con compuerta de alcance

- `CCPIP-144` y `CCPIP-168`: correspondencia y recomendación sobre imágenes.
- `CCPIP-176`, `CCPIP-272`, `CCPIP-281`, `CCPIP-282` y `CCPIP-364`: mejora, comparación, aceptación/rechazo y reemplazo de imágenes.
- `CCPIP-50`: análisis preliminar de certificados/documentos.

Estas historias se trabajan como una cadena, pero solo después de una ficha de decisión que no invente el modelo, proveedor, formatos o reglas de certificados. La primera entrega técnica puede ser una interfaz verificable y un estado de recomendación no bloqueante; no puede fingir que un modelo propio fue entrenado o validado si no lo fue.

### Carril 2 — Flujos operativos con dependencias explícitas

- **Cumplimiento y moderación:** `CCPIP-29`, `CCPIP-34`, `CCPIP-35`, `CCPIP-38`, `CCPIP-43`, `CCPIP-46`, `CCPIP-329`, `CCPIP-331`, `CCPIP-332`, `CCPIP-336`, `CCPIP-169`, `CCPIP-184`.
- **Entrega/distribución:** `CCPIP-59`, `CCPIP-60`, `CCPIP-61`, `CCPIP-65`, `CCPIP-68`, `CCPIP-107` a `CCPIP-110`, `CCPIP-149`, `CCPIP-317` a `CCPIP-320`, `CCPIP-393` a `CCPIP-403`.
- **Reportes/analítica:** `CCPIP-78`, `CCPIP-80`, `CCPIP-94`, `CCPIP-114`, `CCPIP-115`, `CCPIP-117`, `CCPIP-122`, `CCPIP-123`, `CCPIP-135`, `CCPIP-138`, `CCPIP-333` a `CCPIP-356`, `CCPIP-376` a `CCPIP-383`.

En cada carril se implementa primero el dato y la consulta protegida por rol; después la exportación PDF/Excel y finalmente los agregados. No se adelantan reportes que no tengan fuente de datos confiable.

### Fuera de la cola automática

Historias retiradas, absorbidas, futuras o condicionadas por reglas no aprobadas —por ejemplo servicios, logística internacional, geolocalización real, moneda o métodos de pago alternativos— se mantienen como bloqueadas. El pool no intenta “completarlas” mediante supuestos.

## Primera ficha de ejecución posterior a este plan

La siguiente ejecución de código no se elige por título ni por prioridad aislada. El orquestador debe comparar, en este orden:

1. `CCPIP-144` contra el alcance real del requisito de modelo propio y decidir si existe una especificación suficiente;
2. las seis historias de reconciliación, para evitar duplicar una capacidad ya construida;
3. la primera historia de cumplimiento, entrega o consulta con dependencias satisfechas y criterios completos.

La ficha resultante habilita un único worktree de implementación. Hasta entonces, el trabajo iniciado es la estabilización del entorno, la trazabilidad del backlog y el control de riesgos.

## Comandos de verificación observados

```text
frontend: npm ci
frontend: npm run test:coverage        # 12 archivos, 184 pruebas
frontend: npm run build                # pasa
frontend: npm audit --omit=dev --audit-level=high  # 0 vulnerabilidades
backend: ./mvnw -B test                # 725 pruebas, sin fallos ni errores
e2e: npm ci && npm run stack && npm test && npm run stack:down  # 22 pruebas Playwright, pasa y limpia contenedores
```
