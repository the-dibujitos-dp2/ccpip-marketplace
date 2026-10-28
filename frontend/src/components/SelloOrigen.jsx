import { useTranslation } from 'react-i18next'
import iconoEscudo from '../assets/icons/escudo-check.svg'
import iconoCompacto from '../assets/icons/sello-compacto.svg'
import iconoCompleto from '../assets/icons/sello-completo.svg'
import iconoMini from '../assets/icons/sello-mini.svg'
import { fecha } from '../formato.js'
import './SelloOrigen.css'

/**
 * Sello de origen verificado por la Camara. Solo para productores verificados (RF-15).
 * completo: ficha · compacto: tarjetas y cabeceras · mini: listados.
 *
 * CCPIP-38: el completo muestra solo los sellos que el servidor respalda ({@code sellos} de
 * /api/productos/{id}/sellos), cada uno con su significado. No afirma reglas que nadie acredito.
 */
export default function SelloOrigen({ variante = 'compacto', sellos = [] }) {
  const { t } = useTranslation()
  if (variante === 'mini') {
    return (
      <span className="sello sello--mini" title={t('sello.titulo')}>
        <img src={iconoMini} width="16" height="16" alt={t('sello.corto')} />
      </span>
    )
  }
  if (variante === 'compacto') {
    return (
      <span className="sello sello--compacto">
        <img src={iconoCompacto} width="14" height="14" alt="" />
        {t('sello.corto')}
      </span>
    )
  }
  const origen = sellos.find((s) => s.tipo === 'ORIGEN_VERIFICADO')
  const certificados = sellos.filter((s) => s.tipo === 'CERTIFICADO')
  if (!origen && !certificados.length) return null
  return (
    <div className="sello sello--completo">
      {origen && (
        <div className="sello__origen">
          <p className="sello__titulo">
            <img src={iconoCompleto} width="24" height="24" alt="" />
            {t('sello.titulo')}
          </p>
          <p className="sello__significado">
            {t('sello.origen.significado', { comunidad: origen.titulo, region: origen.emisor })}
            {origen.desde && ` ${t('sello.origen.desde', { fecha: fecha(origen.desde) })}`}
          </p>
        </div>
      )}
      {certificados.length > 0 && (
        <>
          <p className="sello__subtitulo">{t('sello.certificados')}</p>
          <ul aria-label={t('sello.certificados')}>
            {certificados.map((c) => (
              <li key={`${c.titulo}-${c.hasta}`}>
                <img src={iconoEscudo} width="20" height="20" alt="" />
                <span>
                  <span className="sello__certificado">{c.titulo}</span>
                  <span className="sello__respaldo">
                    {c.emisor
                      ? t('sello.certificado.respaldo', { emisor: c.emisor, fecha: fecha(c.hasta) })
                      : t('sello.certificado.vigente', { fecha: fecha(c.hasta) })}
                  </span>
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
      <p className="sello__nota">{t('sello.nota')}</p>
    </div>
  )
}
