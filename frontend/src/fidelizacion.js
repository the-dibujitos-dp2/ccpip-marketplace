import { precio, unidades } from './formato.js'

// CCPIP-86: el calculo de puntos de un pedido, igual que ReglaPuntos del servidor, para la vista previa de una
// campana. El servidor es el que acredita: aqui solo se anticipa lo que dara una regla.

/** Puntos de un pedido de `monto` soles en productos: `puntos` por cada `cadaSoles` completos, hasta `tope`. */
export function puntosDe(monto, { puntos, cadaSoles, tope }) {
  const total = Number(monto)
  const cada = Number(cadaSoles)
  if (!(total > 0) || !(cada > 0) || !(puntos > 0)) return 0
  const ganados = Math.floor(total / cada + 1e-9) * puntos
  return tope ? Math.min(ganados, Number(tope)) : ganados
}

/** Cuantas veces la regla base da una regla (2 = el doble de puntos por sol). */
export function multiplicador(regla, base) {
  return (regla.puntos / Number(regla.cadaSoles)) / (base.puntos / Number(base.cadaSoles))
}

/**
 * Puntos de un pedido de `monto` en una campana: con su regla si llega al minimo; si no, con la base. Es lo que
 * hace el servidor al acreditar.
 */
export function puntosEnCampana(monto, campana, base) {
  const llega = Number(monto) >= Number(campana.montoMinimo || 0)
  return llega ? puntosDe(monto, { ...campana, tope: campana.topePuntos }) : puntosDe(monto, base)
}

/** "S/ 10.00 de descuento · desde S/ 30.00" o "10 % de descuento, hasta S/ 8.00" (cupones y codigos, CCPIP-88/89). */
export function textoBeneficio(t, { tipo, valor, montoMinimo, topeDescuento }) {
  const B = 'fidelizacion.beneficio'
  let texto = tipo === 'PORCENTAJE' ? t(`${B}.porcentaje`, { valor: unidades(valor) }) : t(`${B}.monto`, { monto: precio(valor) })
  if (topeDescuento) texto += t(`${B}.hasta`, { monto: precio(topeDescuento) })
  if (Number(montoMinimo) > 0) texto += t(`${B}.desde`, { monto: precio(montoMinimo) })
  return texto
}
