import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { mensajeError } from '../../i18n/errores.js'
import { VigenciaYArchivo } from '../productor/EvidenciasPage.jsx'

/**
 * Piezas comunes de las bandejas y expedientes del sub administrador: autorizaciones de productos (S-01,
 * S-02) y moderacion de publicaciones (CCPIP-46). `textos` es el espacio de nombres de cada modulo.
 */

/** Las pestanas por estado de una bandeja: `{textos}.pestana.{estado}`. */
export function PestanasEstado({ textos, estados, valor, onCambiar }) {
  const { t } = useTranslation()
  return (
    <Pestanas etiqueta={t(`${textos}.pestanasEtiqueta`)} valor={valor} onCambiar={onCambiar}
      opciones={estados.map((e) => ({ valor: e, etiqueta: t(`${textos}.pestana.${e}`) }))} />
  )
}

/** Las dos primeras celdas de una solicitud: el producto con su linea y la organizacion con su pueblo y region. */
export function CeldasSolicitud({ solicitud: s }) {
  return (
    <>
      <span className="tabla__celda tabla__celda--flex" role="cell">
        <span className="label-m">{s.producto}</span>
        <span className="caption autorizaciones__linea">{s.linea}</span>
      </span>
      <span className="tabla__celda autorizaciones__organizacion" role="cell">
        <span className="label-m">{s.organizacion}</span>
        <span className="caption autorizaciones__linea">{[s.pueblo, s.region].filter(Boolean).join(' · ')}</span>
      </span>
    </>
  )
}

/** La ultima celda: revisar una pendiente o ver una decidida. */
export function CeldaRevisar({ textos, solicitud: s, pendiente, to }) {
  const { t } = useTranslation()
  return (
    <span className="tabla__celda autorizaciones__accion" role="cell">
      <Boton tipo={pendiente ? 'primario' : 'secundario'} tamano="S" to={to}
        aria-label={t(`${textos}.revisarDe`, { producto: s.producto, organizacion: s.organizacion })}>
        {pendiente ? t(`${textos}.revisar`) : t(`${textos}.ver`)}
      </Boton>
    </span>
  )
}

/** Mientras el expediente carga, o si no se pudo cargar, con el enlace para volver a la bandeja. */
export function SinExpediente({ path, migas, textos, error, volverA }) {
  const { t } = useTranslation()
  return (
    <LayoutAdmin path={path} migas={migas} titulo={t(`${textos}.expediente.titulo`)}>
      {error ? <Alerta tono="error" titulo={mensajeError(error, `${textos}.expediente.error`)} /> : <Cargando />}
      <Boton tipo="secundario" tamano="M" to={volverA}>{t(`${textos}.volver`)}</Boton>
    </LayoutAdmin>
  )
}

/**
 * La tabla de una bandeja con su error, su carga y su estado vacio (`{textos}.vacio.{estado}`).
 * `columnas`: [{ clase, texto }] del encabezado; `fila(solicitud)` dibuja cada fila.
 */
export function TablaBandeja({ textos, estado, datos, error, columnas, fila }) {
  const { t } = useTranslation()
  if (error) return <Alerta tono="error" titulo={mensajeError(error, `${textos}.error`)} />
  if (!datos) return <Cargando />
  if (datos.solicitudes.length === 0) return <EstadoVacio titulo={t(`${textos}.vacio.${estado}`)} />
  return (
    <div className="tabla" role="table" aria-label={t(`${textos}.pestana.${estado}`)}>
      <div className="tabla__fila tabla__fila--encabezado" role="row">
        {columnas.map((c) => <span key={c.clase} className={`tabla__celda ${c.clase}`} role="columnheader">{c.texto}</span>)}
      </div>
      {datos.solicitudes.map(fila)}
    </div>
  )
}

/** Una evidencia de un expediente: titulo, `detalle` (tipo, emisor, formato...), vigencia y archivo. */
export function FilaEvidencia({ evidencia, detalle }) {
  return (
    <div className="expediente__evidencia-fila">
      <span className="expediente__evidencia-datos">
        <span className="label-m">{evidencia.titulo}</span>
        <span className="caption">{detalle.filter(Boolean).join(' · ')}</span>
      </span>
      <VigenciaYArchivo evidencia={evidencia} />
    </div>
  )
}

/**
 * Una decision con motivo (aprobar, rechazar, revocar): el motivo es obligatorio, se confirma en un dialogo
 * y el resultado queda en `aviso`. `enviar[accion](id, motivo)` la envia y devuelve el expediente nuevo, que
 * recibe `alDecidir`; si el error dice que la solicitud cambio (`recargarSi`), se llama a `recargar`.
 * Los textos salen de `{textos}.expediente.*`.
 */
export function useDecision({ textos, id, enviar, alDecidir, recargar, recargarSi }) {
  const { t } = useTranslation()
  const [motivo, setMotivo] = useState('')
  const [errorMotivo, setErrorMotivo] = useState(null)
  const [confirmar, setConfirmar] = useState(null)
  const [decidiendo, setDecidiendo] = useState(false)
  const [aviso, setAviso] = useState(null)

  function pedirConfirmacion(accion) {
    if (!motivo.trim()) {
      setErrorMotivo(t(`${textos}.expediente.motivoRequerido`))
      return
    }
    setErrorMotivo(null)
    setConfirmar(accion)
  }

  async function decidir() {
    setDecidiendo(true)
    try {
      alDecidir(await enviar[confirmar](id, motivo.trim()))
      setAviso({ tono: 'exito', titulo: t(`${textos}.expediente.hecho.${confirmar}`) })
      setMotivo('')
    } catch (e) {
      setAviso({ tono: 'error', titulo: mensajeError(e, `${textos}.expediente.errorDecision`) })
      if (recargarSi.includes(e.code)) await recargar()
    } finally {
      setDecidiendo(false)
      setConfirmar(null)
    }
  }

  return { textos, motivo, setMotivo, errorMotivo, confirmar, setConfirmar, decidiendo, aviso, setAviso, pedirConfirmacion, decidir }
}

/** El campo del motivo de una decision de `useDecision`. */
export function CampoMotivo({ decision: d, etiqueta, ayuda }) {
  return (
    <Campo tipo="area" etiqueta={etiqueta} ayuda={ayuda} error={d.errorMotivo} value={d.motivo} maxLength={500}
      onChange={(e) => d.setMotivo(e.target.value)} />
  )
}

/**
 * El dialogo que confirma una decision de `useDecision`, con su motivo: `{textos}.dialogo.{accion}.titulo`
 * (con `producto`), `.confirmar` y `.texto` (con `organizacion`). Aprobar es la unica que no es destructiva.
 */
export function DialogoDecision({ decision: d, producto, organizacion }) {
  const { t } = useTranslation()
  const accion = d.confirmar
  return (
    <Dialogo
      abierto={accion !== null}
      titulo={accion ? t(`${d.textos}.dialogo.${accion}.titulo`, { producto }) : ''}
      confirmar={accion ? t(`${d.textos}.dialogo.${accion}.confirmar`) : undefined}
      tipoConfirmar={accion === 'aprobar' ? 'primario' : 'destructivo'}
      ocupado={d.decidiendo}
      onCancelar={() => d.setConfirmar(null)}
      onConfirmar={d.decidir}
    >
      <p>{t(`${d.textos}.dialogo.motivo`, { motivo: d.motivo.trim() })}</p>
      <p>{accion && t(`${d.textos}.dialogo.${accion}.texto`, { organizacion })}</p>
    </Dialogo>
  )
}
