# PortKit

Moves Nuvio Plus features into a compatible Nuvio project (the official app or a fork of it),
feature by feature, without overwriting what the target already changed. Build 15, phase P7.

**Status: foundation, dry run, safe apply and transfer tests.** `verified` in `compatibility.json`
turns true for a feature only after the transfer tests pass for it in CI. No feature is marked verified until its transfer test passes.

| File | What it is |
|---|---|
| `../.github/plus/features.json` | The one feature registry (also used by the upstream review): files, test files, removed files, dependencies |
| `compatibility.json` | PortKit's level for each feature (portable, adapter-required, manual, unsupported), the upstream base, and the owners of files several features change |
| `LICENSES/GPL-3.0.txt` | The licence of Nuvio Plus and of every file PortKit transfers (the GPL-3.0 text of `../LICENSE`); a target must carry the same licence (`target_contract`), and PortKit never transfers or edits a target's `LICENSE` or its notices (`test_portkit_licence.py`) |
| `policy.json` | What is never transferred: signing keys, secrets, `local.properties`, CI and release pipelines |
| `portkit.py` | `validate` (registry, levels and policy agree), `inventory` (what each feature changes against upstream) and `plan` (dry run on a target: what a transfer would do, as a report and a diff; writes nothing) |
| `reports/inventory.md` | The current inventory |
| `reports/compatibility-matrix.md` | Level, verified, evidence, dependencies and shared files of every feature |
| `reports/test-report.md` | PASS / FAIL / BLOCKED / NOT TESTED for every required check, with evidence |

```sh
python3 portkit/portkit.py validate
python3 portkit/portkit.py inventory --markdown portkit/reports/inventory.md
python3 portkit/portkit.py plan --target ../my-nuvio-fork --json plan.json --diff plan.diff lazy-key-collisions
```

`plan` first checks that the target is a Nuvio project (`target_contract`), refuses unsupported
features, unknown ones and one owner of a shared file alone, adds dependencies, then gives each
file one action. **Only portable features whose transfer test passed (`verified`, with the run as
`evidence`) are planned for a real transfer.** `--trial` also plans unverified, adapter-required or
manual features; trials are for the CI transfer tests and isolated branches, not for keeping:

| Action | Meaning |
|---|---|
| `create` | the file is new and the target has none |
| `patch-exact` | the target file is exactly upstream's, so the change applies as is |
| `patch-context` | the target changed the file elsewhere; the change still applies by context |
| `delete` | the feature removes an upstream file the target left unchanged |
| `already` | the target already has the result |
| `conflict` | the target changed the same place, has its own file at a new path, lacks the upstream file, or the path is forbidden |

A conflict stops that feature only. Exit status: 0 ready or already, 3 refused, conflict or
unsupported target.

### Applying

```sh
python3 portkit/portkit.py apply --target ../my-nuvio-fork --branch portkit/lazy-keys lazy-key-collisions
python3 portkit/portkit.py rollback --target ../my-nuvio-fork --branch portkit/lazy-keys
```

- The transfer starts from the target's last commit. A target with uncommitted changes (staged,
  unstaged or untracked) is refused; `--from-head` transfers onto the last commit anyway and records
  that the uncommitted work was left out.
- Only portable, verified features are transferred. `--trial` (unverified, adapter-required or manual
  features, for tests) must use a `portkit-trial/` branch and is marked as a trial in the ledger and
  commit; a real transfer cannot use such a branch.
- The target's checkout is never touched: PortKit adds a Git worktree on a **new** branch from the
  target's HEAD (an existing branch is refused), plans against it, computes every result first,
  writes, checks each file holds what was planned, and commits with `.portkit/transfers.json`
  (before and after fingerprint of every file) and a `PortKit-Transfer:` trailer.
- Anything other than a clean plan, or any failure, leaves no branch and no worktree behind.
- Applying a feature the target already has changes nothing (`already`).
- `rollback` reverts, as a new commit on that branch, only files that still hold exactly what
  PortKit wrote; files the fork changed afterwards are kept and listed.

### Examples

A transfer that works (official Nuvio 1.0.0, two of the verified features):

```text
$ python3 portkit/portkit.py apply --target ../nuvio --branch portkit/fixes lazy-key-collisions subtitle-parser-worst-case
| `composeApp/.../DuplicateSafeLazyKeys.kt` | patch-exact |  |
| `composeApp/.../SubtitleParserWorstCaseTest.kt` | create |  |
Applied on branch portkit/fixes as 1a2b3c…; the target's checkout is unchanged.
```

Transfers that stop, and why:

```text
$ python3 portkit/portkit.py apply --target ../nuvio --branch portkit/style subtitle-style
## Refused
- subtitle-style is adapter-required: only portable features are transferred; use a trial to test it

$ python3 portkit/portkit.py apply --target ../nuvio --branch portkit/id identity
- identity is unsupported: package, signing and release pipeline of Nuvio Plus; never transferred

$ python3 portkit/portkit.py apply --target ../dirty-fork --branch portkit/x lazy-key-collisions
::error::the target has 2 uncommitted change(s); commit or stash them, or pass --from-head …

$ python3 portkit/portkit.py plan --target ../fork-that-edited-the-line --trial lazy-key-collisions
| `composeApp/.../DuplicateSafeLazyKeys.kt` | conflict | the target changed this file where the feature changes it |
```

### Transfer tests

`.github/workflows/portkit.yml` runs `transfer-test.sh` for each target: a Git repository of the
upstream base, and the same with `fixtures/fork-a.patch` (a fork that changed the transferred files
elsewhere and has its own file). Each run transfers the features with `apply`, builds the result
with Gradle and runs the transferred tests; every one must run and pass. Results:
`portkit-transfer.md` in the run summary and artifact.

Identity (package, signing, release pipeline) and the updater are never transferred: a target
keeps its own identity, keys and update source. Transferred code stays under GPL-3.0 with its
notices; a target without the GPL-3.0 `LICENSE` is refused before anything is planned.

---

<div dir="rtl">

## بالعربية

أداة PortKit تنقل ميزات Nuvio Plus إلى مشروع Nuvio متوافق، سواء النسخة الرسمية أو أي Fork منها، ميزةً ميزة، ولا تكتب فوق تعديلات المشروع الهدف.

**الحالة: الأساس، والتجربة دون تعديل، والتطبيق الآمن، واختبارات النقل.**
- **التخطيط (`plan`):** يعرض ما سيتغير في المشروع الهدف ملفًا ملفًا مع Diff كامل، ولا يكتب فيه شيئًا.
- **التطبيق (`apply`):** يكتب على فرع جديد في المشروع الهدف عبر Worktree مستقل، ولا يلمس نسخة العمل الأصلية. أي فشل لا يترك فرعًا ولا تعديلًا.
- **التراجع (`rollback`):** يعيد فقط الملفات التي ما زالت تحمل ما كتبه PortKit نفسه.
- **اختبارات النقل:** تنقل الميزات إلى مشروعين هدفين، وتبني الناتج، وتشغّل اختباراته. لا تُعلَّم أي ميزة «مُتحقَّق منها» قبل نجاح اختبار نقلها.

**لا يُنقل أبدًا:** مفاتيح التوقيع، والأسرار، و`local.properties`، وخطوط البناء والنشر، ولا ملف الترخيص `LICENSE` في المشروع الهدف ولا عبارات حقوق النشر فيه. كذلك هوية التطبيق والمحدّث لا يُنقلان؛ فالمشروع الهدف يحتفظ بهويته ومفاتيحه ومصدر تحديثاته.

**الترخيص:** Nuvio Plus مرخّص بـ GPL-3.0 (النص في `LICENSES/GPL-3.0.txt`)، ويُرفض أي مشروع هدف لا يحمل الترخيص نفسه قبل أي تخطيط.

**الاستخدام باختصار:**
1. **الفحص:** `validate` يتأكد من اتساق السجل.
2. **التجربة:** `plan` يعرض ما سيتغير دون أي كتابة.
3. **النقل:** `apply` ينقل إلى فرع جديد.
4. **التراجع:** `rollback` عند الحاجة.

**الميزات القابلة للنقل الآن:** ثلاث ميزات من 15، تحقق منها اختبار بناء فعلي على Nuvio 1.0.0 الرسمي وعلى Fork مختلف: `lazy-key-collisions` و`subtitle-parser-worst-case` و`playback-credential-tls`.

**الميزات الأخرى:** مرفوضة في النقل الفعلي حتى تُكتب لها محوّلات وتنجح اختباراتها. التفاصيل في `reports/compatibility-matrix.md`، ونتائج الاختبارات في `reports/test-report.md`.

**مثال على رفض صحيح:** طلب نقل `identity` أو `updater`، أو مشروع هدف فيه تغييرات غير محفوظة.

</div>
