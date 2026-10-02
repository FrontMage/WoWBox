# AdrenoTools provenance

This directory is vendored because Winlator carries source changes that are
not represented by a single upstream Git commit.

- Upstream: `https://github.com/bylaws/libadrenotools.git`
- Baseline commit: `8fae8ce254dfc1344527e05301e43f37dea2df80`
- `lib/linkernsbypass` upstream:
  `https://github.com/Pipetto-crypto/liblinkernsbypass.git`
- `lib/linkernsbypass` baseline commit:
  `aa3975893d83ef1bc84c321ec60c65fbf1287887`

The vendored tree additionally contains Winlator's Meson build files and local
`src/driver.cpp` diagnostics/temporary-library handling. Preserve those deltas
when updating the upstream baseline.
