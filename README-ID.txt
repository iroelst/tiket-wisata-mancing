PROJECT ANDROID - TIKET WISATA MANCING

Target:
- Android 14/15 (minSdk 23, targetSdk 35)
- Printer Bluetooth Classic SPP (RPP02N)
- Kertas thermal 58 mm
- WebView memuat HTML lokal dari app/src/main/assets/index.html
- Cetak tiket satuan dengan nomor dan QR Code unik
- GitHub Actions untuk membangun APK tanpa memasang Android Studio di komputer

CARA MEMBUAT REPOSITORY GITHUB
1. Masuk ke https://github.com/new
2. Repository name: tiket-wisata-mancing
3. Pilih Private atau Public sesuai kebutuhan. Public tidak wajib.
4. Jangan centang Add a README, .gitignore, atau license (project ini sudah punya file).
5. Klik Create repository.
6. Upload seluruh ISI folder project ini ke repository, termasuk folder tersembunyi .github/workflows.
   Struktur .github/workflows/build-apk.yml harus tetap sama.
7. Pastikan branch utama bernama main.

CARA BUILD APK DENGAN GITHUB ACTIONS
1. Buka tab Actions pada repository.
2. Jika diminta, klik I understand my workflows, go ahead and enable them.
3. Pilih workflow "Build Tiket Mancing APK".
4. Klik Run workflow > Run workflow.
5. Tunggu sampai proses selesai dengan tanda centang hijau.
6. Buka hasil workflow yang selesai, gulir ke bagian Artifacts.
7. Download artifact "tiket-mancing-debug-apk". Ekstrak ZIP hasil download untuk mendapatkan app-debug.apk.
8. Kirim APK ke HP, izinkan instalasi dari sumber tersebut, lalu instal.

PENGUJIAN PRINTER
1. Nyalakan RPP02N.
2. Di HP, buka Pengaturan > Bluetooth dan pasangkan RPP02N. Jika diminta PIN, gunakan PIN yang tertera pada manual/label printer.
3. Buka aplikasi Tiket Wisata Mancing.
4. Buka menu Printer > Pilih / sambungkan RPP02N.
5. Pilih perangkat RPP02N yang sudah dipasangkan.
6. Buat transaksi uji, lalu tekan Cetak Tiket.

FITUR YANG SUDAH ADA DI SOURCE
- Android WebView untuk menjalankan HTML lokal.
- Bluetooth Classic RFCOMM/SPP dengan UUID standar 00001101-0000-1000-8000-00805F9B34FB.
- Dialog memilih perangkat Bluetooth yang sudah dipasangkan.
- Cetak teks, QR Code (ZXing), dan raster ESC/POS untuk setiap tiket.
- Workflow GitHub Actions untuk menghasilkan debug APK.

BATASAN / CATATAN PENTING
- Workflow ini menghasilkan DEBUG APK untuk uji coba, bukan APK rilis Play Store.
- APK belum dibangun dan belum diuji pada unit RPP02N fisik. Keberhasilan koneksi tergantung varian/firmware printer dan kompatibilitas perintah ESC/POS-nya.
- Printer harus dipasangkan terlebih dahulu melalui pengaturan Bluetooth Android.
- Firebase belum dikonfigurasi. Saat ini transaksi HTML tersimpan lokal pada perangkat. Firebase Auth/Firestore dan role Admin/Petugas adalah tahap berikutnya.
- QR pada cetak native dibuat dari nomor tiket di dalam Android, sehingga pencetakan native tidak bergantung pada internet.
- Data pengaturan nama tempat, alamat, footer diteruskan dari HTML saat cetak; header fallback adalah WISATA MANCING.
- Untuk menghindari duplikasi tiket saat uji coba, gunakan transaksi percobaan terlebih dahulu.
