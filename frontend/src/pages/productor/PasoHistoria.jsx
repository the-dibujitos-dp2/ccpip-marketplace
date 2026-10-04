import { useEffect, useState } from 'react'
import iconoCheck from '../../assets/icons/check.svg'
import iconoEditar from '../../assets/icons/editar.svg'
import iconoIa from '../../assets/icons/ia.svg'
import { aprobarHistoria, guardarHistoria, proponerHistoria } from '../../api/productor.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Icono from '../../components/Icono.jsx'
import './Editor.css'

export const PREGUNTAS = [
  { campo: 'quienes', texto: '¿Quiénes lo elaboran?', ejemplo: '34 mujeres shipibo de la asociación, en Yarinacocha.' },
  { campo: 'como', texto: '¿Cómo lo hacen?', ejemplo: 'Recolectamos la semilla en el monte, la secamos al sol y la prensamos en frío.' },
  { campo: 'significado', texto: '¿Qué significa para su comunidad?', ejemplo: 'Nos permite ganar sin talar y enseñar a nuestras hijas.' },
  { campo: 'cuidado', texto: '¿Cómo cuidan el bosque al producirlo?', ejemplo: 'Solo cosechamos en nuestras chacras y purmas; no quemamos ni talamos.' },
]

const MINIMO = 40

/**
 * PW-05 · paso 2 "Historia" (CCPIP-48, CCPIP-49). La IA propone un borrador a partir de las
 * respuestas; nunca se publica sin que el productor lo apruebe, y siempre se indica que fue
 * asistido (RNF-29). Si la IA no esta disponible, el productor la escribe con sus palabras.
 */
export default function PasoHistoria({ producto, onAprobada, onAtras, onOmitir }) {
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

  async function pedir(variante) {
    if (respondidas === 0) {
      setAlerta({ tono: 'aviso', titulo: 'Responde al menos una pregunta', descripcion: 'La IA arma el borrador solo con tus palabras.' })
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
        setAlerta({ tono: 'aviso', titulo: 'La redacción asistida no está disponible', descripcion: 'Escribe la historia con tus palabras; tus respuestas te sirven de guía.' })
      } else {
        setAlerta({ tono: 'error', titulo: err.message ?? 'No pudimos generar el borrador' })
      }
    } finally {
      setEstado(null)
    }
  }

  async function aprobar({ titulo, texto, asistida }) {
    if (texto.trim().length < MINIMO) {
      setAlerta({ tono: 'error', titulo: `La historia debe tener al menos ${MINIMO} caracteres` })
      return
    }
    setEstado('aprobando')
    setAlerta(null)
    try {
      await guardarHistoria(producto.id, { ...respuestas, titulo: titulo || null, textoFinal: texto })
      await aprobarHistoria(producto.id, { titulo: titulo || null, textoFinal: texto, asistida })
      onAprobada()
    } catch (err) {
      setAlerta({ tono: 'error', titulo: err.message ?? 'No pudimos aprobar la historia', descripcion: err.body?.campos ? Object.values(err.body.campos).join(' · ') : undefined })
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
      setAlerta({ tono: 'error', titulo: err.message ?? 'No pudimos guardar' })
    } finally {
      setEstado(null)
    }
  }

  return (
    <div className="editor__historia">
      <section className="tarjeta-panel" aria-labelledby="titulo-preguntas">
        <div>
          <h2 id="titulo-preguntas" className="heading-h2">Cuenta la historia con tus palabras</h2>
          <p className="body-s">Responde 4 preguntas cortas. Con ellas se arma el relato que lee el comprador.</p>
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
                  <span className="label-m pregunta__texto" id={`pregunta-${p.campo}`}>{p.texto}</span>
                  {!activa && (
                    <button type="button" className="pregunta__editar" aria-label={`Responder: ${p.texto}`} onClick={() => {
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
                    placeholder={p.ejemplo}
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
            <Badge tono="exito">Historia aprobada</Badge>
            <h2 id="titulo-propuesta" className="editor__propuesta-titulo">{manual.titulo || 'Historia de la comunidad'}</h2>
            <p className="editor__propuesta-texto">{manual.texto}</p>
            {h.asistida && <p className="caption">Redactada con apoyo de IA y aprobada por la comunidad.</p>}
            <div className="editor__botones">
              <Boton tipo="terciario" onClick={onAtras}>Atrás</Boton>
              <Boton tipo="secundario" icono={iconoEditar} onClick={() => setModo('manual')}>Editar historia</Boton>
              <Boton onClick={onOmitir}>Siguiente: primer lote</Boton>
            </div>
          </>
        )}

        {modo === 'inicial' && (
          <>
            <h2 id="titulo-propuesta" className="heading-h2">Tu historia, en borrador</h2>
            <p className="body-s">
              Cuando tengas tus respuestas, la plataforma puede proponerte un borrador. Lo revisas, lo cambias y solo se publica cuando lo apruebas.
            </p>
            <div className="editor__botones editor__botones--columna">
              <Boton icono={iconoIa} onClick={() => pedir('NORMAL')} disabled={estado === 'generando'}>
                {estado === 'generando' ? 'Preparando borrador…' : 'Generar borrador con IA'}
              </Boton>
              <Boton tipo="secundario" onClick={() => setModo('manual')}>Prefiero escribirla yo</Boton>
              <Boton tipo="terciario" onClick={guardarYSeguir} disabled={estado === 'guardando'}>Guardar respuestas y seguir después</Boton>
            </div>
          </>
        )}

        {modo === 'ia' && propuesta && (
          <>
            <span className="editor__ia-chip">
              <Icono src={iconoIa} tamano={14} />
              Borrador asistido por IA · revisa antes de publicar
            </span>
            {editando ? (
              <>
                <Campo etiqueta="Título" value={propuesta.titulo} onChange={(e) => setPropuesta({ ...propuesta, titulo: e.target.value })} maxLength={160} />
                <Campo etiqueta="Historia" tipo="area" rows={8} value={propuesta.texto} onChange={(e) => setPropuesta({ ...propuesta, texto: e.target.value })} maxLength={4000} />
              </>
            ) : (
              <>
                <h2 id="titulo-propuesta" className="editor__propuesta-titulo">{propuesta.titulo}</h2>
                <p className="editor__propuesta-texto">{propuesta.texto}</p>
              </>
            )}
            <div className="editor__ia-acciones">
              <Boton tipo="secundario" tamano="S" icono={iconoEditar} onClick={() => setEditando((v) => !v)}>
                {editando ? 'Listo' : 'Editar texto'}
              </Boton>
              <Boton tipo="terciario" tamano="S" icono={iconoIa} onClick={() => pedir('OTRA')} disabled={estado === 'generando'}>
                Proponer otra versión
              </Boton>
              <Boton tipo="terciario" tamano="S" onClick={() => pedir('CORTA')} disabled={estado === 'generando'}>
                Más corto
              </Boton>
            </div>
            <p className="caption">Este texto no se publicará hasta que lo apruebes. Se mostrará como “Relato de la comunidad”.</p>
            <div className="editor__botones">
              <Boton tipo="terciario" onClick={onAtras}>Atrás</Boton>
              <Boton icono={iconoCheck} onClick={() => aprobar({ titulo: propuesta.titulo, texto: propuesta.texto, asistida: true })} disabled={estado === 'aprobando'}>
                Aprobar historia
              </Boton>
            </div>
          </>
        )}

        {modo === 'manual' && (
          <>
            <h2 id="titulo-propuesta" className="heading-h2">Escribe la historia</h2>
            <Campo etiqueta="Título (opcional)" value={manual.titulo} onChange={(e) => setManual({ ...manual, titulo: e.target.value })} maxLength={160} placeholder="Aceite de las abuelas del monte" />
            <Campo
              etiqueta="Historia"
              tipo="area"
              rows={8}
              value={manual.texto}
              onChange={(e) => setManual({ ...manual, texto: e.target.value })}
              maxLength={4000}
              ayuda={`${manual.texto.trim().length} caracteres · mínimo ${MINIMO}`}
              placeholder="Cuenta quiénes lo hacen, cómo y por qué importa para tu comunidad."
            />
            <div className="editor__botones">
              <Boton tipo="terciario" onClick={() => setModo(propuesta ? 'ia' : 'inicial')}>Atrás</Boton>
              <Boton icono={iconoCheck} onClick={() => aprobar({ titulo: manual.titulo, texto: manual.texto, asistida: false })} disabled={estado === 'aprobando'}>
                Aprobar historia
              </Boton>
            </div>
          </>
        )}
      </section>
    </div>
  )
}
