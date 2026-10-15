import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCheck from '../../assets/icons/check.svg'
import iconoEditar from '../../assets/icons/editar.svg'
import iconoIa from '../../assets/icons/ia.svg'
import { aprobarHistoria, guardarHistoria, proponerHistoria } from '../../api/productor.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Icono from '../../components/Icono.jsx'
import { mensajeError } from '../../i18n/errores.js'
import i18n from '../../i18n/index.js'
import './Editor.css'

/** Preguntas guiadas; su texto y ejemplo estan en el diccionario (productor.historia.preguntas). */
export const PREGUNTAS = ['quienes', 'como', 'significado', 'cuidado'].map((campo) => ({ campo }))

const MINIMO = 40

/**
 * PW-05 · paso 2 "Historia" (CCPIP-48, CCPIP-49). La IA propone un borrador a partir de las
 * respuestas; nunca se publica sin que el productor lo apruebe, y siempre se indica que fue
 * asistido (RNF-29). Si la IA no esta disponible, el productor la escribe con sus palabras.
 */
export default function PasoHistoria({ producto, onAprobada, onAtras, onOmitir }) {
  const { t } = useTranslation()
  const h = producto.historia ?? {}
  const [respuestas, setRespuestas] = useState({ quienes: h.quienes ?? '', como: h.como ?? '', significado: h.significado ?? '', cuidado: h.cuidado ?? '' })
  const [abierta, setAbierta] = useState(() => PREGUNTAS.find((p) => !h[p.campo])?.campo ?? null)
  // Solo se lleva el foco a una pregunta cuando el productor la abre con su boton; si se abre
  // sola al salir de otra o al cargar, robaria el foco del campo donde esta escribiendo
  const [enfocar, setEnfocar] = useState(false)
  useEffect(() => {
    if (enfocar && abierta) document.getElementById(`respuesta-${abierta}`)?.focus()
  }, [enfocar, abierta])
  const [propuesta, setPropuesta] = useState(h.textoPropuesto && !h.aprobada ? { titulo: h.titulo, texto: h.textoPropuesto } : null)
  const [modo, setModo] = useState(h.aprobada ? 'aprobada' : h.textoPropuesto ? 'ia' : 'inicial')
  const [editando, setEditando] = useState(false)
  const [manual, setManual] = useState({ titulo: h.titulo ?? '', texto: h.textoFinal ?? '' })
  const [estado, setEstado] = useState(null)
  const [alerta, setAlerta] = useState(null)

  useEffect(() => {
    if (h.aprobada) setManual({ titulo: h.titulo ?? '', texto: h.textoFinal ?? '' })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [producto.id])

  const respondidas = PREGUNTAS.filter((p) => respuestas[p.campo].trim()).length
  const pregunta = (p) => t(`productor.historia.preguntas.${p.campo}.texto`)

  async function pedir(variante) {
    if (respondidas === 0) {
      setAlerta({ tono: 'aviso', titulo: t('productor.historia.respondeUna'), descripcion: t('productor.historia.respondeUnaTexto') })
      return
    }
    setEstado('generando')
    setAlerta(null)
    try {
      const p = await proponerHistoria(producto.id, respuestas, variante)
      setPropuesta({ titulo: p.titulo ?? '', texto: p.texto })
      setModo('ia')
      setEditando(false)
    } catch (err) {
      if (err.code === 'IA_NO_DISPONIBLE') {
        setModo('manual')
        setAlerta({ tono: 'aviso', titulo: t('productor.datos.ia.noDisponible'), descripcion: t('productor.historia.noDisponibleTexto') })
      } else {
        setAlerta({ tono: 'error', titulo: mensajeError(err, 'productor.datos.ia.error') })
      }
    } finally {
      setEstado(null)
    }
  }

  async function aprobar({ titulo, texto, asistida }) {
    if (texto.trim().length < MINIMO) {
      setAlerta({ tono: 'error', titulo: t('productor.historia.minimo', { minimo: MINIMO }) })
      return
    }
    setEstado('aprobando')
    setAlerta(null)
    try {
      await guardarHistoria(producto.id, { ...respuestas, titulo: titulo || null, textoFinal: texto })
      await aprobarHistoria(producto.id, { titulo: titulo || null, textoFinal: texto, asistida })
      onAprobada()
    } catch (err) {
      // Los mensajes por campo llegan del servidor en espanol: en ingles basta el del code
      const campos = i18n.resolvedLanguage === 'es' && err.body?.campos ? Object.values(err.body.campos).join(' · ') : undefined
      setAlerta({ tono: 'error', titulo: mensajeError(err, 'productor.historia.errorAprobar'), descripcion: campos })
    } finally {
      setEstado(null)
    }
  }

  async function guardarYSeguir() {
    setEstado('guardando')
    try {
      await guardarHistoria(producto.id, { ...respuestas, titulo: null, textoFinal: null })
      onOmitir()
    } catch (err) {
      setAlerta({ tono: 'error', titulo: mensajeError(err, 'productor.datos.errorGuardar') })
    } finally {
      setEstado(null)
    }
  }

  return (
    <div className="editor__historia">
      <section className="tarjeta-panel" aria-labelledby="titulo-preguntas">
        <div>
          <h2 id="titulo-preguntas" className="heading-h2">{t('productor.historia.cuenta')}</h2>
          <p className="body-s">{t('productor.historia.cuentaTexto')}</p>
        </div>
        <ol className="preguntas">
          {PREGUNTAS.map((p, i) => {
            const lista = respuestas[p.campo].trim() !== ''
            const activa = abierta === p.campo
            return (
              <li key={p.campo} className={`pregunta${activa ? ' pregunta--activa' : ''}`}>
                <div className="pregunta__cabecera">
                  <span className={`pregunta__marca${lista && !activa ? ' pregunta__marca--lista' : ''}`} aria-hidden="true">
                    {lista && !activa ? <Icono src={iconoCheck} tamano={14} /> : i + 1}
                  </span>
                  <span className="label-m pregunta__texto" id={`pregunta-${p.campo}`}>{pregunta(p)}</span>
                  {!activa && (
                    <button type="button" className="pregunta__editar" aria-label={t('productor.historia.responder', { pregunta: pregunta(p) })} onClick={() => {
                        setEnfocar(true)
                        setAbierta(p.campo)
                      }}>
                      <Icono src={iconoEditar} tamano={18} />
                    </button>
                  )}
                </div>
                {activa ? (
                  <textarea
                    className="campo__control campo__control--area pregunta__respuesta"
                    aria-labelledby={`pregunta-${p.campo}`}
                    placeholder={t(`productor.historia.preguntas.${p.campo}.ejemplo`)}
                    maxLength={1000}
                    value={respuestas[p.campo]}
                    id={`respuesta-${p.campo}`}
                    onChange={(e) => setRespuestas({ ...respuestas, [p.campo]: e.target.value })}
                    onBlur={() => {
                      setEnfocar(false)
                      setAbierta(PREGUNTAS.find((x) => x.campo !== p.campo && !respuestas[x.campo].trim())?.campo ?? null)
                    }}
                  />
                ) : (
                  lista && <p className="body-s pregunta__resumen">{respuestas[p.campo]}</p>
                )}
              </li>
            )
          })}
        </ol>
      </section>

      <section className={`editor__propuesta${modo === 'ia' ? ' editor__propuesta--ia' : ''}`} aria-labelledby="titulo-propuesta" aria-live="polite">
        {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}

        {modo === 'aprobada' && (
          <>
            <Badge tono="exito">{t('productor.historia.aprobada')}</Badge>
            <h2 id="titulo-propuesta" className="editor__propuesta-titulo">{manual.titulo || t('productor.historia.deLaComunidad')}</h2>
            <p className="editor__propuesta-texto">{manual.texto}</p>
            {h.asistida && <p className="caption">{t('productor.historia.redactadaIa')}</p>}
            <div className="editor__botones">
              <Boton tipo="terciario" onClick={onAtras}>{t('productor.historia.atras')}</Boton>
              <Boton tipo="secundario" icono={iconoEditar} onClick={() => setModo('manual')}>{t('productor.historia.editar')}</Boton>
              <Boton onClick={onOmitir}>{t('productor.historia.siguienteLote')}</Boton>
            </div>
          </>
        )}

        {modo === 'inicial' && (
          <>
            <h2 id="titulo-propuesta" className="heading-h2">{t('productor.historia.enBorrador')}</h2>
            <p className="body-s">{t('productor.historia.enBorradorTexto')}</p>
            <div className="editor__botones editor__botones--columna">
              <Boton icono={iconoIa} onClick={() => pedir('NORMAL')} disabled={estado === 'generando'}>
                {estado === 'generando' ? t('productor.historia.preparando') : t('productor.historia.generar')}
              </Boton>
              <Boton tipo="secundario" onClick={() => setModo('manual')}>{t('productor.historia.escribirYo')}</Boton>
              <Boton tipo="terciario" onClick={guardarYSeguir} disabled={estado === 'guardando'}>{t('productor.historia.seguirDespues')}</Boton>
            </div>
          </>
        )}

        {modo === 'ia' && propuesta && (
          <>
            <span className="editor__ia-chip">
              <Icono src={iconoIa} tamano={14} />
              {t('productor.datos.ia.borrador')}
            </span>
            {editando ? (
              <>
                <Campo etiqueta={t('productor.historia.titulo')} value={propuesta.titulo} onChange={(e) => setPropuesta({ ...propuesta, titulo: e.target.value })} maxLength={160} />
                <Campo etiqueta={t('productor.historia.historia')} tipo="area" rows={8} value={propuesta.texto} onChange={(e) => setPropuesta({ ...propuesta, texto: e.target.value })} maxLength={4000} />
              </>
            ) : (
              <>
                <h2 id="titulo-propuesta" className="editor__propuesta-titulo">{propuesta.titulo}</h2>
                <p className="editor__propuesta-texto">{propuesta.texto}</p>
              </>
            )}
            <div className="editor__ia-acciones">
              <Boton tipo="secundario" tamano="S" icono={iconoEditar} onClick={() => setEditando((v) => !v)}>
                {editando ? t('productor.historia.listo') : t('productor.historia.editarTexto')}
              </Boton>
              <Boton tipo="terciario" tamano="S" icono={iconoIa} onClick={() => pedir('OTRA')} disabled={estado === 'generando'}>
                {t('productor.historia.otraVersion')}
              </Boton>
              <Boton tipo="terciario" tamano="S" onClick={() => pedir('CORTA')} disabled={estado === 'generando'}>
                {t('productor.historia.masCorto')}
              </Boton>
            </div>
            <p className="caption">{t('productor.historia.noSePublica')}</p>
            <div className="editor__botones">
              <Boton tipo="terciario" onClick={onAtras}>{t('productor.historia.atras')}</Boton>
              <Boton icono={iconoCheck} onClick={() => aprobar({ titulo: propuesta.titulo, texto: propuesta.texto, asistida: true })} disabled={estado === 'aprobando'}>
                {t('productor.historia.aprobar')}
              </Boton>
            </div>
          </>
        )}

        {modo === 'manual' && (
          <>
            <h2 id="titulo-propuesta" className="heading-h2">{t('productor.historia.escribe')}</h2>
            <Campo etiqueta={t('productor.historia.tituloOpcional')} value={manual.titulo} onChange={(e) => setManual({ ...manual, titulo: e.target.value })} maxLength={160} placeholder={t('productor.historia.tituloEjemplo')} />
            <Campo
              etiqueta={t('productor.historia.historia')}
              tipo="area"
              rows={8}
              value={manual.texto}
              onChange={(e) => setManual({ ...manual, texto: e.target.value })}
              maxLength={4000}
              ayuda={t('productor.historia.caracteres', { total: manual.texto.trim().length, minimo: MINIMO })}
              placeholder={t('productor.historia.historiaPlaceholder')}
            />
            <div className="editor__botones">
              <Boton tipo="terciario" onClick={() => setModo(propuesta ? 'ia' : 'inicial')}>{t('productor.historia.atras')}</Boton>
              <Boton icono={iconoCheck} onClick={() => aprobar({ titulo: manual.titulo, texto: manual.texto, asistida: false })} disabled={estado === 'aprobando'}>
                {t('productor.historia.aprobar')}
              </Boton>
            </div>
          </>
        )}
      </section>
    </div>
  )
}
