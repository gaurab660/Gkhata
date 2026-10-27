/**
 * GKhata — Google Drive Backup receiver (Google Apps Script)
 * ----------------------------------------------------------
 * तपाईंको अहिलेको Apps Script project (Sheets पठाउने) मा यो कोड थप्नुहोस्:
 *
 *  1. यो फाइलको सबै कोड अहिलेको project मा नयाँ फाइल (backup.gs) बनाएर paste गर्नुहोस्।
 *  2. अहिलेको doPost(e) मा, secret जाँचेको तुरुन्तै पछि (Sheet मा लेख्नु अघि) यी २ लाइन थप्नुहोस्:
 *
 *       if (String(data.type).indexOf('backup') === 0) return gkBackup_(data);   // backup, backup-ping, backup-list, backup-get
 *
 *     (नयाँ: 'backup-list' र 'backup-get' (Drive बाट फर्काउन) पनि यही लाइनले समेट्छ — पहिले लेखेको लाइन छ भने यसैले बदल्नुहोस्।)
 *     (data = JSON.parse(e.postData.contents) भएको चरको नाम तपाईंको कोडमा जे छ, त्यही लेख्नुहोस्।)
 *  3. Deploy > Manage deployments > ✏ > Version: "New version" > Deploy.
 *     पहिलो पटक Google ले Drive को अनुमति माग्छ — Allow गर्नुहोस्। (Run गरेर Authorize गर्नुपर्‍यो भने
 *     माथिको मेनुबाट gkAuthorize चलाउनुहोस्।)
 *  4. Web app URL उही रहन्छ — एपमा केही बदल्न पर्दैन।
 *
 * Drive मा "GKhata-Backup" फोल्डर बन्छ। फाइलहरू इन्क्रिप्टेड छन् — पासफ्रेजबिना कसैले पढ्न सक्दैन।
 */
var GK_FOLDER = 'GKhata-Backup';
var GK_NAME_RX = /^gkhata-\d{4}-\d{2}-\d{2}(-\d{6})?\.gkb$/;
var GK_CHUNK = 3000000; // एपको CH सँग उही (४ ले भाग जाने)

function gkAuthorize() { DriveApp.getRootFolder(); LockService.getScriptLock(); }

function gkOut_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function gkFolder_(name, parent) {
  var it = parent ? parent.getFoldersByName(name) : DriveApp.getFoldersByName(name);
  if (it.hasNext()) return it.next();
  return parent ? parent.createFolder(name) : DriveApp.createFolder(name);
}

function gkTrashByName_(folder, name) {
  var it = folder.getFilesByName(name);
  while (it.hasNext()) it.next().setTrashed(true);
}

function gkPad_(n) { return ('000' + n).slice(-4); }

// ७ पछिल्ला दैनिक + पछिल्ला १२ महिनाको हरेक महिनाको अन्तिम राख्ने (एपको नियमसँग उस्तै)
function gkRotate_(root) {
  var names = [], it = root.getFiles();
  while (it.hasNext()) { var n = it.next().getName(); if (GK_NAME_RX.test(n)) names.push(n); }
  names.sort().reverse();
  var keep = {}, seen = {}, seenCount = 0, i;
  for (i = 0; i < names.length && i < 7; i++) keep[names[i]] = 1;
  for (i = 0; i < names.length; i++) {
    var m = names[i].slice(7, 14);
    if (!seen[m]) { seen[m] = 1; seenCount++; if (seenCount <= 12) keep[names[i]] = 1; }
  }
  for (i = 0; i < names.length; i++) if (!keep[names[i]]) gkTrashByName_(root, names[i]);
}

/** d = JSON.parse(e.postData.contents); secret पहिले नै जाँचिसकेको हुनुपर्छ */
function gkBackup_(d) {
  if (d.type === 'backup-ping') return gkOut_({ ok: true, drive: true });
  if (d.type === 'backup-list') return gkList_();
  if (d.type === 'backup-get') return gkGet_(d);
  var name = String(d.name || ''), part = Number(d.part), parts = Number(d.parts), b64 = String(d.b64 || '');
  if (!GK_NAME_RX.test(name)) return gkOut_({ ok: false, err: 'bad name' });
  if (!(parts >= 1 && parts <= 200 && part >= 0 && part < parts && part % 1 === 0 && parts % 1 === 0))
    return gkOut_({ ok: false, err: 'bad part' });
  if (!b64) return gkOut_({ ok: false, err: 'empty' });

  var lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    var root = gkFolder_(GK_FOLDER, null), tmp = gkFolder_('_parts', root);
    var pn = name + '.p' + gkPad_(part);
    gkTrashByName_(tmp, pn);
    tmp.createFile(pn, b64, 'text/plain');
    if (part < parts - 1) return gkOut_({ ok: true, drive: true, part: part });

    var s = '';
    for (var i = 0; i < parts; i++) {
      var f = tmp.getFilesByName(name + '.p' + gkPad_(i));
      if (!f.hasNext()) return gkOut_({ ok: false, err: 'missing part ' + i });
      s += f.next().getBlob().getDataAsString();
    }
    var bytes = Utilities.base64Decode(s);
    gkTrashByName_(root, name);
    root.createFile(Utilities.newBlob(bytes, 'application/octet-stream', name));
    for (var j = 0; j < parts; j++) gkTrashByName_(tmp, name + '.p' + gkPad_(j));
    gkRotate_(root);
    return gkOut_({ ok: true, drive: true, name: name, bytes: bytes.length });
  } catch (err) {
    return gkOut_({ ok: false, err: String(err).slice(0, 120) });
  } finally {
    lock.releaseLock();
  }
}

/** Drive को GKhata-Backup भित्रका .gkb फाइलको सूची (नाम + साइज) */
function gkList_() {
  try {
    var it = DriveApp.getFoldersByName(GK_FOLDER);
    if (!it.hasNext()) return gkOut_({ ok: true, drive: true, files: [] });
    var fs = it.next().getFiles(), out = [];
    while (fs.hasNext()) {
      var f = fs.next(), n = f.getName();
      if (GK_NAME_RX.test(n) && !f.isTrashed()) out.push({ name: n, size: f.getSize() });
    }
    out.sort(function (a, b) { return a.name < b.name ? 1 : -1; });
    return gkOut_({ ok: true, drive: true, files: out.slice(0, 30) });
  } catch (err) {
    return gkOut_({ ok: false, err: String(err).slice(0, 120) });
  }
}

/** एउटा फाइल base64 टुक्रामा फर्काउने: d = {name, part} -> {b64, part, parts} */
function gkGet_(d) {
  var name = String(d.name || ''), part = Number(d.part);
  if (!GK_NAME_RX.test(name)) return gkOut_({ ok: false, err: 'bad name' });
  if (!(part >= 0 && part % 1 === 0)) return gkOut_({ ok: false, err: 'bad part' });
  try {
    var it = DriveApp.getFoldersByName(GK_FOLDER);
    if (!it.hasNext()) return gkOut_({ ok: false, err: 'no folder' });
    var fs = it.next().getFilesByName(name);
    if (!fs.hasNext()) return gkOut_({ ok: false, err: 'not found' });
    var b64 = Utilities.base64Encode(fs.next().getBlob().getBytes());
    var parts = Math.max(1, Math.ceil(b64.length / GK_CHUNK));
    if (part >= parts) return gkOut_({ ok: false, err: 'bad part' });
    return gkOut_({ ok: true, drive: true, part: part, parts: parts, b64: b64.slice(part * GK_CHUNK, (part + 1) * GK_CHUNK) });
  } catch (err) {
    return gkOut_({ ok: false, err: String(err).slice(0, 120) });
  }
}
