import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useConsulta } from '../../api/useConsulta.js'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import i18n from '../../i18n/index.js'
import { mensajeError } from '../../i18n/errores.js'

// Piezas comunes a las lineas y los productos del catalogo maestro (CCPIP-96, 309-314, 351)

export const ESTADOS = ['TODOS', 'ACTIVOS', 'INACTIVOS']

/** Listado con filtros que se consultan en el servidor (RNF-05): estado, texto y lo que agregue cada pestana. */
export function useListado(listar, inicial = {}) {
  const [filtros, setFiltros] = useState({ estado: 'TODOS', q: '', ...inicial })
  const consultar = useCallback(() => listar(filtros), [listar, filtros])
  const { datos, error, recargar } = useConsulta(consultar)
  return { filtros, setFiltros, datos, error, recargar }
}

/** Estado de una linea o un producto: siempre texto y color. */
export function BadgeActivo({ activo }) {
  const { t } = useTranslation()
  return activo ? <Badge tono="exito">{t('catalogoMaestro.activo')}</Badge> : <Badge tono="neutro">{t('catalogoMaestro.inactivo')}</Badge>
}

/** Buscador, filtro de estado y la accion de crear. `children`: filtros propios de la pestana. */
export function Filtros({ filtros, onFiltrar, nuevo, onNuevo, contadores, children }) {
  const { t } = useTranslation()
  const [q, setQ] = useState(filtros.q)
  return (
    <div className="catalogo-maestro__barra">
      <form
        className="catalogo-maestro__filtros"
        role="search"
        onSubmit={(e) => {
          e.preventDefault()
          onFiltrar({ ...filtros, q: q.trim() })
        }}
      >
        <Campo etiqueta={t('catalogoMaestro.filtro.buscar')} type="search" value={q} onChange={(e) => setQ(e.target.value)} maxLength={80} />
        <Campo
          etiqueta={t('catalogoMaestro.filtro.estado')}
          tipo="selector"
          placeholder={t('catalogoMaestro.estado.TODOS')}
          opciones={ESTADOS.slice(1).map((e) => ({ valor: e, etiqueta: t(`catalogoMaestro.estado.${e}`) }))}
          value={filtros.estado === 'TODOS' ? '' : filtros.estado}
          onChange={(e) => onFiltrar({ ...filtros, q: q.trim(), estado: e.target.value || 'TODOS' })}
        />
        {children}
        <Boton type="submit" tipo="secundario" tamano="M">{t('catalogoMaestro.filtro.aplicar')}</Boton>
      </form>
      <div className="catalogo-maestro__resumen">
        {contadores && <span className="caption">{t('catalogoMaestro.contadores', contadores)}</span>}
        <Boton tamano="M" onClick={onNuevo}>{nuevo}</Boton>
      </div>
    </div>
  )
}

/** Acciones de una fila: editar y desactivar o reactivar. */
export function AccionesFila({ nombre, activo, onEditar, onEstado }) {
  const { t } = useTranslation()
  return (
    <span className="tabla__celda tabla__celda--accion catalogo-maestro__acciones" role="cell">
      <Boton tipo="secundario" tamano="S" onClick={onEditar} aria-label={t('catalogoMaestro.editarDe', { nombre })}>
        {t('catalogoMaestro.editar')}
      </Boton>
      <Boton tipo="terciario" tamano="S" onClick={onEstado} aria-label={t(activo ? 'catalogoMaestro.desactivarDe' : 'catalogoMaestro.reactivarDe', { nombre })}>
        {t(activo ? 'catalogoMaestro.desactivar' : 'catalogoMaestro.reactivar')}
      </Boton>
    </span>
  )
}

/** Mensaje de un campo que el servidor rechazo: el suyo en espanol, uno generico en otro idioma. */
const mensajeCampo = (mensaje) => (i18n.resolvedLanguage === 'es' && mensaje ? mensaje : i18n.t('catalogoMaestro.validacion.revisar'))

/**
 * Formulario de crear o editar en un dialogo. `campoDelCode`: codes del servidor que senalan un campo.
 * `onGuardado(resultado, nuevo)` se llama al guardar.
 */
export function useEdicion({ api, aFormulario, aCuerpo, validar, campoDelCode, onGuardado }) {
  const [edicion, setEdicion] = useState(null)
  const [errores, setErrores] = useState({})
  const [errorGeneral, setErrorGeneral] = useState(null)
  const [ocupado, setOcupado] = useState(false)

  const abrir = (item = null) => {
    setEdicion({ item, f: aFormulario(item) })
    setErrores({})
    setErrorGeneral(null)
  }
  const set = (campo) => (e) => {
    const valor = e.target.type === 'checkbox' ? e.target.checked : e.target.value
    setEdicion((actual) => ({ ...actual, f: { ...actual.f, [campo]: valor } }))
    setErrores((actual) => ({ ...actual, [campo]: undefined }))
  }

  async function guardar() {
    const e = validar(edicion.f)
    setErrores(e)
    setErrorGeneral(null)
    if (Object.keys(e).length) return
    setOcupado(true)
    try {
      const { item, f } = edicion
      const resultado = item ? await api.modificar(item.id, aCuerpo(f)) : await api.crear(aCuerpo(f))
      setEdicion(null)
      onGuardado(resultado, !item)
    } catch (error) {
      const campos = {}
      Object.entries(error.body?.campos ?? {}).forEach(([campo, mensaje]) => {
        campos[campo] = mensajeCampo(mensaje)
      })
      const porCode = campoDelCode[error.code]
      if (porCode) campos[porCode] = mensajeError(error)
      setErrores(campos)
      if (!Object.keys(campos).length) setErrorGeneral(mensajeError(error))
    } finally {
      setOcupado(false)
    }
  }

  return { edicion, f: edicion?.f, errores, errorGeneral, ocupado, abrir, cerrar: () => setEdicion(null), set, guardar }
}

/** Confirmacion para desactivar o reactivar. Desactivar nunca borra: el dialogo dice que se conserva. */
export function useCambioEstado({ api, onCambiado, onError }) {
  const [pendiente, setPendiente] = useState(null)
  const [ocupado, setOcupado] = useState(false)
  async function confirmar() {
    setOcupado(true)
    try {
      const activo = estaActivo(pendiente)
      onCambiado(await api.cambiarEstado(pendiente.id, !activo), !activo)
    } catch (error) {
      onError(error)
    } finally {
      setOcupado(false)
      setPendiente(null)
    }
  }
  return { pendiente, pedir: setPendiente, cancelar: () => setPendiente(null), confirmar, ocupado }
}

/** Las lineas dicen `activa` y los productos `activo`. */
export const estaActivo = (item) => item?.activa ?? item?.activo ?? false

export function DialogoEstado({ cambio, titulo, texto }) {
  const { t } = useTranslation()
  const activo = estaActivo(cambio.pendiente)
  return (
    <Dialogo
      abierto={Boolean(cambio.pendiente)}
      titulo={titulo}
      confirmar={t(activo ? 'catalogoMaestro.desactivar' : 'catalogoMaestro.reactivar')}
      tipoConfirmar={activo ? 'destructivo' : 'primario'}
      onConfirmar={cambio.confirmar}
      onCancelar={cambio.cancelar}
      ocupado={cambio.ocupado}
    >
      <p>{texto}</p>
    </Dialogo>
  )
}

/** Dialogo con el formulario de crear o editar. */
export function DialogoEdicion({ edicion, titulo, children }) {
  const { t } = useTranslation()
  return (
    <Dialogo
      abierto={Boolean(edicion.edicion)}
      titulo={titulo}
      confirmar={t(edicion.edicion?.item ? 'catalogoMaestro.guardar' : 'catalogoMaestro.crear')}
      onConfirmar={edicion.guardar}
      onCancelar={edicion.cerrar}
      ocupado={edicion.ocupado}
    >
      {edicion.edicion && (
        <form
          className="catalogo-maestro__formulario"
          noValidate
          onSubmit={(e) => {
            e.preventDefault()
            edicion.guardar()
          }}
        >
          {edicion.errorGeneral && <p className="campo__ayuda campo__ayuda--error" role="alert">{edicion.errorGeneral}</p>}
          {children}
        </form>
      )}
    </Dialogo>
  )
}
