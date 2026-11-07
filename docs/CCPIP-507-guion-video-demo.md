# CCPIP-507 · Guion del video comercial para la demo

**Estado.** Borrador para aprobación del equipo. Cuando esté aprobado, se graba sobre el ambiente
desplegado y el video se guarda en la carpeta del proyecto (`Entregables/Demo`).

**Formato.** 2:30 a 3:00 minutos, 1920×1080, en español con subtítulos.

- Voz en off generada con IA (por ejemplo, ElevenLabs o la voz de Google AI Studio), con una voz
  cálida y pausada.
- Música libre de derechos, a volumen bajo.

**Reglas.** Solo datos de prueba:

- las cuentas `@ccpip.test`;
- las comunidades y los productos de la semilla;
- las portadas con créditos en `backend/src/main/resources/semilla/portadas/CREDITOS.md`.

Sin nombres, correos, DNI, teléfonos ni cuentas bancarias reales. Antes de grabar, revisa que la barra
del navegador no muestre otras pestañas ni marcadores.

## Escenas

| # | Tiempo | Pantalla (qué se graba) | Voz en off | Texto en pantalla |
|---|---|---|---|---|
| 1 | 0:00–0:12 | Portada de la tienda (`/`), recorrido lento por las comunidades destacadas | «Detrás de cada producto hay una comunidad, un territorio y una historia.» | **Mercado CCPIP** |
| 2 | 0:12–0:25 | Comunidades (`/comunidades`), tarjetas con pueblo y región | «Mercado CCPIP es la tienda en línea de la Cámara de Comercio de los Pueblos Indígenas del Perú: une a las comunidades productoras con quienes compran en todo el país.» | Comercio justo · Origen verificado |
| 3 | 0:25–0:50 | **Comprador.** Busca «café», filtra por línea y región, abre la ficha: precio por unidad, comunidad, historia, sellos y certificación | «Quien compra encuentra el producto, conoce a la comunidad que lo hace y ve sus certificaciones.» | Origen verificado por la Cámara |
| 4 | 0:50–1:05 | **Comprador.** Asistente de catálogo: «¿Qué café de Junín me recomiendas?», respuesta con productos citados | «Y si no sabe por dónde empezar, el asistente con inteligencia artificial le recomienda productos reales del catálogo, sin inventar nada.» | Asistente con IA |
| 5 | 1:05–1:25 | **Comprador.** Carrito, compra con recojo en punto de acopio o envío a domicilio, pago de prueba, «Mis pedidos» con el seguimiento | «Compra en soles, elige recoger en un punto de acopio o recibir en casa, y sigue su pedido paso a paso.» | Pago seguro · Seguimiento |
| 6 | 1:25–1:50 | **Productor** (`productor@ccpip.test`). Panel, publicar producto: fotos, línea, precio, «Sugerir una descripción con IA» y «Generar borrador con IA» para la historia, primer lote | «Las comunidades publican sus productos en minutos. La IA les ayuda a contar su historia con sus propias palabras: siempre es un borrador que ellas revisan.» | Borrador con IA · La comunidad decide |
| 7 | 1:50–2:05 | **Productor.** Validación de la foto con el modelo propio, lotes con vencimiento, panel de ventas | «La plataforma valida las fotos y controla cada lote, para que lo que llega al comprador sea lo que se publicó.» | Trazabilidad por lote |
| 8 | 2:05–2:30 | **Cámara** (`sub_admin@ccpip.test`). Revisión de una solicitud de autorización: evidencias, análisis preliminar con IA marcado como sugerencia, decisión humana | «La Cámara revisa cada productor y cada producto. La IA señala lo que conviene mirar; la decisión siempre es de una persona.» | La IA sugiere, la Cámara decide |
| 9 | 2:30–2:45 | **Cámara** (`sudo@ccpip.test`). Tablero y auditoría de operaciones | «Todo queda registrado: quién hizo qué y cuándo.» | Auditoría completa |
| 10 | 2:45–3:00 | Vuelta a la portada, logo | «Mercado CCPIP: el valor de los pueblos indígenas, a un clic.» | **Mercado CCPIP** · URL del ambiente |

## Cuentas y datos para grabar

| Escena | Cuenta | Preparación |
|---|---|---|
| 3–5 | `consumidor@ccpip.test` | Carrito vacío al empezar. Pago con la tarjeta de prueba de la pasarela (modo prueba) |
| 6–7 | `productor@ccpip.test` | Un producto nuevo de ejemplo («Miel de abeja nativa 500 g») listo para publicar en la toma |
| 8 | `sub_admin@ccpip.test` | Una solicitud de autorización pendiente con un PDF de prueba |
| 9 | `sudo@ccpip.test` | — |

Las contraseñas son las de la semilla del ambiente de pruebas. No se escriben en el video: se
ingresa antes de empezar cada toma.

## Cómo producirlo con IA

1. **Voz.** Se pega la columna «Voz en off» en el generador de voz y se exporta una pista por escena.
2. **Grabación.** Pantalla completa del navegador a 1080p, con el cursor visible y movimientos lentos.
   Una toma por escena, dejando 1 segundo de margen al inicio y al final.
3. **Edición.** Se unen tomas, voz, música y textos (CapCut, DaVinci Resolve o Clipchamp). Los
   subtítulos se generan automáticamente y se revisan a mano.
4. **Revisión.** El equipo revisa que no aparezcan datos reales, que no haya errores en pantalla y que
   los textos coincidan con el producto.

## Pendiente

- [ ] El equipo aprueba el guion, en la reunión o como comentario en CCPIP-507.
- [ ] Grabar sobre el ambiente desplegado, con los cambios del Sprint 3 ya integrados.
- [ ] Guardar el video final y sus fuentes (voz, tomas) en `Entregables/Demo`.
