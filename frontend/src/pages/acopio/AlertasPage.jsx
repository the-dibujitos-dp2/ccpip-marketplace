import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import LayoutAcopio from '../../components/layout/LayoutAcopio.jsx'
import PanelAlertas from '../alertas/PanelAlertas.jsx'
import '../productor/productor.css'

/** A-08 · Alertas del punto de acopio: lotes por vencer y estancados de sus puntos. */
export default function AlertasPage({ path }) {
  const { t } = useTranslation()
  const [punto, setPunto] = useState()
  return (
    <LayoutAcopio path={path} punto={punto} titulo={t('acopio.nav.alertas')}>
      <PanelAlertas rol="acopio" onPuntos={(puntos) => setPunto(puntos.map((p) => p.punto).join(', ') || undefined)} />
    </LayoutAcopio>
  )
}
