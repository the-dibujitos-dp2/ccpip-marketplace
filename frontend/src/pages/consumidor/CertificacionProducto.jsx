import { useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import iconoEscudo from '../../assets/icons/escudo-check.svg'
import { obtenerCertificacion } from '../../api/catalogo.js'
import { useConsulta } from '../../api/useConsulta.js'
import Badge from '../../components/Badge.jsx'
import Icono from '../../components/Icono.jsx'
import { fecha, fechaLarga } from '../../formato.js'
import './CertificacionProducto.css'

const TONO = { VERIFICADA: 'exito', VENCIDA: 'aviso', SIN_EVIDENCIA: 'neutro' }

/**
 * CCPIP-365 (RF-255): las evidencias publicas de certificacion del producto y que significa cada una.
 * Solo muestra lo que el servidor publica (emisor, fecha y vigencia; nunca el archivo) y solo habla de
 * "verificado" cuando el servidor lo dice. CCPIP-408: sin ninguna evidencia publica no hay seccion
 * (nada de un bloque vacio). Si la consulta falla, la ficha sigue sin esta seccion.
 */
export default function CertificacionProducto({ productoId }) {
  const { t } = useTranslation()
  const consultar = useCallback(() => obtenerCertificacion(productoId), [productoId])
  const { datos } = useConsulta(consultar)
  // CCPIP-408: sin certificaciones que mostrar, la ficha sigue sin esta seccion
  if (!datos?.evidencias?.length) return null

  const { estado, evidencias } = datos
  return (
    <section className="seccion certificacion" aria-labelledby="titulo-certificacion">
      <div className="certificacion__encabezado">
        <h2 id="titulo-certificacion" className="seccion__titulo">{t('producto.certificacion.titulo')}</h2>
        <Badge tono={TONO[estado]}>{t(`producto.certificacion.estado.${estado}`)}</Badge>
      </div>
      <p className="body-m">
        {t(`producto.certificacion.resumen.${estado}`, {
          fecha: datos.verificadaEn ? fechaLarga(datos.verificadaEn.slice(0, 10)) : '',
          hasta: datos.vigenteHasta ? fechaLarga(datos.vigenteHasta) : '',
        })}
      </p>
      <ul className="certificacion__lista" aria-label={t('producto.certificacion.lista')}>
        {evidencias.map((e) => (
          <li key={`${e.tipo}-${e.titulo}-${e.vigenteHasta ?? ''}`} className="certificacion__item">
            <Icono src={iconoEscudo} tamano={20} className="certificacion__icono" />
            <div>
              <p className="label-m">{e.titulo}</p>
              <p className="caption certificacion__tipo">{t(`producto.certificacion.tipo.${e.tipo}`)}</p>
              <dl className="certificacion__datos">
                {e.emisor && (
                  <div>
                    <dt>{t('producto.certificacion.emisor')}</dt>
                    <dd>{e.emisor}</dd>
                  </div>
                )}
                {e.vigenteDesde && (
                  <div>
                    <dt>{t('producto.certificacion.desde')}</dt>
                    <dd>{fecha(e.vigenteDesde)}</dd>
                  </div>
                )}
                <div>
                  <dt>{t('producto.certificacion.vigencia')}</dt>
                  <dd>
                    {e.vigenteHasta
                      ? t(e.vigente ? 'producto.certificacion.vigenteHasta' : 'producto.certificacion.vencioEl', { fecha: fecha(e.vigenteHasta) })
                      : t('producto.certificacion.sinVencimiento')}
                  </dd>
                </div>
              </dl>
              <p className="caption">{t(`producto.certificacion.significado.${e.tipo}`)}</p>
            </div>
          </li>
        ))}
      </ul>
      <p className="caption">{t('producto.certificacion.nota')}</p>
    </section>
  )
}
