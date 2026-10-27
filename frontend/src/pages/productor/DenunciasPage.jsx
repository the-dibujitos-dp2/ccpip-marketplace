import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { denunciasRecibidas, responderDenuncia } from '../../api/valoracion.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import BadgeDenuncia from '../../components/BadgeDenuncia.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import { diaMes, fecha } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import PaginaProductor from './PaginaProductor.jsx'
import './productor.css'
import '../consumidor/Valoraciones.css'

const MINIMO = 10

/**
 * CCPIP-116 (RF-91): las denuncias recibidas sobre las ofertas del productor. El servidor ya las
 * entrega anonimizadas: sin denunciante, solo el dia y con correos y telefonos ocultos (RNF-41).
 * CCPIP-323 (RF-216): el productor responde cada denuncia abierta una sola vez.
 */
export default function DenunciasPage({ path }) {
  const { t } = useTranslation()
  const { datos, setDatos, error } = useConsulta(denunciasRecibidas)

  function respondida(actualizada) {
    setDatos((d) => {
      const denuncias = d.denuncias.map((x) => (x.id === actualizada.id ? actualizada : x))
      return { resumen: { ...d.resumen, sinResponder: denuncias.filter((x) => x.puedeResponder).length }, denuncias }
    })
  }

  const r = datos?.resumen
  return (
    <PaginaProductor path={path} seccion={t('productor.nav.denuncias')} titulo={t('denunciasProductor.titulo')}>
      <Alerta tono="info" titulo={t('denunciasProductor.anonimas')} descripcion={t('denunciasProductor.anonimasTexto')} />
      {error && <Alerta tono="error" titulo={t('denunciasProductor.error')} descripcion={t('comun.errorConexion')} />}
      {!datos && !error && <Cargando />}
      {r && r.total > 0 && (
        <p className="body-m">{t('denunciasProductor.resumen', { total: r.total, abiertas: r.abiertas, sinResponder: r.sinResponder })}</p>
      )}
      {datos && datos.denuncias.length === 0 && (
        <EstadoVacio titulo={t('denunciasProductor.vacio')} descripcion={t('denunciasProductor.vacioTexto')} />
      )}
      {datos?.denuncias.length > 0 && (
        <ul className="valoraciones__resenas" aria-label={t('denunciasProductor.titulo')}>
          {datos.denuncias.map((d) => (
            <li key={d.id} className="valoraciones__resena">
              <Denuncia denuncia={d} onRespondida={respondida} />
            </li>
          ))}
        </ul>
      )}
    </PaginaProductor>
  )
}

function Denuncia({ denuncia: d, onRespondida }) {
  const { t } = useTranslation()
  const [texto, setTexto] = useState('')
  const [error, setError] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const titulo = d.producto ? d.producto.nombre : t('denunciasProductor.sobreComunidad')

  async function responder(e) {
    e.preventDefault()
    if (texto.trim().length < MINIMO) {
      setError(t('denunciasProductor.respuestaCorta', { minimo: MINIMO }))
      return
    }
    setEnviando(true)
    setError(null)
    try {
      onRespondida(await responderDenuncia(d.id, texto))
    } catch (err) {
      setError(mensajeError(err, 'denunciasProductor.errorRespuesta'))
    } finally {
      setEnviando(false)
    }
  }

  return (
    <article aria-label={t('denunciasProductor.denunciaDe', { titulo, fecha: fecha(d.fecha) })}>
      <div className="calificar__cabecera">
        <span className="label-m">{titulo}</span>
        <BadgeDenuncia estado={d.estado} />
      </div>
      <p className="caption">{t('denunciasProductor.recibida', { fecha: fecha(d.fecha), motivo: t(`denuncia.motivos.${d.motivo}`) })}</p>
      <p className="body-s">{d.descripcion}</p>
      {d.respuesta && (
        <div>
          <p className="label-m">{t('denunciasProductor.tuRespuesta', { fecha: diaMes(d.respondidaEn) })}</p>
          <p className="body-s">{d.respuesta}</p>
        </div>
      )}
      {d.puedeResponder && (
        <form className="calificar" onSubmit={responder} noValidate>
          <Campo tipo="area" etiqueta={t('denunciasProductor.responder')} value={texto} maxLength={1000} rows={3}
            ayuda={t('denunciasProductor.responderAyuda')} onChange={(e) => setTexto(e.target.value)} />
          {error && <Alerta tono="error" titulo={error} />}
          <div className="calificar__acciones">
            <Boton type="submit" tamano="S" cargando={enviando}>{enviando ? t('calificaciones.enviando') : t('denunciasProductor.enviarRespuesta')}</Boton>
          </div>
        </form>
      )}
      {!d.respuesta && !d.puedeResponder && <p className="caption">{t('denunciasProductor.cerradaSinRespuesta')}</p>}
    </article>
  )
}
