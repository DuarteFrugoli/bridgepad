# USB accessory transport

Experimental Android Open Accessory (AOA) transport. The desktop is the USB
host and switches the phone into accessory mode; Android then sends BridgePad
v1 packets over the accessory bulk endpoints.

This module is intentionally independent from Android's USB-host input path,
which reads a physical controller connected to the phone.
