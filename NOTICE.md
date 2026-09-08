# Third-party notices

SPT (Satellite Pass Tracker) is licensed under the GNU General Public License v3.0 —
see [LICENSE](LICENSE). This file records the third-party code and data it builds on.

## Why this project is copyleft

The pass-prediction engine, `predict4java`, is licensed **GPL-2.0-or-later**. Its "or later"
clause is what permits this combined work to be distributed under GPL-3.0. The choice of a
copyleft licence for SPT is therefore not merely a preference — it follows from the licence
of a component the app cannot function without.

## Libraries

| Component | Version | Licence |
|---|---|---|
| [predict4java](https://github.com/davidmoten/predict4java) | 1.3.1 | GPL-2.0-or-later |
| [OkHttp](https://square.github.io/okhttp/) | 5.4.0 | Apache-2.0 |
| Apache Commons Logging *(transitive, via predict4java)* | — | Apache-2.0 |
| AndroidX: Compose, Room, DataStore, Navigation, Lifecycle, Activity, Core, SplashScreen | see [gradle/libs.versions.toml](gradle/libs.versions.toml) | Apache-2.0 |

### predict4java lineage

`predict4java` carries an unusually long provenance chain, reproduced here because the
attribution belongs to more people than the Maven coordinate suggests:

- **Dr. T. S. Kelso** — author of the SGP4/SDP4 orbital models, originally in Fortran and
  Pascal, released into the public domain via [CelesTrak](https://celestrak.org/).
- **Neoklis Kyriazis (5B4AZ)** — re-wrote Kelso's models in C and released them under the
  GNU GPL in 2002.
- **John A. Magliacane (KD2BD)** — author of PREDICT (1991–2003), whose core is based on
  5B4AZ's translation.
- **David A. B. Johnson (G4DPZ)** — Java port of PREDICT's core, 2004–2010; the library
  itself, GPL-2.0-or-later.
- **Dave Moten** — maintains the `com.github.davidmoten` fork this app depends on.

## Bundled map data

Vector layers in `app/src/main/assets/` come from
[Natural Earth](https://www.naturalearthdata.com/), which places its data in the **public
domain**. No permission is needed and none of the authors endorse this app:

```
ne_110m_admin_0_boundary_lines_land.geojson    ne_110m_land.json
ne_110m_admin_0_countries.geojson              ne_110m_populated_places.geojson
ne_110m_glaciated_areas.json                   ne_110m_rivers_lake_centerlines.geojson
ne_110m_lakes.geojson
```

The two Earth textures are NASA imagery. NASA material is generally not subject to
copyright and may be reused freely; NASA requests credit and does not endorse any product
that uses it:

- `world.topo.bathy.200406.3x5400x2700.jpg` — **Blue Marble Next Generation** (June 2004),
  Reto Stöckli, NASA Earth Observatory / NASA Visible Earth. Used as the daylit Earth
  surface texture.
- `BlackMarble_2016_global_7km.jpg` — **Black Marble 2016**, NASA Earth Observatory. Used
  as the night-side city-lights texture.

## Network data sources

Neither service requires an API key, and SPT ships no credentials of any kind.

- **[CelesTrak](https://celestrak.org/)** (Dr. T. S. Kelso) — Orbit Mean-Elements Messages,
  fetched per NORAD catalog number by
  [`CelestrakApi.kt`](app/src/main/java/com/example/eps_sgtracker/network/CelestrakApi.kt).
  The app deliberately honours CelesTrak's usage policy: a non-200 response stops all
  querying for a cooling-off window rather than being retried, because repeated errors are
  what get a client's IP firewalled. Please consider
  [donating to CelesTrak](https://giving.gofundme.com/campaign/750670/donate) — it is the
  data source the whole app depends on.
- **[NASA GIBS](https://nasa-gibs.github.io/gibs-api-docs/)** — the daily
  `MODIS_Terra_CorrectedReflectance_TrueColor` mosaic, fetched by
  [`GibsCloudApi.kt`](app/src/main/java/com/example/eps_sgtracker/network/GibsCloudApi.kt)
  for the (currently completely disabled) cloud-cover layer.
