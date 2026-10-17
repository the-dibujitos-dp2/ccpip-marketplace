import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { aprobarTraducciones, consultarTraducciones, traducirContenido } from '../../api/productor.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import { mensajeError } from '../../i18n/errores.js'
import './PanelTraduccion.css'

const TONO = { SIN: 'neutro', PENDIENTE: 'info', APROBADA: 'exito', DESACTUALIZADA: 'aviso' }
const id = (c) => `${c.entidad}.${c.campo}`

/**
 * CCPIP-147 (RF-168) en PW-04 y PW-08: el productor pide la traduccion automatica de su contenido
 * con el proveedor que exista y la revisa antes de publicarla (RNF-33). Si no hay proveedor, avisa
 * que no esta disponible y el contenido sigue en su original (RNF-34). Muestra tambien que campos
 * quedaron desactualizados al editar el original (CCPIP-373). Sin `productoId`, es la tienda.
 */
export default function PanelTraduccion({ productoId, destino = 'en' }) {
  const { t } = useTranslation()
  const [estado, setEstado] = useState(null)
  const [borradores, setBorradores] = useState({})
  const [ocupado, setOcupado] = useState(null)
  const [alerta, setAlerta] = useState(null)

  useEffect(() => {
    let vigente = true
    setEstado(null)
    consultarTraducciones(productoId, destino)
      .then((e) => vigente && setEstado(e))
      // Sin el estado no se ofrece traducir; el resto del editor sigue igual
      .catch(() => vigente && setEstado(null))
    return () => {
      vigente = false
    }
  }, [productoId, destino])

  if (!estado) return null

  const idioma = t(`traduccion.idiomas.${destino}`)
  const pendientes = estado.campos.filter((c) => c.estado === 'PENDIENTE')

  async function traducir() {
    setOcupado('traducir')
    setAlerta(null)
    try {
      setEstado(await traducirContenido(productoId, destino))
      setBorradores({})
    } catch (e) {
      setAlerta(e.code === 'TRADUCCION_NO_DISPONIBLE'
        ? { tono: 'aviso', titulo: t('traduccion.panel.noDisponible'), descripcion: t('traduccion.panel.noDisponibleTexto') }
        : { tono: 'error', titulo: mensajeError(e, 'traduccion.panel.error') })
    } finally {
      setOcupado(null)
    }
  }

  async function publicar() {
    setOcupado('publicar')
    setAlerta(null)
    // Solo viajan los textos que el productor corrigio; lo demas se publica como se propuso
    const textos = pendientes
      .filter((c) => borradores[id(c)] != null && borradores[id(c)].trim() !== c.texto)
      .map((c) => ({ entidad: c.entidad, campo: c.campo, texto: borradores[id(c)].trim() }))
    if (textos.some((x) => !x.texto)) {
      setAlerta({ tono: 'error', titulo: t('traduccion.panel.vacia') })
      setOcupado(null)
      return
    }
    try {
      setEstado(await aprobarTraducciones(productoId, destino, textos))
      setBorradores({})
      setAlerta({ tono: 'exito', titulo: t('traduccion.panel.publicada') })
    } catch (e) {
      setAlerta({ tono: 'error', titulo: mensajeError(e, 'traduccion.panel.errorPublicar') })
    } finally {
      setOcupado(null)
    }
  }

  return (
    <section className="tarjeta-panel panel-traduccion" aria-labelledby={`titulo-traduccion-${productoId ?? 'tienda'}`}>
      <h2 id={`titulo-traduccion-${productoId ?? 'tienda'}`} className="heading-h4">{t('traduccion.panel.titulo', { idioma })}</h2>
      <p className="caption">{t('traduccion.panel.texto', { idioma })}</p>

      {!estado.automaticaDisponible && (
        <Alerta tono="info" titulo={t('traduccion.panel.noDisponible')} descripcion={t('traduccion.panel.noDisponibleTexto')} />
      )}
      {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}

      {estado.campos.length === 0 ? (
        <p className="body-s">{t('traduccion.panel.sinTexto')}</p>
      ) : (
        <ul className="panel-traduccion__campos">
          {estado.campos.map((c) => {
            const clave = c.texto == null ? 'SIN' : c.estado
            return (
              <li key={id(c)} className="panel-traduccion__campo">
                <div className="panel-traduccion__cabecera">
                  <span className="label-m">{t(`traduccion.campos.${c.entidad}.${c.campo}`)}</span>
                  <Badge tono={TONO[clave]}>{t(`traduccion.estado.${clave}`)}</Badge>
                  {c.origen && clave !== 'SIN' && <span className="caption">{t(`traduccion.origen.${c.origen}`)}</span>}
                </div>
                {c.estado === 'PENDIENTE' ? (
                  <Campo
                    tipo="area"
                    rows={3}
                    lang={destino}
                    etiqueta={t('traduccion.panel.revisa', { campo: t(`traduccion.campos.${c.entidad}.${c.campo}`) })}
                    value={borradores[id(c)] ?? c.texto}
                    onChange={(e) => setBorradores((b) => ({ ...b, [id(c)]: e.target.value }))}
                    maxLength={4000}
                  />
                ) : (
                  c.texto && <p className="body-s panel-traduccion__texto" lang={destino}>{c.texto}</p>
                )}
              </li>
            )
          })}
        </ul>
      )}

      <div className="panel-traduccion__acciones">
        <Boton tipo="secundario" tamano="M" onClick={traducir} disabled={!estado.automaticaDisponible || estado.campos.length === 0 || ocupado != null}>
          {ocupado === 'traducir' ? t('traduccion.panel.traduciendo') : t(`traduccion.traducirAl.${destino}`)}
        </Boton>
        {pendientes.length > 0 && (
          <Boton tamano="M" onClick={publicar} disabled={ocupado != null}>
            {ocupado === 'publicar' ? t('traduccion.panel.publicando') : t('traduccion.panel.publicar')}
          </Boton>
        )}
      </div>
    </section>
  )
}
