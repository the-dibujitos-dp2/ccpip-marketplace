# Backend CCPIP-336 — lectura privada del vínculo y retiro lógico

## Contrato ejercitado

- `GET /api/productor/productos/{productoId}/evidencias/{evidenciaId}/lote`, solo `PRODUCTOR` autenticado y propietario: devuelve `200 { evidenciaId, productoId, loteId }` para vínculo existente; `204` para evidencia vigente sin vínculo; `404` para evidencia ajena/inexistente o retirada; `403` para otros roles y `401` sin sesión.
- La lectura es del vínculo persistido, no modifica el certificado, la revisión, la aprobación ni el documento privado.
- La escritura `POST .../lote` ahora excluye `retirada_en IS NOT NULL`, igual que la lista y el flujo de buscar/retirar. No permite vincular después del retiro lógico. Archivo privado conserva su flujo histórico (archivoPropio lo permite incluso retirada).
- Sin nuevas vistas públicas, permisos ni restricciones de estado logístico del lote.

## TDD y comprobaciones

- RED real (ruta GET ausente y POST conseguía vincular evidencia retirada): `evidencia-336/proyeccion-privada/red.log`.
- GREEN dirigido (lectura, roles/sesión/propiedad, vacío, retiro): `evidencia-336/proyeccion-privada/green.log`, XML dirigido en el mismo directorio.
- Suite completa real: 1022 tests, 0 failures, 0 errors, 0 skipped. Log `evidencia-336/proyeccion-privada/full.log`; XML JUnit, 100 suites, en `evidencia-336/proyeccion-privada/xml/`.
- `git diff --check` limpio. Se mantiene intacto el POST previo y sus comportamientos de aprobación/privacidad.
