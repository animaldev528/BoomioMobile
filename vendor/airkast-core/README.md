# airkast-core (vendored)

`io.github.aivanyuk:airkast-core:0.6.0` — the Kotlin AirPlay 2 sender from
https://github.com/aivanyuk/airkast, Apache-2.0 (see `LICENSE` and `NOTICE`).

It is vendored rather than consumed from Maven Central because the released artifact cannot open a
session on an Apple TV, and the fix is one line that is not configurable through the public API:

- **`timingProtocol` in the base SETUP** (`session/Opening.kt`). Upstream sends `"None"` when NTP
  timing is off and `"NTP"` when it is on. tvOS 26 on an Apple TV 6,2 answers `"None"` with **500**
  and stalls **~32.4 s** before answering `"NTP"` with **400**; `"PTP"` with no `timingPort` is
  accepted in **0.1 s**. Measured 2026-11-02 with a pair-verified channel, changing only that field.
  The patch sends `"PTP"` whenever NTP is off. The app sets `ntpTiming = false` for Apple TVs, and
  leaves NTP on for receivers (such as a Mac) that require it.

Everything else is upstream 0.6.0 at commit `f6a27e7`. When upstream ships an equivalent fix,
delete this module and depend on the published artifact.

Only `core` is vendored: the app has its own mDNS discovery and does not use `airkast-android`.
