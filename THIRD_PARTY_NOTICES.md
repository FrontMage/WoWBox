# Third-party software and source provenance

The application code retains the Winlator MIT notice in LICENSE. Runtime libraries, fonts, native tools and integrations retain their own licenses; the application MIT license does not replace those licenses.

| Component | Source / identity | License / distribution notes |
| --- | --- | --- |
| Winlator application and helpers | [BrunoSX Winlator](https://github.com/brunodev85/winlator); common winhandler/wfm match official v8.0.0 binaries | MIT; original notice retained in APK assets/licenses |
| WFM | [brunodev85/wfm](https://github.com/brunodev85/wfm) | MIT |
| Wine | [FrontMage/proton-wine](https://github.com/FrontMage/proton-wine) `a545dee5aa1991ba77985a2cfe8d6a490152d166`, pinned by Proton `212f9e729f7f79a582aa1def2d313f21b4c2181c`; `tools/wine-openfonts-mainline.patch` changes four font substitutions and adds two non-overwriting fallback mappings | LGPL; corresponding pinned source, build instructions and the font-data patch accompany the runtime release |
| FEX | [FrontMage/FEX](https://github.com/FrontMage/FEX) `7519b9605209e9e19eaf7be669dae7c1848fe5f1`, pinned by Proton `212f9e729f7f79a582aa1def2d313f21b4c2181c` | MIT; original copyright and dependency licenses retained in source archive |
| jemalloc | [jemalloc/jemalloc](https://github.com/jemalloc/jemalloc), 97d986993dc735a2022856e7e9fdfa1180e8527a | BSD; source vendored in runtime-sources |
| Vulkan wrapper | [leegao/bionic-vulkan-wrapper](https://github.com/leegao/bionic-vulkan-wrapper), c8baafbd4f4835ca103acb55ec3ac13642b6b7e3 plus preserved Bionic integration patch | Upstream license retained; corresponding source included in runtime-sources |
| Turnip / Mesa | [FrontMage/mesa](https://github.com/FrontMage/mesa), ebad1310762e8c33014aa4cffface3f13e514ca2; optional Mesa 25.3.6 builds | MIT and component licenses; source revision and IR3 patch preserved |
| DXVK | [doitsujin/dxvk](https://github.com/doitsujin/dxvk), v2.7.1 / v2.3.1; ARM64EC candidate 83e503b4 | zlib license |
| vkd3d-proton | [HansKristian-Work/vkd3d-proton](https://github.com/HansKristian-Work/vkd3d-proton), f1d78e4d79e764afa8146a4333b569c9913e3258 | LGPL; upstream component licenses retained |
| D8VK | [AlpyneDreams/d8vk](https://github.com/AlpyneDreams/d8vk), 1.0 | zlib |
| XInput bridge | [brunodev85/wine-9.2-custom](https://github.com/brunodev85/wine-9.2-custom) `a4ef2bf8963fe4bfab390eebf73336364ce209bc`, process-filter patch and build recipe in tools/ and scripts/ | Wine component license; public archive `149a9ca4…` removes DWARF debug sections from v0.1 archive `437d6859…`; runtime sections and ARM64X loader metadata are unchanged. No proprietary XInput DLL is distributed |
| LSFG layer | [GameNative/lsfg-vk-android](https://github.com/GameNative/lsfg-vk-android), feb7899a6d0f419e27bf47102b6df8ff17e50122 plus three patches in tools/lsfg-vk-android | MIT license included in layer/source; Lossless.dll must be provided by its owner and is not distributed |
| OpenXR SDK | [KhronosGroup/OpenXR-SDK](https://github.com/KhronosGroup/OpenXR-SDK), 288d3a7e | Apache-2.0; source and license vendored under app/src/main/cpp/OpenXR-SDK |
| AdrenoTools / linker namespace helpers | app/src/main/cpp/adrenotools | Original licenses and provenance retained |
| PRoot | app/src/main/cpp/proot | GPLv2; source and COPYING included |
| patchelf | app/src/main/cpp/patchelf | GPLv3+; source and license included |
| JSch | com.github.mwiede:jsch:2.28.0 sources, compiled for Android by Gradle | BSD-style license in assets/licenses |
| Source Han Sans CN | [Adobe Source Han Sans](https://github.com/adobe-fonts/source-han-sans), original 2.004R font | SIL OFL 1.1; internal family name unchanged, license retained |
| DejaVu | [DejaVu Fonts](https://dejavu-fonts.github.io/) | Bitstream Vera / DejaVu license retained |
| Wine fonts | Wine share/wine/fonts | Wine's own LGPL font replacements, not Microsoft font files |

## Base runtime libraries

The preserved Bionic image filesystem also contains open-source dependencies including GLib 2.84.1, GStreamer 1.26.1, FFmpeg 7.1.1, ALSA 1.2.14, GnuTLS 3.8.9, readline 8.2, GMP 6.3.0, x265 4.1, Rubber Band 4.0.0 and FFTW 3.3.10. They retain their GPL/LGPL or other upstream licenses. This FFmpeg build enables GPL/version3 and disables nonfree; it must not be described as an LGPL-only build.

The inherited GnuTLS binaries contain public, fixed upstream test private keys
used for benchmarking and cryptographic self-tests. The embedded fixtures
match GnuTLS source revision `0e7b36b58a259d5c67f298adb66291d79b019f22`
(`src/benchmark-tls.c` and `lib/crypto-selftests-pk.c`); they are not user or
device credentials and must not be used as real authentication keys. These
same binaries were already present in the v0.1.0 public runtime.

Upstream source entry points: [GLib](https://gitlab.gnome.org/GNOME/glib), [GStreamer](https://gstreamer.freedesktop.org/src/), [FFmpeg 7.1.1](https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz), [ALSA](https://www.alsa-project.org/), [GnuTLS](https://www.gnutls.org/download.html), [GNU software](https://ftp.gnu.org/gnu/), [x265](https://bitbucket.org/multicoreware/x265_git), [Rubber Band](https://github.com/breakfastquay/rubberband), [FFTW](https://www.fftw.org/download.html), and the public [Termux build definitions](https://github.com/termux/termux-packages).

The base image filesystem was imported from the Winlator Bionic runtime. Exact original Android build revisions/options for every inherited library are not established by this snapshot; known versions and remaining provenance gaps are recorded rather than invented. The app can be built with the exact checked runtime inputs provided in the Release. The runtime-sources archive supplies the Wine/FEX/wrapper integrations controlled by this project, not a claim that every inherited dependency can be rebuilt offline from that one archive.

## Public distribution changes

- Microsoft/Monotype/Founder font files and closed dgVoodoo/nglide configuration tools were removed from common/base/prefix support archives.
- Native Microsoft optional-component archives are absent; the public Box selects Wine builtin implementations.
- Free fonts are registered under their original family names. GDI and DirectWrite compatibility aliases and four Wine INF defaults select those real font families.
- The Wine archive comes from the pinned mainline build; the publication copy changes four font substitution rows and adds two non-overwriting fallback mappings in `share/wine/wine.inf`. Wine executable, DLL and shared-library members are unchanged relative to that build.
- The repository and Release contain no Blizzard installer/game data, user account state, licensed Lossless.dll, private signing key or device session captures.
