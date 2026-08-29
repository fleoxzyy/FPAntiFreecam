![Banner](https://i.imgur.com/5v2PPgO.png)

<div align="center">

[![Paper](https://img.shields.io/badge/Platform-Paper%2FSpigot%2FFolia-blue?style=for-the-badge&logo=papermc&logoColor=white)](https://papermc.io/)
[![bStats Servers](https://img.shields.io/bstats/servers/33706?style=for-the-badge&logo=apachespark&logoColor=white&label=bStats%20Servers&labelColor=181717&color=00AF5C)](https://bstats.org/plugin/bukkit/FPAntiFreecam/33706)
[![bStats Players](https://img.shields.io/bstats/players/33706?style=for-the-badge&logo=minecraft&logoColor=white&label=bStats%20Players&labelColor=181717&color=5865F2)](https://bstats.org/plugin/bukkit/FPAntiFreecam/33706)
[![GitHub](https://img.shields.io/badge/Source-GitHub-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/Fleoxzyy/FPAntiFreeCam)
[![Discord](https://img.shields.io/badge/Community-Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/5XEqzkSwza)
[![Modrinth](https://img.shields.io/badge/Download-Modrinth-00AF5C?style=for-the-badge&logo=modrinth&logoColor=white)](https://modrinth.com/plugin/fpantifreecam)
[![License](https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge)](https://github.com/Fleoxzyy/FPAntiFreeCam/blob/master/LICENSE)

</div>

---

Protect underground bases, caves, and hidden structures from players using **FreeCam** / **X-Ray** / **Block ESP** mods. FPAntiFreeCam intercepts outgoing packets and replaces underground blocks with air so FreeCam users see nothing but void below the surface.

---

## 🔌 Dependencies
FPAntiFreeCam requires the following plugin to be installed on your server:
- **[PacketEvents 2.x](https://modrinth.com/plugin/packetevents)** — Required for packet interception & protocol handling.

---

## ✨ Features

- 👁️ **Anti-FreeCam / Anti-Xray** — hides all underground blocks via packet manipulation.
- 📦 **Entity hiding** — conceals mobs, item frames, and farms inside protected zones.
- 🕵️ **Freecam client detection** — probes for Freecam-mod translation keys via a hidden anvil GUI and runs a configurable command (kick/ban/webhook/etc.) on detection.
- 🔔 **Update checker** — notifies admins in-game and in console when a new version is available.
- ⚡ **Async & optimized** — tick-batched chunk refreshes, minimal performance impact.
- 🌍 **Universal platform support** — Paper, Purpur, Spigot, Bukkit & Folia (multi-threaded region-aware scheduling).
- 📱 **Bedrock support** — Geyser / Floodgate compatible.

---

## 🖥️ Commands

| Command | Description | Permission |
|---|---|---|
| `/fpac reload` | Reload the config | `fpantifreecam.admin` |
| `/fpac stats` | Show live plugin stats | `fpantifreecam.admin` |
| `/fpac debug` | Toggle debug logging | `fpantifreecam.admin` |
| `/fpac world <add\|remove> <world>` | Add/remove a protected world | `fpantifreecam.admin` |
| `/fpac bypass <player>` | Toggle bypass for a player | `fpantifreecam.admin` |
| `/fpac freecamtest <player>` | Manually probe a player for Freecam translation keys (testing) | `fpantifreecam.admin` |
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
#  1.19+ · 1.20+ · 1.21+ · 26.1+
# ============================================================

# PLEASE DO NOT CHANGE!!
config-version: 4.2

# ── General ──────────────────────────────────────────────────
settings:
  # Check for updates on startup and notify admins (fpantifreecam.admin permission) on join.
  update-checker: true

  # Language file in plugins/FPAntiFreeCam/lang/<language>.yml
  language: "en"

  # Print verbose debug info to console (leave false in production)
  debug-mode: false

  # Seconds a player must wait between FreeCam-state refresh triggers.
  # Prevents spam-refreshing when rapidly crossing the surface Y level.
  refresh-cooldown-seconds: 3

# ── Protected worlds ─────────────────────────────────────────
# Add every world name that should have underground-hiding active.
# Tip: avoid nether/end worlds – they can cause visual glitches.
worlds:
  list:
    - "world"
    # - "survival"
    # - "resource_world"

# ── FreeCam protection thresholds ────────────────────────────
protection:
  # Y level at-or-above which hiding is ALWAYS armed.
  # Players standing at or above this Y cannot see blocks below void-y.
  # Recommended: set to roughly your world's actual surface height.
  # Example: 64 for a normal overworld, 31 for a flat/custom world.
  # NOTE: This replaces the old "surface-y" key. Both are supported.
  protection-y: 64.0

  # Blocks below protection-y before protection turns OFF when descending.
  # Prevents chunk-refresh spam when jumping at the surface boundary.
  hysteresis-y: 2.0

  # Every block at-or-below this Y is replaced with the void block
  # (replacement.block-type) when protection is active.
  # Pick the highest Y where your bases/storage actually sit, then add ~5 blocks.
  # Unsure? Stand on your deepest vault floor in-game and use that Y minus 2.
  # Default 15 works for typical overworld bases dug to Y~11-20.
  void-y: 15

  # Optional per-world void-y overrides (world name -> Y level).
  # Worlds not listed here use the global void-y above.
  # per-world-void-y:
  #   world_nether: 40
  #   resource_world: 20

  # Pie-chart protection: strips tile entities and entity spawns from packets
  # while protection is active. This prevents players from using the F3 pie chart
  # to find bases (chests, mob farms, etc.) through the void.
  pie-chart-protection: true

  # Absolute Y floor. Players BELOW this value always have protection OFF.
  # Fixes the void-floor bug when digging straight down near void-y level.
  # Rule: void-y  <  deep-deactivation-y  <  protection-y
  # Example with defaults: 15 < 20 < 64
  deep-deactivation-y: 20.0

  # Raycast zone: players between deep-deactivation-y and protection-y.
  # Two sub-checks decide whether to arm protection here:
  #   1. Look-direction: camera pointing upward (freecam angle exploit)
  #   2. Sky access: open vertical path to protection-y (cave ceiling gap)
  raycast:
    enabled: true
    # Minimum upward look vector (0.0–1.0) to arm protection. ~0.15 ≈ 9° above horizontal.
    min-upward-angle: 0.15
    # Milliseconds of stability before applying a state change via raycast.
    # Prevents flicker near zone boundaries. 500ms is a good balance.
    deactivation-debounce-ms: 500
    # Also cast diagonal rays (NE/NW/SE/SW) to catch angled cave openings.
    multi-directional: true

# ── Replacement block ─────────────────────────────────────────
# Block type sent to the client in place of hidden underground blocks.
# "air" produces a clean void look (recommended).
# "stone" can be used to fake a solid floor instead.
replacement:
  block-type: "air"

# ── Entity hiding ─────────────────────────────────────────────
entities:
  # Hide non-player entities that are inside the hidden zone.
  # Prevents FreeCam from revealing mob farms / storage mobs.
  hide-entities: true

# ── Player notifications ────────────────────────────────────────
notifications:
  action-bar:
    # Show a HUD message while protection is active for a player.
    enabled: false
    message-active: "&c☠ FreeCam Protected"

# ── Anti-cheat helpers ────────────────────────────────────────
anti-cheat:
  freeze-detection:
    # Log a console warning if a protected player above protection-y
    # has not moved for N seconds (possible position-spoofing FreeCam).
    enabled: false
    seconds: 30

# ── Performance ───────────────────────────────────────────────
performance:
  # Folia: enable region-aware chunk scheduling (auto-detected)
  folia-optimizations: true

  # Paper/Spigot: max chunks refreshed per server tick
  max-chunks-per-tick: 40

  # Instant protection: force a large-radius refresh when a player
  # first enters the surface-y zone so bases are hidden immediately.
  instant-protection:
    enabled: true
    # Chunks radius to refresh when instant protection fires
    instant-load-radius: 14
    # Pre-load distance in blocks above surface-y where instant refresh is armed
    pre-load-distance: 10
    # Force re-encode of chunk data immediately (prevents momentary base glimpse)
    force-immediate-refresh: true

  # Limit how far chunk REFRESHES reach when protection toggles.
  # WARNING: does NOT limit packet masking — but stale client cache outside
  # this radius may briefly show real blocks until those chunks reload.
  # Leave disabled unless you need the perf savings and accept that tradeoff.
  limited-area:
    enabled: false
    chunk-radius: 4

# ── Freecam translation-key detection ──────────────────────────
freecam-detection:
  enabled: false
  probe-interval-seconds: 60
  probe-timeout-ticks: 20
  detection-cooldown-seconds: 300
  debug: false
  translation-keys:
    - key: "key.freecam.freecam"
      expected: "Freecam"
    - key: "gui.freecam.title"
      expected: "Freecam"
  detected-commands:
    - "kick %player% &cFreecam client detected"
```

</details>

---

## 📊 Statistics
<div align="center">
  
[![bStats](https://bstats.org/signatures/bukkit/FPAntiFreecam.svg)](https://bstats.org/plugin/bukkit/FPAntiFreecam/33706)

</div>

---

## 💬 Support

[![Discord Banner](https://img.shields.io/badge/Join_Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/5XEqzkSwza)
[![GitHub Banner](https://img.shields.io/badge/GitHub_Repository-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/Fleoxzyy/FPAntiFreeCam)

*Secure what players shouldn't see.*
