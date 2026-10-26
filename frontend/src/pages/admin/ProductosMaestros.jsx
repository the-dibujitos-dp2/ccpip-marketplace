import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoInfo from '../../assets/icons/info.svg'
import { lineasMaestras, productosMaestros } from '../../api/admin.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Icono from '../../components/Icono.jsx'
import { mensajeError } from '../../i18n/errores.js'
import { AccionesFila, BadgeActivo, DialogoEdicion, DialogoEstado, Filtros, useCambioEstado, useEdicion, useListado } from './catalogoMaestro.jsx'

const CODIGO = /^[A-Za-z0-9][A-Za-z0-9_-]{1,39}$/
const CAMPO_DEL_CODE = { CODIGO_DUPLICADO: 'codigo', PRODUCTO_DUPLICADO: 'nombre', LINEA_INACTIVA: 'linea', LINEA_INVALIDA: 'linea' }

const aFormulario = (p) => ({
  codigo: p?.codigo ?? '',
  nombre: p?.nombre ?? '',
  descripcion: p?.descripcion ?? '',
  linea: p?.linea.codigo ?? '',
})

const aCuerpo = (f) => ({ codigo: f.codigo.trim(), nombre: f.nombre.trim(), descripcion: f.descripcion.trim() || null, linea: f.linea })

/**
 * Productos del catalogo maestro de CCPIP-42. CCPIP-351 consultar, CCPIP-312 crear, CCPIP-313 modificar sin alterar ofertas historicas y CCPIP-314
 * desactivar conservando referencias historicas. Un producto maestro no tiene precio, unidad ni stock: no es
 * una oferta. Sus ofertas vinculadas se cuentan, pero nunca se editan desde aqui.
 */
export default function ProductosMaestros() {
  const { t } = useTranslation()
  const { filtros, setFiltros, datos, error, recargar } = useListado(productosMaestros.listar, { linea: '' })
  const todasLasLineas = useCallback(() => lineasMaestras.listar(), [])
  const { datos: lineas } = useConsulta(todasLasLineas)
  const [alerta, setAlerta] = useState(null)

  const validar = (f) => {
    const e = {}
    if (!CODIGO.test(f.codigo.trim())) e.codigo = t('catalogoMaestro.validacion.codigoProducto')
    if (!f.nombre.trim()) e.nombre = t('catalogoMaestro.validacion.nombre')
    if (!f.linea) e.linea = t('catalogoMaestro.validacion.linea')
    return e
  }
  const edicion = useEdicion({
    api: productosMaestros,
    aFormulario,
    aCuerpo,
    validar,
    campoDelCode: CAMPO_DEL_CODE,
    onGuardado: (p, nuevo) => {
      setAlerta({ tono: 'exito', titulo: t(nuevo ? 'catalogoMaestro.productos.creado' : 'catalogoMaestro.productos.guardado', { nombre: p.nombre }) })
      recargar()
    },
  })
  const cambio = useCambioEstado({
    api: productosMaestros,
    onCambiado: (p, activado) => {
      setAlerta({ tono: 'exito', titulo: t(activado ? 'catalogoMaestro.productos.reactivado' : 'catalogoMaestro.productos.desactivado', { nombre: p.nombre, ofertas: p.ofertas }) })
      recargar()
    },
    onError: (e) => setAlerta({ tono: 'error', titulo: t('catalogoMaestro.errorEstado'), descripcion: mensajeError(e) }),
  })

  const { f, errores, set } = edicion
  const pendiente = cambio.pendiente
  const actual = edicion.edicion?.item
  const todas = lineas?.lineas ?? []
  // Para elegir: las activas y, al editar, la que el producto ya tiene aunque este inactiva
  const elegibles = todas
    .filter((l) => l.activa || l.codigo === actual?.linea.codigo)
    .map((l) => ({ valor: l.codigo, etiqueta: l.activa ? l.nombre : `${l.nombre} (${t('catalogoMaestro.inactiva')})` }))

  return (
    <>
      <Filtros filtros={filtros} onFiltrar={setFiltros} nuevo={t('catalogoMaestro.productos.nuevo')} onNuevo={() => edicion.abrir()} contadores={datos?.contadores}>
        <Campo
          etiqueta={t('catalogoMaestro.filtro.linea')}
          tipo="selector"
          placeholder={t('catalogoMaestro.filtro.todas')}
          opciones={todas.map((l) => ({ valor: l.codigo, etiqueta: l.nombre }))}
          value={filtros.linea}
          onChange={(e) => setFiltros({ ...filtros, linea: e.target.value })}
        />
      </Filtros>
      {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}
      {error && <Alerta tono="error" titulo={t('catalogoMaestro.errorCargar')} descripcion={mensajeError(error, 'comun.errorConexion')} />}
      {!datos && !error && <Cargando />}
      {datos?.productos.length === 0 && <EstadoVacio titulo={t('catalogoMaestro.productos.vacio')} />}
      {datos?.productos.length > 0 && (
        <div className="tabla" role="table" aria-label={t('catalogoMaestro.vista.productos')}>
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('catalogoMaestro.productos.col.producto')}</span>
            <span className="tabla__celda catalogo-maestro__linea" role="columnheader">{t('catalogoMaestro.productos.col.linea')}</span>
            <span className="tabla__celda catalogo-maestro__col" role="columnheader">{t('catalogoMaestro.productos.col.ofertas')}</span>
            <span className="tabla__celda catalogo-maestro__estado" role="columnheader">{t('catalogoMaestro.col.estado')}</span>
            <span className="tabla__celda catalogo-maestro__acciones" role="columnheader"><span className="visually-hidden">{t('catalogoMaestro.col.acciones')}</span></span>
          </div>
          {datos.productos.map((p) => (
            <div key={p.id} className="tabla__fila" role="row">
              <span className="tabla__celda tabla__celda--flex" role="cell">
                <span className="label-m">{p.nombre}</span>
                <span className="caption catalogo-maestro__detalle">{[p.codigo, p.descripcion].filter(Boolean).join(' · ')}</span>
              </span>
              <span className="tabla__celda catalogo-maestro__linea" role="cell">
                <span>{p.linea.nombre}</span>
                {!p.linea.activa && <Badge tono="aviso">{t('catalogoMaestro.productos.lineaInactiva')}</Badge>}
              </span>
              <span className="tabla__celda catalogo-maestro__col" role="cell">{t('catalogoMaestro.productos.ofertas', { total: p.ofertas, publicadas: p.ofertasPublicadas })}</span>
              <span className="tabla__celda catalogo-maestro__estado" role="cell"><BadgeActivo activo={p.activo} /></span>
              <AccionesFila nombre={p.nombre} activo={p.activo} onEditar={() => edicion.abrir(p)} onEstado={() => cambio.pedir(p)} />
            </div>
          ))}
        </div>
      )}

      <DialogoEdicion edicion={edicion} titulo={t(actual ? 'catalogoMaestro.productos.form.tituloEditar' : 'catalogoMaestro.productos.form.tituloNuevo')}>
        {f && (
          <>
            <Campo etiqueta={t('catalogoMaestro.productos.form.codigo')} value={f.codigo} onChange={set('codigo')} error={errores.codigo}
              ayuda={t('catalogoMaestro.productos.form.codigoAyuda')} disabled={Boolean(actual)} maxLength={40} autoComplete="off" />
            <Campo etiqueta={t('catalogoMaestro.productos.form.nombre')} value={f.nombre} onChange={set('nombre')} error={errores.nombre} maxLength={150} />
            <Campo etiqueta={t('catalogoMaestro.productos.form.descripcion')} tipo="area" rows={3} value={f.descripcion} onChange={set('descripcion')}
              error={errores.descripcion} maxLength={1000} />
            <Campo etiqueta={t('catalogoMaestro.productos.form.linea')} tipo="selector" opciones={elegibles} value={f.linea} onChange={set('linea')} error={errores.linea} />
            {actual && (
              <p className="catalogo-maestro__aviso">
                <Icono src={iconoInfo} tamano={16} />
                {t('catalogoMaestro.productos.form.historico', { total: actual.ofertas })}
              </p>
            )}
          </>
        )}
      </DialogoEdicion>

      <DialogoEstado
        cambio={cambio}
        titulo={pendiente ? t(pendiente.activo ? 'catalogoMaestro.productos.desactivarTitulo' : 'catalogoMaestro.productos.reactivarTitulo', { nombre: pendiente.nombre }) : ''}
        texto={pendiente ? t(pendiente.activo ? 'catalogoMaestro.productos.desactivarTexto' : 'catalogoMaestro.productos.reactivarTexto',
          { ofertas: pendiente.ofertas, publicadas: pendiente.ofertasPublicadas }) : ''}
      />
    </>
  )
}
