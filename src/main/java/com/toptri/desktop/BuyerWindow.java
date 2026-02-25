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

    private Button sendBtn;
    private Button newBtn;

    public BuyerWindow(FirestoreService fs) {
        this.fs = fs;
    }

    public static void open(FirestoreService fs) {
        new BuyerWindow(fs).show();
    }

    public void show() {
        Stage stage = new Stage();

        Region header = UiKit.headerBar("Toptri Chat - Buyer");

        // RIGHT: Chat
        VBox card = new VBox(12);
        card.setPadding(new Insets(6));

        Label title = UiKit.h1("Buyer");

        HBox sendRow = new HBox(10);
        input.setPromptText("Type message (example: nasi padang / drink)");
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

        card.getChildren().addAll(title, sendRow, ridRow, UiKit.divider(), chatScroll);
        Region chatCardWrap = UiKit.cardContainer(card);

        // LEFT: My Requests
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

        HBox center = new HBox(18, leftCardWrap, chatCardWrap);
        center.setPadding(new Insets(26));
        center.setAlignment(Pos.TOP_CENTER);

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(center);
        UiKit.applyAppBackground(root);

        renderChat();
        updateSendButtonState(); // ✅ initial state

        // ✅ enable Send on Enter
        input.setOnAction(e -> onSend());

        stage.setTitle("Toptri Chat - Buyer");
        stage.setScene(new Scene(root, 1060, 720));
        stage.show();

        stage.setOnCloseRequest(e -> cleanup());

        attachMyRequestsListener();
    }

    private boolean isCurrentCompleted() {
        return currentRequestId != null && "COMPLETED".equalsIgnoreCase(currentRequestStatus);
    }

    private void updateSendButtonState() {
        // ✅ rule:
        // - if completed -> disable send
        // - else always allow send (it can auto-create new request)
        boolean disable = isCurrentCompleted();
        sendBtn.setDisable(disable);

        if (disable) {
            input.setDisable(true);
            input.setPromptText("Request completed ✅ (start New Request for new chat)");
        } else {
            input.setDisable(false);
            input.setPromptText("Type message (example: nasi padang / drink)");
        }
    }

    private void onSend() {
        if (isCurrentCompleted()) return;

        String text = input.getText().trim();
        if (text.isBlank()) return;
        input.clear();

        // ✅ If no current request, auto-create one
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

    private long nextBuyerRequestNo() {
        Preferences p = Preferences.userNodeForPackage(BuyerWindow.class);
        long last = p.getLong("buyerRequestNoCounter", 0L);
        long next = last + 1;
        p.putLong("buyerRequestNoCounter", next);
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

            messages.add(new Message(id, senderType, senderId, text));

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

    private void onAllOffersUpdate(QuerySnapshot snap) {
        offersByBuyerMessageId.clear();

        for (QueryDocumentSnapshot d : snap.getDocuments()) {
            String buyerMsgId = safe(d.getString("buyerMessageId"));
            if (buyerMsgId.isBlank()) continue;

            String id = d.getId();
            String sellerId = safe(d.getString("sellerId"));
            String menuName = safe(d.getString("menuName"));

            int price = 0;
            Long p = d.getLong("price");
            if (p != null) price = p.intValue();

            String vendor = safe(d.getString("vendor"));

            int etaMinutes = 0;
            Long eta = d.getLong("etaMinutes");
            if (eta != null) etaMinutes = eta.intValue();

            double rating = 0.0;
            Double r = d.getDouble("rating");
            if (r != null) rating = r;

            Offer offer = new Offer(id, sellerId, menuName, price, vendor, etaMinutes, rating);
            offersByBuyerMessageId.computeIfAbsent(buyerMsgId, k -> new ArrayList<>()).add(offer);
        }

        renderChat();
        Platform.runLater(() -> chatScroll.setVvalue(1.0));
    }

    private void renderChat() {
        chatBox.getChildren().clear();

        if (currentRequestId == null) {
            chatBox.getChildren().add(
                    UiKit.bubbleWait("Send your first message to start a new request.\nOr select an old request from the left.")
            );
            return;
        }

        boolean completed = isCurrentCompleted();
        if (completed) {
            chatBox.getChildren().add(UiKit.bubbleWait("✅ This request is COMPLETED. Start a New Request to chat again."));
            chatBox.getChildren().add(UiKit.divider());
        }

        if (messages.isEmpty()) {
            chatBox.getChildren().add(UiKit.bubbleWait("⏳ Loading conversation..."));
            return;
        }

        for (Message m : messages) {
            boolean isBuyer = "BUYER".equalsIgnoreCase(m.senderType);

            if (isBuyer) {
                chatBox.getChildren().add(UiKit.bubbleRight(m.text));

                List<Offer> offs = offersByBuyerMessageId.getOrDefault(m.id, Collections.emptyList());
                if (!offs.isEmpty()) {
                    for (Offer o : offs) {
                        String sellerPart = o.sellerId.isBlank() ? "-" : o.sellerId;
                        String vendorPart = o.vendor.isBlank() ? "-" : o.vendor;
                        String etaPart = (o.etaMinutes > 0) ? ("ETA " + o.etaMinutes + " min") : "ETA -";
                        String ratingPart = (o.rating > 0) ? ("⭐ " + String.format(Locale.US, "%.1f", o.rating)) : "⭐ -";

                        String subtitle = UiKit.rupiah(o.price)
                                + " • " + sellerPart
                                + " • " + vendorPart
                                + " • " + etaPart
                                + " • " + ratingPart;

                        Region card = UiKit.offerCard(o.menuName, subtitle, () -> {
                            if (!completed) onBuy(o);
                        });

                        if (completed) {
                            card.setOpacity(0.65);
                            card.setDisable(true);
                        }

                        chatBox.getChildren().add(card);
                    }
                } else {
                    if (m.id.equals(latestBuyerMessageId) && !completed) {
                        chatBox.getChildren().add(UiKit.bubbleWait("⏳ Waiting seller offers for this message..."));
                    }
                }

                chatBox.getChildren().add(UiKit.divider());
            } else {
                chatBox.getChildren().add(UiKit.bubbleWait(m.text));
            }
        }
    }

    private void onBuy(Offer offer) {
        if (currentRequestId == null || currentRequestId.isBlank()) return;
        if (isCurrentCompleted()) return;

        TextInputDialog nameDlg = new TextInputDialog("");
        nameDlg.setTitle("Buyer Info");
        nameDlg.setHeaderText("Buyer name (optional)");
        String name = nameDlg.showAndWait().orElse("");

        TextInputDialog addrDlg = new TextInputDialog("");
        addrDlg.setTitle("Buyer Info");
        addrDlg.setHeaderText("Address (optional)");
        String address = addrDlg.showAndWait().orElse("");

        final String reqIdFinal = currentRequestId;
        final String offerIdFinal = offer.id;

        new Thread(() -> {
            try {
                fs.completeRequestSimple(reqIdFinal, offerIdFinal, name, address);

                Platform.runLater(() -> {
                    requestStatusById.put(reqIdFinal, "COMPLETED");
                    currentRequestStatus = "COMPLETED";
                    updateSendButtonState();
                    renderChat();

                    Alert ok = new Alert(Alert.AlertType.INFORMATION);
                    ok.setTitle("Purchase");
                    ok.setHeaderText("✅ Purchase simulated");
                    ok.setContentText("Request marked as COMPLETED ✅");
                    ok.showAndWait();
                });

            } catch (Exception ex) {
                showError("Complete failed", ex.getMessage());
            }
        }).start();
    }

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

                    if (currentRequestId != null) {
                        currentRequestStatus = requestStatusById.getOrDefault(currentRequestId, currentRequestStatus);
                        updateSendButtonState();
                        renderChat();
                    }

                    if (currentRequestId == null && !items.isEmpty()) {
                        // do nothing auto-open; let user pick
                        updateSendButtonState();
                    }
                }),
                err -> Platform.runLater(() -> showError("Requests listener error", err.getMessage()))
        );
    }

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

    private static class RequestItem {
        final String requestId;
        final String previewText;
        final long buyerRequestNo;
        final String status;

        RequestItem(String requestId, String previewText, long buyerRequestNo, String status) {
            this.requestId = requestId;
            this.previewText = previewText == null ? "" : previewText;
            this.buyerRequestNo = buyerRequestNo;
            this.status = status == null ? "OPEN" : status;
        }
    }

    private static class Message {
        final String id;
        final String senderType;
        final String senderId;
        final String text;

        Message(String id, String senderType, String senderId, String text) {
            this.id = id;
            this.senderType = senderType == null ? "" : senderType;
            this.senderId = senderId == null ? "" : senderId;
            this.text = text == null ? "" : text;
        }
    }

    private static class Offer {
        final String id;
        final String sellerId;
        final String menuName;
        final int price;
        final String vendor;
        final int etaMinutes;
        final double rating;

        Offer(String id, String sellerId, String menuName, int price, String vendor, int etaMinutes, double rating) {
            this.id = id;
            this.sellerId = sellerId == null ? "" : sellerId;
            this.menuName = menuName == null ? "" : menuName;
            this.price = price;
            this.vendor = vendor == null ? "" : vendor;
            this.etaMinutes = etaMinutes;
            this.rating = rating;
        }
    }
}