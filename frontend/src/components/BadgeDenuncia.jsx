import { useTranslation } from 'react-i18next'
import Badge from './Badge.jsx'

const TONO = { RECIBIDA: 'aviso', EN_REVISION: 'info', RESUELTA: 'exito', DESESTIMADA: 'neutro' }

/** Los estados de una denuncia, en el orden en que avanza. */
export const ESTADOS_DENUNCIA = Object.keys(TONO)

/** RECIBIDA y EN_REVISION estan abiertas; RESUELTA y DESESTIMADA, cerradas (como en el servidor). */
export function abierta(estado) {
  return estado === 'RECIBIDA' || estado === 'EN_REVISION'
}

/** Estado de una denuncia (CCPIP-84): RECIBIDA, EN_REVISION, RESUELTA o DESESTIMADA. */
export default function BadgeDenuncia({ estado }) {
  const { t } = useTranslation()
  return <Badge tono={TONO[estado] ?? 'neutro'}>{t(`denuncia.estado.${estado}`, { defaultValue: estado })}</Badge>
}
