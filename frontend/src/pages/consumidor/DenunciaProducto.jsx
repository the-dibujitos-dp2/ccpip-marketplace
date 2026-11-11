import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoAviso from '../../assets/icons/alerta-aviso.svg'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import DialogoDenuncia from '../../components/DialogoDenuncia.jsx'
import Icono from '../../components/Icono.jsx'
import { RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import './DenunciaProducto.css'

/**
 * CCPIP-83: el comprador denuncia la oferta; un visitante ingresa para hacerlo sin perder la ficha.
 * CCPIP-510: va en su propio recuadro, destacado y aparte de las calificaciones, que pueden no mostrarse.
 * Los demas roles no denuncian productos.
 */
export default function DenunciaProducto({ producto }) {
  const { t } = useTranslation()
  const { user } = useSesion()
  const [denunciando, setDenunciando] = useState(false)
  const [enviada, setEnviada] = useState(false)
  const comprador = user?.role === 'CONSUMIDOR'
  if (user && !comprador) return null

  return (
    <aside className="denuncia-producto" aria-labelledby="titulo-denuncia-producto">
      <span className="denuncia-producto__icono" aria-hidden="true">
        <Icono src={iconoAviso} tamano={22} />
      </span>
      <div className="denuncia-producto__texto">
        <h2 id="titulo-denuncia-producto" className="heading-h4">{t('denuncia.algoNoCorresponde')}</h2>
        <p className="body-s">{t('denuncia.algoNoCorrespondeTexto')}</p>
      </div>
      {comprador ? (
        <Boton tipo="secundario" tamano="M" peligro onClick={() => setDenunciando(true)}>{t('denuncia.denunciarProducto')}</Boton>
      ) : (
        <Boton tipo="secundario" tamano="M" peligro to={`${RUTAS.ingresar}?volver=${encodeURIComponent(`/productos/${producto.id}`)}`}>
          {t('denuncia.ingresaParaDenunciar')}
        </Boton>
      )}
      {enviada && (
        <div className="denuncia-producto__enviada">
          <Alerta tono="exito" titulo={t('denuncia.enviada')} descripcion={t('denuncia.enviadaTexto')} onCerrar={() => setEnviada(false)} />
        </div>
      )}
      {comprador && (
        <DialogoDenuncia abierto={denunciando} objetivo={{ ids: { productoId: producto.id }, nombre: producto.nombre }}
          onCerrar={() => setDenunciando(false)}
          onEnviada={() => {
            setDenunciando(false)
            setEnviada(true)
          }} />
      )}
    </aside>
  )
}
