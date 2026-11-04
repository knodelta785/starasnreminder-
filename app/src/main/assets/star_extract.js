/*
 * Pembaca presensi STAR ASN. Dijalankan di dalam WebView (lihat StarSync.kt) pada halaman dashboard
 * yang sudah login. Hanya MEMBACA teks halaman; tidak menekan tombol apa pun.
 *
 * Hasil per jenis (in = masuk, out = pulang):
 *   state 'time'    -> jam presensi aktual hari ini ditemukan (field time = HH:MM:SS)
 *   state 'empty'   -> halaman menunjukkan presensi itu BELUM dilakukan
 *   state 'unknown' -> tidak dapat dipastikan (status lokal tidak diubah)
 *
 * File ini juga diuji dengan node (module.exports di bagian bawah).
 */
var STAR_LABEL = {
  in: /(masuk|datang|check[\s-]?in|clock[\s-]?in)/i,
  out: /(pulang|keluar|check[\s-]?out|clock[\s-]?out)/i
};
var STAR_BAD = /(jadwal|shift|batas|ketentuan|seharusnya|jam\s*kerja|target|rencana|toleransi|terlambat\s*jika)/i;
var STAR_GOOD = /(aktual|realisasi)/i;
var STAR_CTX = /(presensi|absen|kehadiran|check|clock|datang)/i;
var STAR_EMPTY = /(belum|--\s*:\s*--|tidak\s*ada|kosong|n\/a|[:\s][-–—]\s*$)/i;
var STAR_TIME = /(?<![\d:.])([01]?\d|2[0-3])[:.]([0-5]\d)(?::([0-5]\d))?(?![\d]|[:.]\d)/g;
var STAR_TIME_ONE = /(?<![\d:.])([01]?\d|2[0-3])[:.]([0-5]\d)(?::([0-5]\d))?(?![\d]|[:.]\d)/;
var STAR_MONTHS = {
  januari: 1, jan: 1, january: 1, februari: 2, feb: 2, pebruari: 2, february: 2,
  maret: 3, mar: 3, march: 3, april: 4, apr: 4, mei: 5, may: 5, juni: 6, jun: 6, june: 6,
  juli: 7, jul: 7, july: 7, agustus: 8, agu: 8, agt: 8, aug: 8, august: 8,
  september: 9, sep: 9, sept: 9, oktober: 10, okt: 10, oct: 10, october: 10,
  november: 11, nov: 11, desember: 12, des: 12, dec: 12, december: 12
};

function starPad(n) { return (n < 10 ? '0' : '') + n; }

function starKey(y, m, d) {
  if (m < 1 || m > 12 || d < 1 || d > 31) return null;
  return y + '-' + starPad(m) + '-' + starPad(d);
}

/** Semua tanggal pada teks, dalam bentuk yyyy-mm-dd. */
function starDates(s) {
  var out = [], m, re;
  re = /(\d{1,2})[\/\-.](\d{1,2})[\/\-.](\d{4})/g;
  while ((m = re.exec(s))) { var k = starKey(+m[3], +m[2], +m[1]); if (k) out.push(k); }
  re = /(\d{4})-(\d{1,2})-(\d{1,2})/g;
  while ((m = re.exec(s))) { var k2 = starKey(+m[1], +m[2], +m[3]); if (k2) out.push(k2); }
  re = /(\d{1,2})\s+([A-Za-z]{3,10})\.?\s+(\d{4})/g;
  while ((m = re.exec(s))) {
    var mo = STAR_MONTHS[m[2].toLowerCase()];
    if (mo) { var k3 = starKey(+m[3], mo, +m[1]); if (k3) out.push(k3); }
  }
  return out;
}

function starTimes(s) {
  var out = [], m;
  STAR_TIME.lastIndex = 0;
  while ((m = STAR_TIME.exec(s))) {
    out.push(starPad(+m[1]) + ':' + m[2] + ':' + (m[3] || '00'));
  }
  return out;
}

function starHas(s, type) { return STAR_LABEL[type].test(s); }

/** Potongan baris yang dimulai dari label `type` sampai label jenis lain (jika satu baris memuat keduanya). */
function starSegment(line, type) {
  var other = type === 'in' ? 'out' : 'in';
  var a = line.search(STAR_LABEL[type]);
  if (a < 0) return null;
  var rest = line.slice(a + 1);
  var b = rest.search(STAR_LABEL[other]);
  return b < 0 ? line.slice(a) : line.slice(a, a + 1 + b);
}

function starFindType(lines, type, today) {
  var best = null;
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i];
    if (!starHas(line, type)) continue;
    var seg = starSegment(line, type);
    var at = line.search(STAR_LABEL[type]);
    var pre = line.slice(Math.max(0, at - 14), at);
    var prev = i > 0 ? lines[i - 1] : '';
    var own = pre + seg;
    var strong = STAR_CTX.test(own) || STAR_GOOD.test(own) || STAR_CTX.test(prev);

    // Jendela: potongan baris ini + baris berikutnya (sampai label lain / baris jadwal).
    var win = [seg];
    var span = strong ? 2 : 1;
    for (var j = 1; j <= span && i + j < lines.length; j++) {
      var nx = lines[i + j];
      if (starHas(nx, 'in') || starHas(nx, 'out')) break;
      if (STAR_BAD.test(nx)) break;   // baris jadwal / jam kerja bukan presensi aktual
      win.push(nx);
    }
    var text = win.join(' ');
    var score = 0;
    if (STAR_GOOD.test(text) || STAR_GOOD.test(prev)) score += 2;
    if (STAR_CTX.test(text) || STAR_CTX.test(prev)) score += 1;
    if (STAR_BAD.test(pre + seg) || (STAR_BAD.test(prev) && !STAR_CTX.test(own))) score -= 5;
    if (score < 0) continue;

    var dates = starDates(text);
    if (dates.length) {
      if (dates.indexOf(today) >= 0) score += 3;
      else continue; // baris milik tanggal lain
    }
    var times = starTimes(text);
    var cand = null;
    // Jika kata "belum"/"--:--" muncul SEBELUM jam pertama, presensi itu belum dilakukan.
    var emptyAt = text.search(STAR_EMPTY);
    var timeAt = times.length ? text.search(STAR_TIME_ONE) : -1;
    if (times.length && !(emptyAt >= 0 && emptyAt < timeAt)) cand = { state: 'time', time: times[0], score: score, line: i };
    else if (STAR_EMPTY.test(text)) cand = { state: 'empty', score: score, line: i };
    if (!cand) continue;
    // 'time' lebih kuat dari 'empty'; lalu skor; lalu yang pertama.
    if (!best ||
        (cand.state === 'time' && best.state !== 'time') ||
        (cand.state === best.state && cand.score > best.score)) {
      best = cand;
    }
  }
  return best ? { state: best.state, time: best.time || null } : { state: 'unknown', time: null };
}

/** Tabel riwayat: baris judul memuat kedua label tanpa jam, lalu baris bertanggal hari ini berisi jam-jamnya. */
function starFindTable(lines, today) {
  for (var i = 0; i < lines.length; i++) {
    var h = lines[i];
    if (!(starHas(h, 'in') && starHas(h, 'out')) || starTimes(h).length) continue;
    var order = h.search(STAR_LABEL.in) <= h.search(STAR_LABEL.out) ? ['in', 'out'] : ['out', 'in'];
    for (var j = i + 1; j < Math.min(lines.length, i + 8); j++) {
      var row = lines[j] + ' ' + (lines[j + 1] || '');
      if (starDates(lines[j]).indexOf(today) < 0) continue;
      var t = starTimes(lines[j] + ' ' + (starDates(lines[j + 1] || '').length ? '' : (lines[j + 1] || '')));
      var res = { in: { state: 'unknown', time: null }, out: { state: 'unknown', time: null } };
      if (t.length >= 2) { res[order[0]] = { state: 'time', time: t[0] }; res[order[1]] = { state: 'time', time: t[1] }; }
      else if (t.length === 1) { res[order[0]] = { state: 'time', time: t[0] }; res[order[1]] = { state: 'empty', time: null }; }
      else continue;
      return res;
    }
  }
  return null;
}

/** Fungsi murni: teks halaman + kunci tanggal hari ini (yyyy-mm-dd) -> hasil. */
function starParse(text, today) {
  var lines = String(text || '').split(/\r?\n/).map(function (s) { return s.replace(/\s+/g, ' ').trim(); })
    .filter(function (s) { return s.length > 0; });
  var table = starFindTable(lines, today);
  if (table) return table;
  return { in: starFindType(lines, 'in', today), out: starFindType(lines, 'out', today) };
}

/** Dijalankan di WebView. */
function starRun() {
  var d = new Date();
  var today = d.getFullYear() + '-' + starPad(d.getMonth() + 1) + '-' + starPad(d.getDate());
  var pw = [].slice.call(document.querySelectorAll('input[type=password]'))
    .some(function (e) { return e.offsetParent !== null; });
  var text = (document.body && document.body.innerText) || '';
  var res = { page: pw ? 'login' : (text.replace(/\s+/g, '').length < 40 ? 'wait' : 'page'),
              url: location.href, today: today, len: text.length };
  if (res.page === 'page') {
    var p = starParse(text, today);
    res.in = p.in;
    res.out = p.out;
  }
  res.dump = text.replace(/[ \t]+/g, ' ').replace(/\n{2,}/g, '\n').slice(0, 3000);
  return res;
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { starParse: starParse, starDates: starDates, starTimes: starTimes };
}
