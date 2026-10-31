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
| Floating mod menu (draggable, collapsible) | v1.0 |
| Engine Stockfish asm.js di WebView tersembunyi (worker) | v1.0 |
| Profil ELO 600–2800 (Skill Level + movetime) | v1.0 |
| Baca papan dari screenshot (MediaProjection) | v1.0 |
| Panah saran di atas layar | v1.0 |
| Auto-move via Accessibility gesture tap | v1.0 |
| Klasifikasi langkah (Brilliant…Blunder) — port `classify.js` | v1.0 |

## Izin

1. **Draw over other apps** — panel + panah
2. **Aksesibilitas** — tap otomatis untuk auto-move (+ deteksi window)
3. **MediaProjection** — baca papan (diminta sekali tiap sesi)

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
