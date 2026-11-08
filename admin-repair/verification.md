# Admin reports repair evidence

Scope: local worktree `feat/CCPIP-345-reportes-interactivos`, based on `273f1d3`. No shared visualization foundation, root i18n, backend, API, Acopio, network publication, or Jira files changed.

## Contract/data mapping review

- Finanzas (ventas/comisiones/ingresos): periods use API `inicio`, `b2c`, `b2b`, and `total`. B2C/B2B breakdown is temporal; separate controls select the API total or an individual component. Removed duplicate accumulated selector; the existing table still includes `acumulado`.
- Indicadores: periods map `ventas`, `pedidos`, `productoresConVentas`, `productosNuevos`, `compradores` (not UI aliases against payload objects).
- Productores: rankings use `productorId`, `productor`, `total` from the top-20 response.
- Puntos: rankings use `puntoId`, `punto`, `total`; no denunciation counts are added to sales.
- Calificaciones: star distribution uses `resumen.distribucion[{estrellas,cantidad}]`; rating ranks use product `productoId`, `producto`, `promedio`. `promedio: null` is excluded from the rating ranking; KPI/table semantics and configured minimum note remain.
- Origen-destino: point/producer source labels use `nombre`; destinations use `departamento`; routes use `punto` and nullable `destino`; bars use response `total`. Null destinations use localized fallback. No synthetic aggregation across dimensions.
- Selection detail is rendered once by the chart foundation, not duplicated in the admin wrapper. Dataset/range changes reset temporal selection, avoiding the stale selected metric. Charts precede KPIs.
- Labels use a local reactive ES/EN dictionary; no root locale files changed and no `— gráfico` suffix remains.

Fixtures in `ReportesAdminVisuales.test.jsx` were copied from the existing admin report contract/test payload shapes in `Reportes.test.jsx`; they introduce no production/sample business facts.

## Verification

- `npx vitest run src/pages/admin/reportes/ReportesAdminVisuales.test.jsx --maxWorkers=1`: **1 file, 8 tests passed** (financials, indicators, producers, points, ratings, origin/destination, EN, dataset/range rerender).
- `npx vitest run src/pages/admin/Reportes.test.jsx --maxWorkers=1`: **1 file, 23 tests passed** (existing admin access, tabs, filters, tables, downloads/regressions).
- `npm run build`: **passed**, 457 modules transformed. Existing Vite warning: generated JS chunk exceeds 500 kB.
- `git diff --check`: **passed**.

## Files changed

- `frontend/src/pages/admin/reportes/GraficaAdmin.jsx`
- `frontend/src/pages/admin/reportes/{Calificaciones,Indicadores,OrigenDestino,Productores,Puntos}.jsx`
- `frontend/src/pages/admin/reportes/ReportesAdminVisuales.test.jsx`
