import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoIa from '../../assets/icons/ia.svg'
import { preguntarAsistente } from '../../api/catalogo.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Icono from '../../components/Icono.jsx'
import TarjetaProducto from '../../components/TarjetaProducto.jsx'
import './AsistenteCatalogo.css'

const MAXIMO = 300

/**
 * CCPIP-51 y CCPIP-175: asistente de catalogo dentro de CW-02. Responde con productos reales que
 * llevan a su ficha. Si la IA falla, la busqueda y los filtros de la pagina siguen funcionando.
 * `onVerEnBusqueda(busqueda)`: lleva los criterios que uso el asistente al buscador normal.
 */
export default function AsistenteCatalogo({ onVerEnBusqueda }) {
  const { t } = useTranslation()
  const [pregunta, setPregunta] = useState('')
  const [respuesta, setRespuesta] = useState(null)
  const [error, setError] = useState(null)
  const [consultando, setConsultando] = useState(false)

  async function preguntar(e) {
    e.preventDefault()
    const texto = pregunta.trim()
    if (texto.length < 3) {
      setError({ tono: 'aviso', titulo: t('asistente.muyCorta') })
      return
    }
    setConsultando(true)
    setError(null)
    setRespuesta(null)
    try {
      setRespuesta(await preguntarAsistente(texto))
    } catch (err) {
      if (err.code === 'IA_NO_DISPONIBLE') setError({ tono: 'info', titulo: t('asistente.noDisponible'), descripcion: t('asistente.usaFiltros') })
      else if (err.status === 429) setError({ tono: 'aviso', titulo: err.message })
      else setError({ tono: 'error', titulo: t('asistente.error'), descripcion: t('comun.errorConexion') })
    } finally {
      setConsultando(false)
    }
  }

  return (
    <section className="asistente" aria-labelledby="asistente-titulo">
      <div className="asistente__cabecera">
        <Icono src={iconoIa} tamano={20} />
        <h2 id="asistente-titulo" className="heading-h4">{t('asistente.titulo')}</h2>
      </div>
      <form className="asistente__formulario" onSubmit={preguntar}>
        <Campo
          etiqueta={t('asistente.etiqueta')}
          placeholder={t('asistente.ejemplo')}
          value={pregunta}
          maxLength={MAXIMO}
          onChange={(e) => setPregunta(e.target.value)}
          className="asistente__campo"
        />
        <Boton tamano="M" type="submit" disabled={consultando}>
          {consultando ? t('asistente.consultando') : t('asistente.preguntar')}
        </Boton>
      </form>

      {error && <Alerta {...error} onCerrar={() => setError(null)} />}

      {respuesta && (
        <div className="asistente__respuesta" aria-live="polite">
          <p className="body-m">{respuesta.texto}</p>
          {respuesta.productos.length > 0 && (
            <ul className="grilla-productos asistente__productos" aria-label={t('asistente.productosCitados')}>
              {respuesta.productos.map((p) => (
                <li key={p.id}>
                  <TarjetaProducto producto={p} />
                </li>
              ))}
            </ul>
          )}
          <p className="caption asistente__aviso">{t('asistente.aviso')}</p>
          {respuesta.busqueda && onVerEnBusqueda && (
            <button type="button" className="enlace-marca asistente__ver" onClick={() => onVerEnBusqueda(respuesta.busqueda)}>
              {t('asistente.verEnBusqueda')}
            </button>
          )}
        </div>
      )}
    </section>
  )
}
