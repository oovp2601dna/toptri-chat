# 🏪 Indomaret Point — Toptri Chat (Desktop)

Aplikasi chat real-time **Buyer–Seller** berbasis **JavaFX** dan **Firebase Firestore**, dirancang khusus untuk pemesanan produk Indomaret Point. Buyer mengirim request, AI langsung membalas dengan kategori produk, dan Seller menerima pesanan secara real-time.

---

## ✨ Fitur

### 👤 Buyer Window
- Kirim request pesanan via chat
- **AI Auto-Reply** langsung menyambut buyer dengan greeting + video review toko
- Pilih kategori produk: 😋 Yummy Choice · 🥐 Bakery · ☕ Coffee
- Widget menu interaktif per kategori:
  - **Yummy Choice**: Bapao (Rp8.000), Dimsum (Rp7.000), Sosis (Rp10.000)
  - **Bakery**: Roti Asin (Rp5.000), Roti Manis (Rp6.000), Roti Daging (Rp8.000)
  - **Coffee**: Latte (Rp15.000), Americano (Rp12.000), Squash (Rp10.000)
- Form pengiriman (nama, HP, alamat)
- **Peta Leaflet/OpenStreetMap** — tampil lokasi Indomaret terdekat dari alamat buyer
  - Geocoding via Google Geocoding API (jika ada key) atau Nominatim OSM (fallback)
  - Pencarian Indomaret via Google Places API atau Overpass API (fallback)
- Pilih Indomaret → pesanan langsung dikonfirmasi
- Sidebar riwayat semua request
- Toast notifikasi saat pesanan selesai
- Dapat membuka banyak request sekaligus

### 🧑‍🍳 Seller Window
- Inbox real-time semua request buyer (status OPEN)
- Toast notifikasi animasi slide-in saat request baru masuk
- Tampilan chat dengan bubble buyer/seller/AI
- Badge qty pesanan otomatis (contoh: 🛒 2× nasi · 3× es teh)
- Kirim offer via:
  - **Klik menu** dari daftar menu Firestore
  - **Ketik manual** (nama + harga + vendor + kontak)
  - **Tambah menu baru** ke Firestore + kirim offer sekaligus
- Maksimal 3 offer per request
- Multi-seller support (Seller A, Seller B, dst.)

### 🤖 AI Auto-Reply (Ollama)
- Menggunakan **Ollama** lokal dengan model **llama3.2**
- Greeting otomatis saat buyer mengirim pesan pertama
- Fallback statis jika Ollama tidak berjalan (app tetap jalan normal)
- Mengirim notifikasi ke seller saat buyer memilih kategori

### 🔥 Firestore Backend
| Collection | Isi |
|---|---|
| `requests` | Semua request buyer (status, buyerId, text, dll.) |
| `requests/{id}/messages` | Pesan chat buyer, seller, dan AI |
| `requests/{id}/offers` | Offer dari seller (multi-item lines + grandTotal) |
| `menus` | Daftar menu seller (nama, harga, vendor, kategori, rating) |

---

## 🛠️ Tech Stack

| | |
|---|---|
| **Language** | Java 21 |
| **GUI** | JavaFX |
| **Database** | Firebase Firestore (NoSQL, real-time) |
| **AI** | Ollama (local LLM, model: llama3.2) |
| **Maps** | Leaflet.js + OpenStreetMap / Google Maps |
| **Geocoding** | Google Geocoding API / Nominatim OSM |
| **Build** | Maven |

---

## 📂 Struktur Project

```
toptri-chat/
│
├── src/main/java/com/toptri/desktop/
│   ├── ToptriDesktopLauncher.java   # Entry point, launcher window
│   ├── BuyerWindow.java             # UI & logika Buyer
│   ├── SellerWindow.java            # UI & logika Seller
│   ├── FirestoreService.java        # Semua operasi Firestore
│   ├── AiAutoReply.java             # AI reply, geocoding, peta
│   └── UiKit.java                   # Komponen UI & design tokens
│
├── src/main/resources/
│   ├── firebase-service-account.json  # ⚠️ Tidak di-upload (ada di .gitignore)
│   └── application.properties
│
├── pom.xml
└── README.md
```

---

## 🚀 Cara Menjalankan

### 1. Clone project
```bash
git clone https://github.com/oovp2601dna/toptri-chat.git
cd toptri-chat
```

### 2. Tambahkan Firebase Service Account
Letakkan file key Firebase di:
```
src/main/resources/firebase-service-account.json
```
Download dari: **Firebase Console → Project Settings → Service Accounts → Generate new private key**

### 3. (Opsional) Setup Ollama untuk AI
```bash
# Install Ollama: https://ollama.com
ollama pull llama3.2
ollama serve
```
> Jika Ollama tidak dijalankan, app tetap berjalan dengan greeting fallback statis.

### 4. (Opsional) Google Maps API Key
Isi di `AiAutoReply.java`:
```java
public static String GOOGLE_API_KEY = "ISI_API_KEY_DISINI";
```
Tanpa key, peta tetap tampil menggunakan OpenStreetMap + Overpass API (gratis).

### 5. Jalankan app
```bash
mvn javafx:run
```

---

## 📖 Cara Penggunaan

### Buyer
1. Klik **Open Buyer** di launcher
2. Ketik pesanan di kolom chat → klik **Kirim**
3. AI akan menyambut dan menampilkan pilihan kategori
4. Pilih kategori → pilih item menu
5. Isi form pengiriman (nama, HP, alamat)
6. Pilih Indomaret terdekat dari peta → pesanan dikonfirmasi ✅

### Seller
1. Klik **Open Seller A** atau **Open Seller B** di launcher
2. Request buyer muncul otomatis di inbox kiri
3. Klik request → lihat percakapan di tengah
4. Kirim offer via klik menu (kanan) atau ketik manual
5. Maksimal 3 offer per request

---

## ⚙️ Konfigurasi

| File | Yang perlu diubah |
|---|---|
| `AiAutoReply.java` | `GOOGLE_API_KEY`, `STORE_VIDEO_URL`, daftar menu, daftar lokasi Indomaret fallback |
| `AiAutoReply.java` | `ollamaModel` (default: `llama3.2`) |
| `firebase-service-account.json` | Kredensial Firebase project kamu |
