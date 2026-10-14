# Vendored from Osmosis

Files under this directory are copied from [KonradIT/osmosis](https://github.com/KonradIT/osmosis)
(v1.4.5, MIT License, © Konrad Iturbe), with the package renamed to
`com.gpic.android.camera.osmosis`. Osmosis in turn vendors parts of
[dimadesu/dji-remote](https://github.com/dimadesu/dji-remote) (MIT), credited in those files.

Local changes are marked `GPic:`:
- `net/CameraNet.kt` (new): binds only the camera's UDP/TCP sockets to the camera WiFi network,
  instead of osmosis's process-wide `bindProcessToNetwork`, so Google uploads can run concurrently.
- `net/DumlTransport.kt`, `net/DumlSession.kt`: use `CameraNet` when creating sockets.

MIT License text: see https://github.com/KonradIT/osmosis/blob/main/LICENSE.txt
