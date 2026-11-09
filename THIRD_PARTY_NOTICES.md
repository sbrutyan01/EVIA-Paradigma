# Third-party software

EVIA Paradigma itself is MIT licensed (see `LICENSE`). The jar and the installers also contain:

| Component | Used for | License |
|---|---|---|
| [FlatLaf](https://github.com/JFormDesigner/FlatLaf) 3.8, © FormDev Software GmbH | the IntelliJ look: light theme and Darcula | Apache License 2.0 |
| [Inter](https://github.com/rsms/inter), © The Inter Project Authors | interface font (as "EVIA UI", see below) | SIL Open Font License 1.1 |
| [Noto Sans Armenian](https://github.com/notofonts/armenian), © The Noto Project Authors | the Armenian letters of the interface font | SIL Open Font License 1.1 |
| [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), © The JetBrains Mono Project Authors | code font | SIL Open Font License 1.1 |

"EVIA UI" is Inter with the Armenian letters of Noto Sans Armenian added, because Java does not take missing
letters from another font. It is built by `scripts/fonts/make-ui-font.py` and renamed, as the OFL asks. The font
licenses are in `src/main/resources/evia/paradigma/fonts/`.

## Microchip device headers

The PIC32 checks in CI (`scripts/check-pic32.sh`) read Microchip's device headers, fetched at test time from the Pinguino project. They are not part of this repository, the jar or the installers.

The FPGA pin locations in `Fpga.java` are facts read from the boards' published constraint and design files (Digilent master XDC files, Olimex KiCad design, Cologne Chip examples). No code or file text from them is copied. The FPGA checks in CI (`scripts/check-fpga.sh`) use the OSS CAD Suite, downloaded at test time; it is not part of this repository, the jar or the installers.

The **installers** (from 0.8.0: Windows .exe, macOS .dmg, Linux .deb and AppImage) carry two toolchains in a folder `toolchains/`, passed on unchanged: **avr-gcc** with avr-libc and **avrdude** (GNU GPL, avr-libc under a BSD licence; avr-gcc 16.1.0 from [ZakKemble/avr-gcc-build](https://github.com/ZakKemble/avr-gcc-build) on Windows, Arduino's avr-gcc 7.3.0 build on Linux and macOS, avrdude 8.0 from Arduino), and the **OSS CAD Suite** ([YosysHQ/oss-cad-suite-build](https://github.com/YosysHQ/oss-cad-suite-build), release 2026-11-09; each tool under its own free licence, all in its `license/` folder). Each toolchain folder has a `SOURCES.txt` with where it came from and where its source is; `packaging/fetch-toolchains.sh` pins every file by SHA-256. The jar has none of them. The tools that **Settings** can fetch (arduino-cli, and the toolchains above for a jar install) are downloaded from their own projects' releases at the moment you ask for them. **Microchip's XC32 is never passed on**: its licence does not allow it. Settings, PIC32, Get XC32 downloads Microchip's own free XC32 installer from microchip.com, checks it against Microchip's published file, asks you to accept Microchip's licence, and runs Microchip's installer.
