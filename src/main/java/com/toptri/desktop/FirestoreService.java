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
            if (in == null)
                throw new IllegalStateException("firebase-service-account.json not found in src/main/resources");

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

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String norm(String s) {
        return safe(s).toLowerCase();
    }

    // ============================================================
    // ✅ CONVERSATION
    // ============================================================

    // overload: with buyerRequestNo for consistent numbering
    public void createConversation(String requestId, String buyerId, String firstText, long buyerRequestNo) throws Exception {
        String t = safe(firstText);

        Map<String, Object> doc = new HashMap<>();
        doc.put("requestId", requestId);
        doc.put("buyerId", safe(buyerId));
        doc.put("status", "OPEN");
        doc.put("createdAt", Timestamp.now());
        doc.put("updatedAt", Timestamp.now());

        doc.put("buyerText", t);
        doc.put("latestBuyerText", t);

        doc.put("buyerRequestNo", Math.max(buyerRequestNo, 0));

        db.collection("requests")
                .document(requestId)
                .set(doc, SetOptions.merge())
                .get();

        sendBuyerMessage(requestId, buyerId, firstText);
    }

    // keep old signature for backward compatibility
    public void createConversation(String requestId, String buyerId, String firstText) throws Exception {
        createConversation(requestId, buyerId, firstText, 0);
    }

    public DocumentReference sendBuyerMessage(String requestId,
                                              String buyerId,
                                              String text) throws Exception {
        String t = safe(text);
        if (t.isBlank())
            throw new IllegalArgumentException("Buyer message empty");

        Map<String, Object> msg = new HashMap<>();
        msg.put("requestId", requestId);
        msg.put("senderType", "BUYER");
        msg.put("senderId", safe(buyerId));
        msg.put("text", t);
        msg.put("createdAt", Timestamp.now());

        DocumentReference ref = db.collection("requests")
                .document(requestId)
                .collection("messages")
                .add(msg).get();

        Map<String, Object> patch = new HashMap<>();
        patch.put("updatedAt", Timestamp.now());
        patch.put("buyerText", t);
        patch.put("latestBuyerText", t);

        db.collection("requests")
                .document(requestId)
                .set(patch, SetOptions.merge())
                .get();

        return ref;
    }

    public void sendSellerMessage(String requestId,
                                  String sellerId,
                                  String text) throws Exception {
        String t = safe(text);
        if (t.isBlank())
            throw new IllegalArgumentException("Seller message empty");

        Map<String, Object> msg = new HashMap<>();
        msg.put("requestId", requestId);
        msg.put("senderType", "SELLER");
        msg.put("senderId", safe(sellerId));
        msg.put("text", t);
        msg.put("createdAt", Timestamp.now());

        db.collection("requests")
                .document(requestId)
                .collection("messages")
                .add(msg).get();

        db.collection("requests")
                .document(requestId)
                .update("updatedAt", Timestamp.now()).get();
    }

    // ============================================================
    // ✅ LISTENERS
    // ============================================================

    public ListenerRegistration listenMessages(String requestId,
                                               Consumer<QuerySnapshot> onUpdate,
                                               Consumer<Exception> onError) {
        return db.collection("requests")
                .document(requestId)
                .collection("messages")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .addSnapshotListener((snap, err) -> {
                    if (err != null) { onError.accept(err); return; }
                    if (snap != null) onUpdate.accept(snap);
                });
    }

    public ListenerRegistration listenAllOffers(String requestId,
                                                Consumer<QuerySnapshot> onUpdate,
                                                Consumer<Exception> onError) {
        return db.collection("requests")
                .document(requestId)
                .collection("offers")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .addSnapshotListener((snap, err) -> {
                    if (err != null) { onError.accept(err); return; }
                    if (snap != null) onUpdate.accept(snap);
                });
    }

    public ListenerRegistration listenBuyerRequests(String buyerId,
                                                    Consumer<QuerySnapshot> onUpdate,
                                                    Consumer<Exception> onError) {
        return db.collection("requests")
                .whereEqualTo("buyerId", safe(buyerId))
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .addSnapshotListener((snap, err) -> {
                    if (err != null) { onError.accept(err); return; }
                    if (snap != null) onUpdate.accept(snap);
                });
    }

    public ListenerRegistration listenOpenRequests(Consumer<QuerySnapshot> onUpdate,
                                                   Consumer<Exception> onError) {
        return db.collection("requests")
                .whereEqualTo("status", "OPEN")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .addSnapshotListener((snap, err) -> {
                    if (err != null) { onError.accept(err); return; }
                    if (snap != null) onUpdate.accept(snap);
                });
    }

    // ============================================================
    // ✅ MENUS
    // ============================================================

    public List<MenuItem> getMenusByCategory(String category)
            throws ExecutionException, InterruptedException {

        QuerySnapshot snap = db.collection("menus")
                .whereEqualTo("category", norm(category))
                .whereEqualTo("available", true)
                .get().get();

        List<MenuItem> out = new ArrayList<>();

        for (QueryDocumentSnapshot d : snap.getDocuments()) {
            String name = safe(d.getString("name"));
            String sellerId = safe(d.getString("sellerId"));
            String vendor = safe(d.getString("vendor"));

            int price = 0;
            Long p = d.getLong("price");
            if (p != null) price = p.intValue();

            int etaMinutes = 0;
            Long eta = d.getLong("etaMinutes");
            if (eta != null) etaMinutes = eta.intValue();

            double rating = 0.0;
            Double r = d.getDouble("rating");
            if (r != null) rating = r;

            if (!name.isBlank()) {
                out.add(new MenuItem(name, price, sellerId, vendor, etaMinutes, rating));
            }
        }

        out.sort(Comparator
                .comparingDouble(MenuItem::getRating).reversed()
                .thenComparingInt(MenuItem::getPrice));

        return out;
    }

    public String mapCategoryFromText(String text) {
        return norm(text);
    }

    // ============================================================
    // ✅ OFFERS (MUST set buyerMessageId)
    // ============================================================

    public void createOfferFromMenu(String requestId, String sellerId, MenuItem menu, String buyerMessageId) throws Exception {
        Map<String, Object> offer = new HashMap<>();
        offer.put("sellerId", safe(sellerId));
        offer.put("menuName", menu.getName());
        offer.put("price", menu.getPrice());
        offer.put("vendor", menu.getVendor());
        offer.put("etaMinutes", menu.getEtaMinutes());
        offer.put("rating", menu.getRating());
        offer.put("buyerMessageId", safe(buyerMessageId)); // ✅ IMPORTANT
        offer.put("createdAt", Timestamp.now());

        db.collection("requests")
                .document(requestId)
                .collection("offers")
                .add(offer).get();
    }

    public void createOfferTyped(String requestId, String sellerId, String menuName, int price, String buyerMessageId) throws Exception {
        Map<String, Object> offer = new HashMap<>();
        offer.put("sellerId", safe(sellerId));
        offer.put("menuName", safe(menuName));
        offer.put("price", Math.max(price, 0));
        offer.put("vendor", "");
        offer.put("etaMinutes", 0);
        offer.put("rating", 0.0);
        offer.put("buyerMessageId", safe(buyerMessageId)); // ✅ IMPORTANT
        offer.put("createdAt", Timestamp.now());

        db.collection("requests")
                .document(requestId)
                .collection("offers")
                .add(offer).get();
    }

    public void createNewMenuAndSendOffer(String requestId,
                                          String sellerId,
                                          String buyerTextCategory,
                                          String menuName,
                                          int price,
                                          String vendor,
                                          String buyerMessageId) throws Exception {

        // 1) create menu
        Map<String, Object> menu = new HashMap<>();
        menu.put("name", safe(menuName));
        menu.put("sellerId", safe(sellerId));
        menu.put("vendor", safe(vendor));
        menu.put("price", Math.max(price, 0));
        menu.put("etaMinutes", 0);
        menu.put("rating", 0.0);
        menu.put("available", true);
        menu.put("category", norm(buyerTextCategory));

        db.collection("menus")
                .add(menu).get();

        // 2) send offer
        Map<String, Object> offer = new HashMap<>();
        offer.put("sellerId", safe(sellerId));
        offer.put("menuName", safe(menuName));
        offer.put("price", Math.max(price, 0));
        offer.put("vendor", safe(vendor));
        offer.put("etaMinutes", 0);
        offer.put("rating", 0.0);
        offer.put("buyerMessageId", safe(buyerMessageId)); // ✅ IMPORTANT
        offer.put("createdAt", Timestamp.now());

        db.collection("requests")
                .document(requestId)
                .collection("offers")
                .add(offer).get();
    }

    // ============================================================
    // ✅ COMPLETE REQUEST (buyer buy)
    // ============================================================

    public void completeRequestSimple(String requestId, String offerId, String buyerName, String address) throws Exception {
        Map<String, Object> patch = new HashMap<>();
        patch.put("status", "COMPLETED");
        patch.put("updatedAt", Timestamp.now());
        patch.put("completedAt", Timestamp.now());
        patch.put("selectedOfferId", safe(offerId));
        patch.put("buyerName", safe(buyerName));
        patch.put("address", safe(address));

        db.collection("requests").document(requestId).set(patch, SetOptions.merge()).get();
    }

    // ============================================================
    // ✅ MODEL
    // ============================================================

    public static class MenuItem {
        private final String name;
        private final int price;
        private final String sellerId;
        private final String vendor;
        private final int etaMinutes;
        private final double rating;

        public MenuItem(String name, int price, String sellerId, String vendor, int etaMinutes, double rating) {
            this.name = name == null ? "" : name;
            this.price = price;
            this.sellerId = sellerId == null ? "" : sellerId;
            this.vendor = vendor == null ? "" : vendor;
            this.etaMinutes = etaMinutes;
            this.rating = rating;
        }

        public String getName() { return name; }
        public int getPrice() { return price; }
        public String getSellerId() { return sellerId; }
        public String getVendor() { return vendor; }
        public int getEtaMinutes() { return etaMinutes; }
        public double getRating() { return rating; }

        public String vendorOrDash() {
            return (vendor == null || vendor.isBlank()) ? "-" : vendor;
        }

        public String etaText() {
            return etaMinutes > 0 ? ("ETA " + etaMinutes + " min") : "ETA -";
        }

        public String ratingText() {
            return rating > 0 ? ("★ " + String.format(java.util.Locale.US, "%.1f", rating)) : "★ -";
        }

        public String sellerOrDash() {
            return (sellerId == null || sellerId.isBlank()) ? "-" : sellerId;
        }
    }
}