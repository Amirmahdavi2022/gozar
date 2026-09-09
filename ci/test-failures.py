"""Prints the assertion messages out of Gradle's JUnit XML."""
import sys
import xml.etree.ElementTree as ET

for path in sys.argv[1:]:
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for case in root.iter("testcase"):
        for bad in list(case.iter("failure")) + list(case.iter("error")):
            print(f'{case.get("classname")} > {case.get("name")}')
            message = (bad.get("message") or bad.text or "").strip()
            for line in message.splitlines()[:6]:
                print(f"    {line[:200]}")
            print()
