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
    ('productor@ccpip.test',    '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Comunidad Shipibo',                 2, true),
    ('productor2@ccpip.test',   '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Comunidad Awajun',                  2, true),
    ('productor3@ccpip.test',   '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Comunidad Asháninka',               2, true),
    ('admin_acopio@ccpip.test', '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Administrador de Acopio de Prueba', 3, true),
    ('sub_admin@ccpip.test',    '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Sub-Administrador de Prueba',       4, true),
    ('sudo@ccpip.test',         '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Super Administrador de Prueba',     5, true),
    ('distribuidor@ccpip.test', '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Distribuidor de Prueba',            6, true),
    -- CCPIP-27: cuenta sin confirmar para probar que el acceso se rechaza
    ('sin_confirmar@ccpip.test', '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Consumidor sin Confirmar',          1, false),
    -- Productor incorporado por la camara pero con la tienda aun sin armar: prueba el flujo
    -- completo de crear la tienda y publicar el primer producto (PW-08, PW-04)
    ('productor_nuevo@ccpip.test', '$2a$10$2ln4nR8G1zpHwBRRCSr9yufhBT9pGqQk7bXZDe5PfVR0nQivfBxM.', 'Rosa Ancón',                       2, true)
ON CONFLICT (correo) DO NOTHING;

-- El administrador de punto queda ligado al centro nacional (RNF-05).
INSERT INTO usuario_ubicacion (usuario_id, ubicacion_id)
SELECT u.id, l.id
FROM usuario u, ubicacion l
WHERE u.correo = 'admin_acopio@ccpip.test'
  AND l.nombre = 'Centro Nacional Lima'
ON CONFLICT DO NOTHING;

-- Tres productores con su comunidad, region, historia y origen verificado.
INSERT INTO productor (usuario_id, comunidad, region, historia, origen_verificado)
SELECT u.id, d.comunidad, d.region, d.historia, true
FROM usuario u
JOIN (VALUES
    ('productor@ccpip.test', 'Comunidad Shipibo Konibo de Yarinacocha', 'Ucayali',
     'Trabajamos el aceite de ungurahui desde hace cuatro generaciones. La cosecha se hace a mano, subiendo la palmera sin cortarla, para que siga dando fruto el ano siguiente.'),
    ('productor2@ccpip.test', 'Comunidad Awajun del Alto Maranon', 'Amazonas',
     'Nuestro cacao crece bajo la sombra del bosque, sin tumbar un solo arbol. Lo fermentamos en cajones de madera durante seis dias, como nos enseno la abuela.'),
    ('productor3@ccpip.test', 'Comunidad Asháninka de Chanchamayo', 'Junin',
     'Sembramos cafe a mil ochocientos metros, entre arboles nativos que dan sombra al grano. Cada saco lleva el nombre de la familia que lo cosecho.')
) AS d(correo, comunidad, region, historia) ON d.correo = u.correo
WHERE NOT EXISTS (SELECT 1 FROM productor p WHERE p.usuario_id = u.id);

-- Diez productos repartidos entre los tres productores, en estado PUBLICADO.
INSERT INTO producto (productor_id, nombre, descripcion, historia_origen, linea_id, unidad_medida_id, precio, es_materia_prima, estado)
SELECT p.id, d.nombre, d.descripcion, d.historia, l.id, m.id, d.precio, false, 'PUBLICADO'
FROM (VALUES
    ('Comunidad Shipibo Konibo de Yarinacocha', 'Aceite de ungurahui 250 ml',   'Aceite prensado en frio, sin refinar.',        'Cosechado a mano en la cuenca del Ucayali, sin talar la palmera.', 'ACEITES',     'ML',     48.00),
    ('Comunidad Shipibo Konibo de Yarinacocha', 'Aceite de aguaje 250 ml',      'Rico en vitamina A, prensado en frio.',        'El aguaje se recolecta del suelo, nunca cortando el arbol.',       'ACEITES',     'ML',     52.00),
    ('Comunidad Shipibo Konibo de Yarinacocha', 'Camu camu en polvo 150 g',     'Deshidratado a baja temperatura.',             'Crece silvestre en las orillas inundables del rio.',              'SUPERFRUTOS', 'G',      39.00),
    ('Comunidad Shipibo Konibo de Yarinacocha', 'Textil bordado shipibo',       'Bordado a mano con diseno kene.',              'Cada kene es un canto traducido en lineas. No se repite ninguno.', 'TEXTIL',      'UNIDAD', 180.00),
    ('Comunidad Awajun del Alto Maranon',       'Cacao en grano fermentado 1 kg','Fermentado seis dias en cajones de madera.',   'Crece bajo sombra de bosque primario, sin deforestar.',            'CACAO',       'KG',     42.00),
    ('Comunidad Awajun del Alto Maranon',       'Pasta de cacao 500 g',         'Cien por ciento cacao, sin azucar anadida.',    'Molido en piedra, como se hizo siempre en la comunidad.',          'CACAO',       'G',      55.00),
    ('Comunidad Awajun del Alto Maranon',       'Infusion de guayusa 80 g',     'Hoja secada al sol.',                          'Se toma antes del amanecer, en circulo, para contar los suenos.',  'INFUSIONES',  'G',      28.00),
    ('Comunidad Asháninka de Chanchamayo',      'Cafe tostado en grano 500 g',  'Tueste medio, notas de panela y citricos.',     'Sembrado a mil ochocientos metros entre arboles nativos.',         'CAFE',        'G',      46.00),
    ('Comunidad Asháninka de Chanchamayo',      'Cafe molido 250 g',            'Molienda media, para filtro.',                  'Del mismo lote que el grano, molido al momento de empacar.',       'CAFE',        'G',      26.00),
    ('Comunidad Asháninka de Chanchamayo',      'Canasta tejida de tamshi',     'Tejida con fibra de tamshi del bosque.',        'La fibra se recoge sin matar la liana, para que vuelva a crecer.',  'ARTESANIA',   'UNIDAD', 95.00)
) AS d(comunidad, nombre, descripcion, historia, linea, unidad, precio)
JOIN productor p ON p.comunidad = d.comunidad
JOIN linea_producto l ON l.codigo = d.linea
JOIN unidad_medida m ON m.codigo = d.unidad
WHERE NOT EXISTS (
    SELECT 1 FROM producto x WHERE x.productor_id = p.id AND x.nombre = d.nombre
);

-- Stock inicial en el centro nacional, con su movimiento de entrada.
-- Se insertan los dos juntos para que el stock quede explicado por un movimiento
-- desde el primer dia, que es lo que exige RNF-15.
INSERT INTO movimiento_inventario (producto_id, ubicacion_id, tipo, cantidad, observacion)
SELECT pr.id, l.id, 'ENTRADA', 100, 'Carga inicial de demostracion'
FROM producto pr, ubicacion l
WHERE l.nombre = 'Centro Nacional Lima'
  AND NOT EXISTS (
    SELECT 1 FROM movimiento_inventario mv
    WHERE mv.producto_id = pr.id AND mv.ubicacion_id = l.id
      AND mv.observacion = 'Carga inicial de demostracion'
  );

INSERT INTO stock (producto_id, ubicacion_id, cantidad)
SELECT pr.id, l.id, 100
FROM producto pr, ubicacion l
WHERE l.nombre = 'Centro Nacional Lima'
ON CONFLICT (producto_id, ubicacion_id) DO NOTHING;

-- ---------------------------------------------------------------------------------------
-- Tienda, almacen, lotes, lineas e historia (CCPIP-30, CCPIP-41, CCPIP-45, CCPIP-49).
-- Agregado con el flujo del productor. Todo es idempotente y nunca pisa lo que el
-- productor ya edito desde la plataforma.
-- ---------------------------------------------------------------------------------------

INSERT INTO productor (usuario_id, comunidad, region, origen_verificado, verificado_en)
SELECT u.id, 'Comunidad Kukama de Nauta', 'Loreto', true, current_date
FROM usuario u
WHERE u.correo = 'productor_nuevo@ccpip.test'
  AND NOT EXISTS (SELECT 1 FROM productor p WHERE p.usuario_id = u.id);

UPDATE productor p SET
    nombre_tienda = d.tienda,
    descripcion_corta = d.descripcion,
    cita = d.cita,
    pueblo = d.pueblo,
    localidad = d.localidad,
    socios = d.socios,
    verificado_en = coalesce(p.verificado_en, current_date - 120),
    tienda_publicada = true,
    tienda_publicada_en = coalesce(p.tienda_publicada_en, now())
FROM usuario u
JOIN (VALUES
    ('productor@ccpip.test', 'Asoc. Nii Joi', 'Aceites, superfrutos y textiles de mujeres shipibo de Yarinacocha.',
     'Las plantas del monte curan y alimentan.', 'Shipibo-Konibo', 'Yarinacocha', 34),
    ('productor2@ccpip.test', 'C.N. Yarina', 'Cacao fino de aroma e infusiones del Alto Marañón.',
     'Sembramos cacao donde antes hubo tala.', 'Awajún', 'Alto Marañón', 20),
    ('productor3@ccpip.test', 'Coop. Río Ene', 'Café de altura y artesanías asháninka de Chanchamayo.',
     'Nuestro café crece bajo sombra de bosque.', 'Asháninka', 'Chanchamayo', 45)
) AS d(correo, tienda, descripcion, cita, pueblo, localidad, socios) ON d.correo = u.correo
WHERE p.usuario_id = u.id AND p.nombre_tienda IS NULL;

-- Cada productor tiene su almacen, desde donde luego transfiere a un punto de acopio
INSERT INTO ubicacion (tipo, nombre, productor_id, departamento, distrito, direccion)
SELECT 'ALMACEN_PRODUCTOR', 'Almacén ' || p.comunidad, p.id, p.region, p.localidad, 'Almacén de la comunidad'
FROM productor p
WHERE NOT EXISTS (
    SELECT 1 FROM ubicacion x WHERE x.productor_id = p.id AND x.tipo = 'ALMACEN_PRODUCTOR');

-- Los productos sembrados ya estan publicados y en su linea
UPDATE producto SET publicado_en = creado_en WHERE estado = 'PUBLICADO' AND publicado_en IS NULL;

INSERT INTO producto_linea (producto_id, linea_id)
SELECT id, linea_id FROM producto WHERE linea_id IS NOT NULL
ON CONFLICT DO NOTHING;

-- La historia sembrada cuenta como escrita y aprobada por la comunidad (CCPIP-49)
INSERT INTO producto_historia (producto_id, texto_final, aprobada_en)
SELECT id, historia_origen, now() FROM producto
WHERE historia_origen IS NOT NULL AND btrim(historia_origen) <> ''
ON CONFLICT (producto_id) DO NOTHING;

-- Un lote vigente por producto sembrado, que explica el stock de demostracion (RNF-15).
-- Las artesanias no vencen (la linea lo decide).
INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual,
                  fecha_produccion, fecha_vencimiento, registrado_en)
SELECT s.producto_id, s.ubicacion_id, 'L-' || lpad(s.producto_id::text, 4, '0'), s.cantidad, s.cantidad,
       current_date - 30,
       CASE WHEN l.requiere_vencimiento THEN current_date + 365 END,
       now()
FROM stock s
JOIN producto pr ON pr.id = s.producto_id
JOIN linea_producto l ON l.id = pr.linea_id
WHERE s.cantidad > 0
  AND NOT EXISTS (SELECT 1 FROM lote x WHERE x.producto_id = s.producto_id);
