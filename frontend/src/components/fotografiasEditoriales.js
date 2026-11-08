import cafe from '../assets/editorial/home/cafe.webp'
import cacao from '../assets/editorial/home/cacao.webp'
import amazonia from '../assets/editorial/home/amazonia.webp'
import aceites from '../assets/editorial/referencias/aceites.webp'
import infusiones from '../assets/editorial/referencias/infusiones.webp'
import textil from '../assets/editorial/referencias/textil.webp'
import camucamu from '../assets/editorial/referencias/camucamu-planta.webp'

/** Fotos de referencia por linea para el consumidor; nunca se presentan como foto de una oferta o comunidad. */
export const FOTOGRAFIAS_REFERENCIA = {
  CAFE: { src: cafe, caption: 'captionCafeCacao' },
  CACAO: { src: cacao, caption: 'captionCafeCacao' },
  ACEITES: { src: aceites, caption: 'captionOferta' },
  INFUSIONES: { src: infusiones, caption: 'captionOferta' },
  TEXTIL: { src: textil, caption: 'captionOferta' },
  ARTESANIA: { src: textil, caption: 'captionOferta' },
  SUPERFRUTOS: { src: camucamu, caption: 'captionPlanta' },
  PORTADA: { src: amazonia, caption: 'captionComunidad' },
  COMUNIDAD: { src: amazonia, caption: 'captionComunidad' },
}
