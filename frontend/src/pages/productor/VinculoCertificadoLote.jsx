import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { leerLoteEvidencia, vincularEvidenciaLote } from '../../api/autorizaciones.js'
import { listarLotes } from '../../api/productor.js'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'

export default function VinculoCertificadoLote({ productoId, evidencia }) {
  const { t } = useTranslation()
  const [version, setVersion] = useState(0)
  const [cargando, setCargando] = useState(true)
  const [errorLectura, setErrorLectura] = useState(false)
  const [vinculo, setVinculo] = useState(null)
  const [lotes, setLotes] = useState([])
  const [abierto, setAbierto] = useState(false)
  const [seleccion, setSeleccion] = useState('')
  const [guardando, setGuardando] = useState(false)
  const [errorGuardar, setErrorGuardar] = useState(false)
  const envio = useRef(false)
  const montado = useRef(true)
  const completo = Boolean(evidencia.emisor?.trim() && evidencia.vigenteDesde && evidencia.vigenteHasta)
  useEffect(() => { montado.current = true; return () => { montado.current = false } }, [])
  useEffect(() => {
    if (!completo) return undefined
    let activo = true
    setCargando(true)
    setErrorLectura(false)
    Promise.all([leerLoteEvidencia(productoId, evidencia.id), listarLotes(productoId)]).then(([actual, datos]) => {
      if (!activo) return
      setVinculo(actual ?? null)
      setLotes(datos.items)
      setCargando(false)
    }).catch(() => { if (activo) { setErrorLectura(true); setCargando(false) } })
    return () => { activo = false }
  }, [productoId, evidencia.id, completo, version])
  async function guardar(e) {
    e.preventDefault()
    if (envio.current || !lotes.some(l => String(l.id) === seleccion)) return
    envio.current = true
    setGuardando(true)
    setErrorGuardar(false)
    try {
      await vincularEvidenciaLote(productoId, evidencia.id, seleccion)
      const actual = await leerLoteEvidencia(productoId, evidencia.id)
      if (!actual) throw new Error('Vinculo no disponible tras guardar')
      if (montado.current) { setVinculo(actual); setAbierto(false) }
    } catch { if (montado.current) setErrorGuardar(true) }
    finally { envio.current = false; if (montado.current) setGuardando(false) }
  }
  const key = 'evidencias.vinculo.'
  const retry = <Boton tipo="secundario" tamano="S" disabled={guardando} onClick={() => setVersion(v => v + 1)}>{t(key + 'reintentar')}</Boton>
  if (!completo) return <div className="evidencias__vinculo"><p className="caption">{t(key + 'incompleto')}</p></div>
  if (cargando) return <div className="evidencias__vinculo" role="status">{t(key + 'cargando')}</div>
  if (errorLectura) return <div className="evidencias__vinculo"><p role="alert">{t(key + 'errorLeer')}</p>{retry}</div>
  if (vinculo) {
    const codigo = lotes.find(l => l.id === vinculo.loteId)?.codigo ?? String(vinculo.loteId)
    return <div className="evidencias__vinculo"><p role="status">{t(key + 'registrado', { lote: codigo })}</p><p className="caption">{t(key + 'sinAprobacion')}</p></div>
  }
  if (!abierto) return <div className="evidencias__vinculo"><Boton tipo="secundario" tamano="S" onClick={() => setAbierto(true)}>{t(key + 'accion')}</Boton></div>
  return <form className="evidencias__vinculo" onSubmit={guardar} aria-label={t(key + 'formulario', { titulo: evidencia.titulo })} aria-busy={guardando}>
    <p className="caption">{t(key + 'metadatos', { emisor: evidencia.emisor, desde: evidencia.vigenteDesde, hasta: evidencia.vigenteHasta })}</p>
    <Campo tipo="selector" etiqueta={t(key + 'lote', { titulo: evidencia.titulo })} opciones={lotes.map(l => ({ valor: String(l.id), etiqueta: l.codigo }))} value={seleccion} onChange={e => setSeleccion(e.target.value)} disabled={guardando} required />
    {!lotes.length && <p className="caption">{t(key + 'sinLotes')}</p>}
    {errorGuardar && <div><p role="alert">{t(key + 'errorGuardar')}</p>{retry}</div>}
    <p className="caption">{t(key + 'sinAprobacion')}</p>
    <div className="evidencias__vinculo-acciones">
      <Boton type="submit" tamano="S" cargando={guardando} disabled={!seleccion || !lotes.length}>{t(key + 'accion')}</Boton>
      <Boton tipo="terciario" tamano="S" disabled={guardando} onClick={() => { setAbierto(false); setSeleccion(''); setErrorGuardar(false) }}>{t('comun.cancelar')}</Boton>
    </div>
  </form>
}
