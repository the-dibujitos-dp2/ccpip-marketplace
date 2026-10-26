import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoSubir from '../../assets/icons/subir.svg'
import { MAX_MB, MAX_MINUTOS, MAX_VIDEOS, misVideos, ocupaLugar, subirVideo, TIPOS_VIDEO } from '../../api/videos.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import Icono from '../../components/Icono.jsx'
import { duracion } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import './VideosTienda.css'

const MAX_BYTES = MAX_MB * 1024 * 1024

/** Que le falta al formulario antes de enviarlo; el servidor vuelve a validar formato, peso y duracion. */
function validar(titulo, archivo, t) {
  const errores = {}
  if (!titulo.trim()) errores.titulo = t('productor.videos.tituloRequerido')
  if (!archivo) errores.archivo = t('productor.videos.archivoRequerido')
  else if (!TIPOS_VIDEO.includes(archivo.type)) errores.archivo = t('productor.videos.tipoInvalido')
  else if (archivo.size > MAX_BYTES) errores.archivo = t('productor.videos.muyPesado', { max: MAX_MB })
  return errores
}

const TONO = { EN_REVISION: 'info', PUBLICADO: 'exito', RECHAZADO: 'error' }

const megas = (bytes) => (bytes / (1024 * 1024)).toLocaleString(undefined, { maximumFractionDigits: 1 })

/**
 * CCPIP-118 (RF-10): los videos de la tienda en Mi tienda. El productor sube un video propio (MP4 o WebM, hasta
 * 50 MB y 3 minutos); queda en revision y se ve en su tienda cuando la Camara lo aprueba. No hay pantalla en
 * Figma: sigue el formato de la galeria. Si la subida se corta, el archivo elegido se conserva para reintentar.
 * CCPIP-390 (RF-282): ve sus videos con el estado de su revision y, si se rechazaron, el motivo.
 */
export default function VideosTienda() {
  const { t } = useTranslation()
  const [titulo, setTitulo] = useState('')
  const [archivo, setArchivo] = useState(null)
  const [errores, setErrores] = useState({})
  const [subiendo, setSubiendo] = useState(false)
  const [aviso, setAviso] = useState(null)
  const [videos, setVideos] = useState(null)
  const [errorLista, setErrorLista] = useState(false)

  const cargar = useCallback(async () => {
    try {
      setVideos(await misVideos())
      setErrorLista(false)
    } catch {
      setErrorLista(true)
    }
  }, [])

  useEffect(() => {
    void cargar()
  }, [cargar])

  async function subir(e) {
    e.preventDefault()
    const faltan = validar(titulo, archivo, t)
    setErrores(faltan)
    if (Object.keys(faltan).length) return
    setSubiendo(true)
    setAviso(null)
    try {
      await subirVideo(archivo, titulo.trim())
      setAviso({ tono: 'exito', titulo: t('productor.videos.enviado'), descripcion: t('productor.videos.enviadoTexto') })
      setTitulo('')
      setArchivo(null)
      await cargar()
    } catch (err) {
      setAviso({ tono: 'error', titulo: t('productor.videos.noSubio'), descripcion: mensajeError(err, 'productor.videos.seCorto') })
    } finally {
      setSubiendo(false)
    }
  }

  const lleno = (videos ?? []).filter(ocupaLugar).length >= MAX_VIDEOS
  return (
    <section className="tarjeta-panel videos-tienda" aria-labelledby="titulo-videos">
      <div className="tienda-editor__destacados-cabecera">
        <h2 id="titulo-videos" className="heading-h4">{t('productor.videos.titulo')}</h2>
        <span className="caption">{t('productor.videos.limites', { max: MAX_VIDEOS, mb: MAX_MB, minutos: MAX_MINUTOS })}</span>
      </div>
      <p className="body-s">{t('productor.videos.revision')}</p>
      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}

      {errorLista && <Alerta tono="error" titulo={t('productor.videos.errorLista')} />}
      {!videos && !errorLista && <Cargando />}
      {videos?.length === 0 && <p className="caption">{t('productor.videos.vacio')}</p>}
      {videos?.length > 0 && (
        <ul className="videos-tienda__lista" aria-label={t('productor.videos.lista')}>
          {videos.map((v) => (
            <li key={v.id} className="videos-tienda__item">
              <video src={v.url} controls preload="metadata" aria-label={v.titulo} />
              <div className="videos-tienda__datos">
                <span className="label-m">{v.titulo}</span>
                <span className="caption">{[duracion(v.duracionSegundos), v.formato, `${megas(v.bytes)} MB`].join(' · ')}</span>
                <Badge tono={TONO[v.estado]}>{t(`productor.videos.estado.${v.estado}`)}</Badge>
                {v.estado === 'RECHAZADO' && v.motivo && (
                  <p className="body-s">{t('productor.videos.motivo', { motivo: v.motivo })}</p>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {lleno ? (
        <p className="caption">{t('productor.videos.lleno', { max: MAX_VIDEOS })}</p>
      ) : (
        <form className="videos-tienda__formulario" onSubmit={subir} noValidate>
          <Campo etiqueta={t('productor.videos.nombre')} value={titulo} maxLength={120} error={errores.titulo}
            placeholder={t('productor.videos.nombreEjemplo')} onChange={(e) => setTitulo(e.target.value)} />
          <div className="videos-tienda__archivo">
            <label className="boton boton--secundario boton--M videos-tienda__elegir">
              <Icono src={iconoSubir} tamano={16} />
              {archivo ? t('productor.videos.cambiar') : t('productor.videos.elegir')}
              <input className="visually-hidden" type="file" accept={TIPOS_VIDEO.join(',')} aria-describedby="videos-elegido"
                onChange={(e) => { setArchivo(e.target.files?.[0] ?? null); setErrores({ ...errores, archivo: undefined }); e.target.value = '' }} />
            </label>
            <span id="videos-elegido" className={errores.archivo ? 'campo__ayuda campo__ayuda--error' : 'caption'} role={errores.archivo ? 'alert' : undefined}>
              {errores.archivo ?? (archivo ? t('productor.videos.elegido', { nombre: archivo.name, mb: megas(archivo.size) }) : t('productor.videos.ninguno'))}
            </span>
          </div>
          <Boton tamano="M" type="submit" disabled={subiendo}>
            {subiendo ? t('productor.videos.subiendo') : t('productor.videos.subir')}
          </Boton>
        </form>
      )}
    </section>
  )
}
