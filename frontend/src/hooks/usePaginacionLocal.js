import { useEffect, useMemo, useState } from 'react'

export default function usePaginacionLocal(items = [], filtroKey = '', pageSize = 20) {
  const [pagina, setPagina] = useState(0)
  useEffect(() => setPagina(0), [filtroKey])
  const total = items.length
  const totalPaginas = Math.max(1, Math.ceil(total / pageSize))
  const paginaValida = Math.min(pagina, totalPaginas - 1)
  useEffect(() => { if (pagina !== paginaValida) setPagina(paginaValida) }, [pagina, paginaValida])
  const filas = useMemo(() => items.slice(paginaValida * pageSize, (paginaValida + 1) * pageSize), [items, paginaValida, pageSize])
  return { filas, pagina: paginaValida, total, totalPaginas, onCambiar: setPagina }
}