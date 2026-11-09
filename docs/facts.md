# Facts

- The 0.3.0 standard application uses `com.winlator.llm`, version code 20,
  and version name `0.3.0`. The device-specific policy recognizes the
  validated RedMagic NX809J / SM8850 combination and fails closed when
  the SoC identity is unavailable.
  Source: code-evidence (app/build.gradle:319-335).
  Source: code-evidence (app/src/main/java/com/winlator/box/RedMagicDevicePolicy.java:9-20).

- On a RedMagic NX809J, an isolated standard-package v0.3.0 versionCode 19
  fixture upgraded to replacement candidate versionCode 20 while preserving
  explicit environment overrides, a private canary and an unfinished prior
  session record. Nine layers matched, overlay was inactive, and six runtime
  and prefix ntdll hashes matched the patched Wine release.
  Source: config-evidence (docs/VALIDATION_0.3.0.md:3-4).

- In a RedMagic NX809J diagnostic, the user confirmed that a previously
  affected WoW Forever D3D12 scene no longer showed black blocks with V39,
  Vulkan host composition and the two graphics cache settings. The user
  later reported Battle.net and game crashes; the CEF mitigation did not
  eliminate them. This is a device-specific diagnostic observation, not
  a claim of general compatibility or long-term stability.
  Source: user-confirmed (2026-11-09: black blocks disappeared; Battle.net and WoW still exited unexpectedly).

- Wine's packaged INF adds non-overwriting `MS Gothic` and `Noto Sans CJK TC`
  DirectWrite replacements pointing to the bundled Source Han Sans CN font.
  Source: code-evidence (scripts/package-mainline-wine.py:20-70).

- The preview runtime uses a public XInput bridge archive with debug sections
  removed and records the inherited v0.1 archive as the upgrade baseline. The
  standard package disables legacy single-layer debug mutations so they cannot
  bypass the complete release upgrade transaction.
  Source: config-evidence (tools/runtime-mainline-provenance.json:24-34).
  Source: code-evidence (app/src/main/java/com/winlator/box/BoxLegacyLayerPolicy.java:3-21).

- The runtime inputs and source revisions are listed in
  `tools/runtime-mainline-provenance.json`.
  Source: config-evidence (tools/runtime-mainline-provenance.json:1-24).

- The standard preview does not enable the XR activity; its separate process
  needs runtime-upgrade synchronization before it can be enabled safely.
  Source: code-evidence (app/src/main/AndroidManifest.xml:54-63).

- Runtime asset bootstrap preserves existing matching files and their modes;
  it rejects modified inputs rather than overwriting them and validates the
  complete bundle before installing missing files.
  Source: code-evidence (scripts/fetch-runtime-assets.py:64-122).
