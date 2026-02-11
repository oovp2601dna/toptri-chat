package com.toptri;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;

@SpringBootApplication
public class ToptriSimpleFirestoreApp {

  public static void main(String[] args) {
    SpringApplication.run(ToptriSimpleFirestoreApp.class, args);
  }

  // ==================== FIREBASE HOLDER ====================
  @Component
  public static class FirebaseHolder {
    @Value("${firebase.serviceAccountPath}")
    private Resource serviceAccount;

    private Firestore db;

    @PostConstruct
    public void init() throws IOException {
      if (FirebaseApp.getApps().isEmpty()) {
        FirebaseOptions options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(serviceAccount.getInputStream()))
            .build();
        FirebaseApp.initializeApp(options);
      }
      db = FirestoreClient.getFirestore();
    }

    public Firestore db() { return db; }
  }

  // ==================== DTOs ====================
  // Buyer POST /api/requests
  public static class BuyerRequestDto {
    public String requestId;
    public String text;
  }

  // Seller POST /api/seller/row (optional manual)
  public static class SellerRowDto {
    public String requestId;
    public int rowIndex;      // 0..2
    public String content;    // menu text
    public String vendor;     // optional
    public Integer price;     // optional
    public Double score;      // optional
  }
  // Buyer POST /api/buyer/buy { requestId, rowIndex, buyerName, buyerAddress }
public static class BuyerBuyDto {
  public String requestId;
  public int rowIndex;          // 0..2
  public String buyerName;      // optional
  public String buyerAddress;   // optional
}


  // Seller POST /api/seller/pick (simple: click menu)
  public static class PickMenuDto {
    public String requestId;
    public String menuName;
    public String vendor;   // optional
    public Integer price;   // optional
    public Double score;    // optional
  }

  // ==================== HELPERS ====================
  private static String norm(String s) {
    return (s == null) ? "" : s.trim().toLowerCase();
  }

  // ==================== REST API ====================
  @RestController
  @RequestMapping("/api")
  @CrossOrigin
  public static class ApiController {
    private final FirebaseHolder fb;

    public ApiController(FirebaseHolder fb) {
      this.fb = fb;
    }
    public static class OrderDto {
  public String requestId;
  public Integer rowIndex;
  public String item;
  public String vendor;
  public Integer price;
  public Double score;
}

    // -------- Buyer -> Backend --------
    @PostMapping("/requests")
    public ResponseEntity<Void> createRequest(@RequestBody BuyerRequestDto dto)
        throws ExecutionException, InterruptedException {

      if (dto == null || dto.requestId == null || dto.requestId.isBlank()
          || dto.text == null || dto.text.isBlank()) {
        return ResponseEntity.badRequest().build();
      }

      String rid = dto.requestId.trim();
      String text = dto.text.trim();

      Map<String, Object> doc = new HashMap<>();
      doc.put("requestId", rid);
      doc.put("text", text);
      doc.put("category", norm(text));   // simple: category = text
      doc.put("status", "NEW");          // NEW | CLAIMED
      doc.put("createdAt", Timestamp.now());

      fb.db().collection("requests")
          .document(rid)
          .set(doc)
          .get();

      return ResponseEntity.ok().build();
    }

    // -------- Seller: claim latest NEW request --------
   @GetMapping("/requests/latest")
public ResponseEntity<Map<String, Object>> claimLatestRequest() throws Exception {
  Firestore db = fb.db();

  Map<String, Object> out = db.runTransaction(tx -> {
    Query q = db.collection("requests")
        .whereEqualTo("status", "NEW")
        // match index kamu yg ASC
        .orderBy("createdAt", Query.Direction.ASCENDING)
        .limit(20);

    QuerySnapshot snap = tx.get(q).get();
    if (snap.isEmpty()) return null;

    // IMPORTANT: getDocuments() -> List<QueryDocumentSnapshot>
    List<QueryDocumentSnapshot> docs = snap.getDocuments();
    QueryDocumentSnapshot doc = docs.get(docs.size() - 1); // latest (karena ASC)

    tx.update(doc.getReference(), "status", "CLAIMED");

    Map<String, Object> res = new HashMap<>();
    res.put("requestId", doc.getString("requestId"));
    res.put("text", doc.getString("text"));
    return res;
  }).get();

  if (out == null) return ResponseEntity.noContent().build();
  return ResponseEntity.ok(out);
}

    // -------- Buyer: poll rows --------
    @GetMapping("/buyer/rows")
    public Map<String, Object> getBuyerRows(@RequestParam String requestId)
        throws ExecutionException, InterruptedException {

      String rid = requestId == null ? "" : requestId.trim();

      QuerySnapshot snap = fb.db()
          .collection("requests")
          .document(rid)
          .collection("rows")
          .get().get();

      List<Map<String, Object>> rows = new ArrayList<>();
      for (QueryDocumentSnapshot d : snap.getDocuments()) {
        rows.add(d.getData());
      }

      rows.sort(Comparator.comparingInt(m ->
          ((Number) m.getOrDefault("rowIndex", 0)).intValue()
      ));

      return Map.of("requestId", rid, "rows", rows);
    }

    // -------- Seller: pick menu (simple click) --------
    // Auto put into first empty slot 0..2
    @PostMapping("/seller/pick")
    public ResponseEntity<Map<String, Object>> pickMenu(@RequestBody PickMenuDto dto)
        throws ExecutionException, InterruptedException {

      if (dto == null || dto.requestId == null || dto.requestId.isBlank()
          || dto.menuName == null || dto.menuName.isBlank()) {
        return ResponseEntity.badRequest().build();
      }

      String rid = dto.requestId.trim();

      // cek slot yang sudah terisi
      QuerySnapshot rowsSnap = fb.db()
          .collection("requests").document(rid)
          .collection("rows")
          .get().get();

      boolean[] used = new boolean[3];
      for (QueryDocumentSnapshot d : rowsSnap.getDocuments()) {
        Object idxObj = d.get("rowIndex");
        if (idxObj instanceof Number) {
          int idx = ((Number) idxObj).intValue();
          if (idx >= 0 && idx <= 2) used[idx] = true;
        }
      }

      int slot = -1;
      for (int i = 0; i < 3; i++) {
        if (!used[i]) { slot = i; break; }
      }

      if (slot == -1) {
        return ResponseEntity.status(409).body(Map.of("error", "slots_full"));
      }

      Map<String, Object> rowDoc = new HashMap<>();
      rowDoc.put("requestId", rid);
      rowDoc.put("rowIndex", slot);
      rowDoc.put("content", dto.menuName.trim());
      rowDoc.put("vendor", dto.vendor == null ? "" : dto.vendor.trim());
      rowDoc.put("price", dto.price == null ? 0 : dto.price);
      rowDoc.put("score", dto.score == null ? 0.0 : dto.score);
      rowDoc.put("updatedAt", Timestamp.now());

      fb.db().collection("requests")
          .document(rid)
          .collection("rows")
          .document(String.valueOf(slot))
          .set(rowDoc)
          .get();

      return ResponseEntity.ok(Map.of("slot", slot, "menuName", dto.menuName.trim()));
    }

    // -------- (Optional) Seller manual save row 0..2 --------
    @PostMapping("/seller/row")
    public ResponseEntity<Void> saveSellerRow(@RequestBody SellerRowDto dto)
        throws ExecutionException, InterruptedException {

      if (dto == null || dto.requestId == null || dto.requestId.isBlank()) {
        return ResponseEntity.badRequest().build();
      }
      if (dto.rowIndex < 0 || dto.rowIndex > 2) {
        return ResponseEntity.badRequest().build();
      }
      if (dto.content == null || dto.content.isBlank()) {
        return ResponseEntity.badRequest().build();
      }

      String rid = dto.requestId.trim();

      Map<String, Object> rowDoc = new HashMap<>();
      rowDoc.put("requestId", rid);
      rowDoc.put("rowIndex", dto.rowIndex);
      rowDoc.put("content", dto.content.trim());
      rowDoc.put("vendor", dto.vendor == null ? "" : dto.vendor.trim());
      rowDoc.put("price", dto.price == null ? 0 : dto.price);
      rowDoc.put("score", dto.score == null ? 0.0 : dto.score);
      rowDoc.put("updatedAt", Timestamp.now());

      fb.db().collection("requests")
          .document(rid)
          .collection("rows")
          .document(String.valueOf(dto.rowIndex))
          .set(rowDoc)
          .get();

      return ResponseEntity.ok().build();
    }

    // -------- Seller: load menus from Firestore --------
    // Collection "menus": {category,name,price,sellerId,available}
    @GetMapping("/menus")
    public List<Map<String, Object>> menus(@RequestParam String category)
        throws ExecutionException, InterruptedException {

      QuerySnapshot snap = fb.db().collection("menus")
          .whereEqualTo("category", norm(category))
          .whereEqualTo("available", true)
          .get().get();

      List<Map<String, Object>> out = new ArrayList<>();
      for (QueryDocumentSnapshot d : snap.getDocuments()) {
        Map<String, Object> m = new HashMap<>(d.getData());
        m.put("id", d.getId());
        out.add(m);
      }
      return out;
    }
    @PostMapping("/orders")
public ResponseEntity<Void> createOrder(@RequestBody OrderDto dto)
    throws ExecutionException, InterruptedException {

  if (dto == null || dto.requestId == null || dto.requestId.isBlank()
      || dto.item == null || dto.item.isBlank()) {
    return ResponseEntity.badRequest().build();
  }

  Map<String, Object> doc = new HashMap<>();
  doc.put("requestId", dto.requestId.trim());
  doc.put("rowIndex", dto.rowIndex == null ? -1 : dto.rowIndex);
  doc.put("item", dto.item.trim());
  doc.put("vendor", dto.vendor == null ? "" : dto.vendor.trim());
  doc.put("price", dto.price == null ? 0 : dto.price);
  doc.put("score", dto.score == null ? 0.0 : dto.score);
  doc.put("createdAt", Timestamp.now());
  doc.put("status", "NEW_ORDER");

  String orderId = "order_" + UUID.randomUUID();
  fb.db().collection("orders").document(orderId).set(doc).get();

  return ResponseEntity.ok().build();
}

@PostMapping("/buyer/buy")
public ResponseEntity<?> buy(@RequestBody BuyerBuyDto dto) throws Exception {
  if (dto == null || dto.requestId == null || dto.requestId.isBlank()) {
    return ResponseEntity.badRequest().body(Map.of("error", "requestId required"));
  }
  if (dto.rowIndex < 0 || dto.rowIndex > 2) {
    return ResponseEntity.badRequest().body(Map.of("error", "rowIndex must be 0..2"));
  }

  Firestore db = fb.db();
  String rid = dto.requestId.trim();
  int idx = dto.rowIndex;

  try {
    Map<String, Object> result = db.runTransaction(tx -> {
      DocumentReference reqRef = db.collection("requests").document(rid);
      DocumentSnapshot reqDoc = tx.get(reqRef).get();
      if (!reqDoc.exists()) {
        // 404 -> dilempar sebagai IllegalStateException biar ketangkep di catch bawah
        throw new IllegalStateException("REQUEST_NOT_FOUND");
      }

      String status = String.valueOf(reqDoc.getString("status"));
      // kalau request sudah dibeli, jangan bikin order lagi
      if ("BOUGHT".equalsIgnoreCase(status)) {
        throw new IllegalStateException("ALREADY_BOUGHT");
      }

      DocumentReference rowRef = reqRef.collection("rows").document(String.valueOf(idx));
      DocumentSnapshot rowDoc = tx.get(rowRef).get();
      if (!rowDoc.exists()) {
        throw new IllegalStateException("ROW_NOT_FOUND");
      }

      // ambil data row
      String content = rowDoc.getString("content");
      String vendor = rowDoc.getString("vendor");
      Object priceObj = rowDoc.get("price");
      long price = (priceObj instanceof Number) ? ((Number) priceObj).longValue() : 0;

      // bikin orderId
      String orderId = "ord_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

      Map<String, Object> order = new HashMap<>();
      order.put("orderId", orderId);
      order.put("requestId", rid);
      order.put("rowIndex", idx);
      order.put("menu", content == null ? "" : content);
      order.put("vendor", vendor == null ? "" : vendor);
      order.put("price", price);
      order.put("buyerName", dto.buyerName == null ? "" : dto.buyerName.trim());
      order.put("buyerAddress", dto.buyerAddress == null ? "" : dto.buyerAddress.trim());
      order.put("createdAt", Timestamp.now());
      order.put("status", "PAID");

      // simpan order
      tx.set(db.collection("orders").document(orderId), order);

      // lock request
      tx.update(reqRef, Map.of(
          "status", "BOUGHT",
          "boughtAt", Timestamp.now(),
          "boughtRowIndex", idx,
          "boughtOrderId", orderId
      ));

      // tandain row yg dipilih
      tx.update(rowRef, "isBought", true);

      return order;
    }).get();

    return ResponseEntity.ok(result);

  } catch (Exception e) {
    // Ambil "kode" error dari message IllegalStateException di transaction
    String msg = (e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
    if (msg == null) msg = "";

    if (msg.contains("ALREADY_BOUGHT")) {
      return ResponseEntity.status(409).body(Map.of("error", "already_bought"));
    }
    if (msg.contains("REQUEST_NOT_FOUND")) {
      return ResponseEntity.status(404).body(Map.of("error", "request_not_found"));
    }
    if (msg.contains("ROW_NOT_FOUND")) {
      return ResponseEntity.status(404).body(Map.of("error", "row_not_found"));
    }

   
    e.printStackTrace();
    return ResponseEntity.status(500).body(Map.of("error", "internal_error"));
  }
}


  }
}
