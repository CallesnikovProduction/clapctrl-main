# Platform support

| Action | Linux / Hyprland | Windows |
|---|---|---|
| Terminal | Detects available terminal executable | Windows Terminal or controlled PowerShell fallback |
| Screenshot | grim (Wayland), scrot/import (X11), capability-dependent | PowerShell/.NET capture to local PNG |
| Lock | loginctl or available lock helper | user32 LockWorkStation |
| Launcher | Detects available fuzzel/wofi/rofi | Windows Start key via SendInput |
| Media | playerctl | SendInput media key |
| Mute | wpctl or pactl | SendInput volume mute key |
| Workspace | hyprctl with installed-version dispatch handling | Explicitly unsupported numbered workspace |
| Custom | Approved argv, no implicit shell | Approved argv, no implicit shell |

The UI shows capability details. Tool availability is checked, actual failures are surfaced; a supported flag is not proof of a successful session action. GUI actions report launch rather than termination of a terminal. Screenshot succeeds only when output exists. Screenshot files are user-requested desktop captures, separate from microphone privacy.

No X11 screenshot fallback is used in Wayland. Portal consent workflows and automatic portal screenshot fallback are not implemented. Install a supported tool or configure an explicit custom command. No root/admin requirement. Commands inherit the normal user's environment. Custom command approval is explicit and resets in the editor on changes; imported calibration profiles contain no commands.

Java Sound support depends on the JDK's installed mixers/ALSA routing on Linux. PipeWire compatibility may require its ALSA compatibility package; selecting an actual capture mixer matters. Arch/Omarchy/Hyprland capture, tray behavior, actions and package install/uninstall require target-system tests. This repository was developed on Windows, and Linux runtime results must not be inferred from that.

If AWT tray support is absent (common in some Wayland configurations), closing offers Minimize or Quit. The window remains as the visible monitoring indicator. Autostart is opt-in via the provided desktop entry; no systemd service is needed for the resident architecture.
