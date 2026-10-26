import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { listarDirecciones, retirarDireccion } from '../../api/cuenta.js'
import { useConsulta } from '../../api/useConsulta.js'
import Boton from '../../components/Boton.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { RUTAS } from '../../router.js'
import LateralCuenta from './LateralCuenta.jsx'
import './MisPedidosPage.css'
import './MisDireccionesPage.css'

/**
 * CW-13 · Mi cuenta › Mis direcciones. CCPIP-393 (RF-285): el comprador consulta sus direcciones de entrega
 * guardadas, la ultima primero, con su distrito, provincia y departamento. Se guardan al comprar a domicilio
 * (CCPIP-65); retirarlas (CCPIP-395) y modificarlas (CCPIP-394) llegan con sus historias, cada una en su tarjeta.
 */
export default function MisDireccionesPage() {
  const { t } = useTranslation()
  const { datos: direcciones, error, recargar } = useConsulta(listarDirecciones)
  const [seleccionada, setSeleccionada] = useState(null)
  const [retirando, setRetirando] = useState(false)
  const [errorRetiro, setErrorRetiro] = useState(false)
  const [aviso, setAviso] = useState('')
  const envioActivo = useRef(false)

  async function confirmarRetiro() {
    if (!seleccionada || envioActivo.current) return
    envioActivo.current = true
    setRetirando(true)
    setErrorRetiro(false)
    try {
      await retirarDireccion(seleccionada.id)
      setSeleccionada(null)
      setAviso(t('misDirecciones.retirada'))
      recargar()
    } catch {
      setErrorRetiro(true)
    } finally {
      envioActivo.current = false
      setRetirando(false)
    }
  }

  function abrirRetiro(direccion) {
    setAviso('')
    setErrorRetiro(false)
    setSeleccionada(direccion)
  }

  return (
    <LayoutTienda>
      <main className="contenedor mis-pedidos">
        <h1 className="mis-pedidos__titulo">{t('misPedidos.miCuenta')}</h1>
        <div className="mis-pedidos__cuerpo">
          <LateralCuenta activo="direcciones" />
          <section className="mis-pedidos__contenido" aria-labelledby="titulo-mis-direcciones" aria-busy={!direcciones && !error}>
            <h2 id="titulo-mis-direcciones" className="mis-direcciones__titulo">{t('misDirecciones.titulo')}</h2>
            {error && (
              <EstadoVacio titulo={t('misDirecciones.error')} descripcion={t('comun.errorConexion')}>
                <Boton tamano="M" onClick={recargar}>{t('carrito.reintentar')}</Boton>
              </EstadoVacio>
            )}
            {!error && !direcciones && <p className="visually-hidden">{t('comun.cargando')}</p>}
            {direcciones?.length === 0 && (
              <EstadoVacio titulo={t('misDirecciones.vacio')} descripcion={t('misDirecciones.vacioTexto')}>
                <Boton tamano="M" to={RUTAS.buscar}>{t('misPedidos.explorar')}</Boton>
              </EstadoVacio>
            )}
            {direcciones?.length > 0 && (
              <ul className="mis-direcciones__lista" aria-labelledby="titulo-mis-direcciones">
                {direcciones.map((d) => (
                  <li key={d.id} className="mis-direcciones__tarjeta">
                    <p className="mis-direcciones__alias">{d.alias ?? t('misDirecciones.sinAlias')}</p>
                    <p className="mis-direcciones__direccion">{d.direccion}</p>
                    <p className="mis-direcciones__zona">{`${d.distrito} · ${d.provincia} · ${d.departamento}`}</p>
                    {d.referencia && <p className="mis-direcciones__referencia">{t('misDirecciones.referencia', { referencia: d.referencia })}</p>}
                    <Boton tipo="destructivo" tamano="S" onClick={() => abrirRetiro(d)} aria-label={t('misDirecciones.retirarAria')}>{t('misDirecciones.retirar')}</Boton>
                  </li>
                ))}
              </ul>
            )}
            {aviso && <p className="mis-direcciones__aviso" role="status">{aviso}</p>}
          </section>
        </div>
        <Dialogo
          abierto={Boolean(seleccionada)}
          titulo={t('misDirecciones.confirmarTitulo')}
          confirmar={retirando ? t('misDirecciones.retirando') : t('misDirecciones.retirar')}
          tipoConfirmar="destructivo"
          cancelar={t('comun.cancelar')}
          ocupado={retirando}
          onConfirmar={confirmarRetiro}
          onCancelar={() => { if (!retirando) setSeleccionada(null) }}
        >
          {seleccionada && <p>{t('misDirecciones.confirmarTexto', { direccion: seleccionada.direccion })}</p>}
          {errorRetiro && <p role="alert">{t('misDirecciones.errorRetirar')}</p>}
        </Dialogo>
      </main>
    </LayoutTienda>
  )
}
