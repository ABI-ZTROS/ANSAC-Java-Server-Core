# ANSAC - Advanced Network Security Anti-Cheat

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-17%2B-orange.svg)](https://adoptium.net/)
[![Folia](https://img.shields.io/badge/folia-supported-green.svg)](https://papermc.io/software/folia)

ANSAC is a modern, high-performance anti-cheat plugin designed specifically for **Folia** servers, with backward compatibility for Paper and Spigot.

## Features

- **Folia Native**: Built from the ground up for Folia's multithreaded architecture
- **Cross-Platform**: Also works on Paper and Spigot via FoliaLib
- **Packet-Based Detection**: Uses PacketEvents for low-level packet analysis
- **Movement Checks**: Speed, Fly detection with prediction engine
- **Combat Checks**: Reach, KillAura detection
- **Packet Checks**: Timer, BadPackets validation
- **Async Processing**: Non-blocking design for optimal performance
- **Highly Configurable**: Extensive configuration options

## Requirements

- Java 17 or higher
- Folia, Paper, or Spigot 1.20+
- PacketEvents 2.7.0+ (optional but recommended)

## Installation

1. Download the latest release from [Releases](https://github.com/ABI-ZTROS/ANSAC-Java-Server-Core/releases)
2. Place the JAR file in your server's `plugins` folder
3. Install [PacketEvents](https://github.com/retrooper/packetevents) for full functionality
4. Restart your server
5. Configure in `plugins/ANSAC/config.yml`

## Building from Source

```bash
git clone https://github.com/ABI-ZTROS/ANSAC-Java-Server-Core.git
cd ANSAC-Java-Server-Core
./gradlew shadowJar
```

The compiled JAR will be in `build/libs/`.

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/ansac reload` | `ansac.command.reload` | Reload configuration |
| `/ansac status` | `ansac.command.status` | View plugin status |
| `/ansac info <player>` | `ansac.admin` | View player data |

## Permissions

| Permission | Description |
|-----------|-------------|
| `ansac.bypass` | Bypass all checks |
| `ansac.admin` | Access to admin commands |
| `ansac.alerts` | Receive alert notifications |

## Architecture

ANSAC follows a modular architecture inspired by GrimAC:

```
dev.ztros.ansac
├── ANSACPlugin.java          # Main plugin class
├── checks/
│   ├── Check.java             # Base check class
│   ├── CheckManager.java      # Check registration & scheduling
│   ├── movement/
│   │   ├── SpeedCheck.java    # Speed detection
│   │   └── FlyCheck.java      # Flight detection
│   ├── combat/
│   │   ├── ReachCheck.java    # Attack reach detection
│   │   └── KillAuraCheck.java # KillAura detection
│   └── packet/
│       ├── TimerCheck.java    # Game speed detection
│       └── BadPacketsCheck.java # Packet validation
├── player/
│   ├── PlayerData.java        # Player data storage
│   └── PlayerDataManager.java # Player data management
├── listeners/
│   ├── PlayerListener.java    # Bukkit event listener
│   └── PacketListener.java    # PacketEvents listener
├── scheduler/
│   └── SchedulerAdapter.java  # Folia/Paper scheduler abstraction
└── config/
    └── ANSACConfig.java       # Configuration manager
```

## Folia Compatibility

ANSAC uses [FoliaLib](https://github.com/TechnicallyCoded/FoliaLib) for cross-platform compatibility:

- **Folia**: Uses RegionScheduler, EntityScheduler, and GlobalRegionScheduler
- **Paper/Spigot**: Falls back to standard Bukkit scheduler

All player data operations are thread-safe using `ConcurrentHashMap`.

## Credits

- Inspired by [GrimAC](https://github.com/GrimAnticheat/Grim) - The only other Folia-compatible anti-cheat
- Uses [FoliaLib](https://github.com/TechnicallyCoded/FoliaLib) for scheduler abstraction
- Uses [PacketEvents](https://github.com/retrooper/packetevents) for packet interception

## License

This project is licensed under the MIT License.
