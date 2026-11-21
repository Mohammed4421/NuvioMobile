"""Arabic UI text (Build 15, P4-03): every English string has an Arabic translation, and each Arabic
string carries exactly the English placeholders (%s, %d, %1$s ...). A missing or extra placeholder
shows wrong text or throws when the string is formatted."""
import collections
import re
import unittest
import xml.etree.ElementTree as ElementTree
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / "composeApp/src/commonMain/composeResources"
PLACEHOLDER = re.compile(r"%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfcxX]")
# Arabic plural forms that name their count in words ("one", "two") may leave the number out.
WORDED_QUANTITIES = ("zero", "one", "two")


def load(folder):
    strings = {}
    for file in sorted((BASE / folder).glob("*.xml")):
        for element in ElementTree.parse(file).getroot():
            name = element.get("name")
            if element.tag == "string":
                strings[name] = "".join(element.itertext())
            elif element.tag == "plurals":
                for item in element:
                    strings[f"{name}#{item.get('quantity')}"] = "".join(item.itertext())
    return strings


def placeholders(text):
    return collections.Counter(m.group(0) for m in PLACEHOLDER.finditer(text.replace("%%", "")))


class ArabicStrings(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.en, cls.ar = load("values"), load("values-ar")

    def english_of(self, key):
        if key in self.en:
            return self.en[key]
        name, _, _ = key.partition("#")
        return self.en.get(f"{name}#other")

    def test_every_english_string_is_translated(self):
        missing = sorted(k for k in self.en if "#" not in k and k not in self.ar)
        self.assertEqual([], missing, f"{len(missing)} strings without Arabic")

    def test_arabic_strings_exist_in_english(self):
        orphans = sorted(k for k in self.ar if self.english_of(k) is None)
        self.assertEqual([], orphans)

    def test_placeholders_match_english(self):
        wrong = []
        for key, text in self.ar.items():
            english = self.english_of(key)
            if english is None or key.endswith(tuple(f"#{q}" for q in WORDED_QUANTITIES)):
                continue
            if placeholders(text) != placeholders(english):
                wrong.append(f"{key}: ar {dict(placeholders(text))} en {dict(placeholders(english))}")
        self.assertEqual([], wrong)

    def test_the_check_sees_a_broken_string(self):
        self.assertNotEqual(placeholders("%1$s من %2$d"), placeholders("%1$s of %2$s"))
        self.assertEqual(placeholders("100%% %s"), placeholders("%s"))


if __name__ == "__main__":
    unittest.main()
