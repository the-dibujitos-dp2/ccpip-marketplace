import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { listarMermaPunto } from '../../api/merma.js'
import LayoutAcopio from '../../components/layout/LayoutAcopio.jsx'
import { RUTAS } from '../../router.js'
import ListaMerma from '../merma/ListaMerma.jsx'

/**
 * A-04 · CCPIP-76 y CCPIP-75: la merma de los lotes de sus puntos, la marcada por el punto y la
 * automatica por vencimiento. Se marca desde el lote, en el inventario (A-02).
 */
export default function MermaPage({ path }) {
  const { t } = useTranslation()
  const [punto, setPunto] = useState()
  const alCargar = useCallback((d) => setPunto(d.ubicacion), [])
  return (
    <LayoutAcopio path={path} punto={punto} titulo={t('acopio.nav.merma')}>
      <ListaMerma
        consultar={listarMermaPunto}
        onDatos={alCargar}
        irTexto={t('merma.lista.comoMarcarPunto')}
        ir={{ ruta: RUTAS.acopioInventario, texto: t('merma.lista.irInventario') }}
      />
    </LayoutAcopio>
  )
}
