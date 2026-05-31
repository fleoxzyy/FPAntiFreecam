# FPAntiFreeCam

**FPAntiFreeCam** is a high-performance, region-aware Anti-FreeCam/Anti-Xray plugin for Minecraft servers. It prevents players from using FreeCam, and X-ray mods to scout underground bases, mob farms, or hidden structures by replacing underground blocks with air (or a configured replacement) in outbound packets.

Designed for modern Minecraft versions and high-population servers, it features native support for **Paper**, **Folia**, and **Bedrock (via Geyser/Floodgate)**.

## 🚀 Features

*   **Void Protection:** When players are above a certain Y-level (surface), everything below them is hidden, acting as a powerful anti-xray measure for surface-dwellers.
*   **Packet-Level Security:** Uses [PacketEvents](https://github.com/retrooper/packetevents) for low-level block and chunk manipulation.
*   **Folia Support:** Multi-threaded region-aware scheduling to prevent thread-safety issues.
*   **Bedrock Compatibility:** Automatically detects Geyser/Floodgate players and optimizes refresh radii for mobile/console clients.
*   **Optimized Performance:**
    *   Asynchronous chunk refreshing.
    *   Configurable tick-batching for Paper/Spigot.
    *   Low overhead packet interception.
*   **Entity Hiding:** Optionally hides mobs and other entities in the "void zone" to prevent entity-based scouting.
*   **Auto-Updating Config:** Safely merges new configuration options without resetting user settings.

## 🛠️ Installation

1.  Download the latest `.jar` from [Modrinth](https://modrinth.com/plugin/fp-antifreecam).
2.  Place the jar in your server's `plugins/` folder.
3.  Restart your server.
4.  Configure your protected worlds in `plugins/FPAntiFreeCam/config.yml`.

## 💬 Support

Need help? Join our [Discord Server](https://discord.gg/drcf6arhmy) for support and updates!

## ⚙️ Configuration

<details>
<summary><b>Click to expand full config.yml</b></summary>

```yaml
# ============================================================
#  FPAntiFreeCam  –  Configuration
#  Anti-FreeCam protection for Spigot / Paper / Folia
#  1.19 · 1.20 · 1.21 · 26.1+
# ============================================================

config-version: 1

# ── General ──────────────────────────────────────────────────
settings:
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
  # Y level at-or-above which the hiding effect kicks in.
  # This is essentially "surface level" (Y ≥ 16 → void below).
  surface-y: 16.0

  # Every block/entity at-or-below this Y will be hidden from clients
  # when the protection is active (i.e. player is above surface-y).
  void-y: 15

  # Smooth transition zone (blocks above void-y where the fade starts).
  transition-zone: 5

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

  # Limit the hidden-block effect to a smaller radius around each player.
  # Reduces packet processing on large servers; set enabled: true to activate.
  limited-area:
    enabled: false
    chunk-radius: 4
```
</details>

## 📜 Permissions

*   `fpantifreecam.admin`: Full access to all commands.
*   `fpantifreecam.reload`: Permission to use `/fpac reload`.
*   `fpantifreecam.bypass`: Players with this permission will see the world normally.

## 🏗️ Building

To build the project yourself, ensure you have Java 21+ installed and use the Gradle wrapper:

```bash
./gradlew build
```

The shadowed jar will be located in `build/libs/`.

## 📄 License

This project is licensed under the **MIT License**. See the [LICENSE](LICENSE) file for details. Forks and modifications are encouraged!
