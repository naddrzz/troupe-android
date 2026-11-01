# The MVP works against the Troupe Server as it ships today

The app must work with the existing termul-server without any server changes. Consequently there is no pairing handshake: a Paired Server is just a URL plus its bearer token, entered by scanning the desktop's `…#token=…` QR code, pasting that link, or typing it in. There is no push service either, so Attention notifications only arrive while the app holds a WebSocket open, optionally kept alive by a per-server foreground service.

## Consequences

- A token grants full access and cannot be scoped or revoked per device; it is stored encrypted (Keystore + Tink) and excluded from backups.
- Plain `http://` is accepted only for private/LAN/Tailscale addresses; public addresses require `https://`.
- Real pairing and push (FCM or a relay) are deferred to future server work.
