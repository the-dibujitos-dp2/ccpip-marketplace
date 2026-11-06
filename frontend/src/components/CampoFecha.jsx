import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCalendario from '../assets/icons/calendario.svg'
import iconoChevron from '../assets/icons/chevron-abajo.svg'
import { diaLima } from '../formato.js'
import Icono from './Icono.jsx'

const pad = (n) => String(n).padStart(2, '0')

function isoDe(anio, mes, dia) {
  const d = new Date(Date.UTC(anio, mes - 1, dia))
  if (anio < 1000 || d.getUTCFullYear() !== anio || d.getUTCMonth() !== mes - 1 || d.getUTCDate() !== dia) return null
  return `${anio}-${pad(mes)}-${pad(dia)}`
}

/**
 * Lo escrito a fecha ISO, o null si aun no es una fecha valida. Acepta dd/mm/aaaa (tambien con - o .),
 * los 8 digitos seguidos (ddmmaaaa) y aaaa-mm-dd (lo que pega quien copia de otro sistema).
 */
export function aIso(texto) {
  const t = (texto ?? '').trim()
  let m = t.match(/^(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})$/) || t.match(/^(\d{2})(\d{2})(\d{4})$/)
  if (m) return isoDe(Number(m[3]), Number(m[2]), Number(m[1]))
  m = t.match(/^(\d{4})-(\d{2})-(\d{2})$/)
  return m ? isoDe(Number(m[1]), Number(m[2]), Number(m[3])) : null
}

/** "2026-11-05" -> "05/11/2026" */
export function aTexto(iso) {
  const m = (iso ?? '').match(/^(\d{4})-(\d{2})-(\d{2})$/)
  return m ? `${m[3]}/${m[2]}/${m[1]}` : ''
}

const partes = (iso) => iso.split('-').map(Number)
/** Dia de la semana con lunes = 0 */
const diaSemana = (iso) => (new Date(`${iso}T00:00:00Z`).getUTCDay() + 6) % 7
function sumarDias(iso, n) {
  const d = new Date(`${iso}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + n)
  return d.toISOString().slice(0, 10)
}
function sumarMeses(iso, n) {
  const [a, m, d] = partes(iso)
  const ultimo = new Date(Date.UTC(a, m - 1 + n + 1, 0)).getUTCDate()
  const destino = new Date(Date.UTC(a, m - 1 + n, Math.min(d, ultimo)))
  return destino.toISOString().slice(0, 10)
}
const enRango = (iso, min, max) => (!min || iso >= min) && (!max || iso <= max)

/**
 * CCPIP-455: fecha con el mismo formato y en el idioma de la plataforma, sin depender del navegador
 * (el <input type="date"> nativo muestra "mm/dd/yyyy" o "dd/mm/yy" segun el sistema). Se escribe
 * dd/mm/aaaa o se elige en un calendario con meses y dias en el idioma elegido. Hacia afuera es como el
 * nativo: `value` y `onChange(e)` con `e.target.value` en ISO (aaaa-mm-dd) o '' si se borra; mientras
 * lo escrito no es una fecha completa no avisa nada. `min` y `max` (ISO) desactivan dias del calendario.
 */
export default function CampoFecha({ control, etiqueta, value, onChange, onBlur, min, max, name, disabled, ...resto }) {
  const { t } = useTranslation()
  const [escrito, setEscrito] = useState(() => ({ texto: aTexto(value), valor: value }))
  const [abierto, setAbierto] = useState(false)
  const [foco, setFoco] = useState(null)
  const raiz = useRef(null)
  const boton = useRef(null)
  const rejilla = useRef(null)
  const idCalendario = `${control.id}-calendario`

  // Si la fecha cambia desde afuera (valores por defecto, limpiar filtros) se muestra en el mismo render;
  // si es la que ya esta escrita, se respeta lo escrito
  let texto = escrito.texto
  if (escrito.valor !== value) {
    const actual = escrito.texto
    texto = (aIso(actual) ?? '') === (value ?? '') && (actual.trim() !== '' || !value) ? actual : aTexto(value)
    setEscrito({ texto, valor: value })
  }
  const setTexto = (nuevo) => setEscrito((e) => ({ ...e, texto: nuevo }))

  const emitir = (iso) => {
    if (iso !== (value ?? '')) onChange?.({ target: { value: iso, name }, currentTarget: { value: iso, name } })
  }

  function escribir(e) {
    const nuevo = e.target.value
    const iso = aIso(nuevo)
    // Una fecha completa (escrita, pegada o en aaaa-mm-dd) queda de inmediato como dd/mm/aaaa
    setTexto(iso ? aTexto(iso) : nuevo)
    if (nuevo.trim() === '') emitir('')
    else if (iso) emitir(iso)
  }

  function salir(e) {
    // Al dejar el campo queda en dd/mm/aaaa; lo incompleto vuelve a la ultima fecha valida
    const iso = aIso(texto)
    setTexto(iso ? aTexto(iso) : texto.trim() === '' ? '' : aTexto(value))
    onBlur?.(e)
  }

  function abrir() {
    const base = value && /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : diaLima()
    setFoco(enRango(base, min, max) ? base : (min && base < min ? min : max || base))
    setAbierto(true)
  }

  function cerrar(devolverFoco = true) {
    setAbierto(false)
    if (devolverFoco) boton.current?.focus()
  }

  function elegir(iso) {
    setTexto(aTexto(iso))
    emitir(iso)
    cerrar()
  }

  // Al abrir o moverse con el teclado, el foco va al dia activo
  useEffect(() => {
    if (abierto) rejilla.current?.querySelector('[data-activo="true"]')?.focus()
  }, [abierto, foco])

  // Un clic fuera lo cierra sin robar el foco
  useEffect(() => {
    if (!abierto) return undefined
    const fuera = (e) => { if (!raiz.current?.contains(e.target)) cerrar(false) }
    document.addEventListener('mousedown', fuera)
    return () => document.removeEventListener('mousedown', fuera)
  }, [abierto])

  function teclas(e) {
    const mover = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 }[e.key]
    if (mover) {
      e.preventDefault()
      setFoco((f) => sumarDias(f, mover))
    } else if (e.key === 'PageUp' || e.key === 'PageDown') {
      e.preventDefault()
      setFoco((f) => sumarMeses(f, e.key === 'PageUp' ? -1 : 1))
    } else if (e.key === 'Home' || e.key === 'End') {
      e.preventDefault()
      setFoco((f) => sumarDias(f, e.key === 'Home' ? -diaSemana(f) : 6 - diaSemana(f)))
    }
  }

  const meses = t('campo.fecha.meses', { returnObjects: true })
  const dias = t('campo.fecha.dias', { returnObjects: true })
  const diasLargos = t('campo.fecha.diasLargos', { returnObjects: true })
  const hoy = diaLima()
  const nombreDia = (iso) => {
    const [a, m, d] = partes(iso)
    return t('campo.fecha.dia', { diaSemana: diasLargos[diaSemana(iso)], dia: d, mes: meses[m - 1], anio: a })
  }

  let semanas = []
  let titulo = ''
  if (abierto && foco) {
    const [a, m] = partes(foco)
    titulo = t('campo.fecha.mesAnio', { mes: meses[m - 1], anio: a })
    const primero = `${a}-${pad(m)}-01`
    const inicio = sumarDias(primero, -diaSemana(primero))
    for (let s = 0; s < 6; s += 1) {
      const semana = Array.from({ length: 7 }, (_, k) => sumarDias(inicio, s * 7 + k))
      if (s > 0 && partes(semana[0])[1] !== m) break
      semanas.push(semana)
    }
  }
  const mesFoco = foco ? partes(foco)[1] : 0

  return (
    <div className="campo__con-accion campo__fecha" ref={raiz}>
      <input
        {...control}
        {...resto}
        type="text"
        inputMode="numeric"
        autoComplete="off"
        placeholder={t('campo.fecha.formato')}
        name={name}
        disabled={disabled}
        value={texto}
        onChange={escribir}
        onBlur={salir}
      />
      <button
        ref={boton}
        type="button"
        className="campo__accion"
        onClick={() => (abierto ? cerrar() : abrir())}
        aria-label={t('campo.fecha.abrir', { etiqueta })}
        aria-expanded={abierto}
        aria-controls={abierto ? idCalendario : undefined}
        disabled={disabled}
      >
        <Icono src={iconoCalendario} tamano={20} />
      </button>
      {abierto && foco && (
        <div
          id={idCalendario}
          className="campo__calendario"
          role="dialog"
          aria-label={t('campo.fecha.calendario', { etiqueta })}
          onKeyDown={(e) => { if (e.key === 'Escape') { e.stopPropagation(); cerrar() } }}
        >
          <div className="campo__calendario-cabecera">
            <button type="button" className="campo__calendario-mes" onClick={() => setFoco((f) => sumarMeses(f, -1))} aria-label={t('campo.fecha.anterior')}>
              <Icono src={iconoChevron} tamano={20} className="campo__calendario-previo" />
            </button>
            <p className="label-m campo__calendario-titulo" aria-live="polite">{titulo}</p>
            <button type="button" className="campo__calendario-mes" onClick={() => setFoco((f) => sumarMeses(f, 1))} aria-label={t('campo.fecha.siguiente')}>
              <Icono src={iconoChevron} tamano={20} className="campo__calendario-proximo" />
            </button>
          </div>
          <table role="grid" className="campo__calendario-rejilla" aria-label={titulo} ref={rejilla} onKeyDown={teclas}>
            <thead>
              <tr>
                {dias.map((d, k) => <th key={d} scope="col" abbr={diasLargos[k]}>{d}</th>)}
              </tr>
            </thead>
            <tbody>
              {semanas.map((semana) => (
                <tr key={semana[0]}>
                  {semana.map((iso) => {
                    const fuera = partes(iso)[1] !== mesFoco
                    const elegido = iso === value
                    const permitido = enRango(iso, min, max)
                    return (
                      <td key={iso} role="gridcell" aria-selected={elegido}>
                        <button
                          type="button"
                          className={`campo__dia${fuera ? ' campo__dia--fuera' : ''}${elegido ? ' campo__dia--elegido' : ''}${iso === hoy ? ' campo__dia--hoy' : ''}`}
                          tabIndex={iso === foco ? 0 : -1}
                          data-activo={iso === foco}
                          aria-label={nombreDia(iso)}
                          aria-current={iso === hoy ? 'date' : undefined}
                          aria-pressed={elegido}
                          disabled={!permitido}
                          onClick={() => elegir(iso)}
                        >
                          {partes(iso)[2]}
                        </button>
                      </td>
                    )
                  })}
                </tr>
              ))}
            </tbody>
          </table>
          <div className="campo__calendario-pie">
            <button type="button" className="campo__calendario-enlace" onClick={() => elegir(hoy)} disabled={!enRango(hoy, min, max)}>
              {t('campo.fecha.hoy')}
            </button>
            {value && (
              <button type="button" className="campo__calendario-enlace" onClick={() => { setTexto(''); emitir(''); cerrar() }}>
                {t('campo.fecha.borrar')}
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
