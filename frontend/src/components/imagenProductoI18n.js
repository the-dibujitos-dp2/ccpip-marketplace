import i18n from '../i18n/index.js'

const traducciones = {
  es: {
    captionCafeCacao: 'Fotografía referencial',
    captionOferta: 'Imagen referencial; no es la foto de esta oferta',
    captionComunidad: 'Imagen documental de referencia; no representa a esta comunidad',
    altCafe: 'Fotografía referencial de café',
    altCacao: 'Fotografía referencial de cacao',
    altFeria: 'Fotografía documental referencial de feria',
    altComunidad: 'Fotografía documental referencial de comunidad',
    altPortada: 'Fotografía referencial de la Amazonía',
    aclaracion: 'Fotografía referencial',
  },
  en: {
    captionCafeCacao: 'Illustrative photograph',
    captionOferta: 'Illustrative image; not a photograph of this offer',
    captionComunidad: 'Documentary reference image; does not represent this community',
    altCafe: 'Illustrative coffee photograph',
    altCacao: 'Illustrative cacao photograph',
    altFeria: 'Illustrative documentary photograph of a fair',
    altComunidad: 'Illustrative documentary photograph of a community',
    altPortada: 'Illustrative photograph of the Amazon',
    aclaracion: 'Illustrative photograph',
  },
}

for (const [idioma, textos] of Object.entries(traducciones)) {
  if (!i18n.hasResourceBundle(idioma, 'imagenReferencial')) {
    i18n.addResourceBundle(idioma, 'imagenReferencial', textos, true, true)
  }
}
