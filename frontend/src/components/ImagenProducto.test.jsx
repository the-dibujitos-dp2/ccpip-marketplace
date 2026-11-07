import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import ImagenProducto from './ImagenProducto.jsx'
import { ContextoVisualConsumidor } from './ContextoVisualConsumidor.jsx'
import cafe from '../assets/editorial/home/cafe.webp'
import cacao from '../assets/editorial/home/cacao.webp'
import feria from '../assets/editorial/feria.jpg'
import cafeSvg from '../assets/lineas/cafe.svg'

const consumer = (props) => render(<ContextoVisualConsumidor.Provider value>{<ImagenProducto {...props} />}</ContextoVisualConsumidor.Provider>)

describe('ImagenProducto editorial', () => {
  it('prioriza URL real intacta y sin caption', () => { consumer({url:'/api/photo.jpg', linea:'CAFE', alt:'café', className:'foto'}); expect(screen.getByRole('img', {name:'café'})).toHaveAttribute('src','/api/photo.jpg'); expect(screen.queryByText(/referencial/i)).toBeNull() })
  it('muestra foto referencial decorativa con caption no anunciado', () => { consumer({linea:'CAFE', className:'linea'}); const wrapper = document.querySelector('.imagen-producto-referencial'); expect(wrapper).toHaveClass('linea'); expect(wrapper.querySelector('img')).toHaveAttribute('src',cafe); expect(wrapper.querySelector('img')).toHaveAttribute('alt',''); expect(wrapper.querySelector('img')).toHaveAttribute('aria-hidden','true'); expect(screen.getByText('Fotografía referencial')).toHaveAttribute('aria-hidden','true') })
  it('muestra cacao referencial decorativo', () => { consumer({linea:'CACAO'}); expect(document.querySelector('.imagen-producto-referencial img')).toHaveAttribute('src',cacao); expect(document.querySelector('.imagen-producto-referencial img')).toHaveAttribute('alt','') })
  it('mantiene caption visual decorativo para ofertas sin foto', () => { consumer({linea:'INFUSIONES'}); expect(document.querySelector('.imagen-producto-referencial img')).toHaveAttribute('src',feria); expect(screen.getByText('Imagen referencial; no es la foto de esta oferta')).toHaveAttribute('aria-hidden','true') })
  it('mantiene SVG legacy fuera del provider', () => { render(<ImagenProducto linea="CAFE"/>); expect(screen.getByRole('presentation')).toHaveAttribute('src',cafeSvg) })
  it('mantiene caption de comunidad fuera del nombre accesible', () => { consumer({linea:'COMUNIDAD'}); expect(screen.getByText('Imagen documental de referencia; no representa a esta comunidad')).toHaveAttribute('aria-hidden','true') })
  it('anexa aclaración a una foto referencial cuando el alt identifica la entidad', () => { consumer({linea:'CAFE', alt:'Tienda Selva'}); expect(screen.getByRole('img', {name:'Tienda Selva, Fotografía referencial'})).toHaveAttribute('src',cafe); expect(screen.getByText('Fotografía referencial')).toHaveAttribute('aria-hidden','true') })
  it('mantiene estable el nombre de un enlace de producto cuando la foto es decorativa', () => { render(<ContextoVisualConsumidor.Provider value><a href="/productos/1"><ImagenProducto linea="CAFE"/><span>Aceite de aguaje</span></a></ContextoVisualConsumidor.Provider>); expect(screen.getByRole('link', {name:'Aceite de aguaje'})).toHaveAttribute('href','/productos/1') })
})