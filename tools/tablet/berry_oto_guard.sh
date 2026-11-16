#!/system/bin/sh
# Guardia del otoscopio (349c:3301), como root. Dos cosas:
#  - que el kernel ignore su interfaz de disco (lo reinicia al probarla);
#  - que Android no lo tome como placa de audio: se desautorizan sus interfaces de audio
#    apenas aparece, así el micrófono de la llamada no se muda al otoscopio.
echo 349c:3301:i > /sys/module/usb_storage/parameters/quirks
while true; do
  for v in /sys/bus/usb/devices/*/idVendor; do
    d=${v%/idVendor}
    [ "$(cat $v 2>/dev/null)" = "349c" ] || continue
    [ "$(cat $d/idProduct 2>/dev/null)" = "3301" ] || continue
    for i in $d:*; do
      [ "$(cat $i/bInterfaceClass 2>/dev/null)" = "01" ] || continue
      [ "$(cat $i/authorized 2>/dev/null)" = "1" ] && echo 0 > $i/authorized 2>/dev/null
    done
  done
  sleep 0.2
done
