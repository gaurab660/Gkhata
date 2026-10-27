# GKhata — Android (Capacitor) V1 (1.0.1)

## 🚀 Signed release APK बनाउने तरिका (एक पटकको सेटअप + हरेक पटक २ क्लिक)

### एक पटक मात्र
1. कम्प्युटरमा (JDK 17 राखेको): `bash tools/make-keystore.sh` — यसले `dairy-release.jks` बनाउँछ र GitHub मा हाल्नुपर्ने ३ वटा Secret देखाउँछ।
2. GitHub repo (**Private**) > Settings > Secrets and variables > Actions > New secret:
   `KEYSTORE_B64`, `KS_PASS`, `KEY_PASS`
3. `dairy-release.jks` लाई **२ ठाउँमा** सुरक्षित राख्नुहोस् (USB + password manager)। हरायो भने एप update गर्न मिल्दैन (uninstall गर्दा डाटा जान्छ)। GitHub मा कहिल्यै नराख्नुहोस् (`.gitignore` ले रोकेको छ)।

### हरेक नयाँ संस्करणमा
1. फाइल GitHub मा upload (push) गर्नुहोस् → Actions > "Build Android APK" हरियो भएपछि
2. Artifacts > `dairy-order-release` डाउनलोड → `dairy-order-release.apk` + `.sha256`
3. फोनमा install। (पहिले `debug` APK राखेको भए एक पटक uninstall गर्नुपर्छ — signature फरक हुन्छ। **त्यस अघि Backup लिनुहोस्।**)
- Version नाम V1 (`1.0.1`) मा स्थिर छ; भित्री `versionCode` (Android ले update चिन्न प्रयोग गर्ने) भने GitHub run number अनुसार आफैं बढ्छ। नयाँ नाम चाहिए `VERSION_NAME=1.0.2 bash setup.sh`। Secrets नभए workflow `debug` APK बनाउँछ — त्यो दैनिक प्रयोगको लागि होइन।
- Android Studio बाट: `bash setup.sh` → `npx cap open android` → Build > Generate Signed Bundle/APK (उही `.jks` प्रयोग गर्नुहोस्)।

## 🔒 यो संस्करणमा थपिएका सुरक्षा/भरपर्दो सुधार
| सुधार | किन |
|---|---|
| GKhata लोगो: हरियो-निलो "G" पात + क्याल्कुलेटर/ग्राफ/रसिद, गाढा नीलो पृष्ठभूमिमा (adaptive + Android 13 themed आइकन) र launch screen — स्रोत `tools/gkhata-logo-source.png`; लोगो फेर्न त्यही फाइल बदलेर `python3 tools/make-icons.py` (pillow, numpy, opencv-python-headless चाहिन्छ) | Capacitor को सामान्य आइकन हटाउन |
| Release APK आफ्नै key ले sign + `apksigner verify` + SHA-256 | APK बदलिएको/नक्कली छैन भनी जाँच्न |
| Capacitor `6.2.2` pin + `package-lock.json` | हरेक build उही कोड; अचानक नयाँ version नआउने |
| Workflow: `permissions: contents: read`, timeout, secret फाइल build पछि मेटिने | GitHub मार्फत चुहिने जोखिम घटाउन |
| `network_security_config` | HTTP पूरै बन्द; फोनमा राखिएको नक्कली CA ले traffic पढ्न नसक्ने |
| Recent apps मा हिसाबको thumbnail नदेखिने (Android 13+) | कसैले फोन हेर्दा ledger नदेखियोस् |
| Lock screen मा नम्बर / ग्राहकको सन्देश लुक्ने | बन्द फोनमै ग्राहकको जानकारी नदेखियोस् |
| कल लग ढिलो लेखिँदा ४ पटकसम्म पुन:प्रयास | कल सकिएपछि Order छुट्ने समस्या घटाउन |
| शेयर गरेको audio ३० MB सीमा, `content://` मात्र | अर्को एपले ठूलो फाइल हालेर फोन भर्न नसक्ने |
| पुरानो कल सूची ५० मा सीमित; पुरानो कल गलत ढङ्गले नउठ्ने | स्थिरता |
| रेकर्डिङ खोज्दा "call" भएको फाइललाई प्राथमिकता | अरू Voice memo गलत उठ्ने समस्या |
| Android 14+ "Full-screen notifications" अनुमति Setup जाँचमा | Overlay नचल्दा पनि फोन आउँदा Order खुल्न |
| Plugin बन्दा static reference सफा | memory leak रोक्न |

## 💾 Data भण्डारण (Entry-by-Entry)
- पहिले हरेक save मा सबै Data एउटै ठूलो JSON बनाएर फेरि लेखिन्थ्यो। अब IndexedDB मा **हरेक Entry छुट्टै record** (`e:0000000001 …`) र बाँकी Data (`cust`, `items`, `log`, `users`, `closed` …) **भाग-भाग गरेर** (`dm:<नाम>`) राखिन्छ।
- save() ले अघिल्लो सफल लेखाइसँग तुलना गरी **बदलिएको/नयाँ record मात्र लेख्छ, हटेको record मात्र मेट्छ**; केही नबदलिए केही लेखिँदैन। सबै एउटै transaction (`durability: strict`) मा हुने भएकाले आधा लेखिएर Data बिग्रने डर छैन।
- पुरानो Data पहिलो खोल्दा आफैँ सारिन्छ: पहिले सबै लेखिन्छ → पढेर ठ्याक्कै मिलेको जाँचिन्छ → त्यसपछि मात्र नयाँ format चिन्ह लाग्छ। पुरानो ठूलो प्रति `dairy1_v1` मा १४ दिन सुरक्षित रहेर आफैँ हट्छ।
- Entry को `id` दोहोरिए पनि कुनै Entry हराउँदैन (record को key id होइन, आफ्नै क्रम-संख्या हो)।
- कुनै record बिग्रिए: त्यो मेटिँदैन, बाँकी Data खुल्छ, र चेतावनी आउँछ। IndexedDB नचल्ने फोनमा पुरानै localStorage तरिका चल्छ।
- दैनिक एक पटकको स्वचालित सुरक्षा-प्रति (`dairy1_auto`) र हातले लिने इन्क्रिप्टेड Backup पहिलेकै हुन्।

## ⚠️ ध्यान दिनुपर्ने कुरा
- **Developer verification (Google, २०२६–२७):** सेप्टेम्बर २०२६ देखि ब्राजिल, इन्डोनेसिया, सिङ्गापुर, थाइल्यान्डमा र २०२७ देखि विश्वभर Play बाहिरका एपका developer पनि verify हुनुपर्ने नियम आउँदैछ। आफ्नै फोनको लागि: (क) Google को निःशुल्क "limited distribution account" (२० device सम्म), (ख) "advanced flow" (Developer mode + २४ घण्टा पर्खाइ, एक पटक), वा (ग) कम्प्युटरबाट `adb install` (यसमा असर पर्दैन)। नेपालमा तत्काल असर छैन, तर Google को घोषणा हेरिरहनुहोस्।
- **Android 13+ restricted settings:** install पछि App info > ⋮ > "Allow restricted settings" पहिले गर्नुपर्छ (तल "सबै अनुमति" हेर्नुहोस्)।
- **Capacitor 6 → target SDK 34:** आफ्नै फोनमा Android 15/16 मा राम्रै चल्छ। Play Store को लागि Capacitor 8 (SDK 36, Node 22, JDK 21) चाहिन्छ — त्यो अलग परीक्षणसहित गर्ने काम हो।
- `npm audit` ले `@capacitor/cli` (build-time औजार, `tar` निर्भरता) मा चेतावनी देखाउँछ। यो APK भित्र जाँदैन; build मा आफ्नै फाइल मात्र प्रयोग हुन्छ।
- SMS पठाउने नम्बर नक्कल (spoof) गर्न सकिन्छ। त्यसैले एपले Order सिधै save गर्दैन, DRAFT बनाउँछ र तपाईंले Confirm गर्नुपर्छ — यो नियम नहटाउनुहोस्।
- यो संस्करण मैले Android मा build/चलाएर परीक्षण गर्न सकेको छैन (Java को syntax र JS script मात्र जाँचेको, `setup.sh` चलाएर Android project बनेको देखेको)। पहिलो build पछि कल, SMS, WhatsApp, रेकर्डिङ, lock screen सबै आफ्नै फोनमा जाँच्नुहोस्।

## फोनमा पहिलो पटक (Xiaomi)
- Open the app > Order > message/voice screen > "फोन सेटअप जाँच": allow Phone, Call log, Notifications, Audio, Microphone.
- Battery: allow "No restrictions". Xiaomi: Settings > Apps > GKhata > Autostart ON, Notifications ON.
- Put the speech-to-text API key once ("API Key" button).
- Customers must have their phone number saved in the app (last 10 digits are matched).

## Call recording on Xiaomi
- Mi Dialer: Phone > Settings > Call recording > Auto-record (files: MIUI/sound_recorder/call_rec). The app finds them by itself.
- Google Phone (common on global Xiaomi): recordings are not in a public folder. After the call, Share the recording to "GKhata",
  or use "बोलेर रेकर्ड" with the call on speaker.

## Flow
Phone RINGS -> number matched to a saved customer with no order yet -> order screen opens at once (needs "Display over other apps"; otherwise a full-screen notification). After the call: recording
transcribed (Nepali) -> readMsg() fills a DRAFT -> you review and Confirm. Nothing is saved automatically.

## Messages (SMS + WhatsApp)
- SMS: allowed by the SMS permission. WhatsApp: Settings > Notification access > GKhata ON (the setup check opens it for you).
- Only messages from SAVED customers (number or contact name matches) are read; all others are ignored and not stored. Group chats are skipped.
- If the message contains items, an order DRAFT opens by itself; further messages from the same customer are added to it. You Confirm.
- Limits: WhatsApp gives text only (no voice/photo) and nothing arrives while that chat is open on screen or muted.

## Order-taking time (important)
- Calls and messages are used only from 15:00 until 01:00 next day (change with the "Order समय" button).
- Messages are used only if they arrived after that day's accounts were closed (दिन बन्द) and inside the same window;
  older messages (also unread ones WhatsApp keeps showing) and messages processed after 01:00 are dropped.

## Permissions (what and why)
| Permission | Why |
|---|---|
| Phone state, Call log | detect incoming/ended calls and the caller number |
| SMS (receive) | read SMS from saved customers |
| Contacts (read) | turn a WhatsApp contact name into a number |
| Notification access | read WhatsApp message notifications (saved customers only) |
| Display over other apps | open the order screen the moment a customer calls |
| Notifications | "Order टिप्ने?" alerts |
| Audio / Microphone | find the call recording; record by voice |
| Battery: no restrictions | Xiaomi must not kill the background receivers |

## Easiest way to grant everything
1. Install the APK. Android 13+: App info > top-right ⋮ > **Allow restricted settings** (must be done first, otherwise SMS / call log / notification access stay greyed out).
2. Open the app > order screen > "फोन सेटअप जाँच" and follow each step; the app re-checks itself when you return.
3. Xiaomi: Autostart ON, Battery "No restrictions", "Display pop-up windows while running in the background", "Show on Lock screen".

## Security & stability
- API key is encrypted with the Android Keystore; recordings are deleted right after use; backups and WebView debugging are off.
- Only saved customers are processed; everything else is dropped without being stored. Receivers/listener never crash the app (all guarded).
- Recording is sent to the speech service only after a one-time confirmation; 90 s timeout and a 24 MB limit prevent hangs.
- Release APK is signed with your own key (see top). Lock-screen notifications hide numbers/messages; recent-apps thumbnail is blank.

## Content-Security-Policy
`www/index.html` carries a CSP meta tag. connect-src allows only: the app itself, `*.supabase.co`, and `api.openai.com`.
If you use another backend or speech service, add its address to `connect-src` (and run `npx cap sync android`).
`script-src` keeps `'unsafe-inline'` because the app uses ~750 inline onclick handlers; scripts from other sites and eval stay blocked.

## स्थानीय भाषा (Order सन्देश बुझ्ने)

Customer ले लेख्ने/बोल्ने स्थानीय शब्द App ले आफैं मानक Item शब्दमा बदलेर बुझ्छ (तपाईंको Item नाम/Data केही बदलिँदैन):

| Customer ले भन्ने | App ले बुझ्ने |
|---|---|
| पोका, पोको, थैली, पाकेट, प्याकेट, पाउच, poka | पौच (Pouch) |
| दई, दइ, दै, dai | दहि (Curd) |
| मही, छाछ, मठा, mahi | मोहि (Buttermilk) |
| घी, ghi | घ्यू (Ghee) |
| खुला, बोटल/बोत्तल | खुल्ला, बोतल |
| `दही 5 पिस` (प्याक शब्द नभए) | पौच (रसवरी/लालमोहन जस्ता `पिस` भएका Item अछुतै) |

उदाहरण: `पोका दई 2` → पौच दहि 500ml × 2, `मोही पोका 3` → मोहि पौच 500ml × 3।
Item खोज्ने बक्समा पनि "पोका", "दई", "मही" लेख्दा सम्बन्धित Item देखिन्छ।
नयाँ शब्द थप्न `www/index.html` मा `LOCK` (Order बुझ्न) र `SYNG` (खोजी) सूची हेर्नुहोस्।

## 🥛 लिटर / पोका (नयाँ)
- Customerले पोका/पोको/थैली/पाकेट/प्याकेट/पाउच/poka को सट्टा **लिटर** भन्दा (सन्देश, कल रेकर्डिङ, बोलेर Order — सबैमा) App ले लिटरलाई वस्तुको साइजले भाग गरेर संख्या बनाउँछ: `४ फ्याट दूध ५ लिटर` → १० पोका (५००ml), `आधा लिटर` → १, `डेढ लिटर` → ३, `दही २ लिटर` → खुल्ला दहि २।
- यस्ता Entry सधैँ **पहेँलो (अनिश्चित)** हुन्छन् र तल "५ लिटर → १० पोका (५००ml)" देखिन्छ; Customerले साँच्चै ५ पोका नै भनेको भए "५ नै पोका" थिच्नुहोस् — यो Customerको लागि सम्झन पनि सोधिन्छ।
- `दुई पोका`, `पाँच थैली` जस्ता अक्षरमा लेखेका संख्या पनि चिनिन्छन्।
- यो सुविधा सधैँ स्वचालित चल्छ (अन/अफ छैन)।

## 💾 स्वचालित Backup (नयाँ)
- **Settings → Backup** मा "स्वचालित Backup" कार्ड। एक पटक पासफ्रेज राखेपछि हरेक दिन (र डाटा बदलिएको भए एप छोड्दा) आफैँ Backup बन्छ।
- फाइल: `gkhata-YYYY-MM-DD.gkb` = gzip (सानो) + AES-GCM इन्क्रिप्ट। पासफ्रेजबिना कसैले पढ्न सक्दैन — **पासफ्रेज हरायो भने फर्काउन सकिँदैन**।
- **फोनमा:** `Documents/GKhata-Backup/` (Files एपमा देखिन्छ)। Android 10+ मा एप uninstall गर्दा पनि रहन्छ; Android 7–9 मा एपको आफ्नै फोल्डरमा बस्छ र uninstall सँगै जान्छ।
- **Google Drive:** Website/API मा Apps Script URL राखिएको भए त्यही मार्फत तपाईंको Drive को `GKhata-Backup/` मा पनि जान्छ। Apps Script मा `tools/apps-script-backup.gs` थप्नुपर्छ (फाइलको माथि ३ चरण लेखिएको छ)।
- पुराना आफैँ हट्छन्: ७ पछिल्ला दैनिक + पछिल्ला १२ महिनाको हरेक महिनाको अन्तिम।
- फर्काउने: Backup पेजमा फाइलको "फर्काउने" थिच्ने, वा Drive बाट डाउनलोड गरेको `.gkb` छान्ने।
- CSP मा `script.google.com` र `script.googleusercontent.com` थपिएको छ (Google Sheets Sync पनि यसबिना रोकिन्थ्यो)।

## 💾 स्वचालित Backup (नोट)
- फोन: `Documents/GKhata-Backup/gkhata-YYYY-MM-DD.gkb` (Android 10+, uninstall गर्दा रहन्छ तर फेरि install पछि एपले देख्न नसक्न सक्छ — "File बाट" बटनले Files एपबाट छान्नुहोस्)। एउटै नाम पहिले नै भए `gkhata-YYYY-MM-DD-HHmmss.gkb` बन्छ।
- Google Drive: `GKhata-Backup` फोल्डर; "Wi-Fi मात्र" विकल्प छ।
- एप बन्द हुँदा Backup हुँदैन — एप खुला/छोड्दा मात्र।

## ♻️ Backup फर्काउने (स्वचालित प्रस्ताव)
- एप खाली हुँदा (नयाँ install): फोनमा Backup भेटिए → "फर्काउने?"; नभए Google Drive (URL+key राखेको भए) मा खोज्छ; नभए File छान्ने / Drive को URL+key राख्ने बक्स आउँछ।
- Drive बाट फर्काउन `tools/apps-script-backup.gs` को नयाँ कोड paste गर्नुहोस् र `doPost` को लाइन `if (String(data.type).indexOf('backup') === 0) return gkBackup_(data);` बनाई **New version** Deploy गर्नुहोस्।

## 🔀 एपमा Data भएको बेला Backup फर्काउँदा
- "मिलाउने" (सिफारिस): हालको Data जस्ताको तस्तै; Backup मा भएका तर यहाँ नभएका Entry/Customer/वस्तु मात्र थपिन्छन् (ID अनुसार)। नाम+फोन मिल्ने Customer दोहोरो बन्दैन। Entry को Backup संस्करणमा बढी Edit इतिहास भए त्यही राखिन्छ। रेट/सेटिङ/User हालकै रहन्छ।
- "सबै बदल्ने": पहिले हालको Data को सुरक्षित प्रति `gkhata-YYYY-MM-DD-HHmmss.gkb` (पासफ्रेज राखेको भए) बनाएर मात्र बदल्छ।

## 📦 लोड योजना → Dispatch (स्वचालित, पुष्टि बाँकी)
- लोड योजना पक्का गर्दा (विक्रेता) बजारको Dispatch मा "लोड योजनाबाट — Dispatch" Entry आफैँ बन्छ, पहेँलो रेखा र "पुष्टि बाँकी" सहित। परिमाण = वास्तविक (भरिएको भए) नत्र योजना, अरू Dispatch Entry घटाएर।
- Dispatch पेजमा "लोड योजना · कति लैजाने" कार्ड: वस्तुअनुसार योजना | Dispatch | फरक र जम्मा; बाँकी भए "योजनाबाट बाँकी Dispatch भर्ने" बटन।
- Dispatch गर्नेले "✓ पुष्टि गर्नुहोस्" थिचेपछि सामान्य Entry बन्छ (नथिचेसम्म योजना बदलिए परिमाण आफैँ अपडेट हुन्छ)। पुष्टि भएपछि योजना बदलिए पनि छोइँदैन।
- बन्द गर्न: लोड योजना पेजको "योजना पक्का गर्दा Dispatch मा आफैँ भर्ने" चेकबक्स।
