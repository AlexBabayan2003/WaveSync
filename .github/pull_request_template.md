## Summary

<!-- What changed architecturally, and why. One paragraph, not a file list. -->

## Key decisions

<!--
Trade-offs a reviewer would otherwise have to reverse-engineer. For example:
- Constructed ExoPlayer directly in onCreate to avoid a @Singleton player
  outliving the service and being released twice.
-->

## Visual proof

<!-- Screenshot / screen recording / logcat excerpt showing this working on device. -->

## Verification

- [ ] `./gradlew detekt` passes
- [ ] `./gradlew testDebugUnitTest` passes
- [ ] `./gradlew assembleDebug` passes
- [ ] Verified on a device or emulator (attach proof above)

## Notes for the reviewer

<!-- Anything deliberately out of scope, or follow-up work this unblocks. -->
