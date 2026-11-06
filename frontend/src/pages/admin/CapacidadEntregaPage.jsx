import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { consultarCapacidadesEntrega, fijarCapacidadEntrega } from '../../api/admin.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import DialogoFormulario from '../../components/DialogoFormulario.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { fechaHora } from '../../formato.js'
import '../productor/productor.css'
import './admin.css'
import './TarifasEnvioPage.css'

const ENTERO = /^\d{1,5}$/
const MAXIMO = 10000

/** Los pedidos escritos si son un entero de 1 a 10000; si no, null. */
function maximoValido(texto) {
  const limpio = texto.trim()
  if (!ENTERO.test(limpio)) return null
  const valor = Number(limpio)
  return valor >= 1 && valor <= MAXIMO ? valor : null
}

/**
 * CCPIP-484: la Camara fija cuantos pedidos a domicilio puede despachar por dia cada punto de acopio
 * (decision del cliente del 05/11/2026). Sin capacidad el punto no tiene limite; al alcanzarla, el punto
 * deja de ofrecerse a domicilio hasta el dia siguiente (Lima). Cada cambio queda en la Auditoria (CCPIP-97).
 */
export default function CapacidadEntregaPage({ path }) {
  const { t } = useTranslation()
  const consultar = useCallback(() => consultarCapacidadesEntrega(), [])
  const { datos, setDatos, error } = useConsulta(consultar)
  const [editando, setEditando] = useState(null)
  const [maximo, setMaximo] = useState('')
  const [errorMaximo, setErrorMaximo] = useState(null)
  const [enviando, setEnviando] = useState(null)
  const [errorEnvio, setErrorEnvio] = useState(null)
  const [hecho, setHecho] = useState(null)

  const abrir = (punto) => {
    setEditando(punto)
    setMaximo(punto.maximoDiario == null ? '' : String(punto.maximoDiario))
    setErrorMaximo(null)
    setErrorEnvio(null)
    setHecho(null)
  }

  const aplicar = async (punto, valor) => {
    setEnviando(valor == null ? 'quitar' : 'guardar')
    setErrorEnvio(null)
    try {
      const actualizado = await fijarCapacidadEntrega(punto.puntoId, valor)
      setDatos((actual) => actual.map((p) => (p.puntoId === actualizado.puntoId ? actualizado : p)))
      setHecho(actualizado)
      setEditando(null)
    } catch (err) {
      if (err.body?.campos?.maximoDiario) setErrorMaximo(t('admin.capacidadEntrega.validacion.formato', { maximo: MAXIMO }))
      else setErrorEnvio(err)
    } finally {
      setEnviando(null)
    }
  }

  const guardar = (e) => {
    e?.preventDefault()
    if (!maximo.trim()) return setErrorMaximo(t('admin.capacidadEntrega.validacion.vacio'))
    const valor = maximoValido(maximo)
    if (valor == null) return setErrorMaximo(t('admin.capacidadEntrega.validacion.formato', { maximo: MAXIMO }))
    return aplicar(editando, valor)
  }

  return (
    <LayoutAdmin path={path} migas={t('admin.nav.capacidadEntrega')} titulo={t('admin.capacidadEntrega.titulo')}>
      <section className="tarjeta-panel tarifas-envio" aria-labelledby="capacidad-entrega-titulo">
        <h2 id="capacidad-entrega-titulo" className="heading-h4">{t('admin.capacidadEntrega.seccion')}</h2>
        <p className="body-s">{t('admin.capacidadEntrega.intro')}</p>
        {hecho && (
          <Alerta tono="exito" onCerrar={() => setHecho(null)}
            titulo={hecho.maximoDiario == null
              ? t('admin.capacidadEntrega.quitada', { punto: hecho.punto })
              : t('admin.capacidadEntrega.guardada', { punto: hecho.punto, count: hecho.maximoDiario })} />
        )}
        {error && <Alerta tono="error" titulo={t('admin.capacidadEntrega.errorCargar')} descripcion={t('comun.errorConexion')} />}
        {!datos && !error && <Cargando />}
        {datos?.length === 0 && <EstadoVacio titulo={t('admin.capacidadEntrega.vacio')} />}
        {datos?.length > 0 && (
          <ul className="tarifas-envio__lista" aria-label={t('admin.capacidadEntrega.lista')}>
            {datos.map((p) => {
              const lleno = p.maximoDiario != null && p.usadosHoy >= p.maximoDiario
              return (
                <li key={p.puntoId} className="tarifas-envio__distrito">
                  <div className="tarifas-envio__texto">
                    <span className="body-m">{p.punto}</span>
                    <span className="caption">
                      {p.departamento}
                      {' · '}
                      {p.maximoDiario == null
                        ? t('admin.capacidadEntrega.hoySinLimite', { count: p.usadosHoy })
                        : t('admin.capacidadEntrega.hoy', { usados: p.usadosHoy, maximo: p.maximoDiario })}
                    </span>
                    {p.actualizadoEn && p.actualizadoPor && (
                      <span className="caption">
                        {t('admin.capacidadEntrega.ultimoCambio', { fecha: fechaHora(p.actualizadoEn), nombre: p.actualizadoPor })}
                      </span>
                    )}
                  </div>
                  <div className="tarifas-envio__acciones">
                    {lleno && <Badge tono="aviso">{t('admin.capacidadEntrega.completa')}</Badge>}
                    {p.maximoDiario == null
                      ? <Badge tono="neutro">{t('admin.capacidadEntrega.sinLimite')}</Badge>
                      : <strong className="label-l">{t('admin.capacidadEntrega.porDia', { count: p.maximoDiario })}</strong>}
                    <Boton tipo="secundario" tamano="S" onClick={() => abrir(p)}
                      aria-label={t('admin.capacidadEntrega.cambiarDe', { punto: p.punto })}>
                      {t('admin.capacidadEntrega.cambiar')}
                    </Boton>
                  </div>
                </li>
              )
            })}
          </ul>
        )}
      </section>
      {editando && (
        <DialogoFormulario
          titulo={t('admin.capacidadEntrega.dialogo', { punto: editando.punto })}
          confirmar={t('admin.capacidadEntrega.guardar')}
          enviando={enviando === 'guardar'}
          onEnviar={guardar}
          onCancelar={() => setEditando(null)}
          error={errorEnvio}
          tituloError={t('admin.capacidadEntrega.errorGuardar')}
        >
          <p className="body-s">{t('admin.capacidadEntrega.rige')}</p>
          <Campo etiqueta={t('admin.capacidadEntrega.campo')} value={maximo} inputMode="numeric" maxLength={5} autoComplete="off"
            ayuda={t('admin.capacidadEntrega.ayuda', { maximo: MAXIMO })} error={errorMaximo}
            onChange={(e) => { setMaximo(e.target.value); setErrorMaximo(null) }} />
          {editando.maximoDiario != null && (
            <Boton tipo="terciario" tamano="S" type="button" cargando={enviando === 'quitar'} onClick={() => aplicar(editando, null)}>
              {t('admin.capacidadEntrega.quitar')}
            </Boton>
          )}
        </DialogoFormulario>
      )}
    </LayoutAdmin>
  )
}
