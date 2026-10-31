# Board Coach Mod — Android (native overlay, di atas aplikasi chess.com)

Versi **native Android** dari extension `board-coach-kiwi`. Bukan lewat browser lagi:
ini **APK mod menu** yang berjalan *bersamaan* dengan aplikasi resmi chess.com —
menampilkan floating panel, membaca papan dari layar, menjalankan Stockfish lokal,
menggambar panah saran, dan (opsional) memainkan langkah otomatis.

> Filosofi: tanpa root, tanpa repack APK chess.com, tanpa menyentuh binary mereka.
> Semuanya lewat 3 izin standar Android (overlay, aksesibilitas, capture layar).

## Apa yang bisa

| Fitur | Status |
|---|---|
| Panel floating: **drag** dari header, **minimize ke bubble ♞**, **3 ukuran (⤢)**, tombol stop (✕) | v1.1 |
| Scan + engine di **worker thread** (UI tidak freeze) | v1.1 |
| **Indikator best move** live + warna panah sesuai evaluasi | v1.1 |
| **Statistik**: jumlah langkah, akurasi %, hitungan per kategori | v1.1 |
| **Kalibrasi manual papan** (tap 2 pojok) — panah andal tanpa auto-detect | v1.1 |
| Klasifikasi langkah (diff FEN + eval engine) | v1.1 |
| Engine Stockfish asm.js di WebView tersembunyi (worker) | v1.0 |
| Auto-move via Accessibility gesture tap | v1.0 |
| Crash logger + tombol kirim log | v1.0.2 |

## Izin

1. **Draw over other apps** — panel + panah
2. **Aksesibilitas** — tap otomatis untuk auto-move (+ deteksi window)
3. **MediaProjection** — baca papan (diminta sekali tiap sesi)

## Troubleshooting

### "Aplikasi tidak diberi akses" / "Dikontrol oleh Setelan Terbatas" (Android 13+)

App yang di-install dari luar Play Store (sideload) **diblokir** dari izin
Aksesibilitas — ini proteksi OS, bukan bug. Buka blokirnya:

1. **Setelan → Aplikasi → Board Coach Mod**
2. Tap **⋮** (titik tiga, kanan atas)
3. Pilih **"Izinkan setelan terbatas"** (Allow restricted settings)
4. Balik ke **Setelan → Aksesibilitas** → aktifkan **Board Coach - baca papan**

Alternatif cepat: di dalam app tap **"2. Aktifkan Aksesibilitas"** → tombol
**Info app** → lakukan langkah 2–4 di atas.

### Panel tidak muncul / tidak ada panah
- Pastikan izin **overlay** sudah aktif (Setelan → Aplikasi → Board Coach → Gambar di atas app lain).
- Restart mod menu (tombol **Stop mod**, lalu **3. Aktifkan mod menu**).

### Papan tidak terbaca
- Papan harus **terlihat penuh** di layar (jangan ketutup dialog/keyboard).
- Mode gelap chess.com lebih stabil daripada mode terang.


## Build

- `./gradlew :app:assembleDebug` (bootstrap download Gradle 8.7 kalau perlu)
- Atau: push ke GitHub → workflow `build-apk.yml` menghasilkan artifact `app-debug.apk`
- SDK minimum API 26 (Android 8.0). Engine: `app/src/main/assets/stockfish.js`
  (salinan dari extension; dijalankan di WebView via blob worker).

## Catatan teknis

- **BoardReader** mendeteksi area papan dari pola warna petak terang/gelap, lalu membaca
  FEN dari bitmap. Pendekatan screen-scraping — bekerja tanpa reverse-engineering APK.
- **EngineClient** memuat `stockfish.js` di WebView tersembunyi dan berkomunikasi lewat
  `evaluateJavascript` + `@JavascriptInterface` (UCI pipeline sama seperti di browser).
- **ELO** diterapkan lewat `setoption Skill Level` + `go movetime` (padanan `elo.js`).
- **Klasifikasi** = port `Classifier.kt` dari `classify.js`.

## Batas yang jujur

- Butuh **Android 11+** idealnya (MediaProjection per-sesi).
- Bergantung pada layout visual chess.com: papan harus terlihat penuh (tidak tertutup dialog).
- Auto-move eksperimental: tap koordinat bisa meleset saat scroll/rotate.
- Deteksi *jenis* bidak dari visual itu perkiraan; presisi penuh butuh kalibrasi manual.

## Legal

Untuk belajar dan bermain melawan komputer. Bantuan engine di game rated melanggar ToS
chess.com — risiko akun sepenuhnya di pemakai.
