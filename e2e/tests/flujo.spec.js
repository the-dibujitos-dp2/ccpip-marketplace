import { expect, test } from '@playwright/test'
import { api, enDias, fotoPng, ingresar, sinViolaciones, unico, vigilarConsola } from './ayudas.js'

/**
 * El flujo completo del prototipo, en orden y sobre el stack real (imagenes de produccion):
 * una comunidad arma su tienda desde cero, publica un producto con fotos, historia y su primer
 * lote, y un comprador lo encuentra, lo filtra y lee su historia. Requiere el stack recien creado
 * (productor_nuevo@ccpip.test con la tienda vacia).
 */
test.describe.configure({ mode: 'serial' })

const TIENDA = 'Comunidad Kukama de Nauta'
const PRODUCTO = unico('Aceite de sacha inchi Nauta')
let productoId

test.describe('Productor: arma su tienda y publica su producto', () => {
  test('arma su tienda desde cero y la publica (CCPIP-30, 153, 154)', async ({ page }) => {
    const errores = vigilarConsola(page)
    await ingresar(page, 'productor_nuevo@ccpip.test')
    await expect(page).toHaveURL(/\/productor$/)
    await expect(page.getByText('Publica tu tienda para empezar a vender')).toBeVisible()
    await sinViolaciones(page)

    await page.getByRole('link', { name: 'Armar mi tienda' }).click()
    await expect(page.getByRole('heading', { name: 'Editar mi tienda' })).toBeVisible()

    // Sin nombre no se publica
    await page.getByRole('button', { name: 'Publicar tienda' }).click()
    await expect(page.getByText('Escribe el nombre de tu tienda')).toBeVisible()

    await page.getByLabel('Subir portada').setInputFiles(fotoPng('portada.png', { ancho: 1600, alto: 500 }))
    await expect(page.getByText('Cambiar portada')).toBeVisible()
    await page.getByLabel('Categoría de las fotos nuevas').selectOption('CULTIVO')
    await page.getByLabel('Agregar foto').setInputFiles([fotoPng('chacra.png', { color: [120, 90, 30] }), fotoPng('cosecha.png', { color: [90, 40, 30] })])
    await expect(page.getByRole('heading', { name: 'Galería (2)' })).toBeVisible()

    await page.getByLabel('Nombre de la tienda').fill(TIENDA)
    await page.getByLabel('Descripción de mi tienda').fill('Aceites y superfrutos de familias kukama del río Marañón.')
    await page.getByLabel('Historia de mi comunidad').fill('Somos 18 familias kukama. Cosechamos en nuestras chacras sin talar el monte y prensamos en frío.')
    await page.getByLabel('Cita de la comunidad').fill('El río nos da de comer si lo cuidamos.')
    await page.getByLabel('Pueblo de pertenencia').fill('Kukama')
    await page.getByLabel('Localidad').fill('Nauta')
    await page.getByLabel('Socias y socios').fill('18')
    await page.getByRole('button', { name: 'Publicar tienda' }).click()
    await expect(page.getByText('¡Tu tienda está publicada!')).toBeVisible()

    // La tienda publica (CW-04) ya muestra lo que escribio
    await page.getByRole('link', { name: 'Ver tienda publicada' }).click()
    await expect(page.getByRole('heading', { level: 1, name: TIENDA })).toBeVisible()
    await expect(page.getByText('“El río nos da de comer si lo cuidamos.”')).toBeVisible()
    await expect(page.getByText('Pueblo Kukama · Nauta, Loreto · 18 socias y socios')).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Galería' })).toBeVisible()
    await expect(page.getByText('Aún no hay productos publicados')).toBeVisible()
    await sinViolaciones(page)
    expect(errores).toEqual([])
  })

  test('publica un producto: fotos, lineas, precios, IA, historia y primer lote (CCPIP-40, 41, 44, 45, 48, 49, 132, 143)', async ({ page }) => {
    const errores = vigilarConsola(page)
    await ingresar(page, 'productor_nuevo@ccpip.test')
    await page.getByRole('link', { name: 'Publicar producto' }).first().click()
    await expect(page.getByRole('heading', { name: 'Nuevo producto' })).toBeVisible()
    await sinViolaciones(page)

    // Fotos: piden el nombre primero (el borrador nace al subir la primera)
    await page.getByLabel('Nombre del producto').fill(PRODUCTO)
    await page.getByLabel('Arrastra fotos aquí o selecciónalas').setInputFiles([
      fotoPng('frente.png', { color: [140, 120, 20] }),
      fotoPng('detalle.png', { color: [30, 100, 60] }),
    ])
    await expect(page).toHaveURL(/\/productor\/productos\/\d+\?paso=datos/)
    productoId = page.url().match(/productos\/(\d+)/)[1]
    await expect(page.getByAltText('Foto del producto')).toHaveCount(2)
    // La segunda pasa a ser la principal (la que se ve en el catalogo)
    const fotos = page.locator('.editor__miniaturas > li')
    const segunda = await fotos.nth(1).locator('img').getAttribute('src')
    await fotos.nth(1).getByRole('button', { name: 'Hacer principal' }).click()
    // La principal se muestra primero, como dice la ayuda
    await expect(fotos.nth(0).locator('img')).toHaveAttribute('src', segunda)
    await expect(fotos.nth(0).getByText('Principal', { exact: true })).toBeVisible()

    // Lineas: la primera sugiere la unidad (CCPIP-45)
    await page.getByRole('button', { name: 'Aceites' }).click()
    await page.getByRole('button', { name: 'Superfrutos' }).click()
    await expect(page.getByLabel('Unidad de medida')).toHaveValue('BOTELLA')
    await page.getByLabel('Contenido por unidad').fill('250 ml')

    // Descripcion asistida por IA: se revisa y se acepta, nunca se publica sola (CCPIP-48)
    await page.getByRole('button', { name: 'Sugerir una descripción con IA (borrador)' }).click()
    await expect(page.getByText('Borrador asistido por IA · revisa antes de publicar')).toBeVisible()
    await expect(page.getByLabel('Descripción')).toHaveValue('')
    await page.getByRole('button', { name: 'Aceptar descripción' }).click()
    await expect(page.getByLabel('Descripción (borrador asistido por IA, editable)')).toHaveValue(new RegExp(PRODUCTO))

    // Minorista y mayorista: el mayorista debe ser menor (CCPIP-132, 143)
    await page.getByRole('radio', { name: 'Minorista y mayorista' }).check()
    await page.getByLabel('Precio minorista (S/)').fill('32')
    await page.getByLabel('Precio mayorista (S/)').fill('35')
    await page.getByLabel('Cantidad mínima mayorista').fill('12')
    await page.getByRole('button', { name: 'Siguiente: historia' }).click()
    await expect(page.getByRole('alert')).toContainText('Revisa los datos marcados')
    await expect(page.getByLabel('Precio mayorista (S/)')).toHaveAttribute('aria-invalid', 'true')
    await expect(page.getByRole('alert')).toContainText('Debe ser menor que el precio minorista')
    await page.getByLabel('Precio mayorista (S/)').fill('27')
    await expect(page.getByText('Recibirás S/ 28.80 por unidad')).toBeVisible()
    await page.getByRole('button', { name: 'Siguiente: historia' }).click()

    // Historia con IA (PW-05): con las respuestas del productor, editable y aprobada por el
    await expect(page).toHaveURL(/paso=historia/)
    // Una pregunta a la vez: al salir de una se abre la siguiente sin responder
    const respuestas = [
      ['¿Quiénes lo elaboran?', '18 familias kukama de Nauta'],
      ['¿Cómo lo hacen?', 'prensamos la semilla en frío'],
      ['¿Qué significa para su comunidad?', 'nos permite vivir sin talar'],
      ['¿Cómo cuidan el bosque al producirlo?', 'cosechamos solo en nuestras chacras, sin talar'],
    ]
    for (const [pregunta, respuesta] of respuestas) {
      await page.getByRole('textbox', { name: pregunta }).fill(respuesta)
      await page.keyboard.press('Tab')
    }
    await page.getByRole('button', { name: 'Generar borrador con IA' }).click()
    await expect(page.getByText('Borrador asistido por IA · revisa antes de publicar')).toBeVisible()
    await expect(page.getByRole('heading', { name: `La historia de ${PRODUCTO}` })).toBeVisible()
    await page.getByRole('button', { name: 'Más corto' }).click()
    await expect(page.getByText('18 familias kukama de Nauta. Cosechamos solo en nuestras chacras, sin talar.')).toBeVisible()
    await sinViolaciones(page)
    await page.getByRole('button', { name: 'Aprobar historia' }).click()

    // Primer lote en un punto de acopio (PW-06, CCPIP-41)
    await expect(page).toHaveURL(/paso=lote/)
    await expect(page.getByLabel('Código de lote')).toHaveValue('L-0001')
    await page.getByLabel('Cantidad').fill('60')
    await page.getByLabel('Producción').fill(enDias(-10))
    await page.getByLabel('Vencimiento', { exact: true }).fill(enDias(200))
    await page.getByLabel('Ingresa a').selectOption({ label: 'Centro Nacional Lima · punto de acopio' })
    await page.getByRole('button', { name: 'Registrar lote' }).click()
    await expect(page.getByText('Lote registrado')).toBeVisible()
    await expect(page.getByRole('list', { name: 'Lotes de este producto' }).getByText('L-0001')).toBeVisible()
    await sinViolaciones(page)

    await page.getByRole('button', { name: 'Publicar producto' }).click()
    await expect(page).toHaveURL(/\/productor\/productos\?publicado=1/)
    await expect(page.getByText('Producto publicado')).toBeVisible()
    const fila = page.getByRole('list', { name: 'Productos' }).getByRole('listitem').filter({ hasText: PRODUCTO })
    await expect(fila).toContainText('60 und')
    await expect(fila).toContainText('Minorista y mayorista')
    await expect(fila).toContainText('Publicado')
    await sinViolaciones(page)
    expect(errores).toEqual([])
  })
})

test.describe('Consumidor: encuentra el producto y lee su historia', () => {
  test('lo busca, filtra, abre la ficha y llega a la tienda (CCPIP-52, 53, 54, 152, 165)', async ({ page }) => {
    const errores = vigilarConsola(page)
    await page.goto('/')
    await expect(page.getByRole('heading', { name: /Productos con historia/ })).toBeVisible()
    await sinViolaciones(page)

    await page.getByRole('searchbox', { name: 'Buscar productos' }).fill('sacha inchi nauta')
    await page.getByRole('searchbox', { name: 'Buscar productos' }).press('Enter')
    await expect(page.getByRole('heading', { name: 'Resultados para “sacha inchi nauta”' })).toBeVisible()
    const resultados = page.getByRole('list', { name: 'Productos' })
    await expect(resultados.getByText(PRODUCTO)).toBeVisible()

    // Filtros: linea, region y modalidad; cada uno queda como chip
    await page.getByRole('checkbox', { name: /Aceites/ }).check()
    await expect(page.getByRole('button', { name: 'Quitar filtro Aceites' })).toBeVisible()
    await page.getByRole('checkbox', { name: /Loreto/ }).check()
    await page.getByRole('checkbox', { name: /Mayorista/ }).check()
    await expect(resultados.getByText(PRODUCTO)).toBeVisible()
    await expect(page).toHaveURL(/linea=ACEITES/)
    await sinViolaciones(page)

    await resultados.getByRole('link', { name: new RegExp(PRODUCTO) }).first().click()
    await expect(page.getByRole('heading', { level: 1, name: PRODUCTO })).toBeVisible()
    await expect(page.getByRole('radio', { name: /Precio minorista S\/ 32\.00/ })).toBeVisible()
    await page.getByRole('radio', { name: /Precio mayorista/ }).click()
    await expect(page.getByText('desde 12 und').first()).toBeVisible()
    await expect(page.getByRole('heading', { name: `La historia de ${PRODUCTO}` })).toBeVisible()
    await expect(page.getByText(/redactado con apoyo de inteligencia artificial y aprobado/)).toBeVisible()
    await expect(page.getByText('Consumir antes de')).toBeVisible()
    // El codigo del lote es interno: nunca se muestra al comprador
    await expect(page.getByText('L-0001')).toHaveCount(0)
    await sinViolaciones(page)

    await page.getByRole('link', { name: TIENDA }).first().click()
    await expect(page.getByRole('heading', { level: 1, name: TIENDA })).toBeVisible()
    await expect(page.getByRole('list', { name: `Productos de ${TIENDA}` }).getByText(PRODUCTO)).toBeVisible()
    expect(errores).toEqual([])
  })

  test('la busqueda ignora tildes y mayusculas', async ({ page }) => {
    await page.goto('/buscar?q=CAFÉ')
    await expect(page.getByRole('list', { name: 'Productos' }).getByText(/Cafe tostado en grano/)).toBeVisible()
  })

  test('aparece en Comunidades', async ({ page }) => {
    await page.goto('/comunidades')
    await expect(page.getByRole('heading', { name: TIENDA })).toBeVisible()
    await expect(page.getByRole('link', { name: `Visitar tienda de ${TIENDA}` })).toBeVisible()
    await sinViolaciones(page)
  })
})

test.describe('Edicion y estado del producto (CCPIP-47, 160)', () => {
  test('cambiar el precio se ve en la ficha', async ({ page }) => {
    await ingresar(page, 'productor_nuevo@ccpip.test')
    await page.goto(`/productor/productos/${productoId}`)
    await page.getByLabel('Precio minorista (S/)').fill('30')
    await page.getByRole('button', { name: 'Guardar cambios' }).click()
    await expect(page.getByText('Cambios guardados')).toBeVisible()

    await page.goto(`/productos/${productoId}`)
    await expect(page.getByRole('radio', { name: /Precio minorista S\/ 30\.00/ })).toBeVisible()
  })

  test('despublicar lo saca del catalogo sin borrarlo y se puede volver a publicar', async ({ page }) => {
    await ingresar(page, 'productor_nuevo@ccpip.test')
    await page.goto('/productor/productos')
    await page.getByRole('button', { name: `Más acciones para ${PRODUCTO}` }).click()
    await page.getByRole('menuitem', { name: 'Despublicar' }).click()
    await expect(page.getByRole('dialog')).toContainText('No se borra')
    await page.getByRole('dialog').getByRole('button', { name: 'Despublicar' }).click()
    await expect(page.getByText('Producto despublicado')).toBeVisible()

    await page.goto(`/productos/${productoId}`)
    await expect(page.getByText('Este producto no está disponible')).toBeVisible()
    await page.goto('/buscar?q=sacha%20inchi%20nauta')
    await expect(page.getByText('No encontramos productos para “sacha inchi nauta”')).toBeVisible()

    await page.goto('/productor/productos')
    await page.getByRole('tab', { name: /Despublicados \(1\)/ }).click()
    await page.getByRole('button', { name: `Más acciones para ${PRODUCTO}` }).click()
    await page.getByRole('menuitem', { name: 'Volver a publicar' }).click()
    await expect(page.getByText('Producto publicado')).toBeVisible()

    await page.goto(`/productos/${productoId}`)
    await expect(page.getByRole('heading', { level: 1, name: PRODUCTO })).toBeVisible()
  })
})

test.describe('Reglas y seguridad', () => {
  test('otro productor no ve ni edita el producto (RNF-05)', async ({ page }) => {
    await ingresar(page, 'productor@ccpip.test')
    await page.goto(`/productor/productos/${productoId}`)
    await expect(page.getByText('Este producto no existe')).toBeVisible()
    await page.goto(`/productor/lotes?producto=${productoId}`)
    await expect(page.getByText('Ese producto no existe')).toBeVisible()

    expect((await api(page, 'GET', `/api/productor/productos/${productoId}`)).status).toBe(404)
    const editar = await api(page, 'PUT', `/api/productor/productos/${productoId}`, {
      nombre: 'Robado', lineas: ['ACEITES'], unidad: 'BOTELLA', modalidad: 'MINORISTA', precio: 1,
    })
    expect(editar.status).toBe(404)
    expect((await api(page, 'POST', `/api/productor/productos/${productoId}/retirar`)).status).toBe(404)
  })

  test('sin un lote vendible no se publica; el almacen no cuenta y no se aceptan fechas pasadas (CCPIP-40, 41)', async ({ page }) => {
    await ingresar(page, 'productor_nuevo@ccpip.test')
    const creado = await api(page, 'POST', '/api/productor/productos', {
      nombre: unico('Borrador sin lote'), lineas: ['ACEITES'], unidad: 'BOTELLA', modalidad: 'MINORISTA', precio: 10, descripcion: 'Prueba',
    })
    expect(creado.status).toBe(201)
    const { id } = creado.cuerpo

    const sinLote = await api(page, 'POST', `/api/productor/productos/${id}/publicar`)
    expect(sinLote.status).toBe(422)
    expect(sinLote.cuerpo.code).toBe('PRODUCTO_NO_PUBLICABLE')

    const ubicaciones = (await api(page, 'GET', '/api/productor/ubicaciones')).cuerpo
    const almacen = ubicaciones.find((u) => u.tipo === 'ALMACEN_PRODUCTOR')
    const acopio = ubicaciones.find((u) => u.tipo === 'PUNTO_ACOPIO')

    const pasado = await api(page, 'POST', `/api/productor/productos/${id}/lotes`, {
      codigo: 'L-0100', cantidad: 10, fechaVencimiento: enDias(-1), ubicacionId: acopio.id,
    })
    expect(pasado.status).toBe(422)
    expect(pasado.cuerpo.code).toBe('VENCIMIENTO_PASADO')

    const enAlmacen = await api(page, 'POST', `/api/productor/productos/${id}/lotes`, {
      codigo: 'L-0101', cantidad: 10, fechaVencimiento: enDias(90), ubicacionId: almacen.id,
    })
    expect(enAlmacen.status).toBe(201)
    expect(enAlmacen.cuerpo.enVenta).toBe(0)
    expect((await api(page, 'POST', `/api/productor/productos/${id}/publicar`)).status).toBe(422)

    // En pantalla, el asistente explica lo que falta
    await page.goto(`/productor/productos/${id}?paso=lote`)
    await expect(page.getByText('Para publicar todavía falta:')).toBeVisible()
    await expect(page.getByRole('list', { name: 'Lotes de este producto' }).getByText('En tu almacén')).toBeVisible()
  })

  test('no existe forma de borrar un producto (RNF-17)', async ({ page }) => {
    await ingresar(page, 'productor_nuevo@ccpip.test')
    const borrar = await api(page, 'DELETE', `/api/productor/productos/${productoId}`)
    expect([404, 405]).toContain(borrar.status)
    await page.goto(`/productos/${productoId}`)
    await expect(page.getByRole('heading', { level: 1, name: PRODUCTO })).toBeVisible()
  })

  test('el consumidor no entra al panel del productor y el API lo rechaza (CCPIP-151)', async ({ page }) => {
    await ingresar(page, 'consumidor@ccpip.test')
    await page.goto('/productor/productos')
    await expect(page).toHaveURL(/\/inicio$/)
    expect((await api(page, 'GET', '/api/productor/productos')).status).toBe(403)
  })
})
