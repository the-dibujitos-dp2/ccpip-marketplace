import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoProhibido from '../../assets/icons/prohibido.svg'
import iconoTienda from '../../assets/icons/tienda.svg'
import iconoCheck from '../../assets/icons/check-circulo.svg'
import { decidirProductor, fichaProductor } from '../../api/autorizaciones.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import Enlace from '../../components/Enlace.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { fechaCorta, fechaHora } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import { BadgesProductor } from './ProductoresPage.jsx'
import '../productor/productor.css'
import './admin.css'

const TONO_ESTADO = { SIN_SOLICITUD: 'neutro', SOLICITADA: 'info', APROBADA: 'exito', RECHAZADA: 'error', REVOCADA: 'error' }
const TONO_DECISION = { HABILITAR_TIENDA: 'exito', REVOCAR_VENTA: 'error', RESTITUIR_VENTA: 'info' }

/** Autorizacion de un producto: estado y, si esta aprobada, hasta cuando vale. */
function EstadoAutorizacion({ asociacion: a }) {
  const { t } = useTranslation()
  return (
    <>
      <Badge tono={a.estado === 'APROBADA' && a.vencida ? 'aviso' : TONO_ESTADO[a.estado]}>
        {a.estado === 'APROBADA' && a.vencida ? t('productores.vencida') : t(`productores.estado.${a.estado}`)}
      </Badge>
      {a.estado === 'APROBADA' && a.vigenteHasta && !a.vencida && (
        <span className="caption">{t('productores.hasta', { fecha: fechaCorta(a.vigenteHasta) })}</span>
      )}
    </>
  )
}

/**
 * Ficha del productor para el sub administrador. CCPIP-292: cada producto que puede vender, con su
 * autorizacion individual y las lineas que cubrio (cambiar de linea no extiende el permiso).
 * CCPIP-291: habilitar su tienda. CCPIP-330: revocar o restituir su venta. Todo con motivo.
 */
export default function FichaProductorPage({ path, id }) {
  const { t } = useTranslation()
  const [ficha, setFicha] = useState(null)
  const [error, setError] = useState(null)
  const [aviso, setAviso] = useState(null)
  const [motivo, setMotivo] = useState('')
  const [errorMotivo, setErrorMotivo] = useState(null)
  const [confirmar, setConfirmar] = useState(null) // 'habilitar-tienda' | 'revocar' | 'restituir'
  const [ocupado, setOcupado] = useState(false)

  const cargar = useCallback(async () => {
    try {
      setFicha(await fichaProductor(id))
      setError(null)
    } catch (e) {
      setError(e)
    }
  }, [id])

  useEffect(() => {
    cargar()
  }, [cargar])

  function pedirConfirmacion(accion) {
    if (!motivo.trim()) {
      setErrorMotivo(t('autorizaciones.expediente.motivoRequerido'))
      return
    }
    setErrorMotivo(null)
    setConfirmar(accion)
  }

  async function decidir() {
    setOcupado(true)
    try {
      setFicha(await decidirProductor(id, confirmar, motivo.trim()))
      setAviso({ tono: 'exito', titulo: t(`productores.accion.${confirmar}.hecho`) })
      setMotivo('')
    } catch (e) {
      setAviso({ tono: 'error', titulo: mensajeError(e, 'autorizaciones.expediente.errorDecision') })
      await cargar()
    } finally {
      setOcupado(false)
      setConfirmar(null)
    }
  }

  const migas = `${t('admin.nav.productores')}${ficha ? ` / ${ficha.organizacion}` : ''}`
  if (!ficha) {
    return (
      <LayoutAdmin path={path} migas={migas} titulo={t('productores.ficha')}>
        {error ? <Alerta tono="error" titulo={mensajeError(error, 'productores.errorFicha')} /> : <p className="caption">{t('comun.cargando')}</p>}
        <Boton tipo="secundario" tamano="M" to={RUTAS.adminProductores}>{t('productores.volver')}</Boton>
      </LayoutAdmin>
    )
  }

  const acciones = [
    ficha.puedeHabilitarTienda && { clave: 'habilitar-tienda', tipo: 'primario', icono: iconoTienda },
    !ficha.ventaRevocada && { clave: 'revocar', tipo: 'destructivo', icono: iconoProhibido },
    ficha.ventaRevocada && { clave: 'restituir', tipo: 'secundario', icono: iconoCheck },
  ].filter(Boolean)

  return (
    <LayoutAdmin path={path} migas={migas} titulo={ficha.organizacion}>
      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}
      <div className="expediente">
        <div className="expediente__principal">
          <section className="tarjeta-panel expediente__cabecera" aria-label={t('productores.ficha')}>
            <div>
              <h2 className="heading-h3">{ficha.organizacion}</h2>
              <p className="body-s">{[ficha.comunidad, ficha.pueblo, ficha.region].filter(Boolean).join(' · ')}</p>
              {!ficha.cuentaActiva && <p className="caption">{t('productores.sinCuenta')}</p>}
            </div>
            <div className="productores__estado"><BadgesProductor productor={ficha} /></div>
          </section>

          <section className="tarjeta-panel" aria-labelledby="ficha-productos">
            <h2 id="ficha-productos" className="heading-h4">{t('productores.productos', { count: ficha.productos.length })}</h2>
            <p className="caption">{t('productores.productosAyuda')}</p>
            {ficha.productos.length === 0 && <p className="body-s">{t('productores.sinProductos')}</p>}
            <ul className="expediente__evidencias">
              {ficha.productos.map((a) => (
                <li key={a.productoId} className="expediente__evidencia">
                  <div className="expediente__evidencia-fila">
                    <span className="expediente__evidencia-datos">
                      <span className="label-m">{a.producto}</span>
                      <span className="caption">
                        {t('productores.lineas', { lineas: a.lineas.join(', ') || '—' })}
                        {a.lineasAutorizadas.length > 0 && ` · ${t('productores.lineasAutorizadas', { lineas: a.lineasAutorizadas.join(', ') })}`}
                      </span>
                      {!a.cubreLineas && <Badge tono="aviso">{t('productores.lineaSinAutorizar')}</Badge>}
                    </span>
                    <EstadoAutorizacion asociacion={a} />
                    {a.vendible
                      ? <Badge tono="exito">{t('productores.vendible')}</Badge>
                      : <Badge tono="neutro">{t('productores.pausada')}</Badge>}
                    {a.autorizacionId && (
                      <Enlace className="evidencias__ver" to={`${RUTAS.adminAutorizaciones}/${a.autorizacionId}`}
                        aria-label={t('productores.verExpediente', { producto: a.producto })}>
                        {t('autorizaciones.ver')}
                      </Enlace>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          </section>

          {ficha.decisiones.length > 0 && (
            <section className="tarjeta-panel" aria-labelledby="ficha-historial">
              <h2 id="ficha-historial" className="heading-h4">{t('autorizaciones.expediente.historial')}</h2>
              <ul className="expediente__historial">
                {ficha.decisiones.map((d) => (
                  <li key={d.id}>
                    <Badge tono={TONO_DECISION[d.decision]}>{t(`productores.decision.${d.decision}`)}</Badge>
                    <span className="body-s">«{d.motivo}»</span>
                    <span className="caption">
                      {t('autorizaciones.expediente.decididaPor', { nombre: d.decididoPor, fecha: fechaHora(d.decididoEn) })}
                      {d.decision !== 'HABILITAR_TIENDA' && ` · ${t('productores.alcance', { count: d.alcance })}`}
                    </span>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </div>

        <aside className="tarjeta-panel expediente__decision" aria-labelledby="ficha-decision">
          <h2 id="ficha-decision" className="heading-h3">{t('autorizaciones.expediente.decision')}</h2>
          <p className="body-s">{ficha.ventaRevocada ? t('productores.revocadaTexto') : t('productores.decisionTexto')}</p>
          <Campo
            tipo="area"
            etiqueta={t('autorizaciones.expediente.motivo')}
            ayuda={t('autorizaciones.expediente.motivoAyuda')}
            error={errorMotivo}
            value={motivo}
            maxLength={500}
            onChange={(e) => setMotivo(e.target.value)}
          />
          {acciones.map((a) => (
            <Boton key={a.clave} tipo={a.tipo} tamano="M" icono={a.icono} anchoCompleto onClick={() => pedirConfirmacion(a.clave)} disabled={ocupado}>
              {t(`productores.accion.${a.clave}.boton`)}
            </Boton>
          ))}
        </aside>
      </div>

      <Dialogo
        abierto={confirmar !== null}
        titulo={confirmar ? t(`productores.accion.${confirmar}.titulo`, { organizacion: ficha.organizacion }) : ''}
        confirmar={confirmar ? t(`productores.accion.${confirmar}.confirmar`) : undefined}
        tipoConfirmar={confirmar === 'revocar' ? 'destructivo' : 'primario'}
        ocupado={ocupado}
        onCancelar={() => setConfirmar(null)}
        onConfirmar={decidir}
      >
        <p>{t('autorizaciones.dialogo.motivo', { motivo: motivo.trim() })}</p>
        <p>{confirmar && t(`productores.accion.${confirmar}.texto`)}</p>
      </Dialogo>
    </LayoutAdmin>
  )
}
