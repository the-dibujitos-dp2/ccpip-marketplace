import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { lineasMaestras } from '../../api/admin.js'
import { obtenerCatalogos } from '../../api/catalogo.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import { mensajeError } from '../../i18n/errores.js'
import { AccionesFila, BadgeActivo, DialogoEdicion, DialogoEstado, Filtros, useCambioEstado, useEdicion, useListado } from './catalogoMaestro.jsx'

const CODIGO = /^[A-Za-z0-9_]{2,40}$/
const ORDEN = /^\d{1,3}$/
const CAMPO_DEL_CODE = { CODIGO_DUPLICADO: 'codigo', NOMBRE_DUPLICADO: 'nombre', UNIDAD_INVALIDA: 'unidadSugerida' }

const aFormulario = (l) => ({
  codigo: l?.codigo ?? '',
  nombre: l?.nombre ?? '',
  nombreEn: l?.nombreEn ?? '',
  requiereVencimiento: l?.requiereVencimiento ?? true,
  unidadSugerida: l?.unidadSugerida?.codigo ?? '',
  orden: l ? String(l.orden) : '',
})

const aCuerpo = (f) => ({
  codigo: f.codigo.trim(),
  nombre: f.nombre.trim(),
  nombreEn: f.nombreEn.trim() || null,
  requiereVencimiento: f.requiereVencimiento,
  unidadSugerida: f.unidadSugerida || null,
  orden: f.orden.trim() === '' ? null : Number(f.orden),
})

/**
 * CCPIP-96 consultar, CCPIP-309 crear, CCPIP-310 modificar y CCPIP-311 desactivar las lineas del catalogo
 * maestro. El codigo no se edita: es la clave de sus traducciones y de las ofertas que la usan.
 */
export default function LineasMaestras() {
  const { t } = useTranslation()
  const { filtros, setFiltros, datos, error, recargar } = useListado(lineasMaestras.listar)
  const { datos: catalogos } = useConsulta(obtenerCatalogos)
  const [alerta, setAlerta] = useState(null)

  const validar = (f) => {
    const e = {}
    if (!CODIGO.test(f.codigo.trim())) e.codigo = t('catalogoMaestro.validacion.codigo')
    if (!f.nombre.trim()) e.nombre = t('catalogoMaestro.validacion.nombre')
    if (f.orden.trim() !== '' && !ORDEN.test(f.orden.trim())) e.orden = t('catalogoMaestro.validacion.orden')
    return e
  }
  const edicion = useEdicion({
    api: lineasMaestras,
    aFormulario,
    aCuerpo,
    validar,
    campoDelCode: CAMPO_DEL_CODE,
    onGuardado: (l, nueva) => {
      setAlerta({ tono: 'exito', titulo: t(nueva ? 'catalogoMaestro.lineas.creada' : 'catalogoMaestro.lineas.guardada', { nombre: l.nombre }) })
      recargar()
    },
  })
  const cambio = useCambioEstado({
    api: lineasMaestras,
    onCambiado: (l, activada) => {
      setAlerta({ tono: 'exito', titulo: t(activada ? 'catalogoMaestro.lineas.reactivada' : 'catalogoMaestro.lineas.desactivada', { nombre: l.nombre, ofertas: l.ofertas }) })
      recargar()
    },
    onError: (e) => setAlerta({ tono: 'error', titulo: t('catalogoMaestro.errorEstado'), descripcion: mensajeError(e) }),
  })

  const { f, errores, set } = edicion
  const pendiente = cambio.pendiente
  const unidades = (catalogos?.unidades ?? []).map((u) => ({ valor: u.codigo, etiqueta: u.nombre }))

  return (
    <>
      <Filtros filtros={filtros} onFiltrar={setFiltros} nuevo={t('catalogoMaestro.lineas.nueva')} onNuevo={() => edicion.abrir()} contadores={datos?.contadores} />
      {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}
      {error && <Alerta tono="error" titulo={t('catalogoMaestro.errorCargar')} descripcion={mensajeError(error, 'comun.errorConexion')} />}
      {!datos && !error && <Cargando />}
      {datos?.lineas.length === 0 && <EstadoVacio titulo={t('catalogoMaestro.lineas.vacio')} />}
      {datos?.lineas.length > 0 && (
        <div className="tabla" role="table" aria-label={t('catalogoMaestro.vista.lineas')}>
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('catalogoMaestro.lineas.col.linea')}</span>
            <span className="tabla__celda catalogo-maestro__col" role="columnheader">{t('catalogoMaestro.lineas.col.vencimiento')}</span>
            <span className="tabla__celda catalogo-maestro__col" role="columnheader">{t('catalogoMaestro.lineas.col.unidad')}</span>
            <span className="tabla__celda catalogo-maestro__num" role="columnheader">{t('catalogoMaestro.lineas.col.productos')}</span>
            <span className="tabla__celda catalogo-maestro__num" role="columnheader">{t('catalogoMaestro.lineas.col.ofertas')}</span>
            <span className="tabla__celda catalogo-maestro__estado" role="columnheader">{t('catalogoMaestro.col.estado')}</span>
            <span className="tabla__celda catalogo-maestro__acciones" role="columnheader"><span className="visually-hidden">{t('catalogoMaestro.col.acciones')}</span></span>
          </div>
          {datos.lineas.map((l) => (
            <div key={l.id} className="tabla__fila" role="row">
              <span className="tabla__celda tabla__celda--flex" role="cell">
                <span className="label-m">{l.nombre}</span>
                <span className="caption catalogo-maestro__detalle">{[l.codigo, l.nombreEn].filter(Boolean).join(' · ')}</span>
              </span>
              <span className="tabla__celda catalogo-maestro__col" role="cell">{t(l.requiereVencimiento ? 'catalogoMaestro.lineas.vence' : 'catalogoMaestro.lineas.noVence')}</span>
              <span className="tabla__celda catalogo-maestro__col" role="cell">{l.unidadSugerida?.nombre ?? '—'}</span>
              <span className="tabla__celda catalogo-maestro__num" role="cell">{l.productos}</span>
              <span className="tabla__celda catalogo-maestro__num" role="cell">{l.ofertas}</span>
              <span className="tabla__celda catalogo-maestro__estado" role="cell"><BadgeActivo activo={l.activa} /></span>
              <AccionesFila nombre={l.nombre} activo={l.activa} onEditar={() => edicion.abrir(l)} onEstado={() => cambio.pedir(l)} />
            </div>
          ))}
        </div>
      )}

      <DialogoEdicion edicion={edicion} titulo={t(edicion.edicion?.item ? 'catalogoMaestro.lineas.form.tituloEditar' : 'catalogoMaestro.lineas.form.tituloNueva')}>
        {f && (
          <>
            <Campo etiqueta={t('catalogoMaestro.lineas.form.codigo')} value={f.codigo} onChange={set('codigo')} error={errores.codigo}
              ayuda={t('catalogoMaestro.lineas.form.codigoAyuda')} disabled={Boolean(edicion.edicion.item)} maxLength={40} autoComplete="off" />
            <Campo etiqueta={t('catalogoMaestro.lineas.form.nombre')} value={f.nombre} onChange={set('nombre')} error={errores.nombre} maxLength={80} />
            <Campo etiqueta={t('catalogoMaestro.lineas.form.nombreEn')} value={f.nombreEn} onChange={set('nombreEn')} error={errores.nombreEn}
              ayuda={t('catalogoMaestro.lineas.form.nombreEnAyuda')} maxLength={80} lang="en" />
            <Campo etiqueta={t('catalogoMaestro.lineas.form.unidad')} tipo="selector" opciones={unidades} value={f.unidadSugerida}
              onChange={set('unidadSugerida')} error={errores.unidadSugerida} placeholder={t('catalogoMaestro.lineas.form.sinUnidad')} />
            <Campo etiqueta={t('catalogoMaestro.lineas.form.orden')} value={f.orden} onChange={set('orden')} error={errores.orden}
              ayuda={t('catalogoMaestro.lineas.form.ordenAyuda')} inputMode="numeric" maxLength={3} />
            <label className="catalogo-maestro__casilla">
              <input type="checkbox" checked={f.requiereVencimiento} onChange={set('requiereVencimiento')} />
              {t('catalogoMaestro.lineas.form.vencimiento')}
            </label>
          </>
        )}
      </DialogoEdicion>

      <DialogoEstado
        cambio={cambio}
        titulo={pendiente ? t(pendiente.activa ? 'catalogoMaestro.lineas.desactivarTitulo' : 'catalogoMaestro.lineas.reactivarTitulo', { nombre: pendiente.nombre }) : ''}
        texto={pendiente ? t(pendiente.activa ? 'catalogoMaestro.lineas.desactivarTexto' : 'catalogoMaestro.lineas.reactivarTexto', pendiente) : ''}
      />
    </>
  )
}
