# Facts

- The standard preview build uses Android package `com.winlator.llm`, version
  code 18 and version name `0.2.0-preview`. Its versioned runtime inputs and
  source revisions are listed in `tools/runtime-mainline-provenance.json`.
  Source: code-evidence (app/build.gradle:313-323).
  Source: config-evidence (tools/runtime-mainline-provenance.json:1-24).

- The standard preview does not enable the XR activity; its separate process
  needs runtime-upgrade synchronization before it can be enabled safely.
  Source: code-evidence (app/src/main/AndroidManifest.xml:54-63).

- Runtime asset bootstrap preserves existing matching files and their modes;
  it rejects modified inputs rather than overwriting them and validates the
  complete bundle before installing missing files.
  Source: code-evidence (scripts/fetch-runtime-assets.py:64-122).
