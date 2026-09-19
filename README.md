# FirstDTC

Plugin Paper 1.21 — Événement **Destroy the Core** en équipe pour îles SuperiorSkyblock2.

## Concept

Deux équipes d'îles s'affrontent : chaque équipe possède un **Core** (bloc désigné) sur son île.  
Les joueurs cassent les blocs de l'île adverse pour atteindre et détruire le Core ennemi.  
La première équipe à détruire le Core adverse remporte l'événement et reçoit ses récompenses.

## Fonctionnalités

- Détection automatique du Core via tag Nexo
- Système de récompenses configurable (commandes exécutées au gagnant)
- Statistiques par île (blocs cassés, kills, etc.)
- Intégration PlaceholderAPI pour scoreboards et leaderboards
- Compatible Folia (scheduler asynchrone via `FoliaScheduler`)
- Soft-depend sur SuperiorSkyblock2, PlaceholderAPI, Nexo

## Dépendances

| Plugin | Type |
|--------|------|
| Paper 1.21.4+ | Requis |
| SuperiorSkyblock2 | Requis (vérifié à onEnable) |
| PlaceholderAPI | Optionnel |
| Nexo | Optionnel |

## Installation

1. Placer `FirstDTC-1.0.0.jar` dans le dossier `plugins/`
2. Redémarrer le serveur
3. Configurer `plugins/FirstDTC/config.yml`
4. Configurer `plugins/FirstDTC/messages.yml`

## Configuration

### config.yml

```yaml
# Clé Nexo du bloc qui sert de Core
core-nexo-key: "dtc_core"

# Durée max de l'événement (en minutes, 0 = illimitée)
max-duration: 30

# Récompenses exécutées pour chaque joueur de l'équipe gagnante
rewards:
  - "give %player% diamond 5"
  - "eco give %player% 10000"
```

### messages.yml

Tous les messages sont personnalisables avec support des codes couleur MiniMessage (`<red>`, `<bold>`, etc.).

## Commandes

| Commande | Description | Permission |
|----------|-------------|------------|
| `/firstdtc start <team1> <team2>` | Lance un événement entre deux îles | `firstdtc.admin` |
| `/firstdtc stop` | Arrête l'événement en cours | `firstdtc.admin` |
| `/firstdtc status` | Affiche l'état actuel | `firstdtc.admin` |
| `/firstdtc help` | Aide | `firstdtc.admin` |

Aliases : `/dtc`, `/destroythecore`

## Permissions

| Permission | Description | Défaut |
|------------|-------------|--------|
| `firstdtc.admin` | Contrôle total de l'événement | OP |

## Placeholders (PlaceholderAPI)

| Placeholder | Valeur |
|-------------|--------|
| `%firstdtc_active%` | `true` / `false` |
| `%firstdtc_team1_blocks%` | Blocs cassés équipe 1 |
| `%firstdtc_team2_blocks%` | Blocs cassés équipe 2 |

## Build

```bash
mvn clean package
# → target/FirstDTC-1.0.0.jar
```

Requiert Java 21 et Maven 3.8+.

## Structure du projet

```
src/main/java/fr/first/firstdtc/
├── FirstDtcPlugin.java          # Point d'entrée
├── command/FirstDtcCommand.java # Commandes admin
├── config/PluginConfig.java     # Chargement config
├── game/
│   ├── CoreGame.java            # Logique de partie
│   ├── GameManager.java         # Gestionnaire de parties
│   ├── IslandStats.java         # Stats par île
│   └── RewardService.java       # Distribution récompenses
├── hook/
│   ├── NexoHook.java            # Intégration Nexo
│   ├── OneBlockHook.java        # Intégration AOneBlock
│   └── PapiHook.java            # Intégration PlaceholderAPI
├── listener/
│   ├── BlockBreakListener.java  # Écoute casse de blocs
│   └── PlayerJoinListener.java  # Écoute connexions
├── placeholder/
│   └── FirstDtcExpansion.java   # Extension PAPI
└── util/
    ├── FoliaScheduler.java      # Scheduler Folia-safe
    └── Msg.java                 # Utilitaire messages
```

## Auteur

Développé pour **FirstSkyV2** — [FirstSky-Omega](https://github.com/FirstSky-Omega)
