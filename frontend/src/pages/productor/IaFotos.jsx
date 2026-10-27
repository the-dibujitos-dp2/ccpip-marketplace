import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoAviso from '../../assets/icons/alerta-aviso.svg'
import iconoError from '../../assets/icons/alerta-error.svg'
import iconoExito from '../../assets/icons/check-circulo.svg'
import iconoIa from '../../assets/icons/ia.svg'
import iconoInfo from '../../assets/icons/info.svg'
import { listarValidaciones, validarImagen } from '../../api/productor.js'
import Dialogo from '../../components/Dialogo.jsx'
import Giro from '../../components/Giro.jsx'
import Icono from '../../components/Icono.jsx'

/** El proveedor simulado no es IA real: la interfaz lo dice en vez de afirmar un resultado. */
const esPrueba = (proveedor) => proveedor === 'simulado'

/**
 * CCPIP-144 y CCPIP-168: la ultima validacion de cada foto, con las que se piden desde aqui.
 * `porFoto[id]`: la respuesta del servidor (estado ANALIZANDO, VALIDA, SOSPECHOSA o ERROR) o el
 * estado local mientras se analiza o si fallo la peticion.
 */
export function useValidaciones(productoId, imagenes) {
  const [porFoto, setPorFoto] = useState({})
  const ids = imagenes.map((i) => i.id).join(',')

  useEffect(() => {
    if (!productoId || !ids) return undefined
    let vigente = true
    listarValidaciones(productoId)
      .then((lista) => {
        if (!vigente || !Array.isArray(lista)) return
        // Lo que se esta analizando desde esta pantalla no se pisa con un resultado anterior, y un
        // resultado recien recibido se conserva aunque la lista se haya pedido antes de terminar
        setPorFoto((previo) => ({ ...previo, ...Object.fromEntries(lista.map((v) => [v.imagenId, v])), ...soloLocales(previo) }))
      })
      .catch(() => {})
    return () => {
      vigente = false
    }
  }, [productoId, ids])

  async function validar(imagenId, { conLinea = true } = {}) {
    if (!conLinea) {
      // Sin linea elegida el servidor no puede validar (LINEA_REQUERIDA): se avisa sin pedirlo
      setPorFoto((p) => ({ ...p, [imagenId]: { ...p[imagenId], imagenId, estado: 'ERROR', local: false, codigoError: 'LINEA_REQUERIDA' } }))
      return
    }
    setPorFoto((p) => ({ ...p, [imagenId]: { ...p[imagenId], imagenId, estado: 'ANALIZANDO', local: true } }))
    try {
      const v = await validarImagen(imagenId)
      setPorFoto((p) => ({ ...p, [imagenId]: v }))
    } catch (err) {
      // CCPIP-168: un error no bloquea nada; se sigue con la foto original
      setPorFoto((p) => ({ ...p, [imagenId]: { ...p[imagenId], imagenId, estado: 'ERROR', local: false, codigoError: err.code ?? null } }))
    }
  }

  return { porFoto, validar }
}

function soloLocales(porFoto) {
  return Object.fromEntries(Object.entries(porFoto).filter(([, v]) => v.local))
}

/** Tono, icono y textos del aviso de una foto segun su validacion (DS "Validacion de imagen (IA)"). */
function aviso(v, n, nombreLinea, lineaActual, t) {
  const esperada = nombreLinea(v?.lineaEsperada?.codigo ?? lineaActual) ?? ''
  switch (v?.estado) {
    case 'ANALIZANDO':
      return { tono: 'info', icono: iconoIa, titulo: t('productor.datos.iaFotos.analizandoTitulo', { n }),
        texto: esperada ? t('productor.datos.iaFotos.analizandoTexto', { linea: esperada }) : t('productor.datos.iaFotos.analizandoSinLinea') }
    case 'VALIDA':
      return { tono: 'exito', icono: iconoExito, titulo: t('productor.datos.iaFotos.validaTitulo', { n }),
        texto: t('productor.datos.iaFotos.validaTexto', { linea: nombreLinea(v.linea?.codigo) ?? esperada }) }
    case 'SOSPECHOSA':
      return { tono: 'aviso', icono: iconoAviso, titulo: t('productor.datos.iaFotos.sospechosaTitulo', { n }),
        texto: t('productor.datos.iaFotos.sospechosaTexto', { detectada: nombreLinea(v.linea?.codigo), esperada }) }
    case 'ERROR':
      if (v.codigoError === 'IA_NO_DISPONIBLE') {
        return { tono: 'neutro', icono: iconoInfo, titulo: t('productor.datos.iaFotos.noDisponibleTitulo', { n }),
          texto: t('productor.datos.iaFotos.noDisponibleTexto') }
      }
      if (v.codigoError === 'LINEA_REQUERIDA') {
        return { tono: 'neutro', icono: iconoInfo, titulo: t('productor.datos.iaFotos.sinValidarTitulo', { n }),
          texto: t('productor.datos.iaFotos.lineaRequerida') }
      }
      return { tono: 'error', icono: iconoError, titulo: t('productor.datos.iaFotos.errorTitulo', { n }),
        texto: t('productor.datos.iaFotos.errorTexto') }
    default:
      return { tono: 'neutro', icono: iconoInfo, titulo: t('productor.datos.iaFotos.sinValidarTitulo', { n }),
        texto: t('productor.datos.iaFotos.sinValidarTexto') }
  }
}

/**
 * PW-04 (Figma 185:4509): un aviso por foto con la recomendacion de la IA. Nunca bloquea: la foto
 * sospechosa se puede reemplazar desde el aviso (CCPIP-364) o dejar como esta, y la mejora es
 * siempre una eleccion expresa (CCPIP-176).
 */
export function AvisosFotos({ imagenes, porFoto, nombreLinea, lineaActual, onValidar, onReemplazar, onMejorar, reemplazando, mejorando }) {
  const { t } = useTranslation()
  if (!imagenes.length) return null
  return (
    <ul className="editor__ia-fotos" aria-label={t('productor.datos.iaFotos.titulo')} aria-live="polite">
      {imagenes.map((img, i) => {
        const v = porFoto[img.id]
        const a = aviso(v, i + 1, nombreLinea, lineaActual, t)
        const analizando = v?.estado === 'ANALIZANDO'
        const sinResultado = !v || v.estado === 'ERROR'
        return (
          <li key={img.id} id={`aviso-foto-${img.id}`} tabIndex={-1} className={`editor__ia-foto editor__ia-foto--${a.tono}`}>
            <Icono src={a.icono} tamano={20} />
            <div className="editor__ia-foto-texto">
              <p className="editor__ia-foto-titulo">
                {a.titulo}
                {esPrueba(v?.proveedor) && (
                  <span className="editor__ia-prueba" title={t('productor.datos.iaFotos.pruebaAyuda')}>{t('productor.datos.iaFotos.prueba')}</span>
                )}
              </p>
              <p className="body-s">{a.texto}</p>
              {v?.estado === 'SOSPECHOSA' && <p className="caption">{t('productor.datos.iaFotos.noBloquea')}</p>}
              <div className="editor__ia-foto-acciones">
                {v?.estado === 'SOSPECHOSA' && (
                  <label className={`editor__ia-enlace${reemplazando ? ' editor__ia-enlace--ocupado' : ''}`}>
                    {t('productor.datos.iaFotos.reemplazar')}
                    <input className="visually-hidden" type="file" accept="image/jpeg,image/png,image/webp" disabled={reemplazando}
                      onChange={(e) => { void onReemplazar(img, e.target.files?.[0]); e.target.value = '' }} />
                  </label>
                )}
                {sinResultado && !analizando && (
                  <button type="button" className="editor__ia-enlace" onClick={() => void onValidar(img.id)}>
                    {v ? t('productor.datos.iaFotos.validarOtraVez') : t('productor.datos.iaFotos.validar')}
                  </button>
                )}
                {!analizando && (
                  <button type="button" className="editor__ia-enlace" disabled={mejorando !== null} aria-busy={mejorando === img.id || undefined} onClick={() => void onMejorar(img)}>
                    {/* CCPIP-413: el aro de carga en la foto que se esta mejorando */}
                    {mejorando === img.id ? <Giro tamano={16} /> : <Icono src={iconoIa} tamano={16} />}
                    {mejorando === img.id ? t('productor.datos.mejora.preparando') : t('productor.datos.mejora.mejorar')}
                  </button>
                )}
              </div>
            </div>
          </li>
        )
      })}
    </ul>
  )
}

/** Chip "Revisar" sobre la miniatura de una foto sospechosa: lleva a su aviso. */
export function ChipRevisar({ imagenId }) {
  const { t } = useTranslation()
  function irAlAviso() {
    const aviso = document.getElementById(`aviso-foto-${imagenId}`)
    aviso?.scrollIntoView?.({ block: 'nearest' })
    aviso?.focus()
  }
  return (
    <button type="button" className="editor__revisar" onClick={irAlAviso}>
      <Icono src={iconoAviso} tamano={14} />
      {t('productor.datos.iaFotos.revisar')}
    </button>
  )
}

/**
 * CCPIP-272, CCPIP-281 y CCPIP-282: la original y la mejorada lado a lado. Nada cambia hasta
 * que el productor elige; cerrar sin elegir deja la propuesta pendiente.
 */
export function ComparacionMejora({ comparacion, ocupado, onUsar, onConservar, onCerrar }) {
  const { t } = useTranslation()
  return (
    <Dialogo
      abierto={comparacion !== null}
      ancho="amplio"
      titulo={t('productor.datos.mejora.titulo')}
      confirmar={t('productor.datos.mejora.usar')}
      cancelar={t('productor.datos.mejora.conservar')}
      tipoCancelar="secundario"
      ocupado={ocupado}
      onConfirmar={onUsar}
      onCancelar={onConservar}
      onCerrar={onCerrar}
      etiquetaCerrar={t('productor.datos.mejora.decidirDespues')}
    >
      {comparacion && (
        <>
          <p>{t('productor.datos.mejora.texto')}</p>
          {esPrueba(comparacion.proveedor) && <p className="caption">{t('productor.datos.mejora.prueba')}</p>}
          <div className="editor__comparacion">
            <figure>
              <img src={comparacion.original.url} alt={t('productor.datos.mejora.altOriginal')} />
              <figcaption className="label-m">{t('productor.datos.mejora.original')}</figcaption>
            </figure>
            <figure>
              <img src={comparacion.mejorada.url} alt={t('productor.datos.mejora.altMejorada')} />
              <figcaption className="label-m">{t('productor.datos.mejora.mejorada')}</figcaption>
            </figure>
          </div>
        </>
      )}
    </Dialogo>
  )
}
