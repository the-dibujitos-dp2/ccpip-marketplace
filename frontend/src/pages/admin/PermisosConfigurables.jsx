import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { cambiarPermisoConfigurable, consultarPermisosConfigurables } from '../../api/admin.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import DialogoFormulario from '../../components/DialogoFormulario.jsx'
import { fechaHora } from '../../formato.js'
import './PermisosConfigurables.css'

/**
 * CCPIP-296 (RF-172): el superadministrador concede o revoca los permisos configurables de cada rol, siempre
 * con motivo. Las restricciones de gobernanza se muestran sin accion: nunca se conceden. Cada cambio queda en
 * la Auditoria (CCPIP-97).
 */
export default function PermisosConfigurables() {
  const { t } = useTranslation()
  const consultar = useCallback(() => consultarPermisosConfigurables(), [])
  const { datos, setDatos, error } = useConsulta(consultar)
  const [cambio, setCambio] = useState(null)
  const [motivo, setMotivo] = useState('')
  const [errorMotivo, setErrorMotivo] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const [errorEnvio, setErrorEnvio] = useState(null)
  const [hecho, setHecho] = useState(null)

  const abrir = (rol, permiso) => {
    setCambio({ rol, codigo: permiso.codigo, concedido: !permiso.concedido })
    setMotivo('')
    setErrorMotivo(null)
    setErrorEnvio(null)
    setHecho(null)
  }

  const enviar = async (e) => {
    e?.preventDefault()
    if (!motivo.trim()) {
      setErrorMotivo(t('admin.configurables.motivoRequerido'))
      return
    }
    setEnviando(true)
    setErrorEnvio(null)
    try {
      const actualizado = await cambiarPermisoConfigurable(cambio.rol, cambio.codigo, { concedido: cambio.concedido, motivo: motivo.trim() })
      setDatos((actual) => actual.map((r) => (r.rol === actualizado.rol ? actualizado : r)))
      setHecho(cambio)
      setCambio(null)
    } catch (err) {
      setErrorEnvio(err)
    } finally {
      setEnviando(false)
    }
  }

  const nombre = (codigo) => t(`admin.configurables.codigo.${codigo}.nombre`, { defaultValue: codigo })
  const accion = (c) => (c.concedido ? 'conceder' : 'revocar')

  return (
    <section className="permisos-configurables" aria-labelledby="permisos-configurables-titulo">
      <h2 id="permisos-configurables-titulo" className="heading-s">{t('admin.configurables.titulo')}</h2>
      <p className="body-s">{t('admin.configurables.descripcion')}</p>
      {hecho && (
        <Alerta tono="exito" titulo={t(`admin.configurables.hecho.${accion(hecho)}`,
          { permiso: nombre(hecho.codigo), rol: t(`roles.${hecho.rol}`) })} />
      )}
      {error && <Alerta tono="error" titulo={t('admin.configurables.error')} descripcion={t('comun.errorConexion')} />}
      {!datos && !error && <Cargando />}
      {datos?.map(({ rol, permisos }) => (
        <div key={rol} className="permisos-configurables__rol">
          <h3 className="label-l">{t(`roles.${rol}`, { defaultValue: rol })}</h3>
          <ul className="permisos-configurables__lista" aria-label={t('admin.configurables.deRol', { rol: t(`roles.${rol}`) })}>
            {permisos.map((p) => (
              <li key={p.codigo} className="permisos-configurables__permiso">
                <div className="permisos-configurables__texto">
                  <span className="body-m">{nombre(p.codigo)}</span>
                  <span className="caption">{t(`admin.configurables.codigo.${p.codigo}.descripcion`, { defaultValue: '' })}</span>
                  {p.actualizadoEn && (
                    <span className="caption">
                      {t('admin.configurables.ultimoCambio', { fecha: fechaHora(p.actualizadoEn), nombre: p.actualizadoPor })}
                    </span>
                  )}
                </div>
                {p.restringido ? (
                  <Badge tono="neutro">{t('admin.configurables.restringido')}</Badge>
                ) : (
                  <div className="permisos-configurables__acciones">
                    <Badge tono={p.concedido ? 'exito' : 'error'}>
                      {t(p.concedido ? 'admin.configurables.concedido' : 'admin.configurables.revocado')}
                    </Badge>
                    <Boton tipo="secundario" tamano="S" onClick={() => abrir(rol, p)}
                      aria-label={t(`admin.configurables.${p.concedido ? 'revocarDe' : 'concederDe'}`, { permiso: nombre(p.codigo), rol: t(`roles.${rol}`) })}>
                      {t(p.concedido ? 'admin.configurables.revocar' : 'admin.configurables.conceder')}
                    </Boton>
                  </div>
                )}
              </li>
            ))}
          </ul>
        </div>
      ))}
      {cambio && (
        <DialogoFormulario
          titulo={t(`admin.configurables.dialogo.${accion(cambio)}`, { permiso: nombre(cambio.codigo), rol: t(`roles.${cambio.rol}`) })}
          confirmar={t(`admin.configurables.${accion(cambio)}`)}
          enviando={enviando}
          onEnviar={enviar}
          onCancelar={() => setCambio(null)}
          error={errorEnvio}
          tituloError={t('admin.configurables.errorCambiar')}
        >
          <p className="body-s">{t('admin.configurables.auditado')}</p>
          <Campo etiqueta={t('admin.configurables.motivo')} tipo="area" value={motivo} maxLength={300} error={errorMotivo}
            onChange={(e) => { setMotivo(e.target.value); setErrorMotivo(null) }} />
        </DialogoFormulario>
      )}
    </section>
  )
}
