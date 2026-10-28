import { useCallback, useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCandado from '../../assets/icons/candado.svg'
import iconoMas from '../../assets/icons/mas.svg'
import { listarPremios, registrarPremio, retirarPremio } from '../../api/premios.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import Icono from '../../components/Icono.jsx'
import { diaLima, fechaCorta } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import PaginaProductor from './PaginaProductor.jsx'
import './productor.css'
import './EvidenciasPage.css'

const FORMATOS = ['application/pdf', 'image/jpeg', 'image/png']
const MAX_BYTES = 10 * 1024 * 1024

/** Como se nombra un lote al elegirlo: su codigo y la fecha de produccion, si la tiene. */
function nombreLote(lote, t) {
  return lote.produccion ? t('premios.lote', { codigo: lote.codigo, fecha: fechaCorta(lote.produccion) }) : lote.codigo
}

/**
 * CCPIP-43 (RF-29): el productor registra los premios de un producto, cada uno vinculado a uno de sus
 * lotes, con la entidad que lo otorgo, la fecha, la vigencia y el archivo que lo respalda. El archivo
 * es privado; en la ficha publica solo se ven el premio, su emisor, la fecha y la vigencia (CCPIP-169).
 */
export default function PremiosPage({ productoId }) {
  const { t } = useTranslation()
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(null)
  const [aviso, setAviso] = useState(null)
  const [abierto, setAbierto] = useState(false)
  const [retirar, setRetirar] = useState(null)
  const [ocupado, setOcupado] = useState(false)

  const cargar = useCallback(async () => {
    try {
      setDatos(await listarPremios(productoId))
      setError(null)
    } catch (e) {
      setError(e)
    }
  }, [productoId])

  useEffect(() => {
    cargar()
  }, [cargar])

  async function confirmarRetiro() {
    setOcupado(true)
    try {
      await retirarPremio(productoId, retirar.id)
      setAviso({ tono: 'exito', titulo: t('premios.retirado', { titulo: retirar.titulo }) })
      await cargar()
    } catch (e) {
      setAviso({ tono: 'error', titulo: mensajeError(e, 'premios.errorRetirar') })
    } finally {
      setOcupado(false)
      setRetirar(null)
    }
  }

  const titulo = t('premios.titulo')
  const seccion = `${t('productor.nav.productos')}${datos ? ` / ${datos.producto}` : ''}`
  if (error && !datos) {
    return (
      <PaginaProductor path={RUTAS.productorProductos} seccion={seccion} titulo={titulo}>
        <Alerta tono="error" titulo={mensajeError(error, 'premios.error')} />
        <Boton tipo="secundario" tamano="M" to={RUTAS.productorProductos}>{t('evidencias.volver')}</Boton>
      </PaginaProductor>
    )
  }
  if (!datos) return <PaginaProductor path={RUTAS.productorProductos} seccion={seccion} titulo={titulo}><Cargando /></PaginaProductor>

  const sinLotes = datos.lotes.length === 0
  return (
    <PaginaProductor path={RUTAS.productorProductos} seccion={seccion} titulo={titulo}>
      <p className="body-m evidencias__intro">{t('premios.intro', { producto: datos.producto })}</p>
      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}
      <div className="evidencias">
        <section className="tarjeta-panel evidencias__tipo" aria-labelledby="premios-lista">
          <div className="evidencias__encabezado">
            <h2 id="premios-lista" className="heading-h4">{t('premios.registrados')}</h2>
            <Badge tono={datos.premios.length ? 'exito' : 'neutro'}>{t('premios.cuantos', { count: datos.premios.length })}</Badge>
          </div>
          {datos.premios.length === 0 && <p className="body-s">{t('premios.ninguno')}</p>}
          {datos.premios.length > 0 && (
            <ul className="evidencias__lista" aria-label={t('premios.registrados')}>
              {datos.premios.map((p) => (
                <li key={p.id} className="evidencias__item">
                  <span className="evidencias__datos">
                    <span className="label-m">{p.titulo}</span>
                    <span className="caption">
                      {[p.emisor, t('premios.otorgado', { fecha: fechaCorta(p.otorgadoEn) }), nombreLote(p.lote, t)].join(' · ')}
                    </span>
                  </span>
                  {!p.vigenteHasta && <Badge tono="neutro">{t('evidencias.sinVencimiento')}</Badge>}
                  {p.vigenteHasta && (
                    <Badge tono={p.vigente ? 'exito' : 'error'}>
                      {t(p.vigente ? 'evidencias.vigenteHasta' : 'evidencias.vencida', { fecha: fechaCorta(p.vigenteHasta) })}
                    </Badge>
                  )}
                  <a className="evidencias__ver" href={p.url} target="_blank" rel="noopener noreferrer" aria-label={t('evidencias.verDe', { titulo: p.titulo })}>
                    {t('evidencias.ver')}
                  </a>
                  <Boton tipo="terciario" tamano="S" onClick={() => setRetirar(p)} aria-label={t('premios.retirarDe', { titulo: p.titulo })}>
                    {t('evidencias.retirar')}
                  </Boton>
                </li>
              ))}
            </ul>
          )}
          {sinLotes && <Alerta tono="info" titulo={t('premios.sinLotes')} />}
          {!sinLotes && datos.puedeRegistrar && !abierto && (
            <Boton tipo="secundario" tamano="M" icono={iconoMas} onClick={() => setAbierto(true)}>{t('premios.agregar')}</Boton>
          )}
          {!datos.puedeRegistrar && <p className="caption">{t('premios.limite', { maximo: datos.maximo })}</p>}
          {!sinLotes && datos.puedeRegistrar && abierto && (
            <FormularioPremio
              productoId={productoId}
              lotes={datos.lotes}
              onCancelar={() => setAbierto(false)}
              onRegistrado={async (premio) => {
                setAbierto(false)
                setAviso({ tono: 'exito', titulo: t('premios.registrado', { titulo: premio.titulo }) })
                await cargar()
              }}
            />
          )}
        </section>
        <aside className="evidencias__lateral" aria-labelledby="premios-como">
          <h2 id="premios-como" className="heading-h4">{t('premios.como.titulo')}</h2>
          <p className="caption">{t('premios.como.publico')}</p>
          <p className="caption">
            <Icono src={iconoCandado} tamano={14} /> {t('premios.como.privado')}
          </p>
        </aside>
      </div>

      <Dialogo
        abierto={retirar !== null}
        titulo={t('premios.retirarTitulo')}
        confirmar={t('evidencias.retirar')}
        tipoConfirmar="destructivo"
        ocupado={ocupado}
        onCancelar={() => setRetirar(null)}
        onConfirmar={confirmarRetiro}
      >
        <p>{t('premios.retirarTexto', { titulo: retirar?.titulo })}</p>
      </Dialogo>
    </PaginaProductor>
  )
}

/** Valida lo mismo que el servidor antes de enviar; el servidor vuelve a validar el contenido. */
function validar(campos, archivo, t) {
  const errores = {}
  if (!campos.titulo.trim()) errores.titulo = t('premios.form.errorTitulo')
  if (!campos.emisor.trim()) errores.emisor = t('premios.form.errorEmisor')
  if (!campos.otorgadoEn) errores.otorgadoEn = t('premios.form.errorFecha')
  else if (campos.otorgadoEn > diaLima()) errores.otorgadoEn = t('premios.form.errorFutura')
  if (campos.vigenteHasta && campos.otorgadoEn && campos.vigenteHasta < campos.otorgadoEn) {
    errores.vigenteHasta = t('premios.form.errorOrden')
  }
  if (!campos.loteId) errores.loteId = t('premios.form.errorLote')
  if (!archivo) errores.archivo = t('evidencias.form.errorArchivo')
  else if (!FORMATOS.includes(archivo.type)) errores.archivo = t('evidencias.form.errorFormato')
  else if (archivo.size > MAX_BYTES) errores.archivo = t('evidencias.form.errorPeso')
  return errores
}

function FormularioPremio({ productoId, lotes, onCancelar, onRegistrado }) {
  const { t } = useTranslation()
  const idArchivo = useId()
  const [campos, setCampos] = useState({ titulo: '', emisor: '', otorgadoEn: '', vigenteHasta: '', loteId: lotes.length === 1 ? String(lotes[0].id) : '' })
  const [archivo, setArchivo] = useState(null)
  const [errores, setErrores] = useState({})
  const [enviando, setEnviando] = useState(false)
  const [fallo, setFallo] = useState(null)
  const cambiar = (campo) => (e) => setCampos({ ...campos, [campo]: e.target.value })

  async function enviar(e) {
    e.preventDefault()
    const nuevos = validar(campos, archivo, t)
    setErrores(nuevos)
    if (Object.keys(nuevos).length) return
    setEnviando(true)
    setFallo(null)
    try {
      const premio = await registrarPremio(productoId, archivo, { ...campos, titulo: campos.titulo.trim(), emisor: campos.emisor.trim() })
      await onRegistrado(premio)
    } catch (error) {
      setFallo(mensajeError(error, 'premios.errorRegistrar'))
    } finally {
      setEnviando(false)
    }
  }

  return (
    <form className="evidencias__formulario" onSubmit={enviar} noValidate aria-label={t('premios.agregar')}>
      {fallo && <Alerta tono="error" titulo={fallo} />}
      <Campo etiqueta={t('premios.form.titulo')} value={campos.titulo} onChange={cambiar('titulo')} error={errores.titulo} maxLength={160} />
      <Campo etiqueta={t('premios.form.emisor')} value={campos.emisor} onChange={cambiar('emisor')} error={errores.emisor} maxLength={160} />
      <Campo
        etiqueta={t('premios.form.lote')}
        tipo="selector"
        value={campos.loteId}
        onChange={cambiar('loteId')}
        error={errores.loteId}
        opciones={lotes.map((l) => ({ valor: String(l.id), etiqueta: nombreLote(l, t) }))}
      />
      <div className="evidencias__fechas">
        <Campo etiqueta={t('premios.form.otorgado')} type="date" max={diaLima()} value={campos.otorgadoEn} onChange={cambiar('otorgadoEn')} error={errores.otorgadoEn} />
        <Campo
          etiqueta={`${t('evidencias.form.hasta')} ${t('evidencias.form.opcionalCorto')}`}
          type="date"
          value={campos.vigenteHasta}
          onChange={cambiar('vigenteHasta')}
          error={errores.vigenteHasta}
        />
      </div>
      <div className="campo">
        <label className="campo__etiqueta" htmlFor={idArchivo}>{t('premios.form.archivo')}</label>
        <input
          id={idArchivo}
          type="file"
          accept="application/pdf,image/jpeg,image/png"
          aria-invalid={errores.archivo ? 'true' : undefined}
          onChange={(e) => setArchivo(e.target.files?.[0] ?? null)}
        />
        <p className={`campo__ayuda${errores.archivo ? ' campo__ayuda--error' : ''}`}>{errores.archivo ?? t('evidencias.form.formatos')}</p>
      </div>
      <div className="evidencias__acciones">
        <Boton tipo="terciario" tamano="M" onClick={onCancelar} disabled={enviando}>{t('comun.cancelar')}</Boton>
        <Boton tamano="M" type="submit" cargando={enviando}>{enviando ? t('evidencias.form.subiendo') : t('premios.form.registrar')}</Boton>
      </div>
    </form>
  )
}
