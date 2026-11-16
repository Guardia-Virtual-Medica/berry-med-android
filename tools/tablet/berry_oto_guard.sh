#!/system/bin/sh
# Ajuste del otoscopio (349c:3301) en la tablet, como root: que el kernel no pruebe su
# interfaz de disco, porque al probarla lo reinicia. Se pierde al reiniciar la tablet.
#
# Lo que NO hace: impedir que Android lo tome como placa de audio. Se probaron dos formas
# desde acá y las dos se sacaron (16 nov 2026, detalle en docs/hardware.md):
#  - desautorizar sus interfaces de audio apenas aparece: llega tarde 4 de cada 5 veces y
#    Android se queda con un micrófono registrado que ya no existe;
#  - que el bus no autorice nada solo y autorizar desde un script: el retraso alcanza para
#    que Android no reconozca el adaptador del estetoscopio.
echo 349c:3301:i > /sys/module/usb_storage/parameters/quirks
