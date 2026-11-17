import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { misPuntos, previsualizarDescuento } from '../../api/fidelizacion.js'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import { textoBeneficio } from '../../fidelizacion.js'
import { precio } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'

const D = 'compra.descuento'

/**
 * CCPIP-88/89: el descuento de la compra, uno solo: un cupon que el comprador canjeo con sus puntos o un codigo de
 * campana. Antes de aplicarlo se consulta al servidor cuanto descuenta sobre este carrito; al pagar se vuelve a revisar.
 * `aplicado`: { solicitud, descuento } o nulo.
 */
export default function DescuentoCompra({ aplicado, onAplicar, onQuitar }) {
  const { t } = useTranslation()
  const [cupones, setCupones] = useState([])
  const [codigo, setCodigo] = useState('')
  const [error, setError] = useState(null)
  const [aplicando, setAplicando] = useState(false)
  const aplicandoRef = useRef(false)

  useEffect(() => {
    // Sin sus cupones igual puede escribir un codigo: no es un error de la compra
    misPuntos().then((p) => setCupones(p.cupones.filter((c) => c.estado === 'DISPONIBLE'))).catch(() => setCupones([]))
  }, [])

  async function aplicar(solicitud) {
    if (aplicandoRef.current) return
    aplicandoRef.current = true
    setError(null)
    setAplicando(true)
    try {
      onAplicar({ solicitud, descuento: await previsualizarDescuento(solicitud) })
      setCodigo('')
    } catch (e) {
      setError(mensajeError(e))
    } finally {
      aplicandoRef.current = false
      setAplicando(false)
    }
  }

  if (aplicado) {
    return (
      <div className="compra__descuento compra__descuento--aplicado" role="status">
        <span>
          <span className="label-m">{t(`${D}.aplicado`, { nombre: aplicado.descuento.nombre })}</span>
          <span className="caption">{t(`${D}.ahorras`, { monto: precio(aplicado.descuento.monto) })}</span>
        </span>
        <Boton tipo="terciario" tamano="S" onClick={onQuitar}>{t(`${D}.quitar`)}</Boton>
      </div>
    )
  }
  const conCodigo = codigo.trim()
  return (
    <section className="compra__descuento" aria-labelledby="compra-descuento">
      <h3 id="compra-descuento" className="label-m">{t(`${D}.titulo`)}</h3>
      {cupones.length > 0 && (
        <ul className="compra__cupones" aria-label={t(`${D}.misCupones`)}>
          {cupones.map((c) => (
            <li key={c.id}>
              <span>
                <span className="label-m">{c.nombre}</span>
                <span className="caption">{textoBeneficio(t, c)}</span>
              </span>
              <Boton tipo="secundario" tamano="S" disabled={aplicando} onClick={() => aplicar({ cuponId: c.id })}
                aria-label={t(`${D}.usarDe`, { nombre: c.nombre })}>{t(`${D}.usar`)}</Boton>
            </li>
          ))}
        </ul>
      )}
      <div className="compra__codigo">
        <Campo etiqueta={t(`${D}.codigo`)} value={codigo} maxLength={20} autoComplete="off" placeholder="CACAO10"
          onChange={(e) => setCodigo(e.target.value)}
          // Enter aplica el codigo; no envia el pago
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              if (conCodigo) void aplicar({ codigo: conCodigo })
            }
          }} />
        <Boton tipo="secundario" tamano="M" disabled={!conCodigo} cargando={aplicando} onClick={() => aplicar({ codigo: conCodigo })}>
          {t(`${D}.aplicar`)}
        </Boton>
      </div>
      {error && <p className="campo__ayuda campo__ayuda--error" role="alert">{error}</p>}
      <p className="caption">{t(`${D}.unoPorCompra`)}</p>
    </section>
  )
}
