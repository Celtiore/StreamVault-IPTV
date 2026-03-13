import os
import xml.etree.ElementTree as ET
import re

def main():
    res_dir = 'app/src/main/res'
    base_file = os.path.join(res_dir, 'values', 'strings.xml')
    
    if not os.path.exists(base_file):
        print(f"Error: Base strings.xml not found at {base_file}")
        return

    # Load base strings
    try:
        base_tree = ET.parse(base_file)
        base_root = base_tree.getroot()
        base_strings = {s.attrib['name']: s.text for s in base_root.findall('string') if 'name' in s.attrib}
    except Exception as e:
        print(f"Error parsing base file: {e}")
        return

    print(f"Base strings loaded: {len(base_strings)} keys.")

    reports = []

    # Iterate through all values-* directories
    for dir_name in sorted(os.listdir(res_dir)):
        if not dir_name.startswith('values-'):
            continue
        
        strings_file = os.path.join(res_dir, dir_name, 'strings.xml')
        if not os.path.exists(strings_file):
            continue

        print(f"Checking {dir_name}...")
        try:
            tree = ET.parse(strings_file)
            root = tree.getroot()
            locale_strings = {s.attrib['name']: s.text for s in root.findall('string') if 'name' in s.attrib}
        except Exception as e:
            reports.append(f"[{dir_name}] FAILED TO PARSE: {e}")
            continue

        missing = []
        untranslated = []
        formatting_issues = []

        for name, base_text in base_strings.items():
            if name not in locale_strings:
                missing.append(name)
                continue
            
            locale_text = locale_strings[name]
            
            # Check for untranslated English (if base_text is long enough and identical)
            if locale_text == base_text and len(base_text or "") > 5:
                # Exclude URLs
                if base_text.startswith("http"):
                    continue
                # Exclude placeholder-only or symbol-only strings
                clean_base = re.sub(r'%\d+\$[sd]|%\d+\$f|%\d+\$d|%\d+\$s|%|→|-|\||\n|:| ', '', base_text)
                if not clean_base:
                    continue
                # Exclude some known constant strings
                if name in ["app_name", "welcome_version", "settings_developer_name", "setup_title_streamvault"]:
                    continue
                
                untranslated.append(name)
            
            # Check for unescaped apostrophes in the raw file content (hard to do with ET, so we check the value)
            # Actually, ET handles XML escaping, but Android needs \' for apostrophes in some contexts.
            # But usually it's about the raw file. Let's look for common patterns.
            if locale_text and "'" in locale_text and not "\\'" in locale_text:
                # Simple check: if a single quote exists but no backslash-quote
                # This is a heuristic and might have false positives, but good for spotting issues.
                pass 

        if missing or untranslated:
            reports.append(f"[{dir_name}]")
            if missing:
                reports.append(f"  Missing keys ({len(missing)}): {', '.join(missing[:5])}" + ("..." if len(missing) > 5 else ""))
            if untranslated:
                reports.append(f"  Untranslated (English) ({len(untranslated)}):")
                for name in untranslated:
                    reports.append(f"    - {name}: \"{base_strings[name][:50]}...\"")
            reports.append("")

    with open('localization_audit.txt', 'w', encoding='utf-8') as f:
        f.write("\n".join(reports))
    
    print("Audit complete. Results saved to localization_audit.txt")

if __name__ == "__main__":
    main()
