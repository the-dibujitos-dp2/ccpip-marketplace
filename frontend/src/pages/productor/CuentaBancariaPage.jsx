import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoBillete from '../../assets/icons/billete.svg'
import iconoCampana from '../../assets/icons/info.svg'
import iconoPausa from '../../assets/icons/pausa.svg'
import iconoTienda from '../../assets/icons/tienda.svg'
import {
  actualizarCuentaBancaria,
  consultarCuentaBancaria,
  desactivarCuentaBancaria,
  listarBancos,
  reactivarCuentaBancaria,
  registrarCuentaBancaria,
} from '../../api/productor.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import Icono from '../../components/Icono.jsx'
import { mensajeError } from '../../i18n/errores.js'
import { fechaHora } from '../../formato.js'
import PaginaProductor from './PaginaProductor.jsx'
import './productor.css'
import './CuentaBancariaPage.css'

const VACIO = {
  titular: '', documento: '', banco: '', tipoCuenta: 'AHORROS', numeroCuenta: '', cci: '',
  moneda: 'PEN', correoAvisos: '', conYape: false, yapeCelular: '',
}
const MOTIVOS = ['ROBO', 'BLOQUEO_BANCO', 'SEGURIDAD', 'OTRO']
const CAMPOS = ['titular', 'documento', 'banco', 'tipoCuenta', 'numeroCuenta', 'cci', 'moneda', 'correoAvisos', 'yapeCelular']
// Codes del servidor que senalan un campo concreto
const CAMPO_DEL_CODE = {
  BANCO_INVALIDO: 'banco', CCI_INVALIDO: 'cci', NUMERO_CUENTA_INVALIDO: 'numeroCuenta',
  YAPE_INVALIDO: 'yapeCelular', CONTRASENA_INCORRECTA: 'contrasena',
}

function formulario(c) {
  if (!c) return VACIO
  return {
    titular: c.titular ?? '', documento: c.documento ?? '', banco: c.banco ?? '', tipoCuenta: c.tipoCuenta,
    numeroCuenta: c.numeroCuenta ?? '', cci: c.cci ?? '', moneda: c.moneda, correoAvisos: c.correoAvisos ?? '',
    conYape: Boolean(c.yapeCelular), yapeCelular: c.yapeCelular ?? '',
  }
}

const ultimos4 = (numero) => (numero ?? '').replace(/\D/g, '').slice(-4)

/**
 * PW-19 y PW-20: la cuenta bancaria del productor. CCPIP-32 registrarla, CCPIP-274 actualizarla,
 * CCPIP-275 desactivarla con confirmacion y CCPIP-276 reactivarla validando datos y contrasena.
 */
export default function CuentaBancariaPage({ path }) {
  const { t } = useTranslation()
  const [cuenta, setCuenta] = useState(undefined) // undefined: cargando; null: sin cuenta
  const [bancos, setBancos] = useState([])
  const [f, setF] = useState(VACIO)
  const [contrasena, setContrasena] = useState('')
  const [errores, setErrores] = useState({})
  const [alerta, setAlerta] = useState(null)
  const [ocupado, setOcupado] = useState(false)
  const [dialogo, setDialogo] = useState(false)
  const [motivo, setMotivo] = useState('')
  const [errorMotivo, setErrorMotivo] = useState(null)

  const aplicar = useCallback((c) => {
    setCuenta(c)
    setF(formulario(c))
    setContrasena('')
    setErrores({})
  }, [])

  useEffect(() => {
    listarBancos().then(setBancos).catch(() => setBancos([]))
    consultarCuentaBancaria()
      .then(aplicar)
      .catch((e) => (e.status === 404 ? aplicar(null) : setAlerta({ tono: 'error', titulo: t('productor.cuenta.errorCargar'), descripcion: mensajeError(e) })))
  }, [aplicar, t])

  const set = (campo) => (e) => setF({ ...f, [campo]: e.target.type === 'checkbox' ? e.target.checked : e.target.value })
  const desactivada = cuenta?.estado === 'DESACTIVADA'

  function validar() {
    const e = {}
    ;['titular', 'documento', 'banco', 'numeroCuenta', 'cci'].forEach((c) => {
      if (!String(f[c]).trim()) e[c] = t(`productor.cuenta.validacion.${c}`)
    })
    if (f.documento.trim() && !/^(\d{8}|\d{11})$/.test(f.documento.trim())) e.documento = t('productor.cuenta.validacion.documento')
    if (f.cci.trim() && f.cci.replace(/\D/g, '').length !== 20) e.cci = t('productor.cuenta.validacion.cci')
    if (f.conYape && !f.yapeCelular.trim()) e.yapeCelular = t('productor.cuenta.validacion.yapeCelular')
    if (desactivada && !contrasena) e.contrasena = t('productor.cuenta.validacion.contrasena')
    return e
  }

  function erroresDelServidor(error) {
    const campos = {}
    Object.keys(error.body?.campos ?? {}).forEach((clave) => {
      const campo = clave.replace(/^datos\./, '')
      if (CAMPOS.includes(campo) || campo === 'contrasena') campos[campo] = t(`productor.cuenta.validacion.${campo}`)
    })
    const porCode = CAMPO_DEL_CODE[error.code]
    if (porCode) campos[porCode] = mensajeError(error)
    return campos
  }

  async function guardar(e) {
    e.preventDefault()
    const locales = validar()
    setErrores(locales)
    if (Object.keys(locales).length) return
    const datos = { ...f, yapeCelular: f.conYape ? f.yapeCelular : null, correoAvisos: f.correoAvisos || null }
    delete datos.conYape
    setOcupado(true)
    setAlerta(null)
    try {
      let guardada
      if (cuenta === null) guardada = await registrarCuentaBancaria(datos)
      else if (desactivada) guardada = await reactivarCuentaBancaria(datos, contrasena)
      else guardada = await actualizarCuentaBancaria(datos)
      const clave = cuenta === null ? 'registrada' : desactivada ? 'reactivada' : 'actualizada'
      aplicar(guardada)
      setAlerta({ tono: 'exito', titulo: t(`productor.cuenta.${clave}`), descripcion: t(`productor.cuenta.${clave}Texto`) })
    } catch (error) {
      const campos = erroresDelServidor(error)
      setErrores(campos)
      if (!Object.keys(campos).length) setAlerta({ tono: 'error', titulo: t('productor.cuenta.errorGuardar'), descripcion: mensajeError(error) })
    } finally {
      setOcupado(false)
    }
  }

  async function desactivar() {
    if (!motivo) {
      setErrorMotivo(t('productor.cuenta.validacion.motivo'))
      return
    }
    setOcupado(true)
    try {
      aplicar(await desactivarCuentaBancaria(motivo))
      setDialogo(false)
      setAlerta({ tono: 'aviso', titulo: t('productor.cuenta.desactivadaAviso'), descripcion: t('productor.cuenta.desactivadaAvisoTexto') })
    } catch (error) {
      setErrorMotivo(mensajeError(error))
    } finally {
      setOcupado(false)
    }
  }

  const opcionesBancos = bancos.map((b) => ({ valor: b, etiqueta: b }))
  const titulo = t('productor.cuenta.titulo')

  return (
    <PaginaProductor path={path} seccion={titulo} titulo={titulo}>
      {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}
      {cuenta === undefined && !alerta && <p className="caption">{t('comun.cargando')}</p>}
      {cuenta !== undefined && (
        <div className="cuenta-bancaria">
          <form className="tarjeta-panel cuenta-bancaria__formulario" onSubmit={guardar} noValidate aria-labelledby="cuenta-titulo">
            <header className="cuenta-bancaria__cabecera">
              <span className="cuenta-bancaria__icono"><Icono src={iconoBillete} tamano={20} /></span>
              <div className="cuenta-bancaria__resumen">
                <h2 id="cuenta-titulo" className="heading-h3">
                  {cuenta
                    ? t('productor.cuenta.resumen', { cuenta: t(`productor.cuenta.nombreTipo.${cuenta.tipoCuenta}`), ultimos: ultimos4(cuenta.numeroCuenta) })
                    : t('productor.cuenta.registrar')}
                </h2>
                {cuenta && (
                  <p className="body-s">{[cuenta.banco, t('productor.cuenta.titularDe', { titular: cuenta.titular }), t(`productor.cuenta.moneda.${cuenta.moneda}`)].join(' · ')}</p>
                )}
              </div>
              {cuenta && (
                <Badge tono={desactivada ? 'aviso' : 'exito'}>{t(`productor.cuenta.estado.${cuenta.estado}`)}</Badge>
              )}
            </header>

            {cuenta === null && <Alerta tono="info" titulo={t('productor.cuenta.sinCuenta')} descripcion={t('productor.cuenta.sinCuentaTexto')} />}
            {desactivada && (
              <Alerta
                tono="aviso"
                titulo={t('productor.cuenta.desactivadaDesde', { fecha: fechaHora(cuenta.desactivadaEn), motivo: t(`productor.cuenta.motivo.${cuenta.motivoDesactivacion}`) })}
                descripcion={t('productor.cuenta.revisaYReactiva')}
              />
            )}

            <div className="cuenta-bancaria__campos">
              <Campo etiqueta={t('productor.cuenta.campo.titular')} value={f.titular} onChange={set('titular')} error={errores.titular} maxLength={160} autoComplete="off" />
              <Campo etiqueta={t('productor.cuenta.campo.documento')} value={f.documento} onChange={set('documento')} error={errores.documento} ayuda={t('productor.cuenta.ayudaDocumento')} inputMode="numeric" maxLength={11} />
              <Campo etiqueta={t('productor.cuenta.campo.banco')} tipo="selector" opciones={opcionesBancos} value={f.banco} onChange={set('banco')} error={errores.banco} />
              <Campo etiqueta={t('productor.cuenta.campo.tipoCuenta')} tipo="selector" value={f.tipoCuenta} onChange={set('tipoCuenta')} error={errores.tipoCuenta}
                opciones={['AHORROS', 'CORRIENTE'].map((v) => ({ valor: v, etiqueta: t(`productor.cuenta.tipo.${v}`) }))} />
              <Campo etiqueta={t('productor.cuenta.campo.numeroCuenta')} value={f.numeroCuenta} onChange={set('numeroCuenta')} error={errores.numeroCuenta} inputMode="numeric" maxLength={30} />
              <Campo etiqueta={t('productor.cuenta.campo.cci')} value={f.cci} onChange={set('cci')} error={errores.cci} ayuda={t('productor.cuenta.ayudaCci')} inputMode="numeric" maxLength={30} />
              <Campo etiqueta={t('productor.cuenta.campo.moneda')} tipo="selector" value={f.moneda} onChange={set('moneda')} error={errores.moneda}
                opciones={['PEN', 'USD'].map((v) => ({ valor: v, etiqueta: t(`productor.cuenta.moneda.${v}`) }))} />
              <Campo etiqueta={t('productor.cuenta.campo.correoAvisos')} value={f.correoAvisos} onChange={set('correoAvisos')} error={errores.correoAvisos} type="email" autoComplete="email" maxLength={254} />
            </div>

            <Alerta tono="info" titulo={t('productor.cuenta.noVence')} />

            <fieldset className="cuenta-bancaria__yape">
              <legend className="label-m">{t('productor.cuenta.complementario')}</legend>
              <label className="cuenta-bancaria__check">
                <input type="checkbox" checked={f.conYape} onChange={set('conYape')} />
                {t('productor.cuenta.conYape')}
              </label>
              {f.conYape && (
                <Campo etiqueta={t('productor.cuenta.campo.yapeCelular')} value={f.yapeCelular} onChange={set('yapeCelular')} error={errores.yapeCelular} inputMode="tel" maxLength={15} />
              )}
              <p className="caption">{t('productor.cuenta.yapeNoReemplaza')}</p>
            </fieldset>

            {desactivada && (
              <Campo etiqueta={t('productor.cuenta.campo.contrasena')} tipo="contrasena" value={contrasena} onChange={(e) => setContrasena(e.target.value)}
                error={errores.contrasena} ayuda={t('productor.cuenta.ayudaContrasena')} autoComplete="current-password" />
            )}

            <div className="cuenta-bancaria__acciones">
              <Boton type="submit" tamano="L" disabled={ocupado}>
                {cuenta === null ? t('productor.cuenta.registrarBoton') : desactivada ? t('productor.cuenta.reactivarBoton') : t('productor.cuenta.guardarBoton')}
              </Boton>
            </div>
          </form>

          {cuenta && !desactivada && (
            <aside className="tarjeta-panel cuenta-bancaria__lateral" aria-labelledby="cuenta-seguridad">
              <h2 id="cuenta-seguridad" className="heading-h4">{t('productor.cuenta.seguridad')}</h2>
              <p className="body-s">{t('productor.cuenta.seguridadTexto')}</p>
              <Boton tipo="destructivo" tamano="M" anchoCompleto icono={iconoPausa} onClick={() => { setMotivo(''); setErrorMotivo(null); setDialogo(true) }}>
                {t('productor.cuenta.desactivar')}
              </Boton>
              <p className="caption">{t('productor.cuenta.noSeElimina')}</p>
            </aside>
          )}
        </div>
      )}

      <Dialogo
        abierto={dialogo}
        titulo={t('productor.cuenta.dialogo.titulo')}
        confirmar={t('productor.cuenta.desactivar')}
        tipoConfirmar="destructivo"
        ocupado={ocupado}
        onConfirmar={desactivar}
        onCancelar={() => setDialogo(false)}
      >
        <p>{t('productor.cuenta.dialogo.cuando')}</p>
        <div className="cuenta-bancaria__consecuencias">
          <p className="label-m">{t('productor.cuenta.dialogo.quePasara')}</p>
          <ul>
            <li><Icono src={iconoTienda} tamano={16} />{t('productor.cuenta.dialogo.visibles')}</li>
            <li><Icono src={iconoPausa} tamano={16} />{t('productor.cuenta.dialogo.pausa')}</li>
            <li><Icono src={iconoCampana} tamano={16} />{t('productor.cuenta.dialogo.pedidos')}</li>
          </ul>
        </div>
        <Campo etiqueta={t('productor.cuenta.campo.motivo')} tipo="selector" value={motivo} error={errorMotivo}
          onChange={(e) => { setMotivo(e.target.value); setErrorMotivo(null) }}
          opciones={MOTIVOS.map((m) => ({ valor: m, etiqueta: t(`productor.cuenta.motivo.${m}`) }))} />
      </Dialogo>
    </PaginaProductor>
  )
}
