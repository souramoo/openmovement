# Microchip MLA v2011-12-05

This directory contains a vendored copy of the historical Microchip Libraries for Applications
(MLA) USB slice used by the AX3 firmware project.

Provenance

- Official archive page:
  `https://www.microchip.com/en-us/tools-resources/develop/libraries/microchip-libraries-for-applications`
- Exact package used:
  `https://ww1.microchip.com/downloads/en/softwarelibrary/microchip-application-libraries-v2011-12-05-windows-installer.exe`
- Installer SHA-256:
  `de9d813696e660097f892901b99bdfa46366689cd266116fb3a97c4b19724530`

Why this version

- The AX3 firmware project references `C:\Microchip Solutions v2011-12-05\...` in
  [CWA.mcp](/Users/smooker1/Documents/openmovement/Firmware/AX3/Firmware/src/CWA.mcp#L214)
  and
  [CWA.mcp](/Users/smooker1/Documents/openmovement/Firmware/AX3/Firmware/src/CWA.mcp#L267).

Contents

- `Microchip/USB/usb_device.c`
- `Microchip/USB/usb_device_local.h`
- `Microchip/USB/CDC Device Driver/usb_function_cdc.c`
- `Microchip/USB/MSD Device Driver/usb_function_msd.c`
- `Microchip/Include/USB/*`
- `Microchip/Include/GenericTypeDefs.h`
- `Microchip/Include/Compiler.h`
- `Microchip/Common/TimeDelay.c`
- `Microchip/Include/TimeDelay.h`

Extraction note

- Extracted from the official Windows installer under Wine in unattended mode to preserve the
  exact historical file set.
