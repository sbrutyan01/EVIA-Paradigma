# EVIA Paradigma — visual block programming in Java Swing

Drag blocks onto a canvas, wire them together, run the program.
Combine blocks into **custom blocks**, which work like classes in OOP.
Pure Swing, no external libraries. Maven project, Java 25.

## Open in NetBeans

1. Unzip the project.
2. NetBeans → **File → Open Project…** → select the `EVIA-Paradigma` folder.
3. Make sure the project uses JDK 25: right-click project → **Properties → Build → Compile → Java Platform**.
4. Press **F6**. The main class is `evia.paradigma.App`.

Command line: `mvn package` then `java -jar target/evia-paradigma-1.0.jar`.

## Primitive blocks

**Start**, **Set** (`x = expr`), **Input** (`name`), **Print** (`expr`),
**If** (`condition`; left port = true, right port = false), **End**.

Expressions: numbers, `"strings"`, variables, `+ - * / %`, `== != < <= > >=`, `&& || !`, `( )`,
`true`/`false`, `this.field`.

## Custom blocks (classes and objects)

| OOP idea | In EVIA Paradigma |
|---|---|
| Class | A custom block **definition**: name, parameters, a body made of blocks |
| Object | Each custom block placed on a canvas |
| Constructor / method arguments | Parameters, e.g. `Average(x)`; the object's code line holds the arguments |
| Fields | `this.name` inside the body. They start at 0, every object has its own, and they keep their values between calls |
| Return paths | **Exit** blocks. Each distinct exit name is an output port (`IsEven` has `even` and `odd`) |
| Global state | Plain variable names are program variables, visible everywhere |
| Composition / recursion | A body can contain other custom blocks, including itself |

### Two ways to make one

- **Group existing blocks:** drag a rectangle on empty space, or Ctrl+click blocks to select them. Then press **Ctrl+G** or right-click → *Group into custom block*. The selection moves into a new definition and is replaced by one object. All wires are kept, and the program behaves exactly as before. The selection must have a single entry point.
- **From scratch:** **New custom block…** opens the definition editor with an Enter and an Exit block. Build the body there and set its parameters.

Double-click a custom block in the palette (or right-click an object → *Open definition*) to edit it.
Changes apply to every object right away, and renaming or moving Exits keeps existing wires.

## C++20 code generation

**Generate C++** (toolbar) checks the program the same way Run does, then translates it into one
self-contained C++20 source file. The viewer can **Save .cpp**, **Copy**, or **Compile & Run** it with an
external compiler. The compiler command can be edited and is remembered:
`{src}` is the source file and `{exe}` is the output program. Text typed into **Input** goes to the program's stdin.

- g++ / MinGW-w64: `g++ -std=c++20 -O2 -Wall -o {exe} {src}` (default)
- MSVC (from a Developer Command Prompt): `cl /std:c++20 /EHsc /O2 /Fe:{exe} {src}`

How the program maps to C++:

| Block program | Generated C++ |
|---|---|
| Custom block definition | `struct Name` with `int call(params...)` returning the index of the Exit taken |
| `this.x` fields, parameters | members `f_x` (initialized to 0) |
| Object placed in a body / on the main canvas | `std::unique_ptr<Name> oN` member of the enclosing struct (`Program` for the main canvas), created on first use |
| Program variables | globals `v_x`; reading one before it's assigned throws, as in the interpreter |
| Wires | statements laid out along the main path (fall-through), other wires as `goto` to labels |
| Values | small embedded runtime (`evia_rt::Value`: number or string) with the interpreter's exact semantics |

Generated code compiles cleanly with g++ 13 and clang++ 18 using `-Wall -Wextra -Wpedantic -Werror`, and produces
byte-for-byte the same output as the built-in interpreter, including number formatting and error messages.

## Code map

| File | Role |
|---|---|
| `App.java` | `main` |
| `MainFrame.java` | Main window, runs the interpreter in a `SwingWorker`, opens definition editors |
| `DefinitionEditor.java` | Window for one custom block: name, parameters, color, body canvas |
| `Library.java` | All definitions; keeps objects in sync when a definition changes |
| `BlockDef.java` | A definition ("class"): params + body canvas, exit names → ports |
| `Grouper.java` | Turns a selection into a definition and an object, rewiring the connections |
| `PalettePanel.java` | Palette with primitives and custom blocks (Swing drag-and-drop) |
| `FlowCanvas.java` | Editing surface: drop, select, move, connect, paint wires |
| `Block.java` | One block component; a CUSTOM block is an object of a `BlockDef` |
| `ExprParser.java` / `Expr.java` | Recursive-descent parser → AST (sealed interface + records) → evaluator |
| `Interpreter.java` | Compile pass over all blocks, then execution with per-object state and call depth limit |
| `ProgramIO.java` | `.evia` save/load (definitions included) and the example program |
| `CppGenerator.java` | Block program → C++20 source |
| `CppRuntime.java` | The C++ runtime embedded in every generated file |
| `CodeViewer.java` | Shows generated code; save, compile and run with an external compiler |
