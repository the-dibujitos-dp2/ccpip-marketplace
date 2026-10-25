# CCPIP-138 — trazabilidad de reconciliación (backend)

## Alcance y fuente

Jira `CCPIP-138` está en **To Do**, asignada a **JOSE ALONSO REYES SAMANIEGO**. El texto literal validado para esta reconciliación es: «Como superadministrador quiero consultar el estado operativo agregado de los puntos de acopio para obtener un resultado comprobable de CCPIP». Requisito funcional **RF-115**: «El sistema debe permitir al superadministrador consultar el estado operativo agregado de los puntos de acopio». Aceptación: sin edición de stock; información por permisos. La verificación se limita a esta operación, permisos, un resultado válido y el rechazo de un caso inválido; no añade requisitos no especificados.

Fuente de trazabilidad: Planificado, Modificado 35; origen 138. No se identifican requisitos no funcionales adicionales.

La implementación se limita al backend existente. No se modifica `TableroPage` ni se infiere un significado adicional para “estado operativo”. Figma `06 Super administrador` no está disponible en el material inspeccionado; no se usa para deducir comportamiento.

## Reconciliación por criterio

| Criterio recibido | Implementación y prueba en `origin/main` | Resultado |
|---|---|---|
| Superadministrador consulta el estado operativo agregado de los puntos de acopio | `GET /api/admin/tablero`, protegido con `@RolesPermitidos(Rol.SUPER_ADMIN)` en `backend/src/main/java/pe/ccpip/administracion/TableroController.java:40-42`; lista agregada `puntos` en `TableroModelos.java:25-26, 50-52`; consulta punto-a-punto de totales en `TableroRepository.java:108-128`. `SuperAdministradorTest.elSuperadministradorVeElTableroAgregado` verifica respuesta 200 y presencia del nombre de un punto. | Cubierto en main. |
| Sin edición de stock | `TableroController` expone solo GET. Los endpoints de movimientos/acopio exigen rol `ADMIN_PUNTO` (`MovimientosController.java:26-31`). `SuperAdministradorTest.noEditaTiendasProductosPedidosDatosDeConsumidoresNiExistencias` verifica rechazo 403 de movimientos, alta de lotes y operaciones sensibles; `PermisosTest.laMatrizNoPermiteCambiosYElSuperadministradorNoEditaDatosOperativos` comprueba rechazos y que la matriz no ofrece escrituras operativas al superadministrador. | Cubierto en main. |
| Información por permisos | La ruta requiere `SUPER_ADMIN`. `SuperAdministradorTest.elTableroEsSoloDelSuperadministrador` verifica 403 para subadministrador, consumidor, productor, administrador de punto y distribuidor; `sinSesionNoHayTablero` verifica 401. | Cubierto en main. |
| Caso válido e inválido | Válido: `SuperAdministradorTest.elSuperadministradorVeElTableroAgregado` (superadministrador autenticado, HTTP 200). Inválidos: `elTableroEsSoloDelSuperadministrador` (roles autenticados no autorizados, HTTP 403) y `sinSesionNoHayTablero` (HTTP 401). | Cubierto en main. |

## Decisión de implementación

La comparación del comportamiento existente con los criterios literales validados no revela una carencia funcional backend que se pueda implementar sin inventar el significado de “estado operativo”. Por tanto no se modifica producción ni se duplica cobertura. El cambio de este carril es únicamente esta trazabilidad. La cobertura descrita documenta la operación backend; no afirma que Jira esté completado.

## Evidencia ejecutada

Base inspeccionada: `5a9f0ad2fdeb6bfabebea8bf8d5a2aee764ff9f4`. Ticket: https://dp2-dibuj.atlassian.net/browse/CCPIP-138.

Desde `backend/`, con Docker/Testcontainers disponible:

```sh
./mvnw -B -Dtest=SuperAdministradorTest,PermisosTest test
```

Resultado verificado en los XML de Surefire: `SuperAdministradorTest` 12 pruebas y `PermisosTest` 9 pruebas; 21 en total, sin fallos, errores ni omitidas. `git diff --check` pasó. No se modifica interfaz, por lo que no se afirma una nueva validación visual ni un recorrido E2E de navegador.

## Ficheros revisados / límites

- Backend: `TableroController`, `TableroRepository`, `TableroModelos`, `SuperAdministradorTest`, `PermisosTest`, `MovimientosController`.
- No se modifica `TableroPage` (solapamiento con PR 195); no se modifican migraciones ni datos.
