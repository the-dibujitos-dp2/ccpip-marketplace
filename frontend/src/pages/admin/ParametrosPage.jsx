import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { configurarVigenciaCarrito, obtenerVigenciaCarrito } from '../../api/admin.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { fechaHoraLarga } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import '../productor/productor.css'
import './admin.css'

const MINIMO = 1
const MAXIMO = 720
const ENTERO = /^\d{1,4}$/

/** Las horas escritas si son un entero de 1 a 720; si no, null. */
function horasValidas(texto) {
  const limpio = texto.trim()
  if (!ENTERO.test(limpio)) return null
  const horas = Number(limpio)
  return horas >= MINIMO && horas <= MAXIMO ? horas : null
}

/**
 * SA-02 "Parametros de la plataforma" (CCPIP-142, RF-109), por ahora solo la vida del carrito sin pagar:
 * cuantas horas se guarda desde su ultimo cambio. Mientras la Camara no la configure rige un valor
 * provisional, que se marca como pendiente de definicion (Figma SA-02). El cambio rige de inmediato.
 * El servidor guarda el historial, pero solo informa la ultima configuracion.
 */
export default function ParametrosPage({ path }) {
  const { t } = useTranslation()
  const [vigencia, setVigencia] = useState(null)
  const [errorCarga, setErrorCarga] = useState(false)
  const [horas, setHoras] = useState('')
  const [error, setError] = useState(null)
  const [alerta, setAlerta] = useState(null)
  const [enviando, setEnviando] = useState(false)

  const aplicar = (v) => {
    setVigencia(v)
    setHoras(String(v.horas))
    setError(null)
  }

  useEffect(() => {
    obtenerVigenciaCarrito().then(aplicar).catch(() => setErrorCarga(true))
  }, [])

  async function guardar(evento) {
    evento.preventDefault()
    setAlerta(null)
    const nuevas = horasValidas(horas)
    if (!horas.trim()) return setError(t('admin.parametros.validacion.vacio'))
    if (nuevas == null) return setError(t('admin.parametros.validacion.rango', { minimo: MINIMO, maximo: MAXIMO }))
    if (nuevas === vigencia.horas && !vigencia.provisional) return setError(t('admin.parametros.validacion.mismo'))
    setEnviando(true)
    try {
      const nueva = await configurarVigenciaCarrito(nuevas)
      aplicar(nueva)
      setAlerta({ tono: 'exito', titulo: t('admin.parametros.guardado', { count: nueva.horas }) })
    } catch (e) {
      // El mensaje del campo llega en espanol: se muestra el propio en el idioma elegido
      if (e.body?.campos?.horas) setError(t('admin.parametros.validacion.rango', { minimo: MINIMO, maximo: MAXIMO }))
      else setAlerta({ tono: 'error', titulo: t('admin.parametros.errorGuardar'), descripcion: mensajeError(e) })
    } finally {
      setEnviando(false)
    }
  }

  return (
    <LayoutAdmin path={path} migas={t('admin.nav.parametros')} titulo={t('admin.parametros.titulo')}>
      {errorCarga && <Alerta tono="error" titulo={t('admin.parametros.errorCargar')} descripcion={t('comun.errorConexion')} />}
      {!vigencia && !errorCarga && <p className="caption">{t('comun.cargando')}</p>}
      {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}
      {vigencia && (
        <section className="tarjeta-panel parametros" aria-labelledby="parametros-titulo">
          <header className="parametros__cabecera">
            <h2 id="parametros-titulo" className="heading-h4">{t('admin.parametros.seccion')}</h2>
            {vigencia.provisional && <Badge tono="aviso" conPunto={false}>{t('admin.parametros.pendiente')}</Badge>}
          </header>
          <p className="body-s parametros__texto">{t('admin.parametros.intro')}</p>
          <form className="parametros__formulario" onSubmit={guardar} noValidate>
            <Campo
              etiqueta={t('admin.parametros.campo')}
              value={horas}
              onChange={(e) => {
                setHoras(e.target.value)
                setError(null)
              }}
              error={error}
              ayuda={t('admin.parametros.ayuda', { minimo: MINIMO, maximo: MAXIMO })}
              inputMode="numeric"
              maxLength={4}
              autoComplete="off"
            />
            <Boton type="submit" tamano="M" disabled={enviando}>
              {t('admin.parametros.guardar')}
            </Boton>
          </form>
          <p className="caption parametros__estado" role="status">
            {vigencia.provisional
              ? t('admin.parametros.provisional', { count: vigencia.horas })
              : t('admin.parametros.configurada', { count: vigencia.horas, nombre: vigencia.registradoPor, fecha: fechaHoraLarga(vigencia.registradoEn) })}
          </p>
          <p className="caption">{t('admin.parametros.rigeYa')}</p>
        </section>
      )}
    </LayoutAdmin>
  )
}
