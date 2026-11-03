<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/logo-dark.png">
    <img src="docs/logo.png" alt="EVIA" width="200">
  </picture>
</p>

<h1 align="center">EVIA Paradigma</h1>

<p align="center">
  Visual block programming in Java Swing — with classes, a Vim-powered editor and C++20 generation for Clang.
  <br><br>
  <a href="https://github.com/sbrutyan01/EVIA-Paradigma/actions/workflows/build.yml"><img src="https://github.com/sbrutyan01/EVIA-Paradigma/actions/workflows/build.yml/badge.svg" alt="build"></a>
  <a href="https://github.com/sbrutyan01/EVIA-Paradigma/releases/tag/v0.1"><img src="https://img.shields.io/badge/version-0.1-blue" alt="version 0.1"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-green" alt="MIT license"></a>
</p>

Drag blocks onto a canvas, wire them together, run the program. Combine blocks into
**custom blocks** that work like classes, edit everything with **Vim**, and turn the
program into a single **C++20** file built with **Clang 23**. Pure Swing, no external
libraries; a Maven project for NetBeans and Java 25.

## Getting started

**Requirements:** JDK 25, LLVM/Clang 23 (latest stable) for compiling generated C++, and Vim/gVim (optional, for editing in the real Vim).

1. Clone: `git clone https://github.com/sbrutyan01/EVIA-Paradigma.git`
2. NetBeans → **File → Open Project…** → select the folder. Make sure the project uses JDK 25
   (**Properties → Build → Compile → Java Platform**).
3. Press **F6**. The main class is `evia.paradigma.App`.

Command line: `mvn package` then `java -jar target/evia-paradigma-0.1.jar`.

### Installing Clang 23

| OS | Command |
|---|---|
| Windows | [llvm-mingw](https://github.com/mstorsjo/llvm-mingw/releases) (self-contained Clang + C++ library), or `winget install LLVM.LLVM` together with Visual Studio Build Tools |
| Ubuntu/Debian | `wget https://apt.llvm.org/llvm.sh && chmod +x llvm.sh && sudo ./llvm.sh 23`, then use `clang++-23` |
| macOS | `brew install llvm` |

**Settings…** shows which compiler was found. It's green for Clang 23 and orange for older versions or other compilers.

## Blocks

**Start**, **Set** (`x = expr`), **Input** (`name`), **Print** (`expr`),
**If** (`condition`; left port = true, right port = false), **End**.

Expressions: numbers, `"strings"`, variables, `+ - * / %`, `== != < <= > >=`, `&& || !`, `( )`,
`true`/`false`, `this.field`.

## Custom blocks (classes and objects)

| OOP idea | In EVIA Paradigma |
|---|---|
| Class | A custom block **definition**: name, parameters, a body made of blocks |
| Object | Each custom block placed on a canvas |
| Arguments | Parameters, e.g. `Average(x)`; the object's code line holds the arguments |
| Fields | `this.name` inside the body. They start at 0, every object has its own, and they keep their values between calls |
| Return paths | **Exit** blocks. Each distinct exit name is an output port (`IsEven` has `even` and `odd`) |
| Global state | Plain variable names are program variables |
| Composition / recursion | A body can contain other custom blocks, including itself |

- **Group existing blocks:** select blocks (drag a rectangle, Ctrl+click), press **Ctrl+G**. The selection becomes
  a definition plus one object, all wires are kept, and the program behaves exactly as before.
- **From scratch:** **New custom block…** opens the definition editor.

## Vim

Vim is the text editor throughout EVIA Paradigma:

- **The C++ editor** and **block code dialogs** use built-in Vim keys (toggle with the *Vim keys* checkbox or in Settings):
  - NORMAL, INSERT, VISUAL, VISUAL LINE modes, with counts
  - motions: `h j k l w W b B e E ge 0 ^ $ gg G f F t T ; , % { } n N`
  - operators: `d c y > <`, doubled, and text objects `iw aw i" a" i' i( a( i{ a{ i[ a[`
  - commands: `x X s S D C Y p P J r ~ u Ctrl-R .`, `i a I A o O`
  - registers: `"a`–`"z`, `"+` (system clipboard)
  - search: `/ ? * #`, and `:N`, `:s/a/b/g`, `:%s/…`, `:'<,'>s/…` (Java regex syntax)
  - in the C++ editor: `:w`, `:w file.cpp`, `:q`, `:wq`, `:make` (compile & run), `:vim` (open in real Vim)
  - in block dialogs: Enter or `:w` accepts; Esc in NORMAL mode or `:q` cancels
- **Edit in Vim…** opens the code in the real Vim or gVim. Save and quit (`:wq`) and the changes come back.
  The command is set in Settings and detected automatically, e.g. `gvim -f {file}`.
- **Git commit messages:** `git config --global core.editor vim`

## C++20 code generation

**Generate C++** checks the program the same way Run does, then writes one self-contained C++20 file. In the C++ editor you can
edit it, **Save .cpp**, or **Compile & Run** with Clang. Text typed into **Input** goes to the program's stdin.

| Block program | Generated C++ |
|---|---|
| Custom block definition | `struct Name` with `int call(params...)` returning the index of the Exit taken |
| `this.x` fields, parameters | members `f_x` (initialized to 0) |
| Object placed in a body / on the main canvas | `std::unique_ptr<Name> oN` member of the enclosing struct, created on first use |
| Program variables | globals `v_x`; reading one before it's assigned throws, as in the interpreter |
| Wires | statements laid out along the main path (fall-through), other wires as `goto` |
| Values | small embedded runtime (`evia_rt::Value`: number or string) with the interpreter's exact semantics |

## Command line

```
java -jar evia-paradigma.jar --run program.evia              # run with the interpreter
java -jar evia-paradigma.jar --gen program.evia -o prog.cpp  # generate C++20
clang++ -std=c++20 -O2 -o prog prog.cpp && ./prog
```

This lets you write a program in the editor and then generate and build it from Vim (`:!java -jar … --gen %`)
or a Makefile.

## Continuous integration

Every push runs [`.github/workflows/build.yml`](.github/workflows/build.yml), which:
1. builds the jar with Maven on JDK 25,
2. installs Clang 23 from apt.llvm.org,
3. runs [`scripts/check-examples.sh`](scripts/check-examples.sh). For each program in [`examples/`](examples) it generates C++,
   compiles it with `-Wall -Wextra -Wpedantic -Werror`, and requires the compiled program and the interpreter to produce
   identical stdout, stderr and exit codes.

## Code map

| File | Role |
|---|---|
| `App.java` / `Cli.java` | Entry point; command-line mode |
| `MainFrame.java` | Main window, runs the interpreter in a `SwingWorker` |
| `DefinitionEditor.java` | Window for one custom block definition |
| `Library.java` / `BlockDef.java` | Definitions ("classes") and keeping objects in sync |
| `Grouper.java` | Turns a selection into a definition plus an object |
| `PalettePanel.java` / `FlowCanvas.java` / `Block.java` | Palette, editing surface, blocks |
| `ExprParser.java` / `Expr.java` | Recursive-descent parser → AST (sealed interface + records) → evaluator |
| `Interpreter.java` | Compile pass, then execution with per-object state |
| `ProgramIO.java` | `.evia` files and the built-in example |
| `CppGenerator.java` / `CppRuntime.java` | C++20 generation and the embedded runtime |
| `CodeViewer.java` | C++ editor: Vim keys, save, compile & run |
| `VimKeys.java` | Vim emulation for Swing text components |
| `ExternalEditor.java` / `CodeEditDialog.java` | Real Vim integration; Vim-enabled block code dialog |
| `Settings.java` | Vim, editor and compiler settings; Clang version check |
| `AppIcon.java` | EVIA logo icons and About dialog |

## License

EVIA Paradigma is released under the [MIT License](LICENSE). Copyright (c) 2026 Sergey Brutyan (sbrutyan01).
