// CCPIP-265 (RF-135): elegir el idioma y conservar la preferencia. El visitante la guarda en su
// navegador; con sesion tambien en su cuenta, para que lo siga en otro dispositivo.
import { guardarIdiomaCuenta } from '../api/auth.js'
import i18n, { guardarIdioma, IDIOMAS } from './index.js'

/**
 * Los idiomas que la cuenta guarda (CCPIP-265: espanol e ingles). El portugues solo traduce la
 * interfaz y se recuerda en el navegador (CCPIP-512).
 */
const IDIOMAS_CUENTA = ['es', 'en']
const enCuenta = (idioma) => IDIOMAS_CUENTA.includes(idioma)

// Idioma elegido sin sesion en esta visita: al ingresar se lleva a la cuenta en vez de
// reemplazarlo por el de la cuenta (el visitante acaba de decidirlo).
let elegidoSinSesion = null
// Usuario cuya sesion ya fijo el idioma en esta visita. La app vuelve a confirmar la sesion al
// navegar; si cada vez se tomara el idioma de la cuenta, un cambio recien hecho (o uno que no se
// pudo guardar) se desharia. El idioma de la cuenta se aplica una sola vez por sesion.
let aplicadoA = null

/** Cambia el idioma de la interfaz y lo conserva en el navegador y, con sesion, en la cuenta. */
export async function cambiarIdioma(idioma, user) {
  if (!IDIOMAS.includes(idioma)) return
  await i18n.changeLanguage(idioma)
  guardarIdioma(idioma)
  if (!user) {
    elegidoSinSesion = idioma
    return
  }
  aplicadoA = user.id
  if (!enCuenta(idioma)) return
  try {
    await guardarIdiomaCuenta(idioma)
  } catch {
    // El idioma ya cambio en pantalla y queda en el navegador; la cuenta se actualiza la proxima vez
  }
}

/**
 * Al confirmar una sesion (inicio de sesion o recarga): si el visitante eligio idioma antes de
 * ingresar, esa eleccion pasa a la cuenta; si no, se usa el idioma guardado en la cuenta. Un idioma
 * que la cuenta no guarda (portugues) se conserva: lo eligio en este navegador.
 */
export async function aplicarIdiomaDeSesion(user) {
  if (!user || aplicadoA === user.id) return
  aplicadoA = user.id
  if (elegidoSinSesion) {
    const idioma = elegidoSinSesion
    elegidoSinSesion = null
    if (enCuenta(idioma) && idioma !== user.language) {
      try {
        await guardarIdiomaCuenta(idioma)
      } catch {
        // Se reintenta cuando vuelva a elegir idioma
      }
    }
    return
  }
  if (!enCuenta(i18n.resolvedLanguage)) return
  if (IDIOMAS.includes(user.language) && user.language !== i18n.resolvedLanguage) {
    await i18n.changeLanguage(user.language)
    guardarIdioma(user.language)
  }
}

/** Al cerrar sesion: la siguiente sesion vuelve a tomar el idioma de su cuenta. El navegador lo conserva. */
export function olvidarIdiomaDeSesion() {
  aplicadoA = null
}

/** Solo para pruebas: olvida la eleccion hecha sin sesion y la sesion aplicada. */
export function reiniciarPreferencia() {
  elegidoSinSesion = null
  aplicadoA = null
}
