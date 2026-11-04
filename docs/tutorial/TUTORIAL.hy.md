# EVIA Paradigma. ուսուցում

[English](../../README.md#tutorial) · [Русский](TUTORIAL.ru.md) · **Հայերեն** · [Կայք](https://sbrutyan01.github.io/EVIA-Paradigma/hy/)

Այս ուսուցման ընթացքում մենք կստեղծենք ցիկլով և դասով փոքր նախագիծ, կգործարկենք այն, կտեսնենք, թե ինչ
C և C++ կոդի է այն վերածվում, և կպահենք այն Git-ում։ Վերջում կծրագրավորենք Arduino կամ ESP32 տախտակ։ Բոլոր
սքրինշոթներն արված են իրական հավելվածում։ Ծրագրի ինտերֆեյսն անգլերեն է, ուստի ընտրացանկերի և կոճակների
անունները բերված են այնպես, ինչպես երևում են էկրանին։

Ներբեռնել EVIA Paradigma-ն՝ [վերջին թողարկումը](https://github.com/sbrutyan01/EVIA-Paradigma/releases/latest)
(Windows-ի, macOS-ի և Linux-ի տեղադրիչներն արդեն պարունակում են Java)։

## 1. Ստեղծեք նախագիծ

**File → New Project…** (Ctrl+Shift+N)։ Մուտքագրեք անունը և թղթապանակը. եթե git-ը տեղադրված է, թողեք
*Create a Git repository*-ն նշված։

<p align="center"><img src="../screenshots/01-new-project.png" alt="New Project պատուհանը" width="550"></p>

Նախագիծը բացվում է սկզբնական ծրագրով՝ *Start → Print → End*։ Ելքի վահանակը ցույց է տալիս, որ git-ը ստեղծել է
պահոցը և առաջին commit-ը։

<p align="center"><img src="../screenshots/02-project-created.png" alt="Նոր նախագիծը՝ սկզբնական ծրագրով և առաջին commit-ով" width="900"></p>

## 2. Կառուցեք ցիկլ

Բլոկները գունապնակից քաշեք կտավի վրա և միացրեք դրանք. քաշեք բլոկի ներքևի պորտից (մուգ կետ) դեպի հաջորդ
բլոկի վերևի պորտը (սպիտակ կետ)։ Բլոկի վրա կրկնակի սեղմումը բացում է նրա կոդը խմբագրելու համար։

- **Set**. `n = 3`
- **For**. `i = 1; i <= n; i = i + 1`։ Նրա `body` պորտը տանում է դեպի **Print** `"step " + i`. ցիկլի մարմնի
  վերջը միացրեք **հետ՝ For բլոկին**, որպեսզի սկսվի հաջորդ անցումը։ `done` պորտը տանում է դեպի **End**։

Սեղմեք **Run** (F5)։ Նշեք *Step delay*-ը, որպեսզի տեսնեք, թե ինչպես է շարժվում ակտիվ բլոկը։ Մկնիկի անիվը
սխեմայի վրա [փոխում է մասշտաբը](../../README.md#zoom)։

<p align="center"><img src="../screenshots/03-first-run.png" alt="For ցիկլը և դրա ելքը" width="900"></p>

## 3. Ստեղծեք դաս

**Edit → New Class…** (կամ *+ New class…* գունապնակում), անվանեք այն `Counter`։ Դասի պատուհանում կա **Fields**
ներդիր և յուրաքանչյուր մեթոդի համար մեկական ներդիր։

**Fields**. ավելացրեք `total` և `calls` դաշտերը, երկուսն էլ `0` սկզբնական արժեքով։ Յուրաքանչյուր օբյեկտ ունի
դաշտերի իր պատճենները։

<p align="center"><img src="../screenshots/04-class-fields.png" alt="Դասի պատուհանի Fields ներդիրը" width="800"></p>

**Մեթոդներ**. առաջին մեթոդը վերանվանեք `add`՝ `x` պարամետրով (սեղմեք **Apply**), ապա կառուցեք դրա մարմինը
**Enter**-ից մինչև **Exit**։ Մեթոդի ներսում դաշտերն ու պարամետրերը գրվում են `this.անուն` ձևով։

<p align="center"><img src="../screenshots/05-class-method.png" alt="Counter դասի add(x) մեթոդը" width="800"></p>

**+ Constructor**-ը ավելացնում է `init`-ը, որը կատարվում է մեկ անգամ՝ օբյեկտը ստեղծելիս (այն բանից հետո, երբ
դաշտերը ստացել են իրենց սկզբնական արժեքները)։ Այստեղ այն տպում է `"new counter"`։ **+ Method…**-ը ավելացնում է
`show`-ը, որը տպում է `"total = " + this.total + " after " + this.calls + " calls"`։

<p align="center"><img src="../screenshots/06-class-constructor.png" alt="init կոնստրուկտորը" width="800"></p>

## 4. Օգտագործեք դասը

Այժմ գունապնակում կան `Counter.add(x)` և `Counter.show()`։ Քաշեք `Counter.add(x)`-ը ցիկլի մարմնի մեջ՝ **Print**-ից
հետո, իսկ `Counter.show()`-ը՝ `done`-ի և **End**-ի միջև։ Կանչի բլոկի վրա կրկնակի սեղմումով կարելի է ընտրել
մեթոդը, **օբյեկտը** և արգումենտները։ Երկու բլոկներին էլ տվեք `c` օբյեկտի անունը, որպեսզի նրանք աշխատեն նույն
օբյեկտի հետ։

<p align="center"><img src="../screenshots/07-call-dialog.png" alt="Կանչի պատուհանը՝ մեթոդ, օբյեկտ և արգումենտներ" width="600"></p>

Գործարկեք ծրագիրը։ `c`-ն ստեղծվում է առաջին օգտագործման ժամանակ (այդ պահին էլ տպվում է `new counter`), `add`-ը
կատարվում է երեք անգամ, իսկ `show`-ը տպում է արդյունքը։

<p align="center"><img src="../screenshots/08-run-with-class.png" alt="Counter օբյեկտով ծրագիրը և դրա ելքը" width="900"></p>

## 5. Տեսեք ցանկացած բլոկի կոդը

Աջ սեղմում բլոկի վրա → **View source code**։ Կբացվի գեներացված կոդը, որտեղ ընդգծված են հենց այդ բլոկի տողերը
(այստեղ՝ C++20, լռելյայն լեզուն)։

<p align="center"><img src="../screenshots/09-block-menu.png" alt="Բլոկի համատեքստային ընտրացանկը" width="420"></p>

<p align="center"><img src="../screenshots/10-view-source.png" alt="Գեներացված C++՝ կանչի բլոկի ընդգծված տողերով" width="900"></p>

**Compile & Run**-ը կառուցում է կոդը Clang-ով կամ GCC-ով և գործարկում այն։ Կոմպիլյացված ծրագիրը տպում է նույնը,
ինչ ինտերպրետատորը։ Windows-ում ծրագիրն ինքն է տեղադրում կոմպիլյատորը՝ llvm-mingw (Clang) կամ WinLibs (GCC),
Visual Studio պետք չէ։ Կոմպիլյատորն ընտրվում է **Settings**-ում։

<p align="center"><img src="../screenshots/11-compile-run.png" alt="Գեներացված ծրագիրը՝ կոմպիլյացված Clang-ով և գործարկված" width="900"></p>

## 6. Ընտրեք C կամ C++-ի այլ ստանդարտ

Ընտրեք **Language**-ը բացվող ցանկում (կոդի պատուհանում կամ գլխավոր գործիքագոտում). կոդն անմիջապես նորից
գեներացվում է, և նույն բլոկը մնում է ընդգծված։ **Flow**-ն և **Values**-ը որոշում են կոդի ոճը.
**Structured**՝ if/else, ցիկլեր և switch, **Goto**՝ յուրաքանչյուր բլոկ դառնում է նշիչ, իսկ յուրաքանչյուր կապ՝
անցում. **Safe**՝ թվեր և տեքստ՝ ինտերպրետատորի բոլոր ստուգումներով, **Fast**՝ պարզ `double`-ներ՝ առանց ստուգումների։

<p align="center"><img src="../screenshots/21-language-dropdown.png" alt="Language ցանկը՝ C99, C11, C17, C++98, C++11, C++14, C++17, C++20" width="800"></p>

Նույն ծրագիրը **C11**-ով՝ կոմպիլյացված `clang -std=c11`-ով և գործարկված. ելքը նույնն է։

<p align="center"><img src="../screenshots/22-c11-compile-run.png" alt="Ծրագիրը C11-ով՝ կոմպիլյացված և գործարկված" width="900"></p>

**C++17**՝ **Goto** հոսքով և **Fast** արժեքներով։

<p align="center"><img src="../screenshots/23-cpp17-goto-fast.png" alt="C++17՝ goto հոսքով և fast արժեքներով" width="900"></p>

## 7. Կատարեք commit և հրապարակեք GitHub-ում

**Git → Commit…** (Ctrl+K)-ը պահում է բոլոր փոփոխությունները և կատարում commit։ Հաղորդագրության դաշտում աշխատում
են Vim ստեղները (`Esc`, ապա `:w`՝ commit)։

<p align="center"><img src="../screenshots/17-commit.png" alt="Commit պատուհանը" width="520"></p>

**Git → Connect to GitHub…**. մուտքագրեք `տեր/պահոց`։ *Create repository on GitHub…*-ը բացում է github.com-ը՝
արդեն լրացված անունով. ստեղծեք **դատարկ** պահոց, սեղմեք **OK**, և նախագիծը կուղարկվի։ Git-ն օգտագործում է այն
մուտքը, որն արդեն կարգավորված է ձեր համակարգչում (Git Credential Manager, SSH բանալի կամ `gh auth login`)։

<p align="center"><img src="../screenshots/18-connect-github.png" alt="Connect to GitHub պատուհանը" width="560"></p>

**Git → History**-ն ցույց է տալիս commit-ների ցանկը։

<p align="center"><img src="../screenshots/19-history.png" alt="Git-ի պատմությունը" width="650"></p>

## 8. Ծրագրավորեք Arduino կամ ESP32 տախտակ

**File → Open Example → Arduino and ESP32 Boards → Blink the built-in LED**։ **Board** բլոկները (կանաչ, գունապնակի
*Board* բաժնում) կարգավորում են պինները, կարդում են կոճակներն ու սենսորները և սպասում։

| Բլոկ | Կոդ | Ինչ է անում |
|---|---|---|
| **Pin Mode** | `LED_BUILTIN, OUTPUT` | `OUTPUT`, `INPUT` կամ `INPUT_PULLUP` |
| **Digital Write** | `LED_BUILTIN, HIGH` | `HIGH` ցանկացած ոչ զրոյական արժեքի համար, `LOW`՝ 0-ի համար |
| **Digital Read** | `pressed = 4` | պահում է 1 (HIGH) կամ 0 (LOW) |
| **Analog Read** | `level = A0` | 0..1023 AVR տախտակներում, 0..4095 ESP32-ում |
| **Analog Write** | `5, brightness` | PWM, 0..255 |
| **Delay** | `500` | սպասում է այդքան միլիվայրկյան |
| **Millis** | `t` | ծրագրի սկզբից անցած միլիվայրկյանները |

**Run**-ը (F5) նմանակում է Arduino Uno. ելքում երևում է պինների յուրաքանչյուր փոփոխություն՝ իր ժամանակով։

<p align="center"><img src="../screenshots/27-board-simulated.png" alt="blink օրինակը՝ նմանակված տախտակի պինների փոփոխությունները ելքում" width="900"></p>

Ընտրեք **Language: Arduino** և տախտակը **Board** ցանկում, ապա **Generate code** (F7). մեկ սքեթչը հարմար է
12 տախտակներից ցանկացածի համար (Uno, Nano, Mega 2560, Leonardo, Micro, Pro Mini, ESP32, ESP32-S2, -S3, -C3, -C6,
-H2)։ **Build**-ը այն կառուցում է arduino-cli-ով, **Upload**-ը բեռնում է տախտակի վրա. Print-ը և Input-ը աշխատում
են Serial Monitor-ով՝ 115200 բոդ արագությամբ։ **AVR C**-ն գրում է նույն ծրագիրը առանց Arduino միջուկի՝ ուղիղ
միկրոկոնտրոլերի ռեգիստրներով, և այն կառուցվում է avr-gcc-ով։

<p align="center"><img src="../screenshots/29-avr-build.png" alt="AVR C՝ առանց Arduino-ի, կառուցված avr-gcc-ով Arduino Uno-ի համար" width="900"></p>

## 9. Ինչ անել հետո

- Կիսվեք դասերով ծրագրերի միջև. **File → Export Classes as Module…**, ապա **File → Import Module…** մեկ այլ ծրագրում։
- **File → Open Example**-ում կան պատրաստի ծրագրեր, այդ թվում `classes.evia`-ն (դաշտեր, կոնստրուկտոր, մեթոդներ,
  մոդուլ) և տախտակների օրինակներ։
- **Help → Guide**-ը (F1) բացատրում է յուրաքանչյուր բլոկ, արտահայտությունները, դասերը, նախագծերը, Git-ը,
  ստեղնային համակցությունները և Vim ստեղները։

<p align="center"><img src="../screenshots/20-help-guide.png" alt="Ներկառուցված ուղեցույցը" width="800"></p>

Բոլոր հնարավորությունների մասին մանրամասները՝ [README](../../README.md)-ում (անգլերեն)։
