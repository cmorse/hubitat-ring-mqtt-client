# Hubitat Ring Alarm Implementation

Talks to [ring-alarm-mqtt] (which does all the heavy lifting) via MQTT.

## Dependencies

* NodeJS running [ring-alarm-mqtt] instance
* Mosquitto borker [mosquitto]

[ring-alarm-mqtt]: https://github.com/tsightler/ring-alarm-mqtt
[mosquitto]: https://github.com/eclipse/mosquitto

## Installation

* Install all drivers in [drivers/](drivers/)
* Create a new hubitat device using 'Ring Alarm MQTT Client' as the driver
* Configure the new device to match your environment
