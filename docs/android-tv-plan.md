# Kachur VPN — Android TV

Target devices: Xiaomi Mi Box S and Xiaomi TV Stick. Preserve the existing phone application behavior and keep all TV work on the `android-tv` branch until tested.

## Requirements
- Remote/D-pad navigable TV interface with visible focus and large connect/disconnect controls.
- TV launcher entry and non-touch device compatibility.
- Reuse the existing AmneziaWG tunnel engine and encrypted profile storage.
- User-controlled auto-connect after TV reboot, once VPN consent has been granted; reconnect on network recovery.
- Manual disconnect must suppress unintended reconnect until the user re-enables it.
- Check system Always-on VPN compatibility and boot/background launch restrictions per device; do not assume RECEIVE_BOOT_COMPLETED alone is sufficient.
- No modifications to smartphone behavior in `main`.

## Implementation checkpoints
1. Inspect MainActivity, AwgVpnService, profile storage, and current reconnection logic.
2. Introduce TV-specific UI and launcher handling, with no touch dependency.
3. Implement a safe boot strategy respecting VpnService consent and Android background execution rules.
4. Build separate TV variant/APK if necessary to isolate smartphone behavior.
5. Test cold boot, reboot, sleep/wake, network loss, manual disconnect, handshake, traffic, and remote navigation on both Xiaomi devices.

## Current caveat
The manifest currently sets `android.net.VpnService.SUPPORTS_ALWAYS_ON` to false. Any change requires testing the service lifecycle and manual-disconnect behavior; do not flip it blindly.
