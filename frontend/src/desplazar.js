const curva = (x) => (x < 0.5 ? 4 * x * x * x : 1 - (-2 * x + 2) ** 3 / 2)

/**
 * Lleva la vista hasta `elemento` en `duracion` ms, acelerando y frenando suave: el `scroll-behavior: smooth`
 * del navegador es demasiado brusco para un salto largo (leer la historia de un producto). Respeta su
 * `scroll-margin-top`, y si la persona pidio menos movimiento salta sin animar.
 */
export function desplazarHasta(elemento, duracion = 1000) {
  if (!elemento) return
  const margen = Number.parseFloat(getComputedStyle(elemento).scrollMarginTop) || 0
  const desde = window.scrollY
  const hasta = desde + elemento.getBoundingClientRect().top - margen
  if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
    window.scrollTo(0, hasta)
    return
  }
  const inicio = performance.now()
  const paso = (ahora) => {
    const avance = Math.min((ahora - inicio) / duracion, 1)
    window.scrollTo(0, desde + (hasta - desde) * curva(avance))
    if (avance < 1) requestAnimationFrame(paso)
  }
  requestAnimationFrame(paso)
}
