"""Validate translation coverage and Android format arguments before packaging."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1] / "app/src/main/res"
source = {e.attrib["name"]: e for e in ET.parse(root / "values/strings.xml").getroot()}
translated = {e.attrib["name"]: e for e in ET.parse(root / "values-zh/strings.xml").getroot()}
assert source.keys() == translated.keys(), (
    "missing", source.keys() - translated.keys(), "extra", translated.keys() - source.keys()
)
format_token = r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]"
for key, value in source.items():
    localized = translated[key]
    assert value.tag == localized.tag, key
    assert re.findall(format_token, "".join(value.itertext())) == re.findall(
        format_token, "".join(localized.itertext())
    ), ("format mismatch", key)
    if value.tag == "string-array":
        assert len(value) == len(localized), ("array mismatch", key)
print(f"PASS: {len(source)} resources fully translated; format arguments and arrays match")
