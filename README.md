# Satellite-Pass-Tracker
Android app to track satellite passes over ground stations.

Satellites are added via NORAD ID, associated orbit data is fetched via TLEs provided by https://celestrak.org/.
A selection of predefined ground stations exists, and the user can add custom ones via coordinates.

The calculated orbit and pass data is then used to populate a countdown list of upcoming and ongoing passes as well as for 3D orbit and pass visualization.

Uses https://github.com/davidmoten/predict4java for the pass prediction calculations.
Also uses NASA GIBS cloud data for an optional, (very) experimental cloud overlay: 
We acknowledge the use of imagery provided by services from NASA's Global Imagery Browse Services (GIBS), part of NASA's Earth Science Data and Information System (ESDIS).

This project is completely free, ad-free, and open-source. If you wish to show financial appreciation for it, please consider donating to Celestrak: https://giving.gofundme.com/campaign/750670/donate
