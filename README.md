# Web Kiosko

Android app that locks a device to one website: full screen, no way out for visitors.
For shops, cafés, clinics, receptions, info stands and any other self-service screen.

- Any website (https, optionally http for local networks), list of allowed domains
- Hidden admin menu: 5 quick taps in the top-left corner + PIN
- Lock modes: home app, screen pinning, full lock (device owner)
- Website permissions on request: microphone, camera, location
- Visitor session: return to the start page after inactivity, clear cookies, auto reload
- Volume / brightness control, hardware buttons lock
- Screen schedule (turns the screen off outside working hours)
- Free version shows a small watermark; the paid version removes it

Package: `kz.saubot.webkiosko` · Java · minSdk 26 · targetSdk 36

Build: GitHub Actions (`.github/workflows/build.yml`) → artifacts `webkiosko-apk` and `webkiosko-aab`.
