import { useCallback, useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCandado from '../../assets/icons/candado.svg'
import iconoCheck from '../../assets/icons/check-circulo.svg'
import iconoEscudo from '../../assets/icons/escudo-check.svg'
import iconoHoja from '../../assets/icons/hoja.svg'
import iconoComunidad from '../../assets/icons/comunidad.svg'
import iconoMas from '../../assets/icons/mas.svg'
import { listarEvidencias, retirarEvidencia, subirEvidencia } from '../../api/autorizaciones.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import Icono from '../../components/Icono.jsx'
import { fechaCorta } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import AvisoRenovaciones from './AvisoRenovaciones.jsx'
import PaginaProductor from './PaginaProductor.jsx'
import './productor.css'
import './EvidenciasPage.css'

export const TIPOS = ['CERTIFICADO', 'DOCUMENTO', 'EVIDENCIA']
const ICONOS = { CERTIFICADO: iconoEscudo, DOCUMENTO: iconoHoja, EVIDENCIA: iconoComunidad }
const FORMATOS = ['application/pdf', 'image/jpeg', 'image/png']
const MAX_BYTES = 10 * 1024 * 1024

/** Vigencia de una evidencia, siempre con texto: vigente, vencida o sin vencimiento (RF-13). */
export function BadgeVigencia({ evidencia }) {
  const { t } = useTranslation()
  if (!evidencia.vigenteHasta) return <Badge tono="neutro">{t('evidencias.sinVencimiento')}</Badge>
  if (evidencia.vencida) return <Badge tono="error">{t('evidencias.vencida', { fecha: fechaCorta(evidencia.vigenteHasta) })}</Badge>
  return <Badge tono="exito">{t('evidencias.vigenteHasta', { fecha: fechaCorta(evidencia.vigenteHasta) })}</Badge>
}

function alertaEstado(autorizacion, t) {
  const { estado, motivo, vigenteHasta, cubreLineas } = autorizacion
  // CCPIP-292: una linea nueva no la cubre la autorizacion anterior; se pide otra revision
  if (estado === 'APROBADA' && cubreLineas === false) {
    return { tono: 'aviso', titulo: t('evidencias.estado.lineaNueva'), descripcion: t('evidencias.estado.lineaNuevaTexto') }
  }
  if (estado === 'APROBADA') {
    return {
      tono: 'exito',
      titulo: t('evidencias.estado.APROBADA'),
      descripcion: vigenteHasta ? t('evidencias.estado.aprobadaHasta', { fecha: fechaCorta(vigenteHasta) }) : undefined,
    }
  }
  if (estado === 'RECHAZADA' || estado === 'REVOCADA') {
    return { tono: 'error', titulo: t(`evidencias.estado.${estado}`), descripcion: t('evidencias.estado.conMotivo', { motivo }) }
  }
  return { tono: 'info', titulo: t(`evidencias.estado.${estado}`), descripcion: t(`evidencias.estado.${estado}Texto`) }
}

/**
 * CCPIP-36 (RF-13), adaptada de PW-14: el productor adjunta certificados, documentos y evidencias a
 * cada producto cuya autorizacion solicita. Solo el productor y la Camara las ven (RNF-08). Mientras
 * la autorizacion esta vigente, sus evidencias no se cambian.
 */
export default function EvidenciasPage({ path, productoId }) {
  const { t } = useTranslation()
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(null)
  const [aviso, setAviso] = useState(null)
  const [retirar, setRetirar] = useState(null)
  const [ocupado, setOcupado] = useState(false)

  const cargar = useCallback(async () => {
    try {
      setDatos(await listarEvidencias(productoId))
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
      await retirarEvidencia(productoId, retirar.id)
      setAviso({ tono: 'exito', titulo: t('evidencias.retirada', { titulo: retirar.titulo }) })
      await cargar()
    } catch (e) {
      setAviso({ tono: 'error', titulo: mensajeError(e, 'evidencias.errorRetirar') })
    } finally {
      setOcupado(false)
      setRetirar(null)
    }
  }

  const titulo = t('evidencias.titulo')
  const seccion = `${t('productor.nav.productos')}${datos ? ` / ${datos.producto}` : ''}`
  if (error && !datos) {
    return (
      <PaginaProductor path={RUTAS.productorProductos} seccion={seccion} titulo={titulo}>
        <Alerta tono="error" titulo={mensajeError(error, 'evidencias.error')} />
        <Boton tipo="secundario" tamano="M" to={RUTAS.productorProductos}>{t('evidencias.volver')}</Boton>
      </PaginaProductor>
    )
  }
  if (!datos) return <PaginaProductor path={RUTAS.productorProductos} seccion={seccion} titulo={titulo}><p className="caption">{t('comun.cargando')}</p></PaginaProductor>

  const editable = datos.autorizacion.estado !== 'APROBADA' || datos.puedeAdjuntar
  return (
    <PaginaProductor path={path ?? RUTAS.productorProductos} seccion={seccion} titulo={titulo}>
      <p className="body-m evidencias__intro">{t('evidencias.intro', { producto: datos.producto })}</p>
      <Alerta {...alertaEstado(datos.autorizacion, t)} />
      <AvisoRenovaciones productoId={productoId} />
      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}
      <div className="evidencias">
        <div className="evidencias__principal">
          {TIPOS.map((tipo) => (
            <TarjetaTipo
              key={tipo}
              tipo={tipo}
              evidencias={datos.evidencias.filter((e) => e.tipo === tipo)}
              puedeAdjuntar={datos.puedeAdjuntar}
              editable={editable}
              productoId={productoId}
              onSubida={async (evidencia) => {
                setAviso({ tono: 'exito', titulo: t('evidencias.subida', { titulo: evidencia.titulo }) })
                await cargar()
              }}
              onRetirar={setRetirar}
            />
          ))}
          {!datos.puedeAdjuntar && datos.autorizacion.estado !== 'APROBADA' && (
            <p className="caption">{t('evidencias.limite', { maximo: datos.maximo })}</p>
          )}
        </div>
        <aside className="evidencias__lateral" aria-labelledby="evidencias-necesitas">
          <h2 id="evidencias-necesitas" className="heading-h4">{t('evidencias.necesitas.titulo')}</h2>
          <ul>
            {TIPOS.map((tipo) => (
              <li key={tipo}>
                <Icono src={iconoCheck} tamano={18} />
                {t(`evidencias.necesitas.${tipo}`)}
              </li>
            ))}
          </ul>
          <p className="caption">
            <Icono src={iconoCandado} tamano={14} /> {t('evidencias.privadas')}
          </p>
          <p className="caption">{t('evidencias.decisionHumana')}</p>
        </aside>
      </div>

      <Dialogo
        abierto={retirar !== null}
        titulo={t('evidencias.retirarTitulo')}
        confirmar={t('evidencias.retirar')}
        tipoConfirmar="destructivo"
        ocupado={ocupado}
        onCancelar={() => setRetirar(null)}
        onConfirmar={confirmarRetiro}
      >
        <p>{t('evidencias.retirarTexto', { titulo: retirar?.titulo })}</p>
      </Dialogo>
    </PaginaProductor>
  )
}

function TarjetaTipo({ tipo, evidencias, puedeAdjuntar, editable, productoId, onSubida, onRetirar }) {
  const { t } = useTranslation()
  const [abierto, setAbierto] = useState(false)
  const idTitulo = useId()
  return (
    <section className="tarjeta-panel evidencias__tipo" aria-labelledby={idTitulo}>
      <div className="evidencias__encabezado">
        <Icono src={ICONOS[tipo]} tamano={20} />
        <h2 id={idTitulo} className="heading-h4">{t(`evidencias.tipo.${tipo}`)}</h2>
        {evidencias.length > 0
          ? <Badge tono="exito">{t('evidencias.archivos', { count: evidencias.length })}</Badge>
          : <Badge tono="neutro">{t('evidencias.pendiente')}</Badge>}
      </div>
      <p className="body-s">{t(`evidencias.ayuda.${tipo}`)}</p>
      {evidencias.length > 0 && (
        <ul className="evidencias__lista" aria-label={t(`evidencias.tipo.${tipo}`)}>
          {evidencias.map((e) => (
            <li key={e.id} className="evidencias__item">
              <span className="evidencias__datos">
                <span className="label-m">{e.titulo}</span>
                <span className="caption">
                  {[e.emisor, e.nombreArchivo, e.formato].filter(Boolean).join(' · ')}
                </span>
              </span>
              <BadgeVigencia evidencia={e} />
              <a className="evidencias__ver" href={e.url} target="_blank" rel="noopener noreferrer" aria-label={t('evidencias.verDe', { titulo: e.titulo })}>
                {t('evidencias.ver')}
              </a>
              {editable && (
                <Boton tipo="terciario" tamano="S" onClick={() => onRetirar(e)} aria-label={t('evidencias.retirarDe', { titulo: e.titulo })}>
                  {t('evidencias.retirar')}
                </Boton>
              )}
            </li>
          ))}
        </ul>
      )}
      {puedeAdjuntar && !abierto && (
        <Boton tipo="secundario" tamano="M" icono={iconoMas} onClick={() => setAbierto(true)}>
          {t(`evidencias.agregar.${tipo}`)}
        </Boton>
      )}
      {puedeAdjuntar && abierto && (
        <FormularioEvidencia
          tipo={tipo}
          productoId={productoId}
          onCancelar={() => setAbierto(false)}
          onSubida={(evidencia) => {
            setAbierto(false)
            return onSubida(evidencia)
          }}
        />
      )}
    </section>
  )
}

/** Valida lo mismo que el servidor antes de enviar; el servidor vuelve a validar el contenido. */
function validar(campos, archivo, tipo, t) {
  const errores = {}
  if (!campos.titulo.trim()) errores.titulo = t('evidencias.form.errorTitulo')
  if (tipo === 'CERTIFICADO' && !campos.vigenteHasta) errores.vigenteHasta = t('evidencias.form.errorVigencia')
  if (campos.vigenteDesde && campos.vigenteHasta && campos.vigenteDesde > campos.vigenteHasta) {
    errores.vigenteHasta = t('evidencias.form.errorOrden')
  }
  if (!archivo) errores.archivo = t('evidencias.form.errorArchivo')
  else if (!FORMATOS.includes(archivo.type)) errores.archivo = t('evidencias.form.errorFormato')
  else if (archivo.size > MAX_BYTES) errores.archivo = t('evidencias.form.errorPeso')
  return errores
}

function FormularioEvidencia({ tipo, productoId, onCancelar, onSubida }) {
  const { t } = useTranslation()
  const idArchivo = useId()
  const [campos, setCampos] = useState({ titulo: '', emisor: '', vigenteDesde: '', vigenteHasta: '' })
  const [archivo, setArchivo] = useState(null)
  const [errores, setErrores] = useState({})
  const [enviando, setEnviando] = useState(false)
  const [fallo, setFallo] = useState(null)
  const cambiar = (campo) => (e) => setCampos({ ...campos, [campo]: e.target.value })

  async function enviar(e) {
    e.preventDefault()
    const nuevos = validar(campos, archivo, tipo, t)
    setErrores(nuevos)
    if (Object.keys(nuevos).length) return
    setEnviando(true)
    setFallo(null)
    try {
      const evidencia = await subirEvidencia(productoId, archivo, { tipo, ...campos, titulo: campos.titulo.trim(), emisor: campos.emisor.trim() })
      await onSubida(evidencia)
    } catch (error) {
      setFallo(mensajeError(error, 'evidencias.errorSubir'))
    } finally {
      setEnviando(false)
    }
  }

  return (
    <form className="evidencias__formulario" onSubmit={enviar} noValidate aria-label={t(`evidencias.agregar.${tipo}`)}>
      {fallo && <Alerta tono="error" titulo={fallo} />}
      <Campo etiqueta={t('evidencias.form.titulo')} value={campos.titulo} onChange={cambiar('titulo')} error={errores.titulo} maxLength={160} />
      <Campo etiqueta={t('evidencias.form.emisor')} ayuda={t('evidencias.form.opcional')} value={campos.emisor} onChange={cambiar('emisor')} maxLength={160} />
      <div className="evidencias__fechas">
        <Campo etiqueta={t('evidencias.form.desde')} type="date" value={campos.vigenteDesde} onChange={cambiar('vigenteDesde')} />
        <Campo
          etiqueta={tipo === 'CERTIFICADO' ? t('evidencias.form.hasta') : `${t('evidencias.form.hasta')} ${t('evidencias.form.opcionalCorto')}`}
          type="date"
          value={campos.vigenteHasta}
          onChange={cambiar('vigenteHasta')}
          error={errores.vigenteHasta}
        />
      </div>
      <div className="campo">
        <label className="campo__etiqueta" htmlFor={idArchivo}>{t('evidencias.form.archivo')}</label>
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
        <Boton tamano="M" type="submit" disabled={enviando}>{enviando ? t('evidencias.form.subiendo') : t('evidencias.form.subir')}</Boton>
      </div>
    </form>
  )
}
