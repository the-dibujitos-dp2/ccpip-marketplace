import { useTranslation } from 'react-i18next'
import PanelAlertas from '../alertas/PanelAlertas.jsx'
import PaginaProductor from './PaginaProductor.jsx'

/** PW-21 · Alertas del productor: vencimientos, estancados, reposicion, interes y eventos de sus ofertas. */
export default function AlertasPage({ path }) {
  const { t } = useTranslation()
  return (
    <PaginaProductor path={path} seccion={t('productor.nav.alertas')} titulo={t('alertas.titulo')}>
      <PanelAlertas rol="productor" />
    </PaginaProductor>
  )
}
