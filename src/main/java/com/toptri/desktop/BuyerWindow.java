package com.toptri.desktop;

import com.google.cloud.firestore.ListenerRegistration;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.util.*;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

public class BuyerWindow {

    private final FirestoreService fs;
    private final String buyerId = getOrCreateBuyerId();

    private String currentRequestId = null;
    private String currentRequestStatus = "OPEN";

    private ListenerRegistration messagesListener;
    private ListenerRegistration offersAllListener;
    private ListenerRegistration myReqListener;

    private final Label ridValue = new Label("-");
    private final TextField input = new TextField();

    private final ListView<RequestItem> myReqList = new ListView<>();

    private final VBox chatBox = new VBox(12);
    private final ScrollPane chatScroll = new ScrollPane(chatBox);

    private final List<Message> messages = new ArrayList<>();
    private final Map<String, List<Offer>> offersByBuyerMessageId = new HashMap<>();
    private String latestBuyerMessageId = null;

    private final Map<String, String> requestStatusById = new HashMap<>();

    // ✅ NEW: Order history panel
    private final VBox historyBox = new VBox(8);

    private Button sendBtn;
    private Button newBtn;

    // ✅ NEW: persistent buyer name & address
    private static final Preferences PREFS = Preferences.userNodeForPackage(BuyerWindow.class);

    public BuyerWindow(FirestoreService fs) {
        this.fs = fs;
    }

    public static void open(FirestoreService fs) {
        new BuyerWindow(fs).show();
    }

    public void show() {
        Stage stage = new Stage();

        Region header = UiKit.headerBar("Toptri Chat - Buyer");

        // ── Chat card ──
        VBox card = new VBox(12);
        card.setPadding(new Insets(6));

        Label title = UiKit.h1("Buyer");

        HBox sendRow = new HBox(10);
        // ✅ NEW: prompt shows multi-item example
        input.setPromptText("e.g. '2 nasi padang 3 es teh' or just 'drink'");
        HBox.setHgrow(input, Priority.ALWAYS);

        sendBtn = UiKit.primaryButton("Send");
        sendBtn.setOnAction(e -> onSend());

        newBtn = UiKit.primaryButton("New Request");
        newBtn.setOnAction(e -> startNewConversation());

        sendRow.getChildren().addAll(input, sendBtn, newBtn);

        HBox ridRow = new HBox(6);
        ridRow.setAlignment(Pos.CENTER_LEFT);
        ridRow.getChildren().addAll(UiKit.small("Request ID:"), ridValue);

        chatScroll.setFitToWidth(true);
        chatScroll.setStyle("-fx-background-color: transparent;");
        chatBox.setPadding(new Insets(10));
        chatBox.setFillWidth(true);
        VBox.setVgrow(chatScroll, Priority.ALWAYS);

        card.getChildren().addAll(title, sendRow, ridRow, UiKit.divider(), chatScroll);
        VBox.setVgrow(card, Priority.ALWAYS);
        Region chatCardWrap = UiKit.cardContainer(card);
        VBox.setVgrow(chatCardWrap, Priority.ALWAYS);

        // ── ✅ NEW: Order History card ──
        Label historyTitle = new Label("Order History");
        historyTitle.setStyle("-fx-font-weight: 800; -fx-font-size: 15;");

        ScrollPane historyScroll = new ScrollPane(historyBox);
        historyScroll.setFitToWidth(true);
        historyScroll.setStyle("-fx-background-color: transparent;");
        historyScroll.setPrefHeight(170);
        historyBox.setPadding(new Insets(4));
        historyBox.setFillWidth(true);

        VBox historyInner = new VBox(8, historyTitle, UiKit.divider(), historyScroll);
        historyInner.setPadding(new Insets(10));
        Region historyCardWrap = UiKit.cardContainer(historyInner);

        VBox rightColumn = new VBox(14, chatCardWrap, historyCardWrap);
        VBox.setVgrow(chatCardWrap, Priority.ALWAYS);

        // ── Left: My Requests ──
        myReqList.setPrefWidth(340);
        myReqList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(RequestItem it, boolean empty) {
                super.updateItem(it, empty);
                if (empty || it == null) { setGraphic(null); return; }

                VBox v = new VBox(2);
                boolean completed = "COMPLETED".equalsIgnoreCase(it.status);
                String label = "Buyer " + it.buyerRequestNo + (completed ? " (COMPLETED)" : "");

                Label t = new Label(label);
                t.setStyle("-fx-font-weight: 800;" + (completed ? " -fx-opacity: 0.70;" : ""));

                Label s = UiKit.small(it.requestId);
                Label p = UiKit.small(it.previewText);
                p.setStyle(p.getStyle() + "; -fx-opacity: 0.55;");

                v.getChildren().addAll(t, s, p);
                setGraphic(v);
                setOpacity(completed ? 0.85 : 1.0);
            }
        });

        myReqList.getSelectionModel().selectedItemProperty().addListener((o, a, it) -> {
            if (it != null) openConversation(it.requestId, it.status);
        });

        VBox left = new VBox(10, UiKit.small("My Requests"), myReqList);
        left.setPadding(new Insets(6));
        Region leftCardWrap = UiKit.cardContainer(left);

        HBox center = new HBox(18, leftCardWrap, rightColumn);
        center.setPadding(new Insets(26));
        center.setAlignment(Pos.TOP_CENTER);
        HBox.setHgrow(rightColumn, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(center);
        UiKit.applyAppBackground(root);

        renderChat();
        updateSendButtonState();
        input.setOnAction(e -> onSend());

        stage.setTitle("Toptri Chat - Buyer");
        stage.setScene(new Scene(root, 1060, 820));
        stage.show();
        stage.setOnCloseRequest(e -> cleanup());

        attachMyRequestsListener();
    }

    // ============================================================
    // STATE
    // ============================================================

    private boolean isCurrentCompleted() {
        return currentRequestId != null && "COMPLETED".equalsIgnoreCase(currentRequestStatus);
    }

    private void updateSendButtonState() {
        boolean disable = isCurrentCompleted();
        sendBtn.setDisable(disable);
        if (disable) {
            input.setDisable(true);
            input.setPromptText("Request completed ✅ — start New Request to chat again");
        } else {
            input.setDisable(false);
            input.setPromptText("e.g. '2 nasi padang 3 es teh' or just 'drink'");
        }
    }

    // ============================================================
    // SEND
    // ============================================================

    private void onSend() {
        if (isCurrentCompleted()) return;
        String text = input.getText().trim();
        if (text.isBlank()) return;
        input.clear();

        if (currentRequestId == null) {
            currentRequestId = makeRequestId();
            currentRequestStatus = "OPEN";
            ridValue.setText(currentRequestId);
            messages.clear();
            offersByBuyerMessageId.clear();
            latestBuyerMessageId = null;
            renderChat();
            updateSendButtonState();

            final String firstText = text;
            final long buyerRequestNo = nextBuyerRequestNo();

            new Thread(() -> {
                try {
                    fs.createConversation(currentRequestId, buyerId, firstText, buyerRequestNo);
                    attachMessagesListener(currentRequestId);
                    attachAllOffersListener(currentRequestId);
                } catch (Exception ex) {
                    showError("Failed to start chat", ex.getMessage());
                }
            }).start();
            return;
        }

        final String reqIdFinal = currentRequestId;
        final String textFinal = text;
        new Thread(() -> {
            try {
                fs.sendBuyerMessage(reqIdFinal, buyerId, textFinal);
            } catch (Exception ex) {
                showError("Send failed", ex.getMessage());
            }
        }).start();
    }

    // ============================================================
    // CONVERSATION LIFECYCLE
    // ============================================================

    private long nextBuyerRequestNo() {
        long last = PREFS.getLong("buyerRequestNoCounter", 0L);
        long next = last + 1;
        PREFS.putLong("buyerRequestNoCounter", next);
        return next;
    }

    private void startNewConversation() {
        if (messagesListener != null) messagesListener.remove();
        if (offersAllListener != null) offersAllListener.remove();
        currentRequestId = null;
        currentRequestStatus = "OPEN";
        ridValue.setText("-");
        messages.clear();
        offersByBuyerMessageId.clear();
        latestBuyerMessageId = null;
        renderChat();
        updateSendButtonState();
    }

    private void openConversation(String requestId, String status) {
        if (messagesListener != null) messagesListener.remove();
        if (offersAllListener != null) offersAllListener.remove();
        currentRequestId = requestId;
        currentRequestStatus = (status == null || status.isBlank()) ? "OPEN" : status;
        ridValue.setText(requestId);
        messages.clear();
        offersByBuyerMessageId.clear();
        latestBuyerMessageId = null;
        renderChat();
        updateSendButtonState();
        attachMessagesListener(requestId);
        attachAllOffersListener(requestId);
    }

    // ============================================================
    // LISTENERS
    // ============================================================

    private void attachMessagesListener(String requestId) {
        if (messagesListener != null) messagesListener.remove();
        messagesListener = fs.listenMessages(
                requestId,
                snap -> Platform.runLater(() -> onMessagesUpdate(snap)),
                err -> Platform.runLater(() -> showError("Messages listener error", err.getMessage()))
        );
    }

    private void onMessagesUpdate(QuerySnapshot snap) {
        messages.clear();
        latestBuyerMessageId = null;
        for (QueryDocumentSnapshot d : snap.getDocuments()) {
            String id = d.getId();
            String senderType = safe(d.getString("senderType"));
            String senderId = safe(d.getString("senderId"));
            String text = safe(d.getString("text"));
            // ✅ NEW: parse multi-item order from text
            List<FirestoreService.OrderItem> orderItems = FirestoreService.parseOrderItems(text);
            messages.add(new Message(id, senderType, senderId, text, orderItems));
            if ("BUYER".equalsIgnoreCase(senderType)) {
                latestBuyerMessageId = id;
            }
        }
        renderChat();
        Platform.runLater(() -> chatScroll.setVvalue(1.0));
    }

    private void attachAllOffersListener(String requestId) {
        if (offersAllListener != null) offersAllListener.remove();
        offersAllListener = fs.listenAllOffers(
                requestId,
                snap -> Platform.runLater(() -> onAllOffersUpdate(snap)),
                err -> Platform.runLater(() -> showError("Offers listener error", err.getMessage()))
        );
    }

    @SuppressWarnings("unchecked")
    private void onAllOffersUpdate(QuerySnapshot snap) {
        offersByBuyerMessageId.clear();
        for (QueryDocumentSnapshot d : snap.getDocuments()) {
            String buyerMsgId = safe(d.getString("buyerMessageId"));
            if (buyerMsgId.isBlank()) continue;

            String id = d.getId();
            String sellerId = safe(d.getString("sellerId"));
            String sellerContact = safe(d.getString("sellerContact")); // ✅ NEW
            String vendor = safe(d.getString("vendor"));

            int etaMinutes = 0;
            Long eta = d.getLong("etaMinutes");
            if (eta != null) etaMinutes = eta.intValue();

            double rating = 0.0;
            Double r = d.getDouble("rating");
            if (r != null) rating = r;

            // ✅ NEW: read multi-item offer lines
            List<Map<String, Object>> rawLines = (List<Map<String, Object>>) d.get("offerLines");
            List<OfferLine> offerLines = new ArrayList<>();
            int grandTotal = 0;

            if (rawLines != null && !rawLines.isEmpty()) {
                for (Map<String, Object> line : rawLines) {
                    String name = safe((String) line.get("menuName"));
                    int qty = line.get("qty") instanceof Long ? ((Long) line.get("qty")).intValue() : 1;
                    int price = line.get("price") instanceof Long ? ((Long) line.get("price")).intValue() : 0;
                    offerLines.add(new OfferLine(name, qty, price));
                    grandTotal += qty * price;
                }
            } else {
                // legacy single-item fallback
                String menuName = safe(d.getString("menuName"));
                int price = 0;
                Long p = d.getLong("price");
                if (p != null) price = p.intValue();
                int qty = 1;
                Long q = d.getLong("quantity");
                if (q != null && q > 0) qty = q.intValue();
                offerLines.add(new OfferLine(menuName, qty, price));
                grandTotal = qty * price;
            }

            Long storedTotal = d.getLong("grandTotal");
            if (storedTotal != null && storedTotal > 0) grandTotal = storedTotal.intValue();

            Offer offer = new Offer(id, sellerId, offerLines, grandTotal, vendor, etaMinutes, rating, sellerContact);
            offersByBuyerMessageId.computeIfAbsent(buyerMsgId, k -> new ArrayList<>()).add(offer);
        }
        renderChat();
        Platform.runLater(() -> chatScroll.setVvalue(1.0));
    }

    // ============================================================
    // RENDER CHAT
    // ============================================================

    private void renderChat() {
        chatBox.getChildren().clear();

        if (currentRequestId == null) {
            chatBox.getChildren().add(UiKit.bubbleWait(
                "Send your first message to start a new request.\n" +
                "Or select an old request from the left.\n\n" +
                "💡 You can order multiple items at once!\n" +
                "   e.g. '2 nasi padang 3 es teh'"
            ));
            return;
        }

        boolean completed = isCurrentCompleted();
        if (completed) {
            chatBox.getChildren().add(UiKit.bubbleWait("✅ Request COMPLETED. Start a New Request to chat again."));
            chatBox.getChildren().add(UiKit.divider());
        }

        if (messages.isEmpty()) {
            chatBox.getChildren().add(UiKit.bubbleWait("⏳ Loading conversation..."));
            return;
        }

        for (Message m : messages) {
            boolean isBuyer = "BUYER".equalsIgnoreCase(m.senderType);
            if (isBuyer) {
                // ✅ NEW: show multi-item breakdown in bubble
                chatBox.getChildren().add(UiKit.bubbleRight(buildBuyerBubbleText(m)));

                List<Offer> offs = offersByBuyerMessageId.getOrDefault(m.id, Collections.emptyList());
                if (!offs.isEmpty()) {
                    for (Offer o : offs) {
                        final Offer offerRef = o;
                        Region card = buildOfferCard(o, completed, () -> {
                            if (!completed) onBuy(offerRef);
                        });
                        chatBox.getChildren().add(card);
                    }
                } else {
                    if (m.id.equals(latestBuyerMessageId) && !completed) {
                        chatBox.getChildren().add(UiKit.bubbleWait("⏳ Waiting for seller offers..."));
                    }
                }
                chatBox.getChildren().add(UiKit.divider());
            } else {
                chatBox.getChildren().add(UiKit.bubbleWait(m.text));
            }
        }
    }

    // ✅ NEW: multi-item buyer bubble text
    private String buildBuyerBubbleText(Message m) {
        if (m.orderItems == null || m.orderItems.size() <= 1) return m.text;
        StringBuilder sb = new StringBuilder(m.text).append("\n");
        for (FirestoreService.OrderItem oi : m.orderItems) {
            sb.append("  • ").append(oi.qty).append("× ").append(oi.name).append("\n");
        }
        return sb.toString().trim();
    }

    // ✅ NEW: offer card with per-line items, grand total, seller contact
    private Region buildOfferCard(Offer o, boolean completed, Runnable onBuy) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(14));
        box.setStyle("-fx-background-color: " + UiKit.OFFER_BG + "; -fx-background-radius: 18;");

        // seller + contact
        String sellerLabel = o.sellerId.isBlank() ? "Seller" : o.sellerId;
        Label sellerLbl = new Label(sellerLabel);
        sellerLbl.setStyle("-fx-font-weight: 800; -fx-font-size: 13;");
        box.getChildren().add(sellerLbl);

        // ✅ NEW: seller contact
        if (!o.sellerContact.isBlank()) {
            Label contactLbl = new Label("📞 " + o.sellerContact);
            contactLbl.setStyle("-fx-font-size: 12; -fx-text-fill: #5F5BFF; -fx-font-weight: 600;");
            box.getChildren().add(contactLbl);
        }

        box.getChildren().add(UiKit.divider());

        // ✅ NEW: per-line items (multi-item format: name qty×price = subtotal)
        for (OfferLine line : o.offerLines) {
            int lineTotal = line.qty * line.price;
            String lineText = line.name + "   " + line.qty + "×" + UiKit.rupiah(line.price)
                    + "  =  " + UiKit.rupiah(lineTotal);
            Label lineLbl = new Label(lineText);
            lineLbl.setStyle("-fx-font-size: 13;");
            box.getChildren().add(lineLbl);
        }

        // ✅ NEW: grand total
        Label totalLbl = new Label("Total:  " + UiKit.rupiah(o.grandTotal));
        totalLbl.setStyle("-fx-font-weight: 800; -fx-font-size: 16; -fx-padding: 4 0 0 0;");
        box.getChildren().add(totalLbl);

        // meta (vendor, eta, rating)
        List<String> metaParts = new ArrayList<>();
        if (!o.vendor.isBlank()) metaParts.add(o.vendor);
        if (o.etaMinutes > 0) metaParts.add("ETA " + o.etaMinutes + " min");
        if (o.rating > 0) metaParts.add("⭐ " + String.format(Locale.US, "%.1f", o.rating));
        if (!metaParts.isEmpty()) {
            box.getChildren().add(UiKit.small(String.join(" • ", metaParts)));
        }

        // buy button showing total
        Button buyBtn = new Button("Buy  " + UiKit.rupiah(o.grandTotal));
        buyBtn.setOnAction(e -> onBuy.run());
        buyBtn.setStyle(
                "-fx-background-color: " + UiKit.GREEN_BTN + ";" +
                "-fx-text-fill: white;" +
                "-fx-font-weight: 800;" +
                "-fx-padding: 8 18;" +
                "-fx-background-radius: 14;"
        );
        box.getChildren().add(buyBtn);

        if (completed) {
            box.setOpacity(0.65);
            box.setDisable(true);
        }

        HBox row = new HBox(box);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    // ============================================================
    // ✅ NEW: ORDER HISTORY
    // ============================================================

    private void renderHistory(List<RequestItem> allItems) {
        historyBox.getChildren().clear();
        List<RequestItem> completed = allItems.stream()
                .filter(it -> "COMPLETED".equalsIgnoreCase(it.status))
                .collect(Collectors.toList());

        if (completed.isEmpty()) {
            historyBox.getChildren().add(UiKit.small("No completed orders yet."));
            return;
        }

        for (RequestItem it : completed) {
            HBox row = new HBox(12);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(7, 12, 7, 12));
            row.setStyle("-fx-background-color: " + UiKit.OFFER_BG + "; -fx-background-radius: 10;");

            Label num = new Label("Order #" + it.buyerRequestNo);
            num.setStyle("-fx-font-weight: 800; -fx-font-size: 12;");

            String preview = it.previewText.length() > 32
                    ? it.previewText.substring(0, 32) + "…"
                    : it.previewText;
            Label prev = UiKit.small("  " + preview);
            HBox.setHgrow(prev, Priority.ALWAYS);

            Label badge = new Label("✅ DONE");
            badge.setStyle("-fx-text-fill: " + UiKit.GREEN_BTN + "; -fx-font-weight: 800; -fx-font-size: 11;");

            row.getChildren().addAll(num, prev, badge);
            historyBox.getChildren().add(row);
        }
    }

    // ============================================================
    // BUY
    // ============================================================

    private void onBuy(Offer offer) {
        if (currentRequestId == null || currentRequestId.isBlank()) return;
        if (isCurrentCompleted()) return;

        // ✅ NEW: pre-fill name & address from saved Preferences
        String savedName = PREFS.get("buyerName", "");
        String savedAddress = PREFS.get("buyerAddress", "");

        TextInputDialog nameDlg = new TextInputDialog(savedName);
        nameDlg.setTitle("Buyer Info");
        nameDlg.setHeaderText("Your name (optional)");
        String name = nameDlg.showAndWait().orElse(savedName);

        TextInputDialog addrDlg = new TextInputDialog(savedAddress);
        addrDlg.setTitle("Buyer Info");
        addrDlg.setHeaderText("Delivery address (optional)");
        String address = addrDlg.showAndWait().orElse(savedAddress);

        // ✅ persist for next time
        PREFS.put("buyerName", name == null ? "" : name.trim());
        PREFS.put("buyerAddress", address == null ? "" : address.trim());

        final String reqIdFinal = currentRequestId;
        final String offerIdFinal = offer.id;
        final String nameFinal = name;
        final String addrFinal = address;

        new Thread(() -> {
            try {
                fs.completeRequestWithQuantity(reqIdFinal, offerIdFinal, nameFinal, addrFinal, offer.grandTotal);

                Platform.runLater(() -> {
                    requestStatusById.put(reqIdFinal, "COMPLETED");
                    currentRequestStatus = "COMPLETED";
                    updateSendButtonState();
                    renderChat();

                    // ✅ NEW: itemized confirmation dialog
                    StringBuilder sb = new StringBuilder();
                    for (OfferLine line : offer.offerLines) {
                        sb.append(line.name)
                          .append("   ").append(line.qty).append("×").append(UiKit.rupiah(line.price))
                          .append("  =  ").append(UiKit.rupiah(line.qty * line.price)).append("\n");
                    }
                    sb.append("─────────────────────\n");
                    sb.append("Total:  ").append(UiKit.rupiah(offer.grandTotal)).append("\n");
                    sb.append("Address: ").append(addrFinal.isBlank() ? "-" : addrFinal);

                    Alert ok = new Alert(Alert.AlertType.INFORMATION);
                    ok.setTitle("Purchase Confirmed");
                    ok.setHeaderText("✅ Order confirmed!");
                    ok.setContentText(sb.toString());
                    ok.showAndWait();
                });

            } catch (Exception ex) {
                showError("Complete failed", ex.getMessage());
            }
        }).start();
    }

    // ============================================================
    // MY REQUESTS LISTENER
    // ============================================================

    private void attachMyRequestsListener() {
        if (myReqListener != null) myReqListener.remove();
        myReqListener = fs.listenBuyerRequests(
                buyerId,
                snap -> Platform.runLater(() -> {
                    List<RequestItem> items = new ArrayList<>();
                    requestStatusById.clear();

                    for (QueryDocumentSnapshot d : snap.getDocuments()) {
                        String rid = safe(d.getString("requestId"));
                        if (rid.isBlank()) continue;
                        String preview = safe(d.getString("buyerText"));
                        String st = safe(d.getString("status"));
                        if (st.isBlank()) st = "OPEN";
                        requestStatusById.put(rid, st);
                        long no = 0L;
                        Long n = d.getLong("buyerRequestNo");
                        if (n != null) no = n;
                        if (no <= 0) no = items.size() + 1;
                        items.add(new RequestItem(rid, preview, no, st));
                    }

                    items.sort(Comparator.comparingLong(a -> a.buyerRequestNo));
                    myReqList.getItems().setAll(items);
                    renderHistory(items); // ✅ NEW

                    if (currentRequestId != null) {
                        currentRequestStatus = requestStatusById.getOrDefault(currentRequestId, currentRequestStatus);
                        updateSendButtonState();
                        renderChat();
                    }
                    if (currentRequestId == null && !items.isEmpty()) {
                        updateSendButtonState();
                    }
                }),
                err -> Platform.runLater(() -> showError("Requests listener error", err.getMessage()))
        );
    }

    // ============================================================
    // UTILS
    // ============================================================

    private void showError(String title, String msg) {
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.ERROR);
            a.setTitle(title);
            a.setHeaderText(title);
            a.setContentText(msg == null ? "(no message)" : msg);
            a.showAndWait();
        });
    }

    private void cleanup() {
        if (messagesListener != null) messagesListener.remove();
        if (offersAllListener != null) offersAllListener.remove();
        if (myReqListener != null) myReqListener.remove();
    }

    private String makeRequestId() {
        return "req_" + Integer.toHexString(new Random().nextInt()).replace("-", "")
                + "_" + System.currentTimeMillis();
    }

    private String safe(String s) { return s == null ? "" : s; }

    private static String getOrCreateBuyerId() {
        Preferences p = Preferences.userNodeForPackage(BuyerWindow.class);
        String id = p.get("buyerId", "");
        if (id == null || id.isBlank()) {
            id = "buyer_" + UUID.randomUUID();
            p.put("buyerId", id);
        }
        return id;
    }

    // ============================================================
    // INNER CLASSES
    // ============================================================

    private static class RequestItem {
        final String requestId, previewText, status;
        final long buyerRequestNo;
        RequestItem(String requestId, String previewText, long buyerRequestNo, String status) {
            this.requestId = requestId;
            this.previewText = previewText == null ? "" : previewText;
            this.buyerRequestNo = buyerRequestNo;
            this.status = status == null ? "OPEN" : status;
        }
    }

    private static class Message {
        final String id, senderType, senderId, text;
        final List<FirestoreService.OrderItem> orderItems;
        Message(String id, String senderType, String senderId, String text, List<FirestoreService.OrderItem> orderItems) {
            this.id = id;
            this.senderType = senderType == null ? "" : senderType;
            this.senderId = senderId == null ? "" : senderId;
            this.text = text == null ? "" : text;
            this.orderItems = orderItems == null ? new ArrayList<>() : orderItems;
        }
    }

    // ✅ NEW: one line in a multi-item offer
    static class OfferLine {
        final String name;
        final int qty, price;
        OfferLine(String name, int qty, int price) {
            this.name = name == null ? "" : name;
            this.qty = Math.max(qty, 1);
            this.price = price;
        }
    }

    // ✅ NEW: offer with multiple lines + grand total + seller contact
    private static class Offer {
        final String id, sellerId, vendor, sellerContact;
        final List<OfferLine> offerLines;
        final int grandTotal, etaMinutes;
        final double rating;

        Offer(String id, String sellerId, List<OfferLine> offerLines, int grandTotal,
              String vendor, int etaMinutes, double rating, String sellerContact) {
            this.id = id;
            this.sellerId = sellerId == null ? "" : sellerId;
            this.offerLines = offerLines == null ? new ArrayList<>() : offerLines;
            this.grandTotal = grandTotal;
            this.vendor = vendor == null ? "" : vendor;
            this.etaMinutes = etaMinutes;
            this.rating = rating;
            this.sellerContact = sellerContact == null ? "" : sellerContact;
        }
    }
}