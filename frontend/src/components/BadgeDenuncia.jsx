import { useTranslation } from 'react-i18next'
import Badge from './Badge.jsx'

const TONO = { RECIBIDA: 'aviso', EN_REVISION: 'info', RESUELTA: 'exito', DESESTIMADA: 'neutro' }

/** Estado de una denuncia (CCPIP-84): RECIBIDA, EN_REVISION, RESUELTA o DESESTIMADA. */
export default function BadgeDenuncia({ estado }) {
  const { t } = useTranslation()
  return <Badge tono={TONO[estado] ?? 'neutro'}>{t(`denuncia.estado.${estado}`, { defaultValue: estado })}</Badge>
}
