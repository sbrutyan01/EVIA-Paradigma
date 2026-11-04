<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/logo-dark.png">
    <img src="docs/logo.png" alt="EVIA" width="200">
  </picture>
</p>

<h1 align="center">EVIA Paradigma</h1>

<p align="center">
  Visual block programming in Java Swing — with classes, a Vim-powered editor and C / C++ code generation for Clang.
  <br><br>
  <a href="https://github.com/sbrutyan01/EVIA-Paradigma/actions/workflows/build.yml"><img src="https://github.com/sbrutyan01/EVIA-Paradigma/actions/workflows/build.yml/badge.svg" alt="build"></a>
  <a href="https://github.com/sbrutyan01/EVIA-Paradigma/releases/latest"><img src="https://img.shields.io/badge/version-0.3.1-blue" alt="version 0.3.1"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-green" alt="MIT license"></a>
</p>

Drag blocks onto a canvas, wire them together, zoom with the mouse wheel, run the program. Define **classes** with
fields, a constructor and methods, share them between programs as **modules**, keep your work
in **project folders** under **Git/GitHub**, edit everything with **Vim**, and turn the
program into a single **C** (C99, C11, C17) or **C++** (C++98 to C++20) file built with **Clang 23**,
with structured or goto control flow and safe or fast values. Pure Swing, no external
libraries; a Maven project for NetBeans and Java 25.

<p align="center"><img src="docs/screenshots/08-run-with-class.png" alt="EVIA Paradigma main window: a loop that calls a Counter object, with the output below" width="900"></p>

**Contents:** [Download](#download) · [Tutorial](#tutorial) · [Building from source](#building-from-source) ·
[Blocks](#blocks) · [Classes](#classes-and-objects) · [Zoom](#zoom) · [Menus](#menus) · [Projects and GitHub](#projects-and-github) ·
[Vim](#vim) · [C and C++](#c-and-c-code-generation) · [Command line](#command-line) · [CI](#continuous-integration) · [Screenshots](#screenshots)

## Download

Get the installer for your system from the [latest release](https://github.com/sbrutyan01/EVIA-Paradigma/releases/latest).
Each installer includes its own Java runtime, so Java doesn't need to be installed.

| System | File | Notes |
|---|---|---|
| Windows 10 / 11 (x64) | `EVIA-Paradigma-0.3.1-windows-x64.exe` | Start menu entry, optional desktop shortcut, `.evia` file association, and `evia.exe` for the command line. The installer isn't code-signed yet: on the SmartScreen warning click **More info → Run anyway** |
| macOS, Apple Silicon | `EVIA-Paradigma-0.3.1-macos-arm64.dmg` | Drag to Applications. Not notarized yet: the first time, right-click the app → **Open** |
| macOS, Intel | `EVIA-Paradigma-0.3.1-macos-x64.dmg` | Same as above |
| Ubuntu 22.04+ / Debian 12+ (amd64) | `evia-paradigma_0.3.1_amd64.deb` | `sudo apt install ./evia-paradigma_0.3.1_amd64.deb`. Installs to `/opt/evia-paradigma` with a menu entry and the `evia-paradigma` command |
| Any OS with Java 25 | `evia-paradigma-0.3.1.jar` | `java -jar evia-paradigma-0.3.1.jar` |

### Release 0.3.1

[EVIA Paradigma v0.3.1](https://github.com/sbrutyan01/EVIA-Paradigma/releases/tag/v0.3.1) ([release notes](docs/release-notes/v0.3.1.md)):
zoom block diagrams with the mouse wheel, plus the two Windows fixes listed under 0.3.0.

| File | Platform | Size |
|---|---|---|
| `EVIA-Paradigma-0.3.1-windows-x64.exe` | Windows 10/11 x64 | 28.0 MB |
| `EVIA-Paradigma-0.3.1-macos-arm64.dmg` | macOS, Apple Silicon | 28.2 MB |
| `EVIA-Paradigma-0.3.1-macos-x64.dmg` | macOS, Intel | 29.7 MB |
| `evia-paradigma_0.3.1_amd64.deb` | Ubuntu/Debian amd64 | 22.9 MB |
| `evia-paradigma-0.3.1.jar` | Any OS with Java 25 | 0.5 MB |
| `LICENSE` | MIT license | — |

| CI check | Result |
|---|---|
| Mouse-wheel zoom on the real main window (Linux, virtual display) | All passed |
| Every language and mode, Clang 23 (Linux) | 384 of 384 identical to the interpreter |
| Every language and mode, GCC (Linux) | 384 of 384 identical |
| C and C++ standards, llvm-mingw Clang (Windows, no Visual Studio) | 240 of 240 identical |
| Examples, application and Git checks | All passed |
| Installers built for all platforms and attached to the release | Passed |

### Release 0.3.0

[EVIA Paradigma v0.3.0](https://github.com/sbrutyan01/EVIA-Paradigma/releases/tag/v0.3.0) ([release notes](docs/release-notes/v0.3.0.md)):
C99, C11, C17 and C++98 to C++20, structured/goto flow, safe/fast values.

| File | Platform | Size |
|---|---|---|
| `EVIA-Paradigma-0.3.0-windows-x64.exe` | Windows 10/11 x64 | 28.0 MB |
| `EVIA-Paradigma-0.3.0-macos-arm64.dmg` | macOS, Apple Silicon | 28.2 MB |
| `EVIA-Paradigma-0.3.0-macos-x64.dmg` | macOS, Intel | 29.7 MB |
| `evia-paradigma_0.3.0_amd64.deb` | Ubuntu/Debian amd64 | 22.9 MB |
| `evia-paradigma-0.3.0.jar` | Any OS with Java 25 | 0.5 MB |
| `LICENSE` | MIT license | — |

CI ([`build.yml`](.github/workflows/build.yml)): every program in `examples/` and `tests/programs/` in every
language and mode, compiled with `-Wall -Wextra -Wpedantic -Werror` and compared with the interpreter
(stdout, stderr, exit code):

| Where | Compiler | Identical to the interpreter | Refused by fast values¹ | Failed |
|---|---|---|---|---|
| Linux, all 8 languages | Clang 23.1.5 | 384 of 384 | 64 | 0 |
| Linux, all 8 languages | GCC 13.3 | 384 of 384 | 64 | 0 |
| Windows (C99, C17, C++98, C++14, C++20) | llvm-mingw Clang 23.1.4, `-static` | 240 of 240 | 40 | 0 |
| Linux and Windows | examples, application and Git checks (`check-examples.sh`, `check-app.sh`) | passed | — | 0 |

¹ As intended: those programs keep text in variables, which fast values (plain `double`) don't allow.
On Windows the programs need no Visual Studio DLLs.

Release 0.3.1 also has two fixes found by the Windows CI job (they only show with the strict test flags
`-Wpedantic -Werror`, so the published installers work): whole numbers beyond 32 bits are written as `10000000000.0`
(on Windows `long` is 32-bit, and C++98 rejects `long long` literals), and the test scripts ignore `\r\n` line endings.

### Release 0.2.0

[EVIA Paradigma v0.2.0](https://github.com/sbrutyan01/EVIA-Paradigma/releases/tag/v0.2.0) ([release notes](docs/release-notes/v0.2.0.md))

| File | Platform | Size |
|---|---|---|
| `EVIA-Paradigma-0.2.0-windows-x64.exe` | Windows 10/11 x64 | 27.9 MB |
| `EVIA-Paradigma-0.2.0-macos-arm64.dmg` | macOS, Apple Silicon | 28.2 MB |
| `EVIA-Paradigma-0.2.0-macos-x64.dmg` | macOS, Intel | 29.6 MB |
| `evia-paradigma_0.2.0_amd64.deb` | Ubuntu/Debian amd64 | 22.9 MB |
| `evia-paradigma-0.2.0.jar` | Any OS with Java 25 | 0.4 MB |
| `LICENSE` | MIT license | — |

| Check on the release build | Result |
|---|---|
| Every installer starts and runs the built-in example | Passed |
| Examples: interpreter and the Clang 23 build give identical output | Passed |
| Classes, modules, View C++ source, projects and Git (`scripts/check-app.sh`) | Passed |
| Release notes on the release page | Yes |

## Tutorial

This walk-through builds a small project with a loop and a class, runs it, looks at the C and C++ it becomes,
and puts it under Git. Every picture is the real application (they are regenerated with
[`scripts/take-screenshots.sh`](scripts/take-screenshots.sh)).

### 1. Create a project

**File → New Project…** (Ctrl+Shift+N). Enter a name and a location; keep *Create a Git repository* checked
if git is installed.

<p align="center"><img src="docs/screenshots/01-new-project.png" alt="New Project dialog" width="550"></p>

The project opens with a starter program, *Start → Print → End*. The output panel shows that git created
the repository and the first commit.

<p align="center"><img src="docs/screenshots/02-project-created.png" alt="New project with the starter program and the first commit" width="900"></p>

### 2. Build a loop

Drag blocks from the palette onto the canvas and connect them: drag from a block's bottom port (dark dot)
to the next block's top port (white dot). Double-click a block to edit its code.

- **Set**: `n = 3`
- **For**: `i = 1; i <= n; i = i + 1`. Its `body` port goes to **Print** `"step " + i`; wire the end of the
  body **back into the For block** to start the next pass. Its `done` port goes to **End**.

Press **Run** (F5). Set *Step delay* to watch the active block move. Turn the mouse wheel over the diagram to [zoom](#zoom) in and out.

<p align="center"><img src="docs/screenshots/03-first-run.png" alt="A For loop and its output" width="900"></p>

### 3. Create a class

**Edit → New Class…** (or *+ New class…* in the palette), name it `Counter`. The class window has a
**Fields** tab and one tab per method.

**Fields**: add `total` and `calls`, both starting at `0`. Every object gets its own copy.

<p align="center"><img src="docs/screenshots/04-class-fields.png" alt="Fields tab of the class window" width="800"></p>

**Methods**: rename the first method to `add` with parameter `x` (press **Apply**), then build its body
from **Enter** to **Exit**. Inside a method, fields and parameters are written as `this.name`:

<p align="center"><img src="docs/screenshots/05-class-method.png" alt="Method add(x) of class Counter" width="800"></p>

**+ Constructor** adds `init`, which runs once when an object is created (after the fields get their
initial values). Here it prints `"new counter"`. **+ Method…** adds `show` that prints
`"total = " + this.total + " after " + this.calls + " calls"`.

<p align="center"><img src="docs/screenshots/06-class-constructor.png" alt="The constructor init" width="800"></p>

### 4. Use the class

The palette now lists `Counter.add(x)` and `Counter.show()`. Drag `Counter.add(x)` into the loop body after
**Print** and `Counter.show()` between `done` and **End**. Double-click a call block to choose the method,
the **object** and the arguments. Give both blocks the object name `c` so they share one object:

<p align="center"><img src="docs/screenshots/07-call-dialog.png" alt="Call dialog: method, object and arguments" width="600"></p>

Run it. `c` is created on its first use (so `new counter` is printed then), `add` runs three times,
and `show` prints the total:

<p align="center"><img src="docs/screenshots/08-run-with-class.png" alt="Program with a Counter object and its output" width="900"></p>

### 5. See the code for any block

Right-click a block → **View source code**. The generated code opens with exactly that block's lines highlighted
(here C++20, the default):

<p align="center"><img src="docs/screenshots/09-block-menu.png" alt="Right-click menu of a block" width="420"></p>

<p align="center"><img src="docs/screenshots/10-view-source.png" alt="Generated C++ with the call block's lines highlighted" width="900"></p>

**Compile & Run** builds it with Clang and runs it. The compiled program prints the same as the interpreter.
On Windows the app installs the llvm-mingw Clang for you (no Visual Studio needed).

<p align="center"><img src="docs/screenshots/11-compile-run.png" alt="The generated program compiled with Clang and run" width="900"></p>

### 6. Choose C or another C++ standard

Pick a **Language** in the drop-down list (in the code window or the main toolbar); the code is generated again
at once, and the same block stays highlighted. **Flow** and **Values** choose how it is written
(see [C and C++ code generation](#c-and-c-code-generation)).

<p align="center"><img src="docs/screenshots/21-language-dropdown.png" alt="Language drop-down list: C99, C11, C17, C++98, C++11, C++14, C++17, C++20" width="800"></p>

The same program in **C11**, compiled with `clang -std=c11` and run: the output is identical.

<p align="center"><img src="docs/screenshots/22-c11-compile-run.png" alt="The program as C11, compiled and run" width="900"></p>

**C++17** with **Goto** flow (every block a label, every wire a jump) and **Fast** values (plain `double`):

<p align="center"><img src="docs/screenshots/23-cpp17-goto-fast.png" alt="C++17 with goto flow and fast values" width="900"></p>

### 7. Commit and publish on GitHub

**Git → Commit…** (Ctrl+K) saves and commits all changes. The message box has Vim keys (`Esc`, then `:w` commits).

<p align="center"><img src="docs/screenshots/17-commit.png" alt="Commit dialog" width="520"></p>

**Git → Connect to GitHub…**: type `owner/repo`. *Create repository on GitHub…* opens github.com with the name
filled in; create it **empty**, press **OK**, and the project is pushed. Git uses the login it already has
on your computer (Git Credential Manager, an SSH key or `gh auth login`).

<p align="center"><img src="docs/screenshots/18-connect-github.png" alt="Connect to GitHub dialog" width="560"></p>

**Git → History** lists the commits:

<p align="center"><img src="docs/screenshots/19-history.png" alt="Git history" width="650"></p>

### 8. Keep going

- Share classes between programs: **File → Export Classes as Module…**, then **File → Import Module…** in another program.
- **File → Open Example** has ready-made programs, including `classes.evia` (fields, constructor, methods, a module).
- **Help → Guide** (F1) explains every block, expressions, classes, projects, Git, shortcuts and Vim keys.

<p align="center"><img src="docs/screenshots/20-help-guide.png" alt="The built-in guide" width="800"></p>

## Building from source

**Requirements:** JDK 25, LLVM/Clang 23 (latest stable) for compiling generated C and C++, and Vim/gVim (optional, for editing in the real Vim).

1. Clone: `git clone https://github.com/sbrutyan01/EVIA-Paradigma.git`
2. NetBeans → **File → Open Project…** → select the folder. Make sure the project uses JDK 25
   (**Properties → Build → Compile → Java Platform**).
3. Press **F6**. The main class is `evia.paradigma.App`.

Command line: `mvn package` then `java -jar target/evia-paradigma-0.3.1.jar`.

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

| Block | Code | Ports | Generated code (structured flow) |
|---|---|---|---|
| **If / Else** | `x < 10` | `then`, `else`. Wire both to the same block to join them | `if (…) { … } else { … }` |
| **For** | `i = 1; i <= 10; i = i + 1` (init; condition; step, each part optional) | `body`, `done` | `for (…; …; …) { … }` |
| **While** | `x < 10`, checked before each pass | `body`, `done` | `while (…) { … }` |
| **Do-While** | `x < 10`, checked after each pass, so the body runs at least once | `body`, `done` | `do { … } while (…);` |

To close a loop, **wire the end of its body back into the loop block**. Arriving there from the body
starts the next pass (For runs its step, then the condition). Arriving from anywhere else enters the loop
(For runs its init; Do-While goes straight into the body). Loops nest naturally: an inner For starts over
on each pass of the outer loop. An If/Else whose branches both lead back to the loop block works like `continue`.

When the wiring is block-structured, *Generate code* writes real nested `if/else`, `for`, `while` and `do-while`
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

## Zoom

Turn the **mouse wheel** over a block diagram (the main program or a method in a class window) to zoom in or out,
from 25% to 300%. The point under the pointer stays where it is, so you zoom into what you're looking at. Blocks,
text and wires are drawn sharply at every size; positions in the saved files don't depend on the zoom.

| Input | Does |
|---|---|
| Mouse wheel | Zoom in (up) or out (down) toward the pointer |
| Shift+wheel / Ctrl+wheel | Scroll sideways / up and down |
| Ctrl+= / Ctrl+- | Zoom in / out (also the number pad's + and -) |
| Ctrl+0 | Actual size, 100% |
| Ctrl+9 | Fit the whole diagram in the window |
| `− 100% +` (bottom right) | Zoom out, back to 100%, zoom in |

Prefer the classic behavior? Turn off **Settings → Mouse wheel zooms block diagrams**: then the wheel scrolls and
Ctrl+wheel zooms.

<table>
<tr>
<td><img src="docs/screenshots/25-zoom-in.png" alt="Zoomed in to 161%" width="440"></td>
<td><img src="docs/screenshots/26-zoom-out.png" alt="Zoomed out to 60%" width="440"></td>
</tr>
<tr><td align="center">Zoomed in (161%)</td><td align="center">Zoomed out (60%)</td></tr>
</table>

## Menus

| Menu | Items |
|---|---|
| **File** | New Program (Ctrl+N), New Project… (Ctrl+Shift+N), Open… (Ctrl+O, program, project file or folder), Open Project…, Open Example, Save (Ctrl+S), Save As…, Import Module…, Export Classes as Module…, Close Project, Exit |
| **Edit** | Select All, Delete, New Class…, Group Selection into Class (Ctrl+G), Open Class, Settings… |
| **View** | Zoom In (Ctrl+=), Zoom Out (Ctrl+-), Actual Size (Ctrl+0), Fit Diagram in Window (Ctrl+9) |
| **Run** | Run (F5), Stop (Shift+F5), Generate Code (F7), Code Language and Mode (language, flow, values), Clear Output |
| **Git** | Initialize Repository, Commit… (Ctrl+K), Push (Ctrl+Shift+K), Pull, Connect to GitHub…, Open on GitHub, Status, History |
| **Help** | Guide (F1) with topics, Keyboard Shortcuts, Vim Keys, Examples, Online Documentation, Report an Issue, Check for Updates, About |

<table>
<tr>
<td><img src="docs/screenshots/12-menu-file.png" alt="File menu" width="300"></td>
<td><img src="docs/screenshots/13-menu-edit.png" alt="Edit menu" width="300"></td>
<td><img src="docs/screenshots/14-menu-run.png" alt="Run menu" width="300"></td>
</tr>
<tr>
<td><img src="docs/screenshots/15-menu-git.png" alt="Git menu" width="300"></td>
<td><img src="docs/screenshots/16-menu-help.png" alt="Help menu" width="300"></td>
<td></td>
</tr>
</table>

**Right-click a block → View source code** opens the generated code, in the chosen language, with exactly the lines
that block became highlighted. The same works for a method or class (class window → *View code*, or right-click a class
in the palette).

<p align="center"><img src="docs/screenshots/24-menu-code-language.png" alt="Run → Code Language and Mode" width="560"></p>

## Projects and GitHub

**File → New Project…** creates a folder (by default in `~/EVIA Projects`):

```
MyProject/
  MyProject.eviaproj   project settings
  main.evia            the main program
  modules/             class modules (*.eviam)
  build/               generated C or C++ code (ignored by git)
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

- **The code window** and **block code dialogs** use built-in Vim keys (toggle with the *Vim keys* checkbox or in Settings):
  - NORMAL, INSERT, VISUAL, VISUAL LINE modes, with counts
  - motions: `h j k l w W b B e E ge 0 ^ $ gg G f F t T ; , % { } n N`
  - operators: `d c y > <`, doubled, and text objects `iw aw i" a" i' i( a( i{ a{ i[ a[`
  - commands: `x X s S D C Y p P J r ~ u Ctrl-R .`, `i a I A o O`
  - registers: `"a`–`"z`, `"+` (system clipboard)
  - search: `/ ? * #`, and `:N`, `:s/a/b/g`, `:%s/…`, `:'<,'>s/…` (Java regex syntax)
  - in the code window: `:w`, `:w file.cpp`, `:q`, `:wq`, `:make` (compile & run), `:vim` (open in real Vim)
  - in block dialogs: Enter or `:w` accepts; Esc in NORMAL mode or `:q` cancels
- **Edit in Vim…** opens the code in the real Vim or gVim. Save and quit (`:wq`) and the changes come back.
  The command is set in Settings and detected automatically, e.g. `gvim -f {file}`.
- **Git commit messages:** `git config --global core.editor vim`

## C and C++ code generation

**Generate code** (F7) checks the program the same way Run does, then writes one self-contained source file. Three
drop-down lists in the main toolbar and in the code window choose the output (also **Run → Code Language and Mode**,
and `--lang` / `--flow` / `--values` on the [command line](#command-line)). A project remembers its choice. In the code
window you can edit the code, **Save** it, or **Compile & Run** it; text typed into **Input** goes to the program's stdin.

| Language | Standard | Classes become | Values (safe) | Objects |
|---|---|---|---|---|
| **C99**, **C11**, **C17** | ISO C 1999 / 2011 / 2018 | `struct Name` + functions `Name_m_method(Name *self, ...)`, `Name_new()` | `Value` (kind, number, text); text in a scratch arena | pointers, created on first use |
| **C++98** | ISO C++ 1998 | `struct` with member functions; not copyable | classic `Value` class | raw pointers, deleted by the owner |
| **C++11**, **C++14** | ISO C++ 2011 / 2014 | `struct` with member initializers | classic `Value` class | `std::unique_ptr` (`make_unique` in C++14) |
| **C++17**, **C++20** | ISO C++ 2017 / 2020 | `struct` with member initializers | `std::variant`, `std::to_chars` | `std::unique_ptr` |

| Flow | Generated control flow |
|---|---|
| **Structured** (default) | nested `if`/`else`, `for`, `while`, `do`/`while` and `switch` (for calls with several exits); labels and `goto` only where the wiring isn't block-structured |
| **Goto** | every block is a label and every wire a jump (fall-through where possible), closest to the block diagram |

| Values | Variables and fields | Checks |
|---|---|---|
| **Safe** (default) | numbers or text, exactly like the interpreter | undefined variables, division by zero, text that isn't a number, endless recursion (400 nested calls), field initial values |
| **Fast** | plain `double`; text only in **Print** (`"total = " + x` is printed piece by piece) | none: the code is what you would write by hand. A program that keeps text in a variable, an argument or a comparison is refused with the block that does it |

- Number output is identical in every language: whole numbers without `.0`, others like Java (`0.30000000000000004`,
  `1.0E-4`, `6.666666666666666E9`).
- The C runtime contains only the helpers the program uses, so the code compiles cleanly with
  `-Wall -Wextra -Wpedantic -Werror`. C needs `-lm`; EVIA Paradigma adds it, the matching C compiler (`clang`, `gcc`)
  and `-std=` to the compile command automatically.
- Every combination is tested in CI (see below): 8 languages × 2 flows × 2 value modes, with Clang and GCC.

```
clang   -std=c11   -O2 -o program program.c -lm
clang++ -std=c++98 -O2 -o program program.cpp
```

## Command line

```
java -jar evia-paradigma.jar --run program.evia              # run with the interpreter
java -jar evia-paradigma.jar --run MyProject                 # a project folder runs its main program
java -jar evia-paradigma.jar --gen program.evia -o prog.cpp  # generate C++20 (structured, safe values)
clang++ -std=c++20 -O2 -o prog prog.cpp && ./prog

# other languages and modes
java -jar evia-paradigma.jar --gen program.evia --lang c11 -o prog.c
java -jar evia-paradigma.jar --gen program.evia --lang c++98 --flow goto --values fast -o prog.cpp
clang -std=c11 -O2 -o prog prog.c -lm && ./prog
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
   *View source code* line mapping (C and C++), project creation and the Git commands against a local bare repository,
5. runs [`scripts/check-zoom.sh`](scripts/check-zoom.sh) on the real main window under Xvfb: wheel zoom keeps the
   point under the pointer in place, stops at 25% and 300%, Shift/Ctrl+wheel scroll, saved files don't change with
   the zoom, dragging and dropping land in the right place,
6. runs [`scripts/check-targets.sh`](scripts/check-targets.sh) with Clang 23 and with GCC: every program in `examples/`
   and [`tests/programs/`](tests/programs) (numbers, text, objects, recursion, unstructured wiring and every runtime
   error) in all 32 combinations of language (C99 … C++20), flow and values, compiled with
   `-Wall -Wextra -Wpedantic -Werror` and compared with the interpreter (stdout, stderr, exit code). The Windows job
   does the same with llvm-mingw Clang and `-static` and checks that no program needs a Visual Studio DLL.

**If a release ends up without installers** (for example a download failed while it was being built):
open **Actions → release → Run workflow**, enter the tag (e.g. `v0.2.1`) and run it. It builds that tag's
sources and attaches the installers to its release. The Clang download retries and, if apt.llvm.org is down,
falls back to Ubuntu's Clang with a warning.

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
| `CodeGenerator.java` | C and C++ generation: structured or goto flow, safe or fast values, source map for *View source code* |
| `Runtimes.java` / `CppRuntime.java` | The embedded runtimes: C (only the pieces a program uses), C++98–14, C++17/20, fast values |
| `Target.java` / `TargetBar.java` | Language / flow / values choice and its drop-down lists |
| `CodeViewer.java` | Code window: Vim keys, save, compile & run with the matching compiler and `-std=` |
| `VimKeys.java` | Vim emulation for Swing text components |
| `ExternalEditor.java` / `CodeEditDialog.java` | Real Vim integration; Vim-enabled block code dialog |
| `Settings.java` | Vim, editor and compiler settings; Clang version check |
| `AppIcon.java` | EVIA logo icons and About dialog |

## Screenshots

All pictures are in [`docs/screenshots`](docs/screenshots). They are taken from the running application by
[`scripts/take-screenshots.sh`](scripts/take-screenshots.sh), which drives the UI through the tutorial above.

| | |
|---|---|
| <img src="docs/screenshots/00-main-window.png" alt="Main window" width="420"> | <img src="docs/screenshots/05-class-method.png" alt="Class window" width="420"> |
| Main window | Class window: a method |
| <img src="docs/screenshots/10-view-source.png" alt="View source code" width="420"> | <img src="docs/screenshots/11-compile-run.png" alt="Compile and run" width="420"> |
| View source code (C++20) | Compile & Run with Clang |
| <img src="docs/screenshots/22-c11-compile-run.png" alt="C11" width="420"> | <img src="docs/screenshots/23-cpp17-goto-fast.png" alt="C++17 goto fast" width="420"> |
| The same program as C11 | C++17, goto flow, fast values |
| <img src="docs/screenshots/02-project-created.png" alt="New project" width="420"> | <img src="docs/screenshots/20-help-guide.png" alt="Guide" width="420"> |
| New project with Git | Help → Guide |

## License

EVIA Paradigma is released under the [MIT License](LICENSE). Copyright (c) 2026 Sergey Brutyan (sbrutyan01).
