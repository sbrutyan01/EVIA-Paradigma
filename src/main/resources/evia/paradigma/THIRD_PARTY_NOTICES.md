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
