# GKhata

Android (Capacitor 6) एप। UI `www/index.html` मा, Android कोड `native/` मा।

## APK बनाउने / डाउनलोड गर्ने
- `main` मा push गर्दा GitHub Actions ले आफैँ APK बनाउँछ र **Releases** मा राख्छ (`v1.0.<build नम्बर>`)।
- सधैँ नयाँ APK को स्थिर लिङ्क: `https://github.com/<username>/Gkhata/releases/latest/download/GKhata.apk`
- Version: `version.txt` (`1.0`) + build नम्बर = `1.0.58`। नयाँ श्रृंखला चाहिए `version.txt` बदल्ने।
- Settings > Actions > General > Workflow permissions = **Read and write**।

## Signing (एक पटक)
1. `bash tools/make-keystore.sh` (JDK 17 चाहिन्छ) → `dairy-release.jks` बन्छ।
2. GitHub > Settings > Secrets and variables > Actions: `KEYSTORE_B64`, `KS_PASS`, `KEY_PASS`।
3. `.jks` दुई ठाउँमा सुरक्षित राख्ने, GitHub मा कहिल्यै नराख्ने। हरायो भने एप update हुँदैन।
- Secrets नभए debug APK बन्छ, जसको signature बदलिन सक्छ।

## नियम (नबिगार्ने)
- SMS नम्बर नक्कल हुन सक्छ, त्यसैले Order सिधै save हुँदैन, DRAFT बन्छ र Confirm गर्नुपर्छ।
- `www/index.html` को CSP `connect-src` मा `*.supabase.co`, `api.openai.com`, `script.google.com`, `script.googleusercontent.com` मात्र छन्। अर्को सेवा प्रयोग गर्दा यहाँ थप्ने।
- नयाँ स्थानीय शब्द थप्न `www/index.html` को `LOCK` (Order बुझ्न) र `SYNG` (खोजी) सूची।
- लोगो बदल्न `tools/gkhata-logo-source.png` बदली `python3 tools/make-icons.py` (pillow, numpy, opencv-python-headless)।
- Google Drive Backup चाहिए `tools/apps-script-backup.gs` Apps Script मा थपेर deploy गर्ने।

## फोनमा पहिलो पटक
1. Android 13+: App info > ⋮ > **Allow restricted settings** (पहिले)।
2. एप > Order > "फोन सेटअप जाँच" मा सबै अनुमति दिने (Phone, Call log, SMS, Contacts, Notifications, Audio, Microphone, Notification access, Display over other apps)।
3. Battery: No restrictions। Xiaomi: Autostart ON, Lock screen मा देखाउने ON।
4. Speech-to-text API key एक पटक राख्ने।
