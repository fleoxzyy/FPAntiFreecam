# 🛡️ FPAntiFreeCam [Folia Compatible!]
### Anti-FreeCam & Anti-Xray Protection

![Banner](https://cdn.modrinth.com/data/cached_images/757f87ae5a0bb66d13650ba3438ec10e7360895a.png)

Protect underground bases, caves, and hidden structures from players using **FreeCam** or **X-Ray** mods. FPAntiFreeCam intercepts outgoing packets and replaces underground blocks with air — so FreeCam users see nothing but void below the surface.

> ⚠️ This plugin does **not** block ore X-ray. It is designed to protect **bases, farms, and hidden structures**.

---

## 🚀 No Dependencies Required!

Drop it in, configure your worlds, done. Zero libraries to install.

---

## ✨ Features

- 👁️ **Anti-FreeCam / Anti-Xray** — hides all underground blocks via packet manipulation.
- 📦 **Entity hiding** — conceals mobs, item frames, and farms inside protected zones.
- 🔍 **Two-layer detection** — hard Y-floor deactivation + raycast zone for cave/ceiling edge cases.
- 🔔 **Update checker** — notifies admins in-game and in console when a new version is available.
- ⚡ **Async & optimized** — tick-batched chunk refreshes, minimal performance impact.
- 🌍 **Universal platform support** — Paper, Purpur, Spigot, Bukkit & Folia (multi-threaded region-aware scheduling).
- 📱 **Bedrock support** — Geyser / Floodgate compatible.
- 🛡️ **Robust Edge Case Protection:**
  - 🔄 **Teleport-Safe:** Prevents 1-tick NBT packet leaks on teleports.
  - 🚲 **Vehicle-Aware:** Instantly updates protection states upon entering or exiting mounts/vehicles.
  - 🕶️ **Spectator Friendly:** Automatically bypasses protection for spectator-mode players to prevent vision obscuration.

---

## 🖥️ Commands

| Command | Description | Permission |
|---|---|---|
| `/fpac reload` | Reload the config | `fpantifreecam.admin` |
| `/fpac stats` | Show live plugin stats | `fpantifreecam.admin` |
| `/fpac debug` | Toggle debug logging | `fpantifreecam.admin` |
| `/fpac world <add\|remove> <world>` | Add/remove a protected world | `fpantifreecam.admin` |
| `/fpac bypass <player>` | Toggle bypass for a player | `fpantifreecam.admin` |
| `/fpac help` | Show command help | `fpantifreecam.admin` |
| `/fpreload` | Quick config reload shortcut | `fpantifreecam.admin` |
| `/fpdebug` | Quick debug toggle shortcut | `fpantifreecam.admin` |

**Aliases:** `/fpafc`, `/antifreecam`, `/fpacreload`, `/fpacdebug`

---

## 🔑 Permissions

| Permission | Description | Default |
|---|---|---|
| `fpantifreecam.admin` | Full access to all commands | OP |
| `fpantifreecam.reload` | Reload configuration | OP |
| `fpantifreecam.debug` | Toggle debug mode | OP |
| `fpantifreecam.world` | Manage protected worlds | OP |
| `fpantifreecam.bypass` | Exempt from protection (staff/builders) | ❌ |

---

## ⚙️ Config

<details>
<summary><b>Click to expand config.yml</b></summary>

```yaml
# ============================================================
#  FPAntiFreeCam  –  Configuration
#  Anti-FreeCam protection for Spigot / Paper / Folia
#  1.19 · 1.20 · 1.21 · 26.1+
# ============================================================

config-version: 3

settings:
  update-checker: true
  language: "en"
  debug-mode: false
  refresh-cooldown-seconds: 3

worlds:
  list:
    - "world"

protection:
  surface-y: 16.0
  void-y: 15
  transition-zone: 5
  deep-deactivation-y: 20.0
  raycast:
    enabled: true
    min-upward-angle: 0.15
    deactivation-debounce-ms: 500

replacement:
  block-type: "air"

entities:
  hide-entities: true

performance:
  folia-optimizations: true
  max-chunks-per-tick: 40
  instant-protection:
    enabled: true
    instant-load-radius: 14
    pre-load-distance: 10
    force-immediate-refresh: true
  limited-area:
    enabled: false
    chunk-radius: 4
```

</details>

---

## ✅ Compatibility

| Platform | Supported |
|---|---|
| Paper 1.19 – 26.1.2+ (including 1.21.11) | ✅ |
| Purpur | ✅ |
| Spigot | ✅ |
| Bukkit | ✅ |
| Folia | ✅ |
| Geyser / Floodgate | ✅ |

---

## 💬 Support

**Discord:** [Join our Discord Server](https://discord.gg/ezA5Q6wGj)
**GitHub:** [FPAntiFreeCam GitHub](https://github.com/Fleoxzyy/FPAntiFreeCam)

*Secure what players shouldn't see.*
