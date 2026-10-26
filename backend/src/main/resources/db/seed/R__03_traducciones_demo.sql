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
    ('Cafe tostado en grano 500 g', 'HISTORIA', 'texto', 'Grown on the slopes of the Perené valley, among native trees.'),
    ('Cafe molido 250 g', 'PRODUCTO', 'nombre', 'Ground coffee 250 g'),
    ('Cafe molido 250 g', 'PRODUCTO', 'descripcion', 'Medium grind, for filter.'),
    ('Cafe molido 250 g', 'HISTORIA', 'texto', 'From the same lot as the beans, ground right before packing.'),
    ('Canasta tejida de tamshi', 'PRODUCTO', 'nombre', 'Woven tamshi basket'),
    ('Canasta tejida de tamshi', 'PRODUCTO', 'descripcion', 'Woven with tamshi fiber from the forest.'),
    ('Canasta tejida de tamshi', 'HISTORIA', 'texto', 'The fiber is gathered without killing the vine, so it grows back.'),
    ('Chalina de alpaca teñida con cochinilla', 'PRODUCTO', 'nombre', 'Cochineal-dyed alpaca scarf'),
    ('Chalina de alpaca teñida con cochinilla', 'PRODUCTO', 'descripcion', 'Woven on a backstrap loom with natural dyes.'),
    ('Chalina de alpaca teñida con cochinilla', 'HISTORIA', 'texto', 'The red comes from the cochineal that lives on the valley''s prickly pears.'),
    ('Manta tejida en telar de cintura', 'PRODUCTO', 'nombre', 'Backstrap loom blanket'),
    ('Manta tejida en telar de cintura', 'PRODUCTO', 'descripcion', 'Sheep''s wool hand-spun with a pushka spindle.'),
    ('Manta tejida en telar de cintura', 'HISTORIA', 'texto', 'A large blanket takes up to two months of work on the loom.'),
    ('Chullo tejido a palitos', 'PRODUCTO', 'nombre', 'Hand-knitted chullo'),
    ('Chullo tejido a palitos', 'PRODUCTO', 'descripcion', 'Alpaca wool, knitted by the men of the island.'),
    ('Chullo tejido a palitos', 'HISTORIA', 'texto', 'On Taquile the men knit the chullo, and its colors tell whether the wearer is single or married.'),
    ('Chumpi calendario de Taquile', 'PRODUCTO', 'nombre', 'Taquile calendar chumpi'),
    ('Chumpi calendario de Taquile', 'PRODUCTO', 'descripcion', 'Loom-woven belt showing the months of the year.'),
    ('Chumpi calendario de Taquile', 'HISTORIA', 'texto', 'Each band of the belt tells the work and festivities of one month on the island.')
) AS d(producto, entidad, campo, texto)
JOIN producto pr ON pr.nombre = d.producto
JOIN productor p ON p.id = pr.productor_id
JOIN usuario u ON u.id = p.usuario_id
WHERE u.correo IN ('productor@ccpip.test', 'productor2@ccpip.test', 'productor3@ccpip.test',
                   'productor4@ccpip.test', 'productor5@ccpip.test')
ON CONFLICT (entidad, clave, campo, idioma) DO UPDATE SET
    texto = EXCLUDED.texto, estado = 'APROBADA', origen = 'COMUNIDAD', proveedor = NULL,
    aprobada_en = coalesce(traduccion.aprobada_en, now()), actualizado_en = now()
WHERE traduccion.estado <> 'DESACTUALIZADA' AND (traduccion.texto <> EXCLUDED.texto OR traduccion.estado <> 'APROBADA');

-- Tiendas (CCPIP-357) y comunidades (CCPIP-358) sembradas. Los nombres propios no se traducen.
INSERT INTO traduccion (entidad, clave, campo, idioma, texto, estado, aprobada_en)
SELECT d.entidad, p.id::text, d.campo, 'en', d.texto, 'APROBADA', now()
FROM (VALUES
    ('productor@ccpip.test', 'TIENDA', 'descripcion', 'Kené embroidery, oils and superfruits made by Shipibo-Konibo women from Yarinacocha.'),
    ('productor@ccpip.test', 'COMUNIDAD', 'cita', 'The forest''s plants heal and nourish.'),
    ('productor@ccpip.test', 'COMUNIDAD', 'historia', 'We are Shipibo-Konibo families on the shores of the Yarinacocha lagoon. The women embroider kené, declared Cultural Heritage of the Nation in 2008, and in the forest we harvest ungurahui and aguaje without felling the palm.'),
    ('productor2@ccpip.test', 'TIENDA', 'descripcion', 'Native cacao and Awajún herbal teas from Imaza, on the Marañón.'),
    ('productor2@ccpip.test', 'COMUNIDAD', 'cita', 'Nugkui taught us to sow the land.'),
    ('productor2@ccpip.test', 'COMUNIDAD', 'historia', 'In Umukai, in the district of Imaza, we grow native cacao in the shade of the forest, without felling a single tree. We ferment it in wooden boxes for six days, as our elders taught us.'),
    ('productor3@ccpip.test', 'TIENDA', 'descripcion', 'Coffee, cacao and Asháninka crafts from the Perené valley.'),
    ('productor3@ccpip.test', 'COMUNIDAD', 'cita', 'Our coffee grows in the shade of the forest.'),
    ('productor3@ccpip.test', 'COMUNIDAD', 'historia', 'Pampa Michi lies in the Perené valley, near La Merced. We welcome visitors with our dances and cushmas, and in the community''s plots we grow coffee and cacao among native trees.'),
    ('productor4@ccpip.test', 'TIENDA', 'descripcion', 'Backstrap loom scarves and blankets, dyed with natural dyes.'),
    ('productor4@ccpip.test', 'COMUNIDAD', 'cita', 'What we weave keeps the memory of our people.'),
    ('productor4@ccpip.test', 'COMUNIDAD', 'historia', 'In Chinchero, more than 3,700 meters above sea level, we spin sheep and alpaca wool with a pushka and dye it with cochineal, chilca and other plants of the valley. We learned every design from our grandmothers.'),
    ('productor5@ccpip.test', 'TIENDA', 'descripcion', 'Chullos and belts from Taquile Island, on Lake Titicaca.'),
    ('productor5@ccpip.test', 'COMUNIDAD', 'cita', 'Do not steal, do not lie, do not be lazy.'),
    ('productor5@ccpip.test', 'COMUNIDAD', 'historia', 'We live on Taquile Island, on Lake Titicaca. The men knit chullos and the women spin and weave the belts; in 2005 UNESCO proclaimed our textile art a Masterpiece of the Oral and Intangible Heritage of Humanity.')
) AS d(correo, entidad, campo, texto)
JOIN usuario u ON u.correo = d.correo
JOIN productor p ON p.usuario_id = u.id
ON CONFLICT (entidad, clave, campo, idioma) DO UPDATE SET
    texto = EXCLUDED.texto, estado = 'APROBADA', origen = 'COMUNIDAD', proveedor = NULL,
    aprobada_en = coalesce(traduccion.aprobada_en, now()), actualizado_en = now()
WHERE traduccion.estado <> 'DESACTUALIZADA' AND (traduccion.texto <> EXCLUDED.texto OR traduccion.estado <> 'APROBADA');
