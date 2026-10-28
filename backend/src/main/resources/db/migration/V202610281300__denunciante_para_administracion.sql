-- CCPIP-432: la administracion de la Camara ve el nombre de quien denuncio, para decidir con informacion
-- suficiente. El productor la sigue viendo anonima (RNF-41). Solo cambia la documentacion de la columna.

COMMENT ON COLUMN denuncia.denunciante_id IS 'RNF-41: el productor nunca ve al denunciante. CCPIP-432: solo el subadministrador y el superadministrador ven su nombre en el expediente. Sirve tambien para el limite de duplicados.';
