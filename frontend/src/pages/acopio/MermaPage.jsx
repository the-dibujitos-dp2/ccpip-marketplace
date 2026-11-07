import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { listarMermaPunto, listarRevisionesPunto, solicitarRevisionMerma, urlReporteMermaPunto } from '../../api/merma.js'
import LayoutAcopio from '../../components/layout/LayoutAcopio.jsx'
import { RUTAS } from '../../router.js'
import ListaMerma from '../merma/ListaMerma.jsx'

/**
 * A-04 · CCPIP-76 y CCPIP-75: la merma de los lotes de sus puntos, la marcada por el punto y la
 * automatica por vencimiento. Se marca desde el lote, en el inventario (A-02). CCPIP-120: el punto pide
 * revisar una merma registrada por error; la aprueba o la rechaza el subadministrador.
 */
const REVISION = { listar: listarRevisionesPunto, solicitar: solicitarRevisionMerma }

export default function MermaPage({ path }) {
  const { t } = useTranslation()
  const [punto, setPunto] = useState()
  const alCargar = useCallback((d) => setPunto(d.ubicacion), [])
  return (
    <LayoutAcopio path={path} punto={punto} titulo={t('acopio.nav.merma')}>
      <ListaMerma
        consultar={listarMermaPunto}
        exportar={urlReporteMermaPunto}
        conProductor
        revision={REVISION}
        onDatos={alCargar}
        irTexto={t('merma.lista.comoMarcarPunto')}
        ir={{ ruta: RUTAS.acopioInventario, texto: t('merma.lista.irInventario') }}
      />
    </LayoutAcopio>
  )
}
