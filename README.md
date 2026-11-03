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
  <a href="https://github.com/sbrutyan01/EVIA-Paradigma/releases/latest"><img src="https://img.shields.io/badge/version-0.2.0-blue" alt="version 0.2.0"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-green" alt="MIT license"></a>
</p>

Drag blocks onto a canvas, wire them together, run the program. Define **classes** with
fields, a constructor and methods, share them between programs as **modules**, keep your work
in **project folders** under **Git/GitHub**, edit everything with **Vim**, and turn the
program into a single **C++20** file built with **Clang 23**. Pure Swing, no external
libraries; a Maven project for NetBeans and Java 25.

## Download

Get the installer for your system from the [latest release](https://github.com/sbrutyan01/EVIA-Paradigma/releases/latest).
Each installer includes its own Java runtime, so Java doesn't need to be installed.

| System | File | Notes |
|---|---|---|
| Windows 10 / 11 (x64) | `EVIA-Paradigma-0.2.0-windows-x64.exe` | Start menu entry, optional desktop shortcut, `.evia` file association, and `evia.exe` for the command line. The installer isn't code-signed yet: on the SmartScreen warning click **More info → Run anyway** |
| macOS, Apple Silicon | `EVIA-Paradigma-0.2.0-macos-arm64.dmg` | Drag to Applications. Not notarized yet: the first time, right-click the app → **Open** |
| macOS, Intel | `EVIA-Paradigma-0.2.0-macos-x64.dmg` | Same as above |
| Ubuntu 22.04+ / Debian 12+ (amd64) | `evia-paradigma_0.2.0_amd64.deb` | `sudo apt install ./evia-paradigma_0.2.0_amd64.deb`. Installs to `/opt/evia-paradigma` with a menu entry and the `evia-paradigma` command |
| Any OS with Java 25 | `evia-paradigma-0.2.0.jar` | `java -jar evia-paradigma-0.2.0.jar` |

## Building from source

**Requirements:** JDK 25, LLVM/Clang 23 (latest stable) for compiling generated C++, and Vim/gVim (optional, for editing in the real Vim).

1. Clone: `git clone https://github.com/sbrutyan01/EVIA-Paradigma.git`
2. NetBeans → **File → Open Project…** → select the folder. Make sure the project uses JDK 25
   (**Properties → Build → Compile → Java Platform**).
3. Press **F6**. The main class is `evia.paradigma.App`.

Command line: `mvn package` then `java -jar target/evia-paradigma-0.2.0.jar`.

### Installing Clang 23

| OS | Command |
|---|---|
| Windows | Nothing to do: **Settings → Install Clang…** (also offered automatically on the first compile) downloads the latest [llvm-mingw](https://github.com/mstorsjo/llvm-mingw/releases), a self-contained Clang with its own C++ library and Windows headers, into `%LOCALAPPDATA%\EVIA Paradigma\llvm-mingw`. No Visual Studio, no admin rights, no PATH changes. Programs are linked with `-static`, so they run on any Windows PC |
| Ubuntu/Debian | `wget https://apt.llvm.org/llvm.sh && chmod +x llvm.sh && sudo ./llvm.sh 23`, then use `clang++-23` |
| macOS | `brew install llvm` |

**Settings → Check** test-compiles the C++ runtime with the configured compiler and shows the version. It's green for Clang 23, orange for older versions or other compilers, and red if the compiler or its C++ library is missing.

> **Windows note:** the official LLVM installer (`winget install LLVM.LLVM`) contains only the compiler, without a C++ standard library, so on its own it fails with `'cerrno' file not found`. Use llvm-mingw as described above. EVIA Paradigma detects this and offers to set it up.

## Blocks

**Start**, **Set** (`x = expr`), **Input** (`name`), **Print** (`expr`), **End**.

### Flow control

| Block | Code | Ports | Generated C++ |
|---|---|---|---|
| **If / Else** | `x < 10` | `then`, `else`. Wire both to the same block to join them | `if (…) { … } else { … }` |
| **For** | `i = 1; i <= 10; i = i + 1` (init; condition; step, each part optional) | `body`, `done` | `for (…; …; …) { … }` |
| **While** | `x < 10`, checked before each pass | `body`, `done` | `while (…) { … }` |
| **Do-While** | `x < 10`, checked after each pass, so the body runs at least once | `body`, `done` | `do { … } while (…);` |

To close a loop, **wire the end of its body back into the loop block**. Arriving there from the body
starts the next pass (For runs its step, then the condition). Arriving from anywhere else enters the loop
(For runs its init; Do-While goes straight into the body). Loops nest naturally: an inner For starts over
on each pass of the outer loop. An If/Else whose branches both lead back to the loop block works like `continue`.

When the wiring is block-structured, *Generate C++* writes real nested `if/else`, `for`, `while` and `do-while`
statements (see [`examples/loops.evia`](examples/loops.evia)). Free-form wiring, such as jumping out of the middle of a
loop or into one, is still allowed: that part of the program is generated with labels and `goto`, and it
behaves exactly like the interpreter.

Expressions: numbers, `"strings"`, variables, `+ - * / %`, `== != < <= > >=`, `&& || !`, `( )`,
`true`/`false`, `this.field`.

## Classes and objects

A **class** has **fields** (with initial values), an optional **constructor** and any number of
**methods**. Each method is a block program from **Enter** to one or more **Exit** blocks.

| OOP idea | In EVIA Paradigma |
|---|---|
| Class | **Edit → New Class…** (or the palette's *+ New class*). The class window has a *Fields* tab and one tab per method |
| Fields | Declared with an initial value expression (`balance` = `opening * 2`); read and written as `this.balance`. Every object has its own, and they keep their values between calls |
| Constructor | A method named `init` (*+ Constructor*). When an object is created its fields are initialized in order, then `init` runs once |
| Methods | Name and parameters, e.g. `deposit(x)`; a parameter arrives as the field `this.x` |
| Return paths | **Exit** blocks. Each distinct exit name is an output port of the calling block (`deposit` has `rejected` and `ok`) |
| Calls | Drag `Account.deposit(x)` from the palette. Double-click (or right-click → *Method, object and arguments…*) to choose the method, the **object name** and the arguments |
| Objects | Blocks with the same object name share one object: `acc.deposit(10)` then `acc.report()`. A block without an object name has an object of its own. Objects are created on first use |
| Composition / recursion | A method can call other classes, or its own class |

- **Group existing blocks:** select blocks (drag a rectangle, Ctrl+click), press **Ctrl+G**. The selection becomes
  a class with one method plus a call to it; all wires are kept and the program behaves exactly as before.
- See [`examples/classes.evia`](examples/classes.evia) (*File → Open Example → Classes, objects and a module*).

### Modules

A module (`*.eviam`) is a file of classes that several programs can use.
**File → Export Classes as Module…** moves classes into a module file, **File → Import Module…** adds one to the
open program. A program stores its modules as relative paths (`U|modules/counter.eviam`), and saving the program
also saves the modules it uses. [`examples/classes.evia`](examples/classes.evia) uses
[`examples/modules/counter.eviam`](examples/modules/counter.eviam).

Programs saved by 0.1.x still open; every custom block becomes a class with one method `run`.

## Menus

| Menu | Items |
|---|---|
| **File** | New Program (Ctrl+N), New Project… (Ctrl+Shift+N), Open… (Ctrl+O, program, project file or folder), Open Project…, Open Example, Save (Ctrl+S), Save As…, Import Module…, Export Classes as Module…, Close Project, Exit |
| **Edit** | Select All, Delete, New Class…, Group Selection into Class (Ctrl+G), Open Class, Settings… |
| **Run** | Run (F5), Stop (Shift+F5), Generate C++ (F7), Clear Output |
| **Git** | Initialize Repository, Commit… (Ctrl+K), Push (Ctrl+Shift+K), Pull, Connect to GitHub…, Open on GitHub, Status, History |
| **Help** | Guide (F1) with topics, Keyboard Shortcuts, Vim Keys, Examples, Online Documentation, Report an Issue, Check for Updates, About |

**Right-click a block → View C++ source** opens the generated C++ with exactly the lines that block became highlighted.
The same works for a method or class (class window → *View C++*, or right-click a class in the palette).

## Projects and GitHub

**File → New Project…** creates a folder (by default in `~/EVIA Projects`):

```
MyProject/
  MyProject.eviaproj   project settings
  main.evia            the main program
  modules/             class modules (*.eviam)
  build/               generated C++ (ignored by git)
  README.md
  .gitignore
```

When git is installed the project starts as a Git repository on branch `main` with a first commit.
The **Git** menu runs the git on your computer and uses the login it already has (Git Credential Manager,
an SSH key or `gh auth login`); EVIA Paradigma never asks for or stores passwords or tokens.

- **Commit…** saves the program and commits all changes; the message editor has Vim keys (`:w` commits).
- **Connect to GitHub…** takes `owner/repo` or a clone URL. *Create repository on GitHub…* opens
  github.com/new with the name filled in; create it empty, press OK, and the project is committed and pushed.
- **Push** / **Pull** (fast-forward only), **Status**, **History**, **Open on GitHub**.

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
| Class | `struct Name` with a constructor (field initial values, then `m_init()`) and one `int m_method(params...)` per method, returning the index of the Exit taken |
| Fields, parameters | members `f_x` (declared fields get their initial values, others start at 0) |
| Objects | `obj_name` members for named objects, `o_method_N` for blocks with their own object; `std::unique_ptr`, created on first use |
| Program variables | globals `v_x`; reading one before it's assigned throws, as in the interpreter |
| Wires | nested `if`/`else`, `for`, `while`, `do`/`while` and `switch` when block-structured, otherwise labels and `goto` |
| Values | small embedded runtime (`evia_rt::Value`: number or string) with the interpreter's exact semantics |

## Command line

```
java -jar evia-paradigma.jar --run program.evia              # run with the interpreter
java -jar evia-paradigma.jar --run MyProject                 # a project folder runs its main program
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
   identical stdout, stderr and exit codes,
4. runs [`scripts/check-app.sh`](scripts/check-app.sh): classes and modules (export, import, reload), the
   *View C++ source* line mapping, project creation and the Git commands against a local bare repository.

Reports: [v0.1.2: CI checks and release files (PDF)](docs/reports/EVIA-Paradigma-0.1.2-CI.pdf) · [SHA-256 checksums](docs/reports/SHA256SUMS-0.1.2.txt)

## Code map

| File | Role |
|---|---|
| `App.java` / `Cli.java` | Entry point; command-line mode |
| `MainFrame.java` | Main window, menus, projects, Git menu; runs the interpreter in a `SwingWorker` |
| `ClassEditor.java` / `InstanceDialog.java` | Class window (fields, methods); method/object/arguments of a call |
| `Library.java` / `BlockDef.java` / `Method.java` | Classes, their fields and methods, keeping calls in sync |
| `Grouper.java` | Turns a selection into a class plus a call |
| `Project.java` / `GitService.java` / `GitDialogs.java` | Project folders; the installed git; commit and GitHub dialogs |
| `HelpWindow.java` | Guide (`help/guide.html`), links, update check |
| `PalettePanel.java` / `FlowCanvas.java` / `Block.java` | Palette, editing surface, blocks |
| `ExprParser.java` / `Expr.java` | Recursive-descent parser → AST (sealed interface + records) → evaluator |
| `Interpreter.java` | Compile pass, then execution with per-object state |
| `ProgramIO.java` | `.evia` programs and `.eviam` modules (format v3; v2 still loads) and the built-in example |
| `CppGenerator.java` / `CppRuntime.java` | C++20 generation and the embedded runtime |
| `CodeViewer.java` | C++ editor: Vim keys, save, compile & run |
| `VimKeys.java` | Vim emulation for Swing text components |
| `ExternalEditor.java` / `CodeEditDialog.java` | Real Vim integration; Vim-enabled block code dialog |
| `Settings.java` | Vim, editor and compiler settings; Clang version check |
| `AppIcon.java` | EVIA logo icons and About dialog |

## License

EVIA Paradigma is released under the [MIT License](LICENSE). Copyright (c) 2026 Sergey Brutyan (sbrutyan01).
