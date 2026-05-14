package com.toptri.desktop;

import javafx.application.Platform;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * AiAutoReply — auto-balasan AI via Ollama local.
 *
 * Setup Ollama (wajib agar AI hidup, bukan fallback):
 *   1. Download & install: https://ollama.com
 *   2. Pull model: ollama pull llama3.2   (~2 GB)
 *   3. Jalankan: ollama serve             (atau otomatis jalan di background)
 *
 * Kalau Ollama tidak running → app tetap jalan pakai teks fallback statis.
 *
 * Semua public field/method/class di sini wajib ada karena direferensikan
 * langsung oleh BuyerWindow.java.
 */
public class AiAutoReply {

    // ── Ollama config ─────────────────────────────────────────────
    private static final String OLLAMA_URL  = "http://localhost:11434/api/generate";
    private static       String ollamaModel = "llama3.2"; // ganti kalau pakai model lain

    public static void setModel(String model) { ollamaModel = model; }
    public static String getModel()           { return ollamaModel; }

    /** Berapa lama tunggu seller sebelum AI auto-reply (ms). */
    public static final long SELLER_GRACE_MS = 0;

    // ── Video review toko ─────────────────────────────────────────
    /**
     * URL YouTube standar untuk video review toko.
     * Format: https://www.youtube.com/watch?v=VIDEO_ID
     */
    public static final String STORE_VIDEO_URL =
        "https://www.youtube.com/watch?v=tCNaFCXMrqs"; // ← SUDAH DIGANTI

    // ── Kategori makanan (FoodCategory) ──────────────────────────
    public static final List<FoodCategory> CATEGORIES = List.of(
        new FoodCategory("😋", "Yummy Choice", "yummy"),   // pilihan spesial
        new FoodCategory("🥐", "Bakery",       "bakery"),
        new FoodCategory("☕", "Coffee",        "coffee")
    );

    // ── Yummy Menu items ──────────────────────────────────────────
    /**
     * Daftar produk untuk widget "Yummy Choice".
     * Tambah / ganti sesuai produk toko kamu.
     */
    public static final List<YummyMenuItem> YUMMY_MENU = List.of(
        new YummyMenuItem("Bapao",   8_000, "Bapao lembut isi daging, hangat dan mengenyangkan"),
        new YummyMenuItem("Dimsum",  7_000, "Dimsum kukus lezat, cocok untuk camilan"),
        new YummyMenuItem("Sosis",  10_000, "Sosis premium juicy, digoreng crispy")
    );

    // ── Bakery Menu items ──────────────────────────
    /**
     * Daftar produk untuk widget "Bakery".
     */
    public static final List<YummyMenuItem> BAKERY_MENU = List.of(
        new YummyMenuItem("Roti Asin",   5_000, "Roti gurih asin lembut, cocok untuk sarapan"),
        new YummyMenuItem("Roti Manis",  6_000, "Roti manis dengan berbagai topping pilihan"),
        new YummyMenuItem("Roti Daging", 8_000, "Roti isi daging cincang berbumbu, mengenyangkan")
    );

    // ── Coffee Menu items ──────────────────────────
    /**
     * Daftar produk untuk widget "Coffee".
     */
    public static final List<YummyMenuItem> COFFEE_MENU = List.of(
        new YummyMenuItem("Latte",      15_000, "Espresso lembut berpadu susu creamy, ringan dan nikmat"),
        new YummyMenuItem("Americano",  12_000, "Espresso bold diencerkan air panas, rasa kopi murni"),
        new YummyMenuItem("Squash",     10_000, "Minuman segar buah-buahan dengan rasa manis asam")
    );

    public static final List<IndomaretLocation> INDOMARET_LOCATIONS = List.of(
        new IndomaretLocation(
            "Indomaret Jl. Sudirman No. 12",          // ← ganti nama toko
            "Jl. Jend. Sudirman No. 12, Jakarta Pusat", // ← ganti alamat
            -6.2088, 106.8456,                         // ← ganti lat, lng (dari Google Maps)
            5,                                          // ← estimasi menit jalan kaki
            "06:00 – 23:00",                           // ← jam buka
            4.7                                         // ← rating
        ),
        new IndomaretLocation(
            "Indomaret Jl. Thamrin No. 8",
            "Jl. M.H. Thamrin No. 8, Jakarta Pusat",
            -6.1954, 106.8230,
            8, "07:00 – 22:00", 4.5
        ),
        new IndomaretLocation(
            "Indomaret Jl. Gatot Subroto",
            "Jl. Gatot Subroto No. 55, Jakarta Selatan",
            -6.2297, 106.8198,
            12, "24 jam", 4.3
        )
    );

    // ── Scheduler (daemon thread) ─────────────────────────────────
    private static final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "toptri-ai-autoreply");
            t.setDaemon(true);
            return t;
        });

    /**
     * Tunggu SELLER_GRACE_MS ms → cek seller → kalau belum balas, kirim AI reply.
     *
     * @param requestId  ID request Firestore
     * @param buyerText  teks pesan buyer (untuk prompt Ollama)
     * @param fs         FirestoreService
     * @param onAiMsg    callback di JavaFX thread → terima AiReplyPayload untuk render bubble
     */
    public static void maybeAutoReply(
            String requestId,
            String buyerText,
            FirestoreService fs,
            Consumer<AiReplyPayload> onAiMsg) {

        scheduler.schedule(() -> {
            try {
                // 1. Cek seller sudah balas belum
                if (fs.hasRecentSellerMessage(requestId, SELLER_GRACE_MS + 1_000)) {
                    return; // seller aktif → skip AI
                }

                // 2. Gunakan greeting tetap Indomaret Point
                String greeting = "Selamat datang di Indomaret Point, selamat berbelanja! 🏪\n" +
                    "Tonton video review produk kami, lalu pilih kategori di bawah untuk mulai berbelanja.";

                // 3. Simpan ke Firestore (prefix [AUTO] biar seller tahu)
                try {
                    fs.sendSellerMessage(requestId, "AI_BOT", "[AUTO] " + greeting);
                } catch (Exception ignored) {}

                // 4. Kirim ke UI
                AiReplyPayload payload = new AiReplyPayload(greeting, CATEGORIES, STORE_VIDEO_URL);
                Platform.runLater(() -> onAiMsg.accept(payload));

            } catch (Exception ex) {
                // Fallback kalau Ollama mati / timeout
                String fallback =
                    "Selamat datang di Indomaret Point, selamat berbelanja! 🏪\n" +
                    "Tonton video review produk kami, lalu pilih kategori di bawah untuk mulai berbelanja.";
                try {
                    fs.sendSellerMessage(requestId, "AI_BOT", "[AUTO] " + fallback);
                } catch (Exception ignored) {}
                AiReplyPayload payload = new AiReplyPayload(fallback, CATEGORIES, STORE_VIDEO_URL);
                Platform.runLater(() -> onAiMsg.accept(payload));
            }

        }, SELLER_GRACE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Kirim pesan AI ke Firestore saat buyer memilih kategori.
     * Seller akan melihat pesan ini di SellerWindow.
     * Dipanggil dari background thread (sudah di new Thread di BuyerWindow).
     *
     * @param requestId  ID request
     * @param categoryKey key kategori (contoh: "nasi", "mie", "yummy")
     * @param fs          FirestoreService
     */
    public static void replyWithMenuSuggestion(
            String requestId, String categoryKey, FirestoreService fs) {
        try {
            String categoryLabel = CATEGORIES.stream()
                .filter(c -> c.key.equals(categoryKey))
                .map(c -> c.label)
                .findFirst()
                .orElse(categoryKey);

            String msg;
            if ("yummy".equals(categoryKey)) {
                msg = "Buyer tertarik dengan Yummy Choice 😋 Mohon siapkan menu spesial!";
            } else if ("bakery".equals(categoryKey)) {
                msg = "Buyer tertarik dengan produk Bakery 🥐 Mohon siapkan pilihan roti & pastry!";
            } else if ("coffee".equals(categoryKey)) {
                msg = "Buyer tertarik dengan Coffee ☕ Mohon siapkan pesanan minuman kopi!";
            } else {
                // Tanya Ollama untuk reply sugesti kategori
                String prompt =
                    "Kamu asisten toko makanan Toptri Bot. " +
                    "Buyer memilih kategori \"" + categoryLabel + "\". " +
                    "Balas 1 kalimat singkat ramah dalam bahasa Indonesia, " +
                    "konfirmasi pilihan dan minta tunggu seller menyiapkan offer. " +
                    "Jangan gunakan markdown.";
                try {
                    msg = callOllama(prompt);
                } catch (Exception e) {
                    msg = "Oke! Pesanan kategori " + categoryLabel + " sudah dicatat. Seller segera mempersiapkan offer untuk kamu 🍱";
                }
            }

            fs.sendSellerMessage(requestId, "AI_BOT", "[AUTO] " + msg);
        } catch (Exception ignored) {}
    }

    public static String GOOGLE_API_KEY = ""; // ISI API KEY DI SINI

    // ─────────────────────────────────────────────────────────────
    // GEOCODING — Google Geocoding API, fallback ke Nominatim OSM
    // ─────────────────────────────────────────────────────────────

    /**
     * Geocode alamat teks ke [lat, lng].
     * Prioritas: Google Geocoding API (jika GOOGLE_API_KEY terisi).
     * Fallback: Nominatim OSM (gratis, tanpa key).
     * Return null kalau kedua cara gagal.
     *
     * Query di-enrich otomatis dengan "Indonesia" kalau belum ada,
     * supaya alamat singkat seperti "Senayan City" atau "sbh" tetap ketemu.
     */
    public static double[] geocodeAddress(String address) {
    if (address == null || address.isBlank()) return null;

    // Enrich query: tambah "Indonesia" kalau belum ada
    String enriched = address.trim();
    String lc = enriched.toLowerCase();
    if (!lc.contains("indonesia") && !lc.contains("jakarta")
            && !lc.contains("bandung") && !lc.contains("surabaya")
            && !lc.contains("yogyakarta") && !lc.contains("bali")
            && !lc.contains("bekasi") && !lc.contains("depok")
            && !lc.contains("tangerang") && !lc.contains("bogor")) {
        enriched = enriched + ", Indonesia";
    }

    // Google Geocoding API (prioritas jika ada key)
    if (!GOOGLE_API_KEY.isBlank()) {
        try {
            String encoded = URLEncoder.encode(enriched, StandardCharsets.UTF_8);
            String urlStr  = "https://maps.googleapis.com/maps/api/geocode/json"
                           + "?address=" + encoded
                           + "&region=id"
                           + "&key=" + GOOGLE_API_KEY;

            URL url = new URL(urlStr);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(3_000);
            con.setReadTimeout(5_000);

            if (con.getResponseCode() == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                String body = sb.toString();
                int locIdx = body.indexOf("\"location\":");
                if (locIdx >= 0) {
                    int latIdx = body.indexOf("\"lat\":", locIdx);
                    int lngIdx = body.indexOf("\"lng\":", locIdx);
                    if (latIdx >= 0 && lngIdx >= 0) {
                        double lat = parseNextDouble(body, latIdx + 6);
                        double lng = parseNextDouble(body, lngIdx + 6);
                        if (lat != 0 || lng != 0) return new double[]{lat, lng};
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[GEO] Google API error: " + e.getMessage());
        }
    }

    // Fallback: Nominatim OSM — coba enriched dulu, lalu original, lalu query singkat
    String shortQuery = address.trim()
        .replaceAll("(?i)\\bno\\.?\\s*\\d+", "")   // hapus "No. 10"
        .replaceAll("\\s+", " ").trim();
    
    String[] candidates = enriched.equals(address.trim())
        ? new String[]{enriched, shortQuery}
        : new String[]{enriched, address.trim(), shortQuery};

    for (String q : candidates) {
        if (q == null || q.isBlank()) continue;
        try {
            String encoded = URLEncoder.encode(q, StandardCharsets.UTF_8);
            String urlStr  = "https://nominatim.openstreetmap.org/search?q=" + encoded
                           + "&format=json&limit=1&countrycodes=id&accept-language=id";
            URL url = new URL(urlStr);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty("User-Agent", "ToptriDesktopApp/1.0 (toptri@example.com)");
            con.setConnectTimeout(3_000);
            con.setReadTimeout(5_000);

            int httpCode = con.getResponseCode();
            if (httpCode == 429) {
                // Rate limited — tunggu sebentar lalu skip
                Thread.sleep(1_500);
                continue;
            }
            if (httpCode != 200) {
                System.err.println("[GEO] Nominatim HTTP " + httpCode + " for: " + q);
                continue;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }

            String body = sb.toString().trim();
            System.out.println("[GEO] Nominatim query: " + q + " → " + body.substring(0, Math.min(120, body.length())));

            if (body.equals("[]") || body.isBlank()) continue;

            int latIdx = body.indexOf("\"lat\":\"");
            int lonIdx = body.indexOf("\"lon\":\"");
            if (latIdx < 0 || lonIdx < 0) continue;

            String latStr = body.substring(latIdx + 7, body.indexOf("\"", latIdx + 7));
            String lonStr = body.substring(lonIdx + 7, body.indexOf("\"", lonIdx + 7));

            double lat = Double.parseDouble(latStr);
            double lon = Double.parseDouble(lonStr);
            if (lat != 0 || lon != 0) return new double[]{lat, lon};

        } catch (Exception e) {
            System.err.println("[GEO] Nominatim error for \"" + q + "\": " + e.getMessage());
        }
    }

    // ── Fallback terakhir: koordinat kota berdasarkan keyword di alamat ──
    // Supaya flow tidak buntu total hanya karena Nominatim lambat/down
    return guessCityCoords(lc);
}

/** Koordinat tengah kota sebagai fallback terakhir. */
private static double[] guessCityCoords(String lcAddress) {
    if (lcAddress.contains("jakarta selatan"))  return new double[]{-6.2615, 106.8106};
    if (lcAddress.contains("jakarta pusat"))    return new double[]{-6.1862, 106.8346};
    if (lcAddress.contains("jakarta barat"))    return new double[]{-6.1684, 106.7592};
    if (lcAddress.contains("jakarta timur"))    return new double[]{-6.2251, 106.9004};
    if (lcAddress.contains("jakarta utara"))    return new double[]{-6.1382, 106.8688};
    if (lcAddress.contains("jakarta"))          return new double[]{-6.2088, 106.8456};
    if (lcAddress.contains("bekasi"))           return new double[]{-6.2383, 106.9756};
    if (lcAddress.contains("depok"))            return new double[]{-6.4025, 106.7942};
    if (lcAddress.contains("tangerang selatan")||lcAddress.contains("tangsel"))
                                                return new double[]{-6.2893, 106.6771};
    if (lcAddress.contains("tangerang"))        return new double[]{-6.1783, 106.6319};
    if (lcAddress.contains("bogor"))            return new double[]{-6.5971, 106.8060};
    if (lcAddress.contains("bandung"))          return new double[]{-6.9175, 107.6191};
    if (lcAddress.contains("surabaya"))         return new double[]{-7.2575, 112.7521};
    if (lcAddress.contains("cikarang"))         return new double[]{-6.2561, 107.1418};
    return null; // benar-benar tidak dikenali
}

    // ─────────────────────────────────────────────────────────────
    // HAVERSINE — hitung jarak km antara 2 koordinat
    // ─────────────────────────────────────────────────────────────

    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Estimasi menit jalan kaki dari jarak km (rata-rata 5 km/jam). */
    public static int kmToWalkMinutes(double km) {
        return (int) Math.ceil(km / 5.0 * 60);
    }

    public static List<IndomaretLocation> findNearbyIndomaret(double buyerLat, double buyerLng) {
        // Google Places Nearby Search
        if (!GOOGLE_API_KEY.isBlank()) {
            List<IndomaretLocation> results = findNearbyIndomaretGoogle(buyerLat, buyerLng);
            if (results != null && !results.isEmpty()) return results;
        }
        // Fallback: Overpass (OSM)
        return findNearbyIndomaretOverpass(buyerLat, buyerLng);
    }

    /**
     * Google Places Nearby Search — keyword "Indomaret", radius 3000 m.
     * Dokumentasi: https://developers.google.com/maps/documentation/places/web-service/search-nearby
     *
     * Response diparse manual (tanpa library JSON) untuk menghindari dependensi baru.
     * Field yang diambil: name, vicinity (alamat), geometry.location.lat/lng, rating.
     */
    private static List<IndomaretLocation> findNearbyIndomaretGoogle(double buyerLat, double buyerLng) {
        try {
            String urlStr = "https://maps.googleapis.com/maps/api/place/nearbysearch/json"
                + "?location=" + String.format(java.util.Locale.US, "%.6f,%.6f", buyerLat, buyerLng)
                + "&radius=3000"
                + "&keyword=Indomaret"
                + "&language=id"
                + "&key=" + GOOGLE_API_KEY;

            URL url = new URL(urlStr);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(3_000);
            con.setReadTimeout(10_000);

            if (con.getResponseCode() != 200) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }

            String body = sb.toString();

            // Cek status Google API
            if (body.contains("\"status\":\"REQUEST_DENIED\"")
                    || body.contains("\"status\":\"INVALID_REQUEST\"")) {
                return null;
            }

            List<IndomaretLocation> results = new java.util.ArrayList<>();
            int searchFrom = 0;

            // Iterasi setiap "results" element
            // Struktur: ..."results":[{"geometry":{"location":{"lat":...,"lng":...}},
            //            "name":"...","rating":...,"vicinity":"..."}...]
            while (true) {
                // Cari blok berikutnya dengan pola "geometry"
                int geoIdx = body.indexOf("\"geometry\":", searchFrom);
                if (geoIdx < 0) break;

                // Batas blok element: dari geoIdx mundur ke '{' pembuka element
                // Cukup parse lat/lng dari geometry.location
                int locIdx = body.indexOf("\"location\":", geoIdx);
                if (locIdx < 0) { searchFrom = geoIdx + 10; continue; }

                int latIdx = body.indexOf("\"lat\":", locIdx);
                int lngIdx = body.indexOf("\"lng\":", locIdx);
                if (latIdx < 0 || lngIdx < 0) { searchFrom = geoIdx + 10; continue; }

                double lat = parseNextDouble(body, latIdx + 7);
                double lng = parseNextDouble(body, lngIdx + 7);

                // Cari name (maju dari geoIdx ke belakang, ambil yang terdekat)
                // name bisa sebelum atau sesudah geometry dalam JSON
                // Ambil "name" dalam window 2000 char sekitar geoIdx
                int winStart = Math.max(0, geoIdx - 1000);
                int winEnd   = Math.min(body.length(), geoIdx + 3000);
                String window = body.substring(winStart, winEnd);

                String name = "Indomaret";
                int nIdx = window.lastIndexOf("\"name\":\"");
                if (nIdx >= 0) {
                    int ns = nIdx + 8;
                    int ne = window.indexOf("\"", ns);
                    if (ne > ns) {
                        String candidate = window.substring(ns, ne);
                        // Ambil kalau memang mengandung "Indomaret" (case-insensitive)
                        if (candidate.toLowerCase().contains("indomaret")) {
                            name = candidate;
                        }
                    }
                }

                // vicinity = alamat singkat dari Google
                String addr = "";
                int vicIdx = window.indexOf("\"vicinity\":\"");
                if (vicIdx >= 0) {
                    int vs = vicIdx + 13;
                    int ve = window.indexOf("\"", vs);
                    if (ve > vs) addr = window.substring(vs, ve)
                        .replace("\\u0026", "&").replace("\\n", ", ");
                }
                if (addr.isBlank()) addr = String.format(java.util.Locale.US, "%.5f, %.5f", lat, lng);

                // rating
                double rating = 4.5;
                int rIdx = window.indexOf("\"rating\":");
                if (rIdx >= 0) {
                    rating = parseNextDouble(window, rIdx + 10);
                    if (rating <= 0) rating = 4.5;
                }

                double distKm = haversineKm(buyerLat, buyerLng, lat, lng);
                int    walkMin = kmToWalkMinutes(distKm);

                results.add(new IndomaretLocation(name, addr, lat, lng, walkMin, "06:00 – 23:00", rating));

                searchFrom = geoIdx + 10;
                if (results.size() >= 5) break;
            }

            results.sort(java.util.Comparator.comparingInt(l -> l.distanceMinutes));
            return results.subList(0, Math.min(3, results.size()));

        } catch (Exception e) {
            return null;
        }
    }

    private static List<IndomaretLocation> findNearbyIndomaretOverpass(double buyerLat, double buyerLng) {
        for (int radiusM : new int[]{3000, 6000}) {
            try {
                String query = "[out:json][timeout:25];" +
                    "(" +
                    "node[\"name\"~\"Indomaret\",i](around:" + radiusM + "," + buyerLat + "," + buyerLng + ");" +
                    "way[\"name\"~\"Indomaret\",i](around:"  + radiusM + "," + buyerLat + "," + buyerLng + ");" +
                    ");" +
                    "out center 10;";

                String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
                URL url = new URL("https://overpass-api.de/api/interpreter?data=" + encoded);
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("GET");
                con.setRequestProperty("User-Agent", "ToptriDesktopApp/1.0");
                con.setConnectTimeout(3_000);
                con.setReadTimeout(6_000);

                int httpCode = con.getResponseCode();
                System.out.println("[OVERPASS] HTTP " + httpCode + " radius=" + radiusM +
                    " lat=" + buyerLat + " lng=" + buyerLng);
                if (httpCode == 429 || httpCode == 504) {
                    Thread.sleep(2_000);
                    continue;
                }
                if (httpCode != 200) continue;

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }

                String body = sb.toString();
                System.out.println("[OVERPASS] Response length=" + body.length() +
                    " preview=" + body.substring(0, Math.min(200, body.length())));

                List<IndomaretLocation> results = new java.util.ArrayList<>();
                int idx = 0;

                while (true) {
                    int elemStart = body.indexOf("\"id\":", idx);
                    if (elemStart < 0) break;
                    idx = elemStart + 5;

                    int nextElem  = body.indexOf("\"id\":", idx);
                    int searchEnd = nextElem > 0 ? nextElem : body.length();

                    double lat = 0, lng = 0;

                    // Prioritaskan "center" (untuk way/relation)
                    int centerIdx = body.indexOf("\"center\":", elemStart);
                    if (centerIdx > 0 && centerIdx < searchEnd) {
                        int cLatIdx = body.indexOf("\"lat\":", centerIdx);
                        int cLonIdx = body.indexOf("\"lon\":", centerIdx);
                        if (cLatIdx > 0 && cLatIdx < searchEnd && cLonIdx > 0 && cLonIdx < searchEnd) {
                            lat = parseNextDouble(body, cLatIdx + 6);
                            lng = parseNextDouble(body, cLonIdx + 6);
                        }
                    }

                    // Fallback: lat/lon langsung (node) — cari lon SETELAH lat agar tidak ambil nilai salah
                    if (lat == 0 && lng == 0) {
                        int latIdx2 = body.indexOf("\"lat\":", elemStart);
                        if (latIdx2 > 0 && latIdx2 < searchEnd) {
                            int lonIdx2 = body.indexOf("\"lon\":", latIdx2); // ← setelah lat
                            if (lonIdx2 > 0 && lonIdx2 < searchEnd) {
                                lat = parseNextDouble(body, latIdx2 + 6);
                                lng = parseNextDouble(body, lonIdx2 + 6);
                            }
                        }
                    }

                    if (lat == 0 && lng == 0) continue;

                    // Nama
                    String name = "Indomaret";
                    int nameTagIdx = body.indexOf("\"name\":", elemStart);
                    if (nameTagIdx > 0 && nameTagIdx < searchEnd) {
                        int nameStart = body.indexOf("\"", nameTagIdx + 7) + 1;
                        int nameEnd   = body.indexOf("\"", nameStart);
                        if (nameStart > 0 && nameEnd > nameStart) {
                            String candidate = body.substring(nameStart, nameEnd);
                            if (candidate.toLowerCase().contains("indomaret")) name = candidate;
                        }
                    }

                    // Alamat dari addr:full, addr:street, atau fallback koordinat
                    String addr = "";
                    for (String key : new String[]{"\"addr:full\":", "\"addr:street\":", "\"addr:place\":"}) {
                        int addrIdx = body.indexOf(key, elemStart);
                        if (addrIdx > 0 && addrIdx < searchEnd) {
                            int as = body.indexOf("\"", addrIdx + key.length()) + 1;
                            int ae = body.indexOf("\"", as);
                            if (as > 0 && ae > as) { addr = body.substring(as, ae); break; }
                        }
                    }
                    if (addr.isBlank()) addr = String.format(java.util.Locale.US, "%.5f, %.5f", lat, lng);

                    double distKm  = haversineKm(buyerLat, buyerLng, lat, lng);
                    int    walkMin = kmToWalkMinutes(distKm);
                    results.add(new IndomaretLocation(name, addr, lat, lng, walkMin, "06:00 – 23:00", 4.5));
                    System.out.println("[OVERPASS] Found: " + name + " @ " + lat + "," + lng + " (" + walkMin + " mnt)");
                }

                if (!results.isEmpty()) {
                    results.sort(java.util.Comparator.comparingInt(l -> l.distanceMinutes));
                    return results.subList(0, Math.min(3, results.size()));
                }

                System.out.println("[OVERPASS] Kosong untuk radius=" + radiusM + ", coba lebih besar...");

            } catch (Exception e) {
                System.err.println("[OVERPASS] Error radius=" + radiusM + ": " + e.getMessage());
            }
        }

        System.err.println("[OVERPASS] Semua attempt gagal — gunakan fallback kota");
        return null;
    }


    private static double parseNextDouble(String s, int from) {
        try {
            int end = from;
            while (end < s.length() && (Character.isDigit(s.charAt(end))
                    || s.charAt(end) == '.' || s.charAt(end) == '-')) end++;
            return Double.parseDouble(s.substring(from, end).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Kirim pesan AI berisi pilihan metode pembayaran ke Firestore secara instan.
     * Dipanggil dari background thread di BuyerWindow.onIndomaretPicked().
     *
     * @param requestId  ID request Firestore
     * @param itemName   nama produk yang dipesan
     * @param price      harga produk
     * @param locName    nama Indomaret yang dipilih
     * @param buyerName  nama buyer (bisa kosong)
     * @param fs         FirestoreService
     */
    public static void sendPaymentMethodsMessage(
            String requestId, String itemName, int price,
            String locName, String buyerName, FirestoreService fs) {
        try {
            String nama = (buyerName == null || buyerName.isBlank()) ? "Kak" : buyerName;
            String msg =
                "🎉 Mantap, " + nama + "! Pesananmu sudah kami catat.\n\n" +
                "📦 " + itemName + "  —  " + UiKit.rupiah(price) + "\n" +
                "🏪 " + locName + "\n\n" +
                "💳 Silakan pilih metode pembayaran:\n\n" +
                "🔲 QRIS           — scan & bayar langsung di kasir\n" +
                "🏦 Transfer Bank  — BCA / Mandiri / BRI / BNI\n" +
                "💚 E-Wallet       — GoPay · OVO · Dana · ShopeePay\n" +
                "💵 COD (Tunai)    — bayar saat pesanan tiba\n\n" +
                "Ketuk salah satu tombol di bawah untuk lanjut ya! 👇";

            fs.sendSellerMessage(requestId, "AI_BOT", "[AUTO] " + msg);
        } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────────────────
    // buildLocationReply — dipanggil setelah form delivery submit
    // ─────────────────────────────────────────────────────────────

    /**
     * Geocode alamat buyer → cari Indomaret real via Overpass →
     * return teks konfirmasi + daftar lokasi.
     * Juga update INDOMARET_LOCATIONS_RESOLVED agar BuyerWindow bisa
     * pakai koordinat real untuk peta.
     */
    public static volatile List<IndomaretLocation> INDOMARET_LOCATIONS_RESOLVED = null;

    public static String buildLocationReply(String address, String itemName, int price) {
        // 1. Konfirmasi singkat via Ollama
        String confirm;
        try {
            String prompt =
                "Kamu asisten toko makanan Toptri Bot. " +
                "Buyer memesan " + itemName + " seharga " + UiKit.rupiah(price) + ". " +
                "Alamat buyer: " + address + ". " +
                "Balas 1 kalimat singkat ramah bahasa Indonesia: " +
                "konfirmasi pesanan diterima dan beritahu kamu akan menampilkan lokasi Indomaret terdekat. " +
                "Jangan gunakan markdown.";
            confirm = callOllama(prompt);
        } catch (Exception e) {
            confirm = "Pesanan " + itemName + " kamu sudah kami terima! " +
                      "Berikut lokasi Indomaret terdekat dari alamatmu 📍";
        }

        // 2. Geocode alamat buyer
        double[] coords = geocodeAddress(address);
        List<IndomaretLocation> nearby = null;

        if (coords != null) {
            // 3. Cari Indomaret real via Overpass
            nearby = findNearbyIndomaret(coords[0], coords[1]);
        }

        // 4. Fallback: kalau Overpass kosong tapi coords berhasil,
        //    buat 1 entry di koordinat buyer agar peta tidak nunjuk Jakarta Pusat.
        if (nearby == null || nearby.isEmpty()) {
            if (coords != null) {
                nearby = new java.util.ArrayList<>();
                nearby.add(new IndomaretLocation(
                    "Indomaret (lokasi perkiraan)", address,
                    coords[0], coords[1], 0, "06:00 – 23:00", 4.0));
            } else {
                nearby = INDOMARET_LOCATIONS;
            }
        }

        // 5. Simpan hasil untuk dipakai BuyerWindow (peta Leaflet)
        INDOMARET_LOCATIONS_RESOLVED = nearby;

        // 6. Bangun teks reply
        StringBuilder sb = new StringBuilder(confirm);
        sb.append("\n\n📍 Pilihan Indomaret terdekat:\n");
        int rank = 1;
        for (IndomaretLocation loc : nearby) {
            String medal = rank == 1 ? "🥇" : rank == 2 ? "🥈" : "🥉";
            sb.append(medal).append(" ").append(loc.name).append("\n");
            sb.append("   📌 ").append(loc.address).append("\n");
            sb.append("   🚶 ").append(loc.distanceMinutes).append(" menit");
            sb.append("  ·  ⭐ ").append(String.format(java.util.Locale.US, "%.1f", loc.rating));
            sb.append("  ·  🕐 ").append(loc.operationalHours).append("\n");
            rank++;
        }
        return sb.toString().trim();
    }

    // ─────────────────────────────────────────────────────────────
    // Ollama HTTP call (non-streaming)
    // ─────────────────────────────────────────────────────────────

    private static String callOllama(String promptText) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", ollamaModel);
        body.put("prompt", promptText);
        body.put("stream", false);
        body.put("options", new JSONObject()
            .put("temperature", 0.7)
            .put("num_predict", 150));

        URL url = new URL(OLLAMA_URL);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setDoOutput(true);
        con.setConnectTimeout(3_000);
        con.setReadTimeout(6_000);

        try (OutputStream os = con.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = con.getResponseCode();
        if (code != 200) throw new IOException("Ollama HTTP " + code);

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }

        JSONObject resp = new JSONObject(sb.toString());
        String result = resp.optString("response", "").trim();
        if (result.isBlank()) throw new IOException("Ollama response kosong");
        return result;
    }

    // ─────────────────────────────────────────────────────────────
    // DATA CLASSES — semua direferensikan langsung dari BuyerWindow
    // ─────────────────────────────────────────────────────────────

    /** Kategori makanan untuk widget bubble AI. */
    public static class FoodCategory {
        public final String emoji, label, key;
        public FoodCategory(String emoji, String label, String key) {
            this.emoji = emoji;
            this.label = label;
            this.key   = key;
        }
    }

    /** Item produk untuk widget Yummy Choice. */
    public static class YummyMenuItem {
        public final String name, description;
        public final int    price;
        public YummyMenuItem(String name, int price, String description) {
            this.name        = name;
            this.price       = price;
            this.description = description;
        }
    }

    /** Lokasi Indomaret untuk widget peta setelah form pengiriman. */
    public static class IndomaretLocation {
        public final String name, address, operationalHours;
        public final double lat, lng, rating;
        public final int    distanceMinutes;

        public IndomaretLocation(String name, String address,
                                  double lat, double lng,
                                  int distanceMinutes, String operationalHours, double rating) {
            this.name             = name;
            this.address          = address;
            this.lat              = lat;
            this.lng              = lng;
            this.distanceMinutes  = distanceMinutes;
            this.operationalHours = operationalHours;
            this.rating           = rating;
        }
    }

    /** Hasil isian form pengiriman oleh buyer. */
    public static class DeliveryFormResult {
        public final String name, phone, address, itemName;
        public final int    price;

        public DeliveryFormResult(String name, String phone,
                                   String address, String itemName, int price) {
            this.name     = name     == null ? "" : name;
            this.phone    = phone    == null ? "" : phone;
            this.address  = address  == null ? "" : address;
            this.itemName = itemName == null ? "" : itemName;
            this.price    = price;
        }
    }

    /** Payload AI reply — greeting + categories + videoUrl. */
    public static class AiReplyPayload {
        public final String             greeting;
        public final List<FoodCategory> categories;
        public final String             videoUrl;

        public AiReplyPayload(String greeting, List<FoodCategory> categories, String videoUrl) {
            this.greeting   = greeting;
            this.categories = categories;
            this.videoUrl   = videoUrl;
        }
    }
}