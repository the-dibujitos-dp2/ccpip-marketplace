import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { configurarMinimoCalificaciones, obtenerMinimoCalificaciones } from '../../api/valoracion.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import { fechaHoraLarga } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'

const MAXIMO = 100

/**
 * SA-02 "Reseñas mínimas" (CCPIP-82, RF-89): cuantas calificaciones necesita una oferta para mostrar su
 * promedio. Mientras la Camara no lo fije rige un valor provisional, marcado como pendiente.
 */
export default function MinimoCalificaciones() {
  const { t } = useTranslation()
  const [minimo, setMinimo] = useState(null)
  const [valor, setValor] = useState('')
  const [error, setError] = useState(null)
  const [alerta, setAlerta] = useState(null)
  const [enviando, setEnviando] = useState(false)

  const aplicar = (m) => {
    setMinimo(m)
    setValor(String(m.calificaciones))
  }

  useEffect(() => {
    obtenerMinimoCalificaciones().then(aplicar).catch((e) => setAlerta({ tono: 'error', titulo: mensajeError(e, 'minimoCalificaciones.errorCargar') }))
  }, [])

  async function guardar(e) {
    e.preventDefault()
    setAlerta(null)
    const numero = /^\d{1,3}$/.test(valor.trim()) ? Number(valor.trim()) : 0
    if (numero < 1 || numero > MAXIMO) return setError(t('minimoCalificaciones.rango', { maximo: MAXIMO }))
    setEnviando(true)
    try {
      aplicar(await configurarMinimoCalificaciones(numero))
      setAlerta({ tono: 'exito', titulo: t('minimoCalificaciones.guardado', { count: numero }) })
    } catch (err) {
      setAlerta({ tono: 'error', titulo: mensajeError(err, 'minimoCalificaciones.errorGuardar') })
    } finally {
      setEnviando(false)
    }
  }

  return (
    <section className="tarjeta-panel parametros" aria-labelledby="minimo-calificaciones-titulo">
      <header className="parametros__cabecera">
        <h2 id="minimo-calificaciones-titulo" className="heading-h4">{t('minimoCalificaciones.seccion')}</h2>
      </header>
      <p className="body-s parametros__texto">{t('minimoCalificaciones.intro')}</p>
      {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}
      {/* CCPIP-413: la tarjeta no queda vacia mientras llega el minimo vigente */}
      {!minimo && !alerta && <Cargando />}
      {minimo && (
        <>
          <form className="parametros__formulario" onSubmit={guardar} noValidate>
            <Campo etiqueta={t('minimoCalificaciones.campo')} value={valor} error={error} inputMode="numeric" maxLength={3}
              autoComplete="off" ayuda={t('minimoCalificaciones.ayuda', { maximo: MAXIMO })}
              onChange={(e) => {
                setValor(e.target.value)
                setError(null)
              }} />
            <Boton type="submit" tamano="M" disabled={enviando}>{t('minimoCalificaciones.guardar')}</Boton>
          </form>
          <p className="caption parametros__estado">
            {minimo.provisional
              ? t('minimoCalificaciones.provisional', { count: minimo.calificaciones })
              : t('minimoCalificaciones.configurado', { count: minimo.calificaciones, nombre: minimo.registradoPor, fecha: fechaHoraLarga(minimo.registradoEn) })}
          </p>
        </>
      )}
    </section>
  )
}
