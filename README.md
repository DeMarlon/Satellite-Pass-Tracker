# Satellite-Pass-Tracker
Android app to track satellite passes over ground stations, available here: https://play.google.com/store/apps/details?id=com.mdeutsch.spt&hl=en

Primarily aimed at people working in mission operations, but if you are just curious to know when the ISS flies over your house, this app is also for you!

Designed for Low Earth Orbit (LEO) and geostationary (GEO) satellites.

Satellites can be added via NORAD ID. Associated orbit data is calculated via OMMs provided by https://celestrak.org/. A selection of predefined ground stations exists, and the user can add custom ones via coordinates.

The calculated orbit and pass data is then used to populate lists of upcoming and ongoing passes as well as for 2D/3D orbit and pass visualization.

Uses https://github.com/davidmoten/predict4java for the pass prediction calculations.

This project is completely free, ad-free, and open-source. If you wish to show financial appreciation for it, please consider donating to the data provider Celestrak: https://giving.gofundme.com/campaign/750670/donate

---

## Building

Requires JDK 21 and a recent Android Studio. No API keys, no `local.properties` entries
beyond the SDK path Studio writes itself, and no signing config is needed for a debug build:

```bash
./gradlew :app:assembleDebug
```

Run the unit tests and lint with:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```

- `minSdk` 26, `targetSdk` / `compileSdk` 37
- 100% Kotlin, Jetpack Compose, single Activity
- Room for the orbital-element cache (schemas are committed under `app/schemas/`)
- **R8 is deliberately disabled** for release builds. It is not an oversight — two
  production releases shipped unstartable because of it. The full post-mortem is in
  [RELEASING.md](RELEASING.md) and in the comments in `app/build.gradle.kts`.

## Project layout

Everything lives in `app/src/main/java/com/example/eps_sgtracker/`:

| Package | Contents |
|---|---|
| `data/` | Room database, plus a repository per data layer (TLEs, ground stations, reminders, settings, and the GeoJSON map layers) |
| `model/` | Plain data types and geodesy maths — `GeoMath`, `GlobeModels`, `SatelliteModels` |
| `network/` | CelesTrak and NASA GIBS clients, and the OMM → TLE converter |
| `notifications/` | Pass-reminder scheduling, alarm receiver, boot rescheduling |
| `ui/` | Compose screens (SETUP, PLAN, TRACK, VIEW), the 3D globe renderer, and `TrackerViewModel` |

## Licence

Copyright (C) 2026 Marlon Deutsch

This program is free software: you can redistribute it and/or modify it under the terms of
the GNU General Public License as published by the Free Software Foundation, version 3.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
See the [GNU General Public License](LICENSE) for more details.

The choice of a copyleft licence follows from the dependencies: `predict4java`, which does
the pass prediction, is GPL-2.0-or-later, and its "or later" clause is what allows this
combined work to be distributed under GPL-3.0.

## Third-party code and data

SPT builds on Natural Earth's public-domain vector data, two NASA Earth textures, and
orbital data from CelesTrak. Full attributions and licences are in [NOTICE.md](NOTICE.md).
