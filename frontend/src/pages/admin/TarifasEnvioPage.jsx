import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { consultarTarifasEnvio, fijarTarifaEnvio } from '../../api/admin.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import DialogoFormulario from '../../components/DialogoFormulario.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { fechaHora, precio } from '../../formato.js'
import '../productor/productor.css'
import './admin.css'
import './TarifasEnvioPage.css'

// Soles con hasta dos decimales, de 0 a 999.99 (lo mismo que valida el servidor)
const MONTO = /^\d{1,3}([.,]\d{1,2})?$/

/** El monto escrito como numero si es valido; si no, null. */
function montoValido(texto) {
  const limpio = texto.trim()
  return MONTO.test(limpio) ? Number(limpio.replace(',', '.')) : null
}

/**
 * CCPIP-482: la Camara fija la tarifa de envio a domicilio de cada distrito (decision del cliente del
 * 05/11/2026). Se listan los distritos que algun punto cubre; uno sin tarifa no se ofrece a domicilio
 * hasta fijarla. El cambio rige para los pedidos nuevos: uno ya creado conserva la tarifa que copio.
 * Cada cambio queda en la Auditoria (CCPIP-97).
 */
export default function TarifasEnvioPage({ path }) {
  const { t } = useTranslation()
  const consultar = useCallback(() => consultarTarifasEnvio(), [])
  const { datos, setDatos, error } = useConsulta(consultar)
  const [editando, setEditando] = useState(null)
  const [monto, setMonto] = useState('')
  const [errorMonto, setErrorMonto] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const [errorEnvio, setErrorEnvio] = useState(null)
  const [hecho, setHecho] = useState(null)

  const abrir = (distrito) => {
    setEditando(distrito)
    setMonto(distrito.monto == null ? '' : Number(distrito.monto).toFixed(2))
    setErrorMonto(null)
    setErrorEnvio(null)
    setHecho(null)
  }

  const guardar = async (e) => {
    e?.preventDefault()
    const nuevo = montoValido(monto)
    if (!monto.trim()) return setErrorMonto(t('admin.tarifasEnvio.validacion.vacio'))
    if (nuevo == null) return setErrorMonto(t('admin.tarifasEnvio.validacion.formato'))
    setEnviando(true)
    setErrorEnvio(null)
    try {
      const actualizado = await fijarTarifaEnvio(editando.ubigeo, nuevo)
      setDatos((actual) => actual.map((d) => (d.ubigeo === actualizado.ubigeo ? actualizado : d)))
      setHecho(actualizado)
      setEditando(null)
    } catch (err) {
      if (err.body?.campos?.monto) setErrorMonto(t('admin.tarifasEnvio.validacion.formato'))
      else setErrorEnvio(err)
    } finally {
      setEnviando(false)
    }
  }

  const sinTarifa = datos?.filter((d) => d.monto == null).length ?? 0

  return (
    <LayoutAdmin path={path} migas={t('admin.nav.tarifasEnvio')} titulo={t('admin.tarifasEnvio.titulo')}>
      <section className="tarjeta-panel tarifas-envio" aria-labelledby="tarifas-envio-titulo">
        <h2 id="tarifas-envio-titulo" className="heading-h4">{t('admin.tarifasEnvio.seccion')}</h2>
        <p className="body-s">{t('admin.tarifasEnvio.intro')}</p>
        {hecho && (
          <Alerta tono="exito" onCerrar={() => setHecho(null)}
            titulo={t('admin.tarifasEnvio.guardada', { distrito: hecho.distrito, monto: precio(hecho.monto) })} />
        )}
        {sinTarifa > 0 && (
          <Alerta tono="aviso" titulo={t('admin.tarifasEnvio.pendientes', { count: sinTarifa })}
            descripcion={t('admin.tarifasEnvio.pendientesTexto')} />
        )}
        {error && <Alerta tono="error" titulo={t('admin.tarifasEnvio.errorCargar')} descripcion={t('comun.errorConexion')} />}
        {!datos && !error && <Cargando />}
        {datos?.length === 0 && <EstadoVacio titulo={t('admin.tarifasEnvio.vacio')} descripcion={t('admin.tarifasEnvio.vacioTexto')} />}
        {datos?.length > 0 && (
          <ul className="tarifas-envio__lista" aria-label={t('admin.tarifasEnvio.lista')}>
            {datos.map((d) => (
              <li key={d.ubigeo} className="tarifas-envio__distrito">
                <div className="tarifas-envio__texto">
                  <span className="body-m">{d.distrito}</span>
                  <span className="caption">
                    {t('admin.tarifasEnvio.ubicacion', { provincia: d.provincia, departamento: d.departamento })}
                    {' · '}
                    {t('admin.tarifasEnvio.puntos', { count: d.puntos })}
                  </span>
                  {d.actualizadoEn && d.actualizadoPor && (
                    <span className="caption">
                      {t('admin.tarifasEnvio.ultimoCambio', { fecha: fechaHora(d.actualizadoEn), nombre: d.actualizadoPor })}
                    </span>
                  )}
                </div>
                <div className="tarifas-envio__acciones">
                  {d.monto == null
                    ? <Badge tono="aviso">{t('admin.tarifasEnvio.sinTarifa')}</Badge>
                    : <strong className="label-l">{precio(d.monto)}</strong>}
                  <Boton tipo="secundario" tamano="S" onClick={() => abrir(d)}
                    aria-label={t(d.monto == null ? 'admin.tarifasEnvio.fijarDe' : 'admin.tarifasEnvio.cambiarDe', { distrito: d.distrito })}>
                    {t(d.monto == null ? 'admin.tarifasEnvio.fijar' : 'admin.tarifasEnvio.cambiar')}
                  </Boton>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
      {editando && (
        <DialogoFormulario
          titulo={t('admin.tarifasEnvio.dialogo', { distrito: editando.distrito })}
          confirmar={t('admin.tarifasEnvio.guardar')}
          enviando={enviando}
          onEnviar={guardar}
          onCancelar={() => setEditando(null)}
          error={errorEnvio}
          tituloError={t('admin.tarifasEnvio.errorGuardar')}
        >
          <p className="body-s">{t('admin.tarifasEnvio.rige')}</p>
          <Campo etiqueta={t('admin.tarifasEnvio.monto')} value={monto} inputMode="decimal" maxLength={6} autoComplete="off"
            ayuda={t('admin.tarifasEnvio.montoAyuda')} error={errorMonto}
            onChange={(e) => { setMonto(e.target.value); setErrorMonto(null) }} />
        </DialogoFormulario>
      )}
    </LayoutAdmin>
  )
}
