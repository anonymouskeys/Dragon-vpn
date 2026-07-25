# DragonVPN Xray migration

The migration is intentionally performed side by side.

## Stage 1 (this change)

- downloads the same `libv2ray.aar` release family used by Monster-VPN;
- includes it through DragonVPN's existing `app/libs` dependency;
- adds one isolated `XrayCoreBridge` initialization point;
- keeps the current sing-box/Dragon-core runtime untouched.

This stage must build before service and configuration code is changed. A failed build here means
an AAR/API compatibility problem, not a VPN runtime problem.

## Next stages

1. Add an Xray configuration translator for supported DragonVPN profile types.
2. Add an Xray-backed service instance alongside `bg/proto/BoxInstance`.
3. Connect Xray to the existing TUN lifecycle.
4. Map DragonDPI settings to Xray freedom outbound fragmentation and Monster-VPN's DPI path.
5. Switch the backend behind a feature flag, then remove Dragon-core only after parity tests pass.
