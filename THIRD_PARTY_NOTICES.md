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

The tools that **Settings** can fetch (arduino-cli, AVR GCC, the OSS CAD Suite) are downloaded from their own projects' releases at the moment you ask for them, into EVIA Paradigma's folder. They are not part of this repository, the jar or the installers, and each keeps its own licence. Microchip's XC32 is not downloaded at all: its licence does not allow passing the compiler on, so Settings only opens Microchip's page.
