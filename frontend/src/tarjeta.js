// Reglas de la tarjeta en el formulario de pago (CCPIP-62). Son las mismas que valida el servidor
// (Tarjeta.java); aqui sirven para ayudar mientras se escribe, no para decidir.

const MARCAS = {
  VISA: { nombre: 'Visa', largos: [13, 16, 19], cvv: 3, grupos: [4, 4, 4, 4, 3] },
  MASTERCARD: { nombre: 'Mastercard', largos: [16], cvv: 3, grupos: [4, 4, 4, 4] },
  AMEX: { nombre: 'American Express', largos: [15], cvv: 4, grupos: [4, 6, 5] },
  DINERS: { nombre: 'Diners Club', largos: [14, 16], cvv: 3, grupos: [4, 6, 4, 2] },
}

/** Solo los digitos de lo escrito. */
export const digitos = (texto) => texto.replace(/\D/g, '')

/** Marca por los primeros digitos, o null si aun no se reconoce. */
export function marca(numero) {
  const d = digitos(numero)
  if (/^4/.test(d)) return 'VISA'
  if (/^3[47]/.test(d)) return 'AMEX'
  if (/^3(6|8|0[0-5])/.test(d)) return 'DINERS'
  if (/^(5[1-5]|222[1-9]|22[3-9]\d|2[3-6]\d\d|27[01]\d|2720)/.test(d)) return 'MASTERCARD'
  return null
}

export const nombreMarca = (m) => MARCAS[m]?.nombre ?? null

/** Digitos de seguridad: 4 en American Express, 3 en las demas. */
export const largoCvv = (m) => MARCAS[m]?.cvv ?? 3

/** Cuantos digitos admite como maximo el numero de esa marca. */
export const largoMaximo = (m) => Math.max(...(MARCAS[m]?.largos ?? [16]))

/** "4111111111111111" -> "4111 1111 1111 1111"; Amex en grupos 4-6-5. Recorta al largo de la marca. */
export function formatear(numero) {
  const m = marca(numero)
  const d = digitos(numero).slice(0, largoMaximo(m))
  const grupos = MARCAS[m]?.grupos ?? [4, 4, 4, 4]
  const partes = []
  let i = 0
  for (const g of grupos) {
    if (i >= d.length) break
    partes.push(d.slice(i, i + g))
    i += g
  }
  return partes.join(' ')
}

/** Suma de verificacion de Luhn. */
export function luhn(numero) {
  const d = digitos(numero)
  if (!d) return false
  let suma = 0
  for (let i = 0; i < d.length; i++) {
    let n = Number(d[d.length - 1 - i])
    if (i % 2 === 1) {
      n *= 2
      if (n > 9) n -= 9
    }
    suma += n
  }
  return suma % 10 === 0
}

/** Numero completo para su marca y con Luhn valido. */
export function numeroValido(numero) {
  const m = marca(numero)
  return m !== null && MARCAS[m].largos.includes(digitos(numero).length) && luhn(numero)
}

/** El vencimiento es este mes o uno futuro. `hoy` se recibe para poder probarlo. */
export function vencimientoValido(mes, anio, hoy = new Date()) {
  const actual = hoy.getFullYear() * 12 + hoy.getMonth()
  return Number(anio) * 12 + (Number(mes) - 1) >= actual
}
