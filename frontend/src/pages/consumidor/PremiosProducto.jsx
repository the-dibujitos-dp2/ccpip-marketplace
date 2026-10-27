import { useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import iconoEstrella from '../../assets/icons/estrella.svg'
import { obtenerPremios } from '../../api/catalogo.js'
import { useConsulta } from '../../api/useConsulta.js'
import Icono from '../../components/Icono.jsx'
import { fecha } from '../../formato.js'
import './CertificacionProducto.css'

/**
 * CCPIP-169 (RF-138): los premios del producto con su emisor, fecha y vigencia. Los declara el productor
 * con un archivo de respaldo que no se publica; la seccion lo dice y no habla de aprobacion de la Camara.
 * Sin premios, o si la consulta falla, la ficha sigue sin esta seccion.
 */
export default function PremiosProducto({ productoId }) {
  const { t } = useTranslation()
  const consultar = useCallback(() => obtenerPremios(productoId), [productoId])
  const { datos } = useConsulta(consultar)
  if (!datos?.length) return null

  return (
    <section className="seccion certificacion" aria-labelledby="titulo-premios">
      <h2 id="titulo-premios" className="seccion__titulo">{t('producto.premios.titulo')}</h2>
      <ul className="certificacion__lista" aria-label={t('producto.premios.titulo')}>
        {datos.map((p) => (
          <li key={`${p.titulo}-${p.emisor}-${p.otorgadoEn}`} className="certificacion__item">
            <Icono src={iconoEstrella} tamano={20} className="certificacion__icono" />
            <div>
              <p className="label-m">{p.titulo}</p>
              <dl className="certificacion__datos">
                <div>
                  <dt>{t('producto.premios.emisor')}</dt>
                  <dd>{p.emisor}</dd>
                </div>
                <div>
                  <dt>{t('producto.premios.otorgado')}</dt>
                  <dd>{fecha(p.otorgadoEn)}</dd>
                </div>
                <div>
                  <dt>{t('producto.certificacion.vigencia')}</dt>
                  <dd>
                    {p.vigenteHasta
                      ? t(p.vigente ? 'producto.certificacion.vigenteHasta' : 'producto.certificacion.vencioEl', { fecha: fecha(p.vigenteHasta) })
                      : t('producto.certificacion.sinVencimiento')}
                  </dd>
                </div>
                {p.cosecha && (
                  <div>
                    <dt>{t('producto.premios.lote')}</dt>
                    <dd>{t('producto.premios.cosechaDel', { fecha: fecha(p.cosecha) })}</dd>
                  </div>
                )}
              </dl>
            </div>
          </li>
        ))}
      </ul>
      <p className="caption">{t('producto.premios.nota')}</p>
    </section>
  )
}
