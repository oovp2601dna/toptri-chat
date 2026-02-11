package com.toptri.desktop;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public class FirestoreService {

    private final Firestore db;

    public FirestoreService() {
        this.db = initFirestoreWithFirebaseAdmin();
    }

    private Firestore initFirestoreWithFirebaseAdmin() {
        try {
            InputStream in = getClass().getClassLoader().getResourceAsStream("firebase-service-account.json");
            if (in == null) throw new IllegalStateException("firebase-service-account.json not found in src/main/resources");

            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(in))
                        .build();
                FirebaseApp.initializeApp(options);
            }

            return FirestoreClient.getFirestore();
        } catch (Exception e) {
            throw new RuntimeException("Failed to init Firestore: " + e.getMessage(), e);
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    // Buyer creates request
    public void createRequest(String requestId, String buyerText) throws Exception {
        Map<String, Object> doc = new HashMap<>();
        doc.put("requestId", requestId);
        doc.put("buyerText", buyerText);
        doc.put("status", "OPEN");
        doc.put("createdAt", Timestamp.now());
        doc.put("category", mapCategoryFromText(buyerText)); // match menus.category exactly

        db.collection("requests").document(requestId).set(doc).get();
    }

    // Sellers listen OPEN requests
    public ListenerRegistration listenOpenRequests(Consumer<QuerySnapshot> onUpdate, Consumer<Exception> onError) {
        return db.collection("requests")
                .whereEqualTo("status", "OPEN")
                .addSnapshotListener((snap, err) -> {
                    if (err != null) { onError.accept(err); return; }
                    if (snap != null) onUpdate.accept(snap);
                });
    }

    // Buyer listens offers
    public ListenerRegistration listenOffers(String requestId, Consumer<QuerySnapshot> onUpdate, Consumer<Exception> onError) {
        return db.collection("requests")
                .document(requestId)
                .collection("offers")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .addSnapshotListener((snap, err) -> {
                    if (err != null) { onError.accept(err); return; }
                    if (snap != null) onUpdate.accept(snap);
                });
    }

    // ✅ Fetch menus by category (and include ETA + rating etc)
    public List<MenuItem> getMenusByCategory(String category) throws ExecutionException, InterruptedException {
        String cat = norm(category);

        QuerySnapshot snap = db.collection("menus")
                .whereEqualTo("category", cat)
                .whereEqualTo("available", true)
                .get().get();

        List<MenuItem> out = new ArrayList<>();
        for (QueryDocumentSnapshot d : snap.getDocuments()) {
            String name = safeStr(d.getString("name"));
            int price = safeInt(d.get("price"));
            String sellerId = safeStr(d.getString("sellerId"));
            String vendor = safeStr(d.getString("vendor"));

            int etaMinutes = safeInt(d.get("etaMinutes")); // optional
            double rating = safeDouble(d.get("rating"));   // optional

            if (!name.isBlank()) out.add(new MenuItem(name, price, sellerId, vendor, etaMinutes, rating));
        }

        // example sort: best rating then cheapest
        out.sort(Comparator
                .comparingDouble(MenuItem::getRating).reversed()
                .thenComparingInt(MenuItem::getPrice));

        return out;
    }

    // ✅ Seller sends offer using menu details (NO manual ETA input)
    public void createOfferFromMenu(String requestId, String sellerId, MenuItem menu) throws Exception {
        Map<String, Object> offer = new HashMap<>();
        offer.put("sellerId", sellerId);
        offer.put("menuName", menu.getName());
        offer.put("price", menu.getPrice());
        offer.put("vendor", menu.getVendor());
        offer.put("etaMinutes", menu.getEtaMinutes());
        offer.put("rating", menu.getRating());
        offer.put("createdAt", Timestamp.now());

        db.collection("requests").document(requestId).collection("offers").add(offer).get();
    }

    // category matches your DB exactly = buyer request text
    public String mapCategoryFromText(String buyerText) {
        return norm(buyerText);
    }

    private static String safeStr(String s) { return s == null ? "" : s.trim(); }

    private static int safeInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    private static double safeDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0.0; }
    }

    // ✅ Menu model (methods, not only public attributes)
    public static class MenuItem {
        private final String name;
        private final int price;
        private final String sellerId;
        private final String vendor;
        private final int etaMinutes;
        private final double rating;

        public MenuItem(String name, int price, String sellerId, String vendor, int etaMinutes, double rating) {
            this.name = name;
            this.price = price;
            this.sellerId = sellerId;
            this.vendor = vendor;
            this.etaMinutes = etaMinutes;
            this.rating = rating;
        }

        public String getName() { return name; }
        public int getPrice() { return price; }
        public String getSellerId() { return sellerId; }
        public String getVendor() { return vendor; }
        public int getEtaMinutes() { return etaMinutes; }
        public double getRating() { return rating; }

        public String sellerOrDash() {
            return (sellerId == null || sellerId.isBlank()) ? "-" : sellerId;
        }

        public String vendorOrDash() {
            return (vendor == null || vendor.isBlank()) ? "-" : vendor;
        }

        public String etaText() {
            return etaMinutes > 0 ? ("ETA " + etaMinutes + " min") : "ETA -";
        }

        public String ratingText() {
            return rating > 0 ? ("⭐ " + String.format(java.util.Locale.US, "%.1f", rating)) : "⭐ -";
        }
    }
}
