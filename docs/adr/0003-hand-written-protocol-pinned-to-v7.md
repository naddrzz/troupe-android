# Hand-written protocol models, pinned to WS protocol version 7

The WebSocket protocols (`/ws` and `/terminal/ws`) are modeled as hand-written kotlinx.serialization classes mirroring termul's `web-protocol.types.ts` and `web-terminal-protocol.types.ts`, verified by JSON fixture tests. Generating them from the server's OpenAPI spec was rejected because it only covers the HTTP routes. Since the server states its protocol is not yet stable, the app refuses to connect unless the server reports `protocolVersion` 7 and tells the user which server version is required.
