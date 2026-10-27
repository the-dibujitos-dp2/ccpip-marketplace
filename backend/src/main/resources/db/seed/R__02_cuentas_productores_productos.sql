-- Datos sembrados, parte 2: cuentas, productores, productos y stock inicial
-- CCPIP-150. Idempotente, segun RNF-28.
-- Corre en todos los ambientes: CCPIP-23 pide un usuario de demostracion por rol para
-- las revisiones de sprint, y la prueba de humo del despliegue inicia sesion con
-- consumidor@ccpip.test. Ver docs/devsecops.md.
--
-- Las contrasenas van como hash BCrypt, no en claro (RNF-01). Todas las cuentas de
-- demostracion usan la misma clave, publica a proposito: Demo1234!

INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado) VALUES
    ('consumidor@ccpip.test',   '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Consumidor de Prueba',              1, true),
    ('productor@ccpip.test',    '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Comunidad Shipibo-Konibo',          2, true),
    ('productor2@ccpip.test',   '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Comunidad Awajún',                  2, true),
    ('productor3@ccpip.test',   '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Comunidad Asháninka',               2, true),
    ('admin_acopio@ccpip.test', '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Administrador de Acopio de Prueba', 3, true),
    ('sub_admin@ccpip.test',    '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Sub-Administrador de Prueba',       4, true),
    ('sudo@ccpip.test',         '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Super Administrador de Prueba',     5, true),
    ('distribuidor@ccpip.test', '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Distribuidor de Prueba',            6, true),
    -- CCPIP-27: cuenta sin confirmar para probar que el acceso se rechaza
    ('sin_confirmar@ccpip.test', '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Consumidor sin Confirmar',          1, false),
    -- Productor incorporado por la camara pero con la tienda aun sin armar: prueba el flujo
    -- completo de crear la tienda y publicar el primer producto (PW-08, PW-04)
    ('productor_nuevo@ccpip.test', '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Rosa Ancón',                       2, true),
    -- Comunidades agregadas despues: van al final para no mover los ids de las cuentas de arriba
    ('productor4@ccpip.test',   '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Comunidad Quechua de Chinchero',    2, true),
    ('productor5@ccpip.test',   '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Comunidad Quechua de Taquile',      2, true)
ON CONFLICT (correo) DO NOTHING;

-- El administrador de punto queda ligado al centro nacional (RNF-05).
INSERT INTO usuario_ubicacion (usuario_id, ubicacion_id)
SELECT u.id, l.id
FROM usuario u, ubicacion l
WHERE u.correo = 'admin_acopio@ccpip.test'
  AND l.nombre = 'Centro Nacional Lima'
ON CONFLICT DO NOTHING;

-- Tres productores con su comunidad, region, historia y origen verificado. Cada comunidad
-- sembrada se basa en una comunidad real y su portada es una foto real de ella
-- (PortadasSembradas). Las tiendas, cuentas y precios son de demostracion.
INSERT INTO productor (usuario_id, comunidad, region, historia, origen_verificado)
SELECT u.id, d.comunidad, d.region, d.historia, true
FROM usuario u
JOIN (VALUES
    ('productor@ccpip.test', 'Comunidad Nativa San Francisco de Yarinacocha', 'Ucayali',
     'Somos familias shipibo-konibo a orillas de la laguna de Yarinacocha. Las mujeres bordan el kené, declarado Patrimonio Cultural de la Nación en 2008, y en el monte cosechamos ungurahui y aguaje sin tumbar la palmera.'),
    ('productor2@ccpip.test', 'Comunidad Nativa Umukai', 'Amazonas',
     'En Umukai, distrito de Imaza, sembramos cacao nativo bajo la sombra del bosque, sin tumbar un solo árbol. Lo fermentamos en cajones de madera durante seis días, como nos enseñaron los mayores.'),
    ('productor3@ccpip.test', 'Comunidad Nativa Asháninka Pampa Michi', 'Junin',
     'Pampa Michi está en el valle del Perené, cerca de La Merced. Recibimos a los visitantes con nuestras danzas y cushmas, y en las chacras de la comunidad sembramos café y cacao entre árboles nativos.')
) AS d(correo, comunidad, region, historia) ON d.correo = u.correo
WHERE NOT EXISTS (SELECT 1 FROM productor p WHERE p.usuario_id = u.id);

-- Las comunidades sembradas antes se basaban en comunidades genericas. Se pasan a las reales,
-- solo si conservan el dato sembrado: lo que el productor ya cambio desde la plataforma no se toca.
UPDATE usuario u SET nombre = d.nuevo
FROM (VALUES
    ('productor@ccpip.test',  'Comunidad Shipibo', 'Comunidad Shipibo-Konibo'),
    ('productor2@ccpip.test', 'Comunidad Awajun',  'Comunidad Awajún')
) AS d(correo, anterior, nuevo)
WHERE u.correo = d.correo AND u.nombre = d.anterior;

UPDATE productor p SET comunidad = d.comunidad, region = d.region, historia = coalesce(d.historia, p.historia)
FROM usuario u
JOIN (VALUES
    ('productor@ccpip.test', 'Comunidad Shipibo Konibo de Yarinacocha', 'Comunidad Nativa San Francisco de Yarinacocha', 'Ucayali',
     'Somos familias shipibo-konibo a orillas de la laguna de Yarinacocha. Las mujeres bordan el kené, declarado Patrimonio Cultural de la Nación en 2008, y en el monte cosechamos ungurahui y aguaje sin tumbar la palmera.'),
    ('productor2@ccpip.test', 'Comunidad Awajun del Alto Maranon', 'Comunidad Nativa Umukai', 'Amazonas',
     'En Umukai, distrito de Imaza, sembramos cacao nativo bajo la sombra del bosque, sin tumbar un solo árbol. Lo fermentamos en cajones de madera durante seis días, como nos enseñaron los mayores.'),
    ('productor3@ccpip.test', 'Comunidad Asháninka de Chanchamayo', 'Comunidad Nativa Asháninka Pampa Michi', 'Junin',
     'Pampa Michi está en el valle del Perené, cerca de La Merced. Recibimos a los visitantes con nuestras danzas y cushmas, y en las chacras de la comunidad sembramos café y cacao entre árboles nativos.'),
    -- La tienda de Kukama la arma su productor (PW-08): solo cambia el nombre de la comunidad
    ('productor_nuevo@ccpip.test', 'Comunidad Kukama de Nauta', 'Comunidad Kukama Kukamiria de Nauta', 'Loreto', NULL)
) AS d(correo, anterior, comunidad, region, historia) ON d.correo = u.correo
WHERE p.usuario_id = u.id AND p.comunidad = d.anterior;

-- Diez productos repartidos entre los tres primeros productores, en estado PUBLICADO.
INSERT INTO producto (productor_id, nombre, descripcion, historia_origen, linea_id, unidad_medida_id, precio, es_materia_prima, estado)
SELECT p.id, d.nombre, d.descripcion, d.historia, l.id, m.id, d.precio, false, 'PUBLICADO'
FROM (VALUES
    ('Comunidad Nativa San Francisco de Yarinacocha', 'Aceite de ungurahui 250 ml',   'Aceite prensado en frio, sin refinar.',        'Cosechado a mano en la cuenca del Ucayali, sin talar la palmera.', 'ACEITES',     'ML',     48.00),
    ('Comunidad Nativa San Francisco de Yarinacocha', 'Aceite de aguaje 250 ml',      'Rico en vitamina A, prensado en frio.',        'El aguaje se recolecta del suelo, nunca cortando el arbol.',       'ACEITES',     'ML',     52.00),
    ('Comunidad Nativa San Francisco de Yarinacocha', 'Camu camu en polvo 150 g',     'Deshidratado a baja temperatura.',             'Crece silvestre en las orillas inundables del rio.',              'SUPERFRUTOS', 'G',      39.00),
    ('Comunidad Nativa San Francisco de Yarinacocha', 'Textil bordado shipibo',       'Bordado a mano con diseno kene.',              'Cada kene es un canto traducido en lineas. No se repite ninguno.', 'TEXTIL',      'UNIDAD', 180.00),
    ('Comunidad Nativa Umukai',                       'Cacao en grano fermentado 1 kg','Fermentado seis dias en cajones de madera.',   'Crece bajo sombra de bosque primario, sin deforestar.',            'CACAO',       'KG',     42.00),
    ('Comunidad Nativa Umukai',                       'Pasta de cacao 500 g',         'Cien por ciento cacao, sin azucar anadida.',    'Molido en piedra, como se hizo siempre en la comunidad.',          'CACAO',       'G',      55.00),
    ('Comunidad Nativa Umukai',                       'Infusion de guayusa 80 g',     'Hoja secada al sol.',                          'Se toma antes del amanecer, en circulo, para contar los suenos.',  'INFUSIONES',  'G',      28.00),
    ('Comunidad Nativa Asháninka Pampa Michi',        'Cafe tostado en grano 500 g',  'Tueste medio, notas de panela y citricos.',     'Sembrado en las laderas del valle del Perené, entre árboles nativos.', 'CAFE',     'G',      46.00),
    ('Comunidad Nativa Asháninka Pampa Michi',        'Cafe molido 250 g',            'Molienda media, para filtro.',                  'Del mismo lote que el grano, molido al momento de empacar.',       'CAFE',        'G',      26.00),
    ('Comunidad Nativa Asháninka Pampa Michi',        'Canasta tejida de tamshi',     'Tejida con fibra de tamshi del bosque.',        'La fibra se recoge sin matar la liana, para que vuelva a crecer.',  'ARTESANIA',   'UNIDAD', 95.00)
) AS d(comunidad, nombre, descripcion, historia, linea, unidad, precio)
JOIN productor p ON p.comunidad = d.comunidad
JOIN linea_producto l ON l.codigo = d.linea
JOIN unidad_medida m ON m.codigo = d.unidad
WHERE NOT EXISTS (
    SELECT 1 FROM producto x WHERE x.productor_id = p.id AND x.nombre = d.nombre
);

-- CCPIP-42: una segunda oferta del mismo producto maestro que el cacao awajun, con su propio
-- precio e historia. Va aparte para no mover los ids de los diez de arriba (e2e y fixtures).
INSERT INTO producto (productor_id, nombre, descripcion, historia_origen, linea_id, unidad_medida_id, precio, es_materia_prima, estado)
SELECT p.id, 'Cacao fino de aroma 1 kg', 'Secado al sol sobre tarimas de madera.',
       'Lo sembramos entre los cafetales del Perené, bajo la misma sombra de bosque.', l.id, m.id, 44.00, false, 'PUBLICADO'
FROM productor p
JOIN linea_producto l ON l.codigo = 'CACAO'
JOIN unidad_medida m ON m.codigo = 'KG'
WHERE p.comunidad = 'Comunidad Nativa Asháninka Pampa Michi'
  AND NOT EXISTS (
    SELECT 1 FROM producto x WHERE x.productor_id = p.id AND x.nombre = 'Cacao fino de aroma 1 kg'
);

-- El cafe y el cacao asháninka hablaban de Chanchamayo a 1,800 m y del rio Ene; Pampa Michi esta
-- en el valle del Perené. Solo si la historia sigue siendo la sembrada (RNF-28).
WITH cambio (nombre, anterior, nuevo) AS (VALUES
    ('Cafe tostado en grano 500 g', 'Sembrado a mil ochocientos metros entre arboles nativos.',
     'Sembrado en las laderas del valle del Perené, entre árboles nativos.'),
    ('Cacao fino de aroma 1 kg', 'Lo sembramos entre los cafetales del Ene, bajo la misma sombra de bosque.',
     'Lo sembramos entre los cafetales del Perené, bajo la misma sombra de bosque.')
),
producto_cambiado AS (
    UPDATE producto pr SET historia_origen = c.nuevo
    FROM cambio c
    WHERE pr.nombre = c.nombre AND pr.historia_origen = c.anterior
    RETURNING pr.id
)
UPDATE producto_historia h SET texto_final = c.nuevo
FROM cambio c, producto pr
WHERE h.producto_id = pr.id AND pr.nombre = c.nombre AND h.texto_final = c.anterior;

-- ---------------------------------------------------------------------------------------
-- Tienda, almacen, lotes, lineas e historia (CCPIP-30, CCPIP-41, CCPIP-45, CCPIP-49).
-- Agregado con el flujo del productor. Todo es idempotente y nunca pisa lo que el
-- productor ya edito desde la plataforma.
-- ---------------------------------------------------------------------------------------

INSERT INTO productor (usuario_id, comunidad, region, origen_verificado, verificado_en)
SELECT u.id, 'Comunidad Kukama Kukamiria de Nauta', 'Loreto', true, current_date
FROM usuario u
WHERE u.correo = 'productor_nuevo@ccpip.test'
  AND NOT EXISTS (SELECT 1 FROM productor p WHERE p.usuario_id = u.id);

-- CCPIP-19: dos comunidades quechuas tejedoras. Van despues de Kukama para no mover los ids
-- de los productores ni de las ofertas de arriba (e2e y fixtures).
INSERT INTO productor (usuario_id, comunidad, region, historia, origen_verificado)
SELECT u.id, d.comunidad, d.region, d.historia, true
FROM usuario u
JOIN (VALUES
    ('productor4@ccpip.test', 'Comunidad de Tejedoras de Chinchero', 'Cusco',
     'En Chinchero, a más de 3,700 metros sobre el mar, hilamos con pushka la lana de oveja y alpaca y la teñimos con cochinilla, chilca y otras plantas del valle. Cada diseño lo aprendimos de nuestras abuelas.'),
    ('productor5@ccpip.test', 'Comunidad de la Isla Taquile', 'Puno',
     'Vivimos en la isla de Taquile, en el lago Titicaca. Los hombres tejen los chullos a palitos y las mujeres hilan y tejen las fajas; la UNESCO proclamó nuestro arte textil Obra Maestra del Patrimonio Oral e Inmaterial de la Humanidad en 2005.')
) AS d(correo, comunidad, region, historia) ON d.correo = u.correo
WHERE NOT EXISTS (SELECT 1 FROM productor p WHERE p.usuario_id = u.id);

INSERT INTO producto (productor_id, nombre, descripcion, historia_origen, linea_id, unidad_medida_id, precio, es_materia_prima, estado)
SELECT p.id, d.nombre, d.descripcion, d.historia, l.id, m.id, d.precio, false, 'PUBLICADO'
FROM (VALUES
    ('Comunidad de Tejedoras de Chinchero', 'Chalina de alpaca teñida con cochinilla', 'Tejida en telar de cintura, con tintes naturales.',
     'El rojo sale de la cochinilla que crece en las tunas del valle.', 'TEXTIL', 'UNIDAD', 140.00),
    ('Comunidad de Tejedoras de Chinchero', 'Manta tejida en telar de cintura', 'Lana de oveja hilada a mano con pushka.',
     'Una manta grande toma hasta dos meses de trabajo en el telar.', 'TEXTIL', 'UNIDAD', 320.00),
    ('Comunidad de la Isla Taquile', 'Chullo tejido a palitos', 'Lana de alpaca, tejido por los hombres de la isla.',
     'En Taquile el chullo lo tejen los hombres y sus colores dicen si quien lo lleva es soltero o casado.', 'TEXTIL', 'UNIDAD', 90.00),
    ('Comunidad de la Isla Taquile', 'Chumpi calendario de Taquile', 'Faja tejida en telar con los meses del año.',
     'Cada franja de la faja cuenta las faenas y fiestas de un mes en la isla.', 'TEXTIL', 'UNIDAD', 180.00)
) AS d(comunidad, nombre, descripcion, historia, linea, unidad, precio)
JOIN productor p ON p.comunidad = d.comunidad
JOIN linea_producto l ON l.codigo = d.linea
JOIN unidad_medida m ON m.codigo = d.unidad
WHERE NOT EXISTS (
    SELECT 1 FROM producto x WHERE x.productor_id = p.id AND x.nombre = d.nombre
);

UPDATE productor p SET
    nombre_tienda = d.tienda,
    descripcion_corta = d.descripcion,
    cita = d.cita,
    pueblo = d.pueblo,
    localidad = d.localidad,
    socios = d.socios,
    verificado_en = coalesce(p.verificado_en, current_date - 120),
    -- CCPIP-291: habilitacion manual de datos iniciales; la Camara habilita las demas desde la plataforma
    tienda_habilitada = true,
    tienda_publicada = true,
    tienda_publicada_en = coalesce(p.tienda_publicada_en, now())
FROM usuario u
JOIN (VALUES
    ('productor@ccpip.test', 'Bordadoras de San Francisco', 'Kené bordado, aceites y superfrutos de mujeres shipibo-konibo de Yarinacocha.',
     'Las plantas del monte curan y alimentan.', 'Shipibo-Konibo', 'Yarinacocha', 34),
    ('productor2@ccpip.test', 'Cacaoteros de Umukai', 'Cacao nativo e infusiones awajún de Imaza, en el Marañón.',
     'Nugkui nos enseñó a sembrar la tierra.', 'Awajún', 'Imaza', 20),
    ('productor3@ccpip.test', 'Asháninka de Pampa Michi', 'Café, cacao y artesanías asháninka del valle del Perené.',
     'Nuestro café crece bajo sombra de bosque.', 'Asháninka', 'Perené', 45),
    ('productor4@ccpip.test', 'Tejedoras de Chinchero', 'Chalinas y mantas de telar de cintura, teñidas con tintes naturales.',
     'Lo que tejemos guarda la memoria del pueblo.', 'Quechua', 'Chinchero', 30),
    ('productor5@ccpip.test', 'Tejedores de Taquile', 'Chullos y fajas de la isla Taquile, en el lago Titicaca.',
     'Ama sua, ama llulla, ama qhella.', 'Quechua', 'Taquile', 40)
) AS d(correo, tienda, descripcion, cita, pueblo, localidad, socios) ON d.correo = u.correo
WHERE p.usuario_id = u.id AND p.nombre_tienda IS NULL;

-- Las tiendas sembradas antes pasan a las comunidades reales, solo si el productor aun no cambio
-- el nombre de su tienda desde la plataforma (PW-08)
UPDATE productor p SET
    nombre_tienda = d.tienda,
    descripcion_corta = d.descripcion,
    cita = d.cita,
    localidad = d.localidad
FROM usuario u
JOIN (VALUES
    ('productor@ccpip.test', 'Asoc. Nii Joi', 'Bordadoras de San Francisco',
     'Kené bordado, aceites y superfrutos de mujeres shipibo-konibo de Yarinacocha.', 'Las plantas del monte curan y alimentan.', 'Yarinacocha'),
    ('productor2@ccpip.test', 'C.N. Yarina', 'Cacaoteros de Umukai',
     'Cacao nativo e infusiones awajún de Imaza, en el Marañón.', 'Nugkui nos enseñó a sembrar la tierra.', 'Imaza'),
    ('productor3@ccpip.test', 'Coop. Río Ene', 'Asháninka de Pampa Michi',
     'Café, cacao y artesanías asháninka del valle del Perené.', 'Nuestro café crece bajo sombra de bosque.', 'Perené')
) AS d(correo, anterior, tienda, descripcion, cita, localidad) ON d.correo = u.correo
WHERE p.usuario_id = u.id AND p.nombre_tienda = d.anterior;

-- El almacen y la cuenta llevan el nombre de la comunidad: siguen al cambio de arriba
UPDATE ubicacion x SET nombre = 'Almacén ' || p.comunidad, distrito = coalesce(p.localidad, x.distrito)
FROM productor p
WHERE x.productor_id = p.id AND x.tipo = 'ALMACEN_PRODUCTOR'
  AND x.nombre IN ('Almacén Comunidad Shipibo Konibo de Yarinacocha', 'Almacén Comunidad Awajun del Alto Maranon',
                   'Almacén Comunidad Asháninka de Chanchamayo', 'Almacén Comunidad Kukama de Nauta');

-- Cada productor tiene su almacen, desde donde luego transfiere a un punto de acopio
INSERT INTO ubicacion (tipo, nombre, productor_id, departamento, distrito, direccion)
SELECT 'ALMACEN_PRODUCTOR', 'Almacén ' || p.comunidad, p.id, p.region, p.localidad, 'Almacén de la comunidad'
FROM productor p
WHERE NOT EXISTS (
    SELECT 1 FROM ubicacion x WHERE x.productor_id = p.id AND x.tipo = 'ALMACEN_PRODUCTOR');

-- Los productos sembrados ya estan publicados y en su linea
UPDATE producto SET publicado_en = creado_en WHERE estado = 'PUBLICADO' AND publicado_en IS NULL;

-- CCPIP-55: el cacao en grano es materia prima; la pasta de cacao, producto terminado de la misma linea.
-- Solo si el productor no edito la oferta desde la plataforma.
UPDATE producto SET es_materia_prima = true
WHERE nombre IN ('Cacao en grano fermentado 1 kg', 'Cacao fino de aroma 1 kg')
  AND actualizado_por IS NULL AND NOT es_materia_prima;

-- CCPIP-57: cada tienda sembrada destaca dos productos, como lo haria su productor desde Mi tienda. Solo
-- si el productor aun no eligio sus destacados.
UPDATE producto p SET destacado_tienda = true
WHERE p.nombre IN ('Aceite de ungurahui 250 ml', 'Textil bordado shipibo', 'Cacao en grano fermentado 1 kg',
                   'Pasta de cacao 500 g', 'Cafe tostado en grano 500 g', 'Canasta tejida de tamshi')
  AND p.actualizado_por IS NULL
  AND NOT EXISTS (SELECT 1 FROM producto x WHERE x.productor_id = p.productor_id AND x.destacado_tienda);

INSERT INTO producto_linea (producto_id, linea_id)
SELECT id, linea_id FROM producto WHERE linea_id IS NOT NULL
ON CONFLICT DO NOTHING;

-- La historia sembrada cuenta como escrita y aprobada por la comunidad (CCPIP-49).
-- Solo la de los productos sembrados: la de un producto creado en la plataforma la
-- aprueba la comunidad, no esta semilla.
INSERT INTO producto_historia (producto_id, texto_final, aprobada_en)
SELECT pr.id, pr.historia_origen, now() FROM producto pr
WHERE pr.historia_origen IS NOT NULL AND btrim(pr.historia_origen) <> ''
  AND pr.nombre IN (
        'Aceite de ungurahui 250 ml', 'Aceite de aguaje 250 ml', 'Camu camu en polvo 150 g',
        'Textil bordado shipibo', 'Cacao en grano fermentado 1 kg', 'Pasta de cacao 500 g',
        'Infusion de guayusa 80 g', 'Cafe tostado en grano 500 g', 'Cafe molido 250 g',
        'Canasta tejida de tamshi', 'Cacao fino de aroma 1 kg',
        'Chalina de alpaca teñida con cochinilla', 'Manta tejida en telar de cintura',
        'Chullo tejido a palitos', 'Chumpi calendario de Taquile')
ON CONFLICT (producto_id) DO NOTHING;

-- Existencias iniciales (CCPIP-361, RNF-15): por cada oferta sembrada, un lote en el
-- centro nacional, su movimiento de ENTRADA ligado a ese lote y la fila de stock. Va en un
-- solo comando para que los tres queden juntos o ninguno.
--
-- Solo las ofertas sembradas arriba: un producto creado desde la plataforma nunca
-- recibe existencias de demostracion. Y solo si el producto aun no tiene su carga inicial,
-- asi que volver a correr no duplica nada (RNF-28) ni pisa lo que el inventario ya movio.
-- Las artesanias no vencen (la linea lo decide).
WITH sembrado AS (
    SELECT pr.id AS producto_id, acopio.id AS ubicacion_id, li.requiere_vencimiento
    FROM producto pr
    JOIN productor p ON p.id = pr.productor_id
    JOIN usuario u ON u.id = p.usuario_id
    JOIN linea_producto li ON li.id = pr.linea_id
    JOIN ubicacion acopio ON acopio.nombre = 'Centro Nacional Lima' AND acopio.tipo = 'PUNTO_ACOPIO'
    WHERE u.correo IN ('productor@ccpip.test', 'productor2@ccpip.test', 'productor3@ccpip.test',
                       'productor4@ccpip.test', 'productor5@ccpip.test')
      AND pr.nombre IN (
        'Aceite de ungurahui 250 ml', 'Aceite de aguaje 250 ml', 'Camu camu en polvo 150 g',
        'Textil bordado shipibo', 'Cacao en grano fermentado 1 kg', 'Pasta de cacao 500 g',
        'Infusion de guayusa 80 g', 'Cafe tostado en grano 500 g', 'Cafe molido 250 g',
        'Canasta tejida de tamshi', 'Cacao fino de aroma 1 kg',
        'Chalina de alpaca teñida con cochinilla', 'Manta tejida en telar de cintura',
        'Chullo tejido a palitos', 'Chumpi calendario de Taquile')
      AND NOT EXISTS (
        SELECT 1 FROM movimiento_inventario mv
        WHERE mv.producto_id = pr.id AND mv.observacion = 'Carga inicial de demostracion')
),
nuevo_lote AS (
    INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual,
                      fecha_produccion, fecha_vencimiento, registrado_en)
    SELECT producto_id, ubicacion_id, 'L-' || lpad(producto_id::text, 4, '0'), 100, 100,
           current_date - 30,
           CASE WHEN requiere_vencimiento THEN current_date + 365 END,
           now()
    FROM sembrado
    ON CONFLICT (producto_id, codigo) DO NOTHING
    RETURNING id, producto_id, ubicacion_id, cantidad_inicial
),
entrada AS (
    INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, observacion)
    SELECT producto_id, ubicacion_id, id, 'ENTRADA', cantidad_inicial, 'Carga inicial de demostracion'
    FROM nuevo_lote
    RETURNING producto_id, ubicacion_id, cantidad
)
INSERT INTO stock (producto_id, ubicacion_id, cantidad)
SELECT producto_id, ubicacion_id, cantidad FROM entrada
ON CONFLICT (producto_id, ubicacion_id) DO NOTHING;

-- ---------------------------------------------------------------------------------------
-- Cuenta bancaria y autorizaciones (CCPIP-269, RF-142): solo se compra lo autorizado a un
-- productor con cuenta ACTIVA. Los cinco productores con tienda venden; Kukama
-- (productor_nuevo) aun no registra su cuenta, asi que lo que publique se ve en
-- "Venta pausada" (Figma CW-03c) hasta que la registre (CCPIP-32).
-- ---------------------------------------------------------------------------------------

INSERT INTO cuenta_bancaria (productor_id, banco, titular, numero_cuenta, cci, documento_titular)
SELECT p.id, 'Banco de la Nación', p.comunidad, d.numero, d.cci, d.ruc
FROM productor p
JOIN usuario u ON u.id = p.usuario_id
JOIN (VALUES
    ('productor@ccpip.test',  '04-110-000001', '01811000041100000117', '20601234561'),
    ('productor2@ccpip.test', '04-110-000002', '01811000041100000217', '20601234562'),
    ('productor3@ccpip.test', '04-110-000003', '01811000041100000317', '20601234563'),
    ('productor4@ccpip.test', '04-110-000004', '01811000041100000417', '20601234564'),
    ('productor5@ccpip.test', '04-110-000005', '01811000041100000517', '20601234565')
) AS d(correo, numero, cci, ruc) ON d.correo = u.correo
WHERE NOT EXISTS (SELECT 1 FROM cuenta_bancaria c WHERE c.productor_id = p.id);

-- El titular es la comunidad: sigue al cambio de nombre si nadie lo edito
UPDATE cuenta_bancaria c SET titular = p.comunidad
FROM productor p
WHERE c.productor_id = p.id
  AND c.titular IN ('Comunidad Shipibo Konibo de Yarinacocha', 'Comunidad Awajun del Alto Maranon',
                    'Comunidad Asháninka de Chanchamayo');

INSERT INTO autorizacion_producto (productor_id, producto_id, estado, decidido_en)
SELECT pr.productor_id, pr.id, 'APROBADA', now()
FROM producto pr
JOIN productor p ON p.id = pr.productor_id
JOIN usuario u ON u.id = p.usuario_id
WHERE u.correo IN ('productor@ccpip.test', 'productor2@ccpip.test', 'productor3@ccpip.test',
                       'productor4@ccpip.test', 'productor5@ccpip.test')
  -- Solo los sembrados: lo que un productor publique desde la plataforma pasa por la
  -- revision de la camara, aunque esta semilla vuelva a correr.
  AND pr.nombre IN (
        'Aceite de ungurahui 250 ml', 'Aceite de aguaje 250 ml', 'Camu camu en polvo 150 g',
        'Textil bordado shipibo', 'Cacao en grano fermentado 1 kg', 'Pasta de cacao 500 g',
        'Infusion de guayusa 80 g', 'Cafe tostado en grano 500 g', 'Cafe molido 250 g',
        'Canasta tejida de tamshi', 'Cacao fino de aroma 1 kg',
        'Chalina de alpaca teñida con cochinilla', 'Manta tejida en telar de cintura',
        'Chullo tejido a palitos', 'Chumpi calendario de Taquile')
ON CONFLICT (productor_id, producto_id) DO NOTHING;

-- ---------------------------------------------------------------------------------------
-- Catalogo maestro (CCPIP-42, RF-18): el producto generico de la Camara y, por cada oferta
-- sembrada, a que maestro pertenece. El cacao en grano lo ofrecen dos comunidades, cada una
-- con su precio, historia y existencias. Crear maestros desde la plataforma es CCPIP-312.
-- ---------------------------------------------------------------------------------------

INSERT INTO producto_maestro (codigo, nombre, descripcion, linea_id)
SELECT d.codigo, d.nombre, d.descripcion, l.id
FROM (VALUES
    ('ACEITE-UNGURAHUI', 'Aceite de ungurahui',       'Aceite del fruto de la palmera de ungurahui, prensado en frio.', 'ACEITES'),
    ('ACEITE-AGUAJE',    'Aceite de aguaje',          'Aceite del fruto del aguaje, rico en vitamina A.',               'ACEITES'),
    ('CAMU-CAMU-POLVO',  'Camu camu en polvo',        'Fruto de camu camu deshidratado y molido.',                      'SUPERFRUTOS'),
    ('TEXTIL-KENE',      'Textil bordado kené',       'Tela bordada a mano con diseños kené.',                          'TEXTIL'),
    ('CACAO-GRANO',      'Cacao en grano fermentado', 'Grano de cacao fermentado y secado, listo para tostar.',         'CACAO'),
    ('PASTA-CACAO',      'Pasta de cacao',            'Cacao molido sin azúcar añadida.',                               'CACAO'),
    ('GUAYUSA',          'Infusión de guayusa',       'Hoja de guayusa secada para infusión.',                          'INFUSIONES'),
    ('CAFE-GRANO',       'Café tostado en grano',     'Café de altura tostado, en grano entero.',                       'CAFE'),
    ('CAFE-MOLIDO',      'Café molido',               'Café de altura tostado y molido.',                               'CAFE'),
    ('CANASTA-TAMSHI',   'Canasta tejida de tamshi',  'Canasta tejida con fibra de tamshi.',                            'ARTESANIA'),
    ('CHALINA-ALPACA',   'Chalina de alpaca',         'Chalina tejida con lana de alpaca.',                             'TEXTIL'),
    ('MANTA-TELAR',      'Manta de telar',            'Manta tejida en telar de cintura.',                              'TEXTIL'),
    ('CHULLO',           'Chullo tejido',             'Gorro andino tejido a palitos.',                                 'TEXTIL'),
    ('CHUMPI',           'Chumpi (faja tejida)',      'Faja andina tejida en telar.',                                   'TEXTIL')
) AS d(codigo, nombre, descripcion, linea)
JOIN linea_producto l ON l.codigo = d.linea
ON CONFLICT (codigo) DO NOTHING;

-- Solo las ofertas sembradas y sin maestro: nunca pisa una asociacion hecha desde la plataforma.
UPDATE producto pr SET producto_maestro_id = m.id
FROM (VALUES
    ('Aceite de ungurahui 250 ml',     'ACEITE-UNGURAHUI'),
    ('Aceite de aguaje 250 ml',        'ACEITE-AGUAJE'),
    ('Camu camu en polvo 150 g',       'CAMU-CAMU-POLVO'),
    ('Textil bordado shipibo',         'TEXTIL-KENE'),
    ('Cacao en grano fermentado 1 kg', 'CACAO-GRANO'),
    ('Cacao fino de aroma 1 kg',       'CACAO-GRANO'),
    ('Pasta de cacao 500 g',           'PASTA-CACAO'),
    ('Infusion de guayusa 80 g',       'GUAYUSA'),
    ('Cafe tostado en grano 500 g',    'CAFE-GRANO'),
    ('Cafe molido 250 g',              'CAFE-MOLIDO'),
    ('Canasta tejida de tamshi',       'CANASTA-TAMSHI'),
    ('Chalina de alpaca teñida con cochinilla', 'CHALINA-ALPACA'),
    ('Manta tejida en telar de cintura',        'MANTA-TELAR'),
    ('Chullo tejido a palitos',                 'CHULLO'),
    ('Chumpi calendario de Taquile',            'CHUMPI')
) AS d(oferta, maestro)
JOIN producto_maestro m ON m.codigo = d.maestro,
     productor p
JOIN usuario u ON u.id = p.usuario_id
WHERE pr.nombre = d.oferta
  AND pr.productor_id = p.id
  AND u.correo IN ('productor@ccpip.test', 'productor2@ccpip.test', 'productor3@ccpip.test',
                       'productor4@ccpip.test', 'productor5@ccpip.test')
  AND pr.producto_maestro_id IS NULL;
