# Modelo propio de imágenes (CCPIP-144, CCPIP-176)

El backend valida si una foto corresponde a la línea del producto con un modelo propio que corre
dentro del servidor, en Java puro. La foto no sale de la plataforma y no se usa ningún servicio
externo (RNF-48).

## Cómo está armado

1. **Extractor de características.** MobileNetV2 preentrenado con ImageNet, del ONNX Model Zoo
   (licencia Apache 2.0). Convierte la foto en 1280 números que describen lo que se ve.
2. **Cabeza propia.** Decide a qué línea de CCPIP corresponde la foto.
   - **Versión 0 (la que está en el repositorio).** Agrupa en cada línea las clases de ImageNet que
     la representan, por ejemplo espresso y taza de café para CAFE, o poncho y lana para TEXTIL. El
     mapeo está en `LINEAS`, dentro de `exportar.py`. Lo demás cuenta como `OTRO`.
   - **Versión entrenada.** Una regresión logística aprendida con fotos reales de los productos de la
     Cámara. Se arma con `entrenar.py`.

El backend lee `backend/src/main/resources/ia/modelo-imagenes.bin` (pesos en float16, 7 MB) con
`ModeloImagenes`. Tarda unos 250 ms por foto.

## Regla de decisión

La validación es una recomendación y nunca bloquea la publicación. Una foto se marca como
**sospechosa** solo si otra línea tiene al menos 40 % de probabilidad y duplica a la línea esperada.
Si el modelo no reconoce nada concreto, por ejemplo un empaque, la foto se considera válida.

## Entrenar con fotos reales

```
pip install onnx onnxruntime numpy pillow
# datos/CACAO/*.jpg, datos/CAFE/*.jpg, ... una carpeta por código de línea, 30 fotos o más por línea
python entrenar.py
python exportar.py --cabeza cabeza.npz
```

`entrenar.py` reserva el 20 % de las fotos para medir el acierto y luego entrena con todas. Al
exportar, el archivo del backend se reemplaza. La prueba `ModeloPropioTest` compara la versión 0
contra ONNX Runtime, así que al cambiar la cabeza hay que ajustar esa prueba.

## Mejora de calidad

`MejoradorImagenPropio` no es generativo. Aplica balance de blancos (mundo gris), autocontraste por
canal y una nitidez leve. No puede agregar ni quitar nada del producto (RNF-46).
