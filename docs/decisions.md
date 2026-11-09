# Decisions

- Replace the published v0.3.0 assets and tag in place with the requested Wine `RtlWaitOnAddress` backport, retain the previous artifacts as rollback evidence, and preserve user data across supported upgrades. Keep version name 0.3.0 and increment the Android version code to 20 so installations can distinguish the replacement.
  Source: user-confirmed (2026-11-09: directly overwrite the release and include the requested repair).
  Source: code-evidence (app/build.gradle:319-335).

- Publish the requested 0.3.0 under the existing standard package identity,
  preserving existing application and game data. Limit device-specific V39,
  Vulkan host composition and the Battle.net CEF mitigation to the observed
  RedMagic NX809J / SM8850 lane; disclose unverified paths and open failures.
  Source: user-confirmed (2026-11-09: release 0.3.0 as a standard-package upgrade with RedMagic adaptation and preserve user data).

- Provide the two graphics cache settings as low-priority defaults while
  respecting explicitly configured values and without rewriting persisted
  user specs.
  Source: user-confirmed (2026-11-09: implement the WoW Box graphics plan).
  Source: code-evidence (app/src/main/java/com/winlator/box/BoxGraphicsCacheDefaults.java:5-16).

- Publish the v0.2 preview on the user's request with the incomplete device
  verification and inherited-runtime source provenance openly disclosed.
  Source: user-confirmed (2026-10-29: 直接公开吧).

- Use packaged Source Han Sans CN as a non-overwriting Wine DirectWrite
  replacement for missing `MS Gothic` and `Noto Sans CJK TC` families.
  The alias preserves any user mapping; CN-style glyphs for TC fallback may
  differ regionally.
  Source: code-evidence (scripts/package-mainline-wine.py:20-70).

- Disable legacy single-layer debug mutations for the standard package so
  runtime updates cannot bypass its complete release upgrade transaction;
  preserve those endpoints for other package identities.
  Source: code-evidence (app/src/main/java/com/winlator/box/BoxLegacyLayerPolicy.java:3-21).

- Publish the standard WoW Box package as a preview and use Battle.net's
  original Wine route without the experimental Battle.net relay. Preserve
  existing user data during installation and supported runtime upgrades.
  Source: user-confirmed.

- Keep optional Windows components and font resources within the existing
  public distribution's redistributable boundaries; include their sources,
  provenance and licenses alongside the release.
  Source: user-confirmed.

- Retain public GnuTLS upstream test-key fixtures already present in the
  inherited runtime; user credentials and device-private keys are excluded.
  Source: user-confirmed.
