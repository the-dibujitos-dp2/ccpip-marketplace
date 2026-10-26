import { useTranslation } from 'react-i18next'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Cargando from '../../components/Cargando.jsx'
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
