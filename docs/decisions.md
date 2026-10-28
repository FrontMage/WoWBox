# Decisions

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
