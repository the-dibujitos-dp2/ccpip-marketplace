-- Contenido aprobado en ingles de los datos de demostracion (CCPIP-166, CCPIP-357, CCPIP-358).
-- Idempotente (RNF-28): actualiza el texto si cambio y nunca toca contenido creado desde la
-- plataforma. Las lineas y unidades son catalogos de la Camara; los productos, tiendas y
-- comunidades son los sembrados en R__02.

-- Catalogos: lineas de producto y unidades de medida
INSERT INTO traduccion (entidad, clave, campo, idioma, texto, estado, aprobada_en)
SELECT d.entidad, d.clave, 'nombre', 'en', d.texto, 'APROBADA', now()
FROM (VALUES
    ('LINEA', 'CACAO', 'Cacao & chocolate'),
    ('LINEA', 'CAFE', 'Coffee'),
    ('LINEA', 'SUPERFRUTOS', 'Superfruits'),
    ('LINEA', 'ACEITES', 'Oils'),
    ('LINEA', 'INFUSIONES', 'Herbal teas'),
    ('LINEA', 'ARTESANIA', 'Crafts'),
    ('LINEA', 'TEXTIL', 'Textiles'),
    ('UNIDAD', 'UNIDAD', 'Unit'),
    ('UNIDAD', 'KG', 'Kilogram'),
    ('UNIDAD', 'G', 'Gram'),
    ('UNIDAD', 'L', 'Liter'),
    ('UNIDAD', 'ML', 'Milliliter'),
    ('UNIDAD', 'SACO', 'Sack'),
    ('UNIDAD', 'JABA', 'Crate'),
    ('UNIDAD', 'BOTELLA', 'Bottle'),
    ('UNIDAD', 'FRASCO', 'Jar'),
    ('UNIDAD', 'BOLSA', 'Bag'),
    ('UNIDAD', 'PAQUETE', 'Pack'),
    ('UNIDAD', 'CAJA', 'Box'),
    ('UNIDAD', 'SOBRE', 'Sachet')
) AS d(entidad, clave, texto)
ON CONFLICT (entidad, clave, campo, idioma) DO UPDATE SET
    texto = EXCLUDED.texto, estado = 'APROBADA', origen = 'COMUNIDAD', proveedor = NULL,
    aprobada_en = coalesce(traduccion.aprobada_en, now()), actualizado_en = now()
WHERE traduccion.estado <> 'DESACTUALIZADA' AND (traduccion.texto <> EXCLUDED.texto OR traduccion.estado <> 'APROBADA');

-- Productos sembrados: nombre, descripcion e historia aprobada (CCPIP-166)
INSERT INTO traduccion (entidad, clave, campo, idioma, texto, estado, aprobada_en)
SELECT d.entidad, pr.id::text, d.campo, 'en', d.texto, 'APROBADA', now()
FROM (VALUES
    ('Aceite de ungurahui 250 ml', 'PRODUCTO', 'nombre', 'Ungurahui oil 250 ml'),
    ('Aceite de ungurahui 250 ml', 'PRODUCTO', 'descripcion', 'Cold-pressed, unrefined oil.'),
    ('Aceite de ungurahui 250 ml', 'HISTORIA', 'texto', 'Hand-harvested in the Ucayali basin, without cutting down the palm.'),
    ('Aceite de aguaje 250 ml', 'PRODUCTO', 'nombre', 'Aguaje oil 250 ml'),
    ('Aceite de aguaje 250 ml', 'PRODUCTO', 'descripcion', 'Rich in vitamin A, cold-pressed.'),
    ('Aceite de aguaje 250 ml', 'HISTORIA', 'texto', 'Aguaje is gathered from the ground, never by cutting the tree.'),
    ('Camu camu en polvo 150 g', 'PRODUCTO', 'nombre', 'Camu camu powder 150 g'),
    ('Camu camu en polvo 150 g', 'PRODUCTO', 'descripcion', 'Dried at low temperature.'),
    ('Camu camu en polvo 150 g', 'HISTORIA', 'texto', 'It grows wild on the river''s floodplains.'),
    ('Textil bordado shipibo', 'PRODUCTO', 'nombre', 'Shipibo embroidered textile'),
    ('Textil bordado shipibo', 'PRODUCTO', 'descripcion', 'Hand-embroidered with a kene design.'),
    ('Textil bordado shipibo', 'HISTORIA', 'texto', 'Each kene is a song translated into lines. No two are alike.'),
    ('Cacao en grano fermentado 1 kg', 'PRODUCTO', 'nombre', 'Fermented cacao beans 1 kg'),
    ('Cacao en grano fermentado 1 kg', 'PRODUCTO', 'descripcion', 'Fermented for six days in wooden boxes.'),
    ('Cacao en grano fermentado 1 kg', 'HISTORIA', 'texto', 'It grows in the shade of primary forest, without deforestation.'),
    ('Pasta de cacao 500 g', 'PRODUCTO', 'nombre', 'Cacao paste 500 g'),
    ('Pasta de cacao 500 g', 'PRODUCTO', 'descripcion', 'One hundred percent cacao, no added sugar.'),
    ('Pasta de cacao 500 g', 'HISTORIA', 'texto', 'Stone-ground, as the community has always done.'),
    ('Infusion de guayusa 80 g', 'PRODUCTO', 'nombre', 'Guayusa herbal tea 80 g'),
    ('Infusion de guayusa 80 g', 'PRODUCTO', 'descripcion', 'Sun-dried leaf.'),
    ('Infusion de guayusa 80 g', 'HISTORIA', 'texto', 'It''s drunk before dawn, in a circle, to share dreams.'),
    ('Cafe tostado en grano 500 g', 'PRODUCTO', 'nombre', 'Roasted coffee beans 500 g'),
    ('Cafe tostado en grano 500 g', 'PRODUCTO', 'descripcion', 'Medium roast, notes of panela and citrus.'),
    ('Cafe tostado en grano 500 g', 'HISTORIA', 'texto', 'Grown at one thousand eight hundred meters among native trees.'),
    ('Cafe molido 250 g', 'PRODUCTO', 'nombre', 'Ground coffee 250 g'),
    ('Cafe molido 250 g', 'PRODUCTO', 'descripcion', 'Medium grind, for filter.'),
    ('Cafe molido 250 g', 'HISTORIA', 'texto', 'From the same lot as the beans, ground right before packing.'),
    ('Canasta tejida de tamshi', 'PRODUCTO', 'nombre', 'Woven tamshi basket'),
    ('Canasta tejida de tamshi', 'PRODUCTO', 'descripcion', 'Woven with tamshi fiber from the forest.'),
    ('Canasta tejida de tamshi', 'HISTORIA', 'texto', 'The fiber is gathered without killing the vine, so it grows back.')
) AS d(producto, entidad, campo, texto)
JOIN producto pr ON pr.nombre = d.producto
JOIN productor p ON p.id = pr.productor_id
JOIN usuario u ON u.id = p.usuario_id
WHERE u.correo IN ('productor@ccpip.test', 'productor2@ccpip.test', 'productor3@ccpip.test')
ON CONFLICT (entidad, clave, campo, idioma) DO UPDATE SET
    texto = EXCLUDED.texto, estado = 'APROBADA', origen = 'COMUNIDAD', proveedor = NULL,
    aprobada_en = coalesce(traduccion.aprobada_en, now()), actualizado_en = now()
WHERE traduccion.estado <> 'DESACTUALIZADA' AND (traduccion.texto <> EXCLUDED.texto OR traduccion.estado <> 'APROBADA');

-- Tiendas (CCPIP-357) y comunidades (CCPIP-358) sembradas. Los nombres propios no se traducen.
INSERT INTO traduccion (entidad, clave, campo, idioma, texto, estado, aprobada_en)
SELECT d.entidad, p.id::text, d.campo, 'en', d.texto, 'APROBADA', now()
FROM (VALUES
    ('productor@ccpip.test', 'TIENDA', 'descripcion', 'Oils, superfruits and textiles made by Shipibo women from Yarinacocha.'),
    ('productor@ccpip.test', 'COMUNIDAD', 'cita', 'The forest''s plants heal and nourish.'),
    ('productor@ccpip.test', 'COMUNIDAD', 'historia', 'We have worked ungurahui oil for four generations. The harvest is done by hand, climbing the palm without cutting it, so it keeps bearing fruit the next year.'),
    ('productor2@ccpip.test', 'TIENDA', 'descripcion', 'Fine-flavor cacao and herbal teas from the Upper Marañón.'),
    ('productor2@ccpip.test', 'COMUNIDAD', 'cita', 'We plant cacao where there was once logging.'),
    ('productor2@ccpip.test', 'COMUNIDAD', 'historia', 'Our cacao grows in the shade of the forest, without felling a single tree. We ferment it in wooden boxes for six days, as our grandmother taught us.'),
    ('productor3@ccpip.test', 'TIENDA', 'descripcion', 'High-altitude coffee and Asháninka crafts from Chanchamayo.'),
    ('productor3@ccpip.test', 'COMUNIDAD', 'cita', 'Our coffee grows in the shade of the forest.'),
    ('productor3@ccpip.test', 'COMUNIDAD', 'historia', 'We grow coffee at one thousand eight hundred meters, among native trees that shade the bean. Each sack carries the name of the family that harvested it.')
) AS d(correo, entidad, campo, texto)
JOIN usuario u ON u.correo = d.correo
JOIN productor p ON p.usuario_id = u.id
ON CONFLICT (entidad, clave, campo, idioma) DO UPDATE SET
    texto = EXCLUDED.texto, estado = 'APROBADA', origen = 'COMUNIDAD', proveedor = NULL,
    aprobada_en = coalesce(traduccion.aprobada_en, now()), actualizado_en = now()
WHERE traduccion.estado <> 'DESACTUALIZADA' AND (traduccion.texto <> EXCLUDED.texto OR traduccion.estado <> 'APROBADA');
