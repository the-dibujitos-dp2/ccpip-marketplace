import { useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoAlerta from '../../assets/icons/alerta-campo.svg'
import iconoInfo from '../../assets/icons/info.svg'
import iconoSobre from '../../assets/icons/sobre.svg'
import { consultarInteres, registrarInteres, retirarInteres } from '../../api/intereses.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import Icono from '../../components/Icono.jsx'
import SelectorCantidad from '../../components/SelectorCantidad.jsx'
import { mensajeError } from '../../i18n/errores.js'
import { navigate, RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import './MeInteresa.css'

const MAXIMO = 9999

/**
 * CW-03c y CW-11 (CCPIP-105): en una oferta agotada o en "Venta pausada", el comprador registra cuanto
 * le interesaria comprar. Es por esta oferta y su productor; no reserva ni cobra. Cuando vuelva a
 * venderse le llega un correo (CCPIP-273) y el interes se cierra. Puede cambiar la cantidad o retirarlo.
 */
export default function MeInteresa({ producto }) {
  const { t } = useTranslation()
  const { user } = useSesion()
  const idError = useId()
  const pausada = producto.comprable === false
  // Solo el comprador registra interes; sin sesion se le pide ingresar
  const aplica = (pausada || !producto.disponible) && (!user || user.role === 'CONSUMIDOR')
  const conSesion = Boolean(user)
  const [interes, setInteres] = useState(null)
  const [abierto, setAbierto] = useState(false)
  const [cantidad, setCantidad] = useState('1')
  const [error, setError] = useState(null)
  const [aviso, setAviso] = useState(null)
  const [enviando, setEnviando] = useState(false)

  useEffect(() => {
    if (!aplica || !conSesion) return
    consultarInteres(producto.id).then(setInteres).catch(() => setInteres(null))
  }, [aplica, conSesion, producto.id])

  if (!aplica) return null

  const tienda = producto.tienda?.nombre

  function abrir() {
    if (!user) {
      navigate(`${RUTAS.ingresar}?volver=${encodeURIComponent(window.location.pathname)}`)
      return
    }
    setCantidad(String(interes?.cantidad ?? 1))
    setError(null)
    setAviso(null)
    setAbierto(true)
  }

  async function enviar() {
    const unidades = Number(cantidad)
    if (cantidad === '' || !Number.isInteger(unidades) || unidades < 1 || unidades > MAXIMO) {
      setError(t('interes.cantidadInvalida', { maximo: MAXIMO }))
      return
    }
    setEnviando(true)
    try {
      const nuevo = await registrarInteres(producto.id, unidades)
      const cambio = Boolean(interes)
      setInteres(nuevo)
      setAbierto(false)
      setAviso({
        tono: 'exito',
        titulo: t(cambio ? 'interes.actualizado' : 'interes.registradoAviso', { count: nuevo.cantidad }),
        descripcion: t('interes.teAvisamos'),
      })
    } catch (e) {
      if (e.status !== 401) setError(mensajeError(e))
    } finally {
      setEnviando(false)
    }
  }

  async function retirar() {
    setAviso(null)
    try {
      await retirarInteres(producto.id)
      setInteres(null)
      setAviso({ tono: 'exito', titulo: t('interes.retirado') })
    } catch (e) {
      if (e.status === 404) setInteres(null)
      else if (e.status !== 401) setAviso({ tono: 'error', titulo: t('interes.errorRetirar'), descripcion: mensajeError(e) })
    }
  }

  return (
    <div className="me-interesa">
      {interes ? (
        <div className="me-interesa__registrado" role="status">
          <p className="label-m">{t('interes.registrado', { count: interes.cantidad })}</p>
          <p className="caption">{t('interes.teAvisamos')}</p>
          <div className="me-interesa__acciones">
            <Boton tipo="secundario" tamano="M" onClick={abrir}>
              {t('interes.cambiar')}
            </Boton>
            <Boton tipo="terciario" tamano="M" onClick={retirar}>
              {t('interes.retirar')}
            </Boton>
          </div>
        </div>
      ) : (
        <>
          <Boton tipo="secundario" tamano="L" anchoCompleto icono={iconoSobre} onClick={abrir}>
            {t('interes.boton')}
          </Boton>
          <p className="caption">{t(pausada ? 'interes.explicaPausada' : 'interes.explicaAgotado', { tienda })}</p>
        </>
      )}
      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}

      <Dialogo
        abierto={abierto}
        titulo={t('interes.titulo')}
        confirmar={enviando ? t('interes.enviando') : t(interes ? 'interes.guardar' : 'interes.enviar')}
        onConfirmar={enviar}
        onCancelar={() => setAbierto(false)}
        ocupado={enviando}
      >
        <div className="me-interesa__dialogo">
          <p className="caption">{t('interes.deTienda', { producto: producto.nombre, tienda })}</p>
          <p>{t(pausada ? 'interes.textoPausada' : 'interes.textoAgotado', { tienda })}</p>
          <div className="me-interesa__cantidad">
            <label className="label-m" htmlFor={`${idError}-cantidad`}>{t('interes.pregunta')}</label>
            <SelectorCantidad
              id={`${idError}-cantidad`}
              valor={cantidad}
              onCambiar={(v) => {
                setCantidad(v)
                setError(null)
              }}
              invalido={Boolean(error)}
              etiqueta={t('interes.pregunta')}
              describedBy={error ? idError : undefined}
            />
          </div>
          {error && (
            <p id={idError} className="me-interesa__error">
              <img src={iconoAlerta} width="16" height="16" alt="" />
              {error}
            </p>
          )}
          <p className="me-interesa__nota">
            <Icono src={iconoInfo} tamano={18} />
            <span>{t('interes.noReserva')}</span>
          </p>
        </div>
      </Dialogo>
    </div>
  )
}
