# DirectWrite Chinese fallback probe

The standalone PE32 executable queries installed family names, then calls the system
`IDWriteFontFallback::MapCharacters` on U+6218 U+7F51 U+9B54 U+517D with each
of zh-Hans, zh-CN, zh-Hant, zh-TW, zh-HK, en-US and empty locale and each of null, Arial, Noto Sans and Microsoft YaHei base family. It writes `font-fallback-probe.txt` beside its executable.

`S_OK` and a nonzero mapped length do not guarantee success: require a non-null
returned family and nonzero glyph indexes for all tested characters. The probe
measures DirectWrite behavior in the selected runtime/prefix; it does not prove
which locale or fallback calls CEF actually uses or prove Battle.net pixels.

Built using `i686-w64-mingw32-gcc -O2 -Wall -Wextra -Werror -static -municode`
with `-ldwrite -lole32`. Build is a standalone, unregistered diagnostic, not a
managed runtime modification. Execute in an isolated test-owned environment.
