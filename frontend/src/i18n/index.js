// CCPIP-98 (RF-95, RNF-44): la interfaz en espanol e ingles con react-i18next; CCPIP-512, tambien en
// portugues. El espanol es el idioma original y el de respaldo: una clave sin traduccion se ve en
// espanol, nunca como la clave cruda.
import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import en from './en.json'
import es from './es.json'
import pt from './pt.json'
import editorialEs from './editorial/es.json'
import editorialEn from './editorial/en.json'
import editorialPt from './editorial/pt.json'
import accessEditorial from './accessEditorial.js'

export const IDIOMAS = ['es', 'en', 'pt']
export const IDIOMA_POR_DEFECTO = 'es'
const CLAVE_NAVEGADOR = 'ccpip.idioma'

const TEXTOS = {
  es: { translation: es, editorial: editorialEs },
  en: { translation: en, editorial: editorialEn },
  pt: { translation: pt, editorial: editorialPt },
}

/**
 * Locale de Intl para las fechas de cada idioma. En ingles se usa en-GB porque escribe dia, mes y
 * anio en el orden de Peru (20/11/2026, 4 Oct 2026); en-US los invertiria. En portugues, el de
 * Brasil (CCPIP-512).
 */
const LOCALE_FECHAS = { es: 'es-PE', en: 'en-GB', pt: 'pt-BR' }

/**
 * Idiomas en que el servidor tiene contenido (catalogo aprobado, causas de merma, reportes en PDF):
 * el espanol original y su traduccion al ingles. En portugues solo se traduce la interfaz y el
 * contenido se pide en espanol (CCPIP-512).
 */
const IDIOMAS_CONTENIDO = ['es', 'en']

/** Idioma elegido en este navegador, o null si nunca se eligio (o el almacenamiento no esta disponible). */
export function idiomaGuardado() {
  try {
    const valor = window.localStorage.getItem(CLAVE_NAVEGADOR)
    return IDIOMAS.includes(valor) ? valor : null
  } catch {
    return null
  }
}

/** Guarda el idioma en este navegador. Si el almacenamiento esta bloqueado, se conserva solo en la visita. */
export function guardarIdioma(idioma) {
  try {
    window.localStorage.setItem(CLAVE_NAVEGADOR, idioma)
  } catch {
    // Modo privado o almacenamiento bloqueado: el idioma dura mientras la pestana este abierta
  }
}

/** Locale de Intl para fechas en el idioma actual ("es-PE", "en-GB" o "pt-BR"). */
export function localeFechas() {
  return LOCALE_FECHAS[i18n.resolvedLanguage] ?? LOCALE_FECHAS[IDIOMA_POR_DEFECTO]
}

/** Idioma en que se pide el contenido del servidor: el actual si lo tiene, si no el espanol original. */
export function idiomaContenido() {
  return IDIOMAS_CONTENIDO.includes(i18n.resolvedLanguage) ? i18n.resolvedLanguage : IDIOMA_POR_DEFECTO
}

i18n.use(initReactI18next).init({
  resources: Object.fromEntries(IDIOMAS.map((idioma) => [idioma, { ...TEXTOS[idioma], accessEditorial: accessEditorial[idioma] }])),
  lng: idiomaGuardado() ?? IDIOMA_POR_DEFECTO,
  fallbackLng: IDIOMA_POR_DEFECTO,
  supportedLngs: IDIOMAS,
  // React ya escapa el texto; escaparlo aqui mostraria &amp; en pantalla
  interpolation: { escapeValue: false },
  returnNull: false,
})

// El lector de pantalla y el navegador deben saber en que idioma esta la pagina (WCAG 3.1.1)
document.documentElement.lang = i18n.resolvedLanguage
i18n.on('languageChanged', (idioma) => {
  document.documentElement.lang = idioma
})

export default i18n
