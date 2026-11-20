# PortKit

Moves Nuvio Plus features into a compatible Nuvio project (the official app or a fork of it),
feature by feature, without overwriting what the target already changed. Build 15, phase P7.

**Status: foundation.** The checks below work; planning (dry-run), applying and the transfer tests
come in the next steps. No feature is marked verified until its transfer test passes.

| File | What it is |
|---|---|
| `../.github/plus/features.json` | The one feature registry (also used by the upstream review): files, test files, removed files, dependencies |
| `compatibility.json` | PortKit's level for each feature (portable, adapter-required, manual, unsupported), the upstream base, and the owners of files several features change |
| `policy.json` | What is never transferred: signing keys, secrets, `local.properties`, CI and release pipelines |
| `portkit.py` | `validate` (registry, levels and policy agree) and `inventory` (what each feature changes against upstream) |
| `reports/inventory.md` | The current inventory |

```sh
python3 portkit/portkit.py validate
python3 portkit/portkit.py inventory --markdown portkit/reports/inventory.md
```

Identity (package, signing, release pipeline) and the updater are never transferred: a target
keeps its own identity, keys and update source. Transferred code stays under GPL-3.0 with its
notices.

---

<div dir="rtl">

## بالعربية

أداة PortKit تنقل ميزات Nuvio Plus إلى مشروع Nuvio متوافق، سواء النسخة الرسمية أو أي Fork منها، ميزةً ميزة، ولا تكتب فوق تعديلات المشروع الهدف.

**الحالة: الأساس فقط.** يعمل فحص التناسق وجرد الميزات. أما التخطيط دون تعديل (dry-run) والتطبيق واختبارات النقل فتأتي في الخطوات التالية. لا تُعلَّم أي ميزة «مُتحقَّق منها» قبل نجاح اختبار نقلها.

**لا يُنقل أبدًا:** مفاتيح التوقيع، والأسرار، و`local.properties`، وخطوط البناء والنشر. كذلك هوية التطبيق والمحدّث لا يُنقلان؛ فالمشروع الهدف يحتفظ بهويته ومفاتيحه ومصدر تحديثاته.

</div>
