# Runtime Coordination

This module participates in protocol `pico_power_coord_v1` through `Settings.Global`.

Power Mode writes its selected level to `pico_power_coord_requested_power_mode` and advances `pico_power_coord_generation`. When `pico_power_coord_owner=vsleep` and `pico_power_coord_sleep_active=1`, that request is deferred: Power Mode does not touch eye-buffer, FFR, or other PICO display state. V-Sleep applies the latest request after it releases its transaction.

Outside an active V-Sleep transaction, Power Mode uses PICO's `DeviceSwitchUtilsKt.e` and always verifies the eye-buffer properties. It only writes and verifies `persist.pvr.config.ffr` for Performance mode, leaving FFR unchanged for other modes. This protocol targets PICO 4 A8110 firmware `5.13.7`.
