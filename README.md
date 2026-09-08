# Satellite-Pass-Tracker
Android app to track satellite passes over ground stations, available here: https://play.google.com/store/apps/details?id=com.mdeutsch.spt&hl=en

Primarily aimed at people working in mission operations, but if you are just curious to know when the ISS flies over your house, this app is also for you!

Designed for Low Earth Orbit (LEO) and geostationary (GEO) satellites.

Satellites can be added via NORAD ID. Associated orbit data is calculated via OMMs provided by https://celestrak.org/. A selection of predefined ground stations exists, and the user can add custom ones via coordinates.

The calculated orbit and pass data is then used to populate lists of upcoming and ongoing passes as well as for 2D/3D orbit and pass visualization.

Uses https://github.com/davidmoten/predict4java for the pass prediction calculations.

This project is completely free, ad-free, and open-source. If you wish to show financial appreciation for it, please consider donating to the data provider Celestrak: https://giving.gofundme.com/campaign/750670/donate
