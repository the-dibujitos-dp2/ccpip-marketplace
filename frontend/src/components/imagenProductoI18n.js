import i18n from '../i18n/index.js'

const traducciones = {
  es: {
    captionPlanta: 'Planta de camu camu · Foto referencial: LeRoc, CC BY-SA 3.0',
    captionCafeCacao: 'Fotografía referencial',
    captionOferta: 'Imagen referencial; no es la foto de esta oferta',
    captionComunidad: 'Imagen documental de referencia; no representa a esta comunidad',
  },
  en: {
    captionPlanta: 'Camu camu plant · Illustrative photo: LeRoc, CC BY-SA 3.0',
    captionCafeCacao: 'Illustrative photograph',
    captionOferta: 'Illustrative image; not a photograph of this offer',
    captionComunidad: 'Documentary reference image; does not represent this community',
  },
  // CCPIP-512
  pt: {
    captionPlanta: 'Planta de camu-camu · Foto ilustrativa: LeRoc, CC BY-SA 3.0',
    captionCafeCacao: 'Fotografia ilustrativa',
    captionOferta: 'Imagem ilustrativa; não é a foto desta oferta',
    captionComunidad: 'Imagem documental de referência; não representa esta comunidade',
  },
}

export default traducciones

for (const [idioma, textos] of Object.entries(traducciones)) {
  if (!i18n.hasResourceBundle(idioma, 'imagenReferencial')) {
    i18n.addResourceBundle(idioma, 'imagenReferencial', textos, true, true)
  }
}
