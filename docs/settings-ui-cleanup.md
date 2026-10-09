# 2026-10-10 Settings UI cleanup

## Changes

- Remove feature requirement text from inline switches, search results and detail pages.
  Keep the source metadata, normal descriptions, operation feedback and reboot confirmation.
  Status and feedback text use normal theme colors; danger actions and log levels keep their colors.
- Remove the module status card from Home; diagnostics remain in About.
- Prioritize framework and SystemUI in the scope catalog. Home shows framework, SystemUI,
  screenshot, MiLink, MiShare and smart cards, in that order. Secondary-only hosts stay hidden.
- Replace the card-face reset button with a long press on the selected replacement slot.
  A tap still opens the image picker. Long press is unavailable without a stored replacement
  or during import, and has an accessibility action label.
  Clearing uses the existing per-address mapping update, not the global image-clear operation;
  wallet originals and other card mappings remain unchanged.
- Remove wallet-restart hints from the card picker, including save/clear feedback.
- Add HyperVolumeANC to About acknowledgements with its upstream repository URL.

## Verification And Installation

- `:app:testDebugUnitTest`: 214 tests, no failures, errors or skips.
- `:app:assembleRelease`: successful.
- `git diff --check`: passed.
- Source checks confirm removal of the old card reset button and wallet-restart hints,
  long-press gating, per-card clearing, preserved click-to-pick and no global clear call.
- Release 0.1.3 / 4 installed with `adb install -r` at 2026-10-10 06:21:15 (Asia/Shanghai).
- Local and installed APK SHA-256 both:
  `dc4c768be84a74e6de938932b9e7272a342c0754a7a4c085266a71a007c9e6a3`.
- No device or host restart was requested, and no user card mappings or volume settings
  were changed during verification. Actual long-press/tap/accessibility behavior still needs
  device interaction testing; the JVM and source checks do not constitute visual acceptance.
