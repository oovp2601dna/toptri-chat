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

public class SellerWindow {

    private final FirestoreService fs;
    private final String sellerId;

    private ListenerRegistration requestsListener;
    private ListenerRegistration messagesListener;

    private final Label status = new Label("Waiting...");
    private final ListView<RequestItem> requestList = new ListView<>();

    private final Label reqIdValue = new Label("-");
    private final StackPane buyerMsgHolder = new StackPane();

    // ✅ NEW: quantity indicator label
    private final Label qtyDetectedLabel = new Label("");

    private final ListView<FirestoreService.MenuItem> menuListView = new ListView<>();

    // action row inputs
    private final TextField mainInput = new TextField();
    private final TextField priceInput = new TextField();
    private final TextField vendorInput = new TextField();

    // ✅ NEW: seller contact/phone field
    private final TextField contactInput = new TextField();

    private final Button sendBtn = UiKit.primaryButton("Send");

    private volatile String selectedRequestId = null;
    private volatile String selectedBuyerText = "";
    private volatile String latestBuyerMessageId = null;
    private volatile List<FirestoreService.OrderItem> latestOrderItems = new ArrayList<>(); // ✅ NEW

    private final Set<String> offeredKeys = new HashSet<>();
    private int sentCountForThisRequest = 0;

    private boolean autoFollowLatest = true;
    private String lastAutoSelectedRequestId = null;

    public SellerWindow(FirestoreService fs, String sellerId) {
        this.fs = fs;
        this.sellerId = sellerId;
    }

    public static void open(FirestoreService fs, String sellerId) {
        new SellerWindow(fs, sellerId).show();
    }

    public void show() {
        Stage stage = new Stage();

        Region header = UiKit.headerBar("Toptri Chat - Seller");

        // LEFT: Inbox
        VBox inboxBox = new VBox(10);
        inboxBox.setPadding(new Insets(12));

        Label inboxTitle = new Label("Request Inbox");
        inboxTitle.setStyle("-fx-font-weight: 800; -fx-font-size: 16;");

        requestList.setPrefWidth(340);
        requestList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(RequestItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); return; }
                VBox v = new VBox(2);
                String title = item.buyerRequestNo > 0 ? ("Buyer " + item.buyerRequestNo) : "Buyer";
                Label t = new Label(title);
                t.setStyle("-fx-font-weight: 800;");
                Label s = UiKit.small(item.requestId);
                Label p = UiKit.small(item.previewText);
                p.setStyle(p.getStyle() + "; -fx-opacity: 0.60;");
                v.getChildren().addAll(t, s, p);
                setGraphic(v);
            }
        });

        requestList.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) onSelectRequest(newV);
        });

        inboxBox.getChildren().addAll(inboxTitle, requestList);
        Region inboxCard = UiKit.cardContainer(inboxBox);

        // RIGHT: Dashboard
        VBox dash = new VBox(10);
        dash.setPadding(new Insets(6));

        Label title = UiKit.h1("Seller Dashboard");

        HBox statusRow = new HBox(8, UiKit.small("Status:"), status);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        buyerMsgHolder.getChildren().setAll(UiKit.messagePill("-"));

        // ✅ NEW: qty detected label style
        qtyDetectedLabel.setStyle("-fx-text-fill: #16A34A; -fx-font-weight: 800; -fx-font-size: 12;");

        // action row
        mainInput.setPromptText("Chat / offer name (e.g. fruit tea)");
        HBox.setHgrow(mainInput, Priority.ALWAYS);
        priceInput.setPromptText("Price (optional)");
        priceInput.setPrefWidth(150);
        vendorInput.setPromptText("Vendor (optional)");
        vendorInput.setPrefWidth(170);

        // ✅ NEW: contact input
        contactInput.setPromptText("📞 Contact (optional)");
        contactInput.setPrefWidth(180);

        sendBtn.setMinWidth(110);
        sendBtn.setOnAction(e -> onSendAuto());
        mainInput.setOnAction(e -> onSendAuto());
        priceInput.setOnAction(e -> onSendAuto());
        vendorInput.setOnAction(e -> onSendAuto());
        contactInput.setOnAction(e -> onSendAuto());

        HBox actionRow = new HBox(10, mainInput, priceInput, vendorInput, contactInput, sendBtn);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        // menus list
        menuListView.setPlaceholder(UiKit.small("Waiting menus..."));
        menuListView.setPrefHeight(320);
        VBox.setVgrow(menuListView, Priority.ALWAYS);
        menuListView.setFixedCellSize(82);

        menuListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(FirestoreService.MenuItem m, boolean empty) {
                super.updateItem(m, empty);
                if (empty || m == null) {
                    setGraphic(null); setText(null);
                    setDisable(false); setOpacity(1.0); setOnMouseClicked(null);
                    return;
                }

                boolean alreadySent = isMenuAlreadyOfferedForLatest(m);

                // ✅ NEW: show total per item based on detected qty
                int totalQty = latestOrderItems.stream()
                        .mapToInt(oi -> oi.qty)
                        .sum();
                if (totalQty < 1) totalQty = 1;

                String priceStr = UiKit.rupiah(m.getPrice());
                String totalStr = (totalQty > 1)
                        ? "  ×" + totalQty + " = " + UiKit.rupiah(m.getPrice() * totalQty)
                        : "";

                String subtitle = priceStr + totalStr
                        + " • " + m.vendorOrDash()
                        + " • " + m.etaText()
                        + " • " + m.ratingText();

                String titleText = alreadySent ? (m.getName() + " (DIPILIH)") : m.getName();
                VBox card = UiKit.menuCard(titleText, subtitle);
                card.setMaxWidth(Double.MAX_VALUE);

                if (alreadySent) {
                    card.setOpacity(0.55);
                    card.setDisable(true);
                    card.setStyle(card.getStyle()
                            + "; -fx-background-color: #F3F4F6;"
                            + " -fx-border-color: rgba(0,0,0,0.10);");
                }

                setGraphic(card);
                setText(null);
                setOnMouseClicked(ev -> {
                    if (alreadySent) return;
                    if (getItem() == null) return;
                    onSendOfferFromList(getItem());
                    Platform.runLater(() -> menuListView.getSelectionModel().clearSelection());
                });
            }
        });

        dash.getChildren().addAll(
                title,
                statusRow,
                UiKit.divider(),
                UiKit.small("Seller"), new Label(sellerId),
                UiKit.divider(),
                UiKit.small("Request ID"), reqIdValue,
                UiKit.divider(),
                UiKit.small("Latest Buyer Message"),
                buyerMsgHolder,
                qtyDetectedLabel, // ✅ NEW
                UiKit.divider(),
                UiKit.small("Quick Actions (auto mode)"),
                UiKit.small("• Price empty = Chat  • Price filled = Offer  • Price+Vendor = Add Menu\n• Contact shown to buyer in offer card"),
                actionRow,
                UiKit.divider(),
                UiKit.small("Menus (click to send, max 3)"),
                menuListView
        );

        Region dashCard = UiKit.cardContainer(dash);

        HBox content = new HBox(18, inboxCard, dashCard);
        content.setPadding(new Insets(26));
        content.setAlignment(Pos.TOP_CENTER);

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(content);
        UiKit.applyAppBackground(root);

        stage.setTitle("Toptri Chat - Seller (" + sellerId + ")");
        stage.setScene(new Scene(root, 1220, 720));
        stage.show();
        stage.setOnCloseRequest(e -> cleanup());

        attachRequestsListener();
    }

    // ============================================================
    // REQUESTS LISTENER + AUTO FOLLOW
    // ============================================================

    private void attachRequestsListener() {
        if (requestsListener != null) requestsListener.remove();
        requestsListener = fs.listenOpenRequests(
                snap -> Platform.runLater(() -> onRequestsUpdate(snap)),
                err -> Platform.runLater(() -> {
                    status.setText("Error ❌");
                    Alert a = new Alert(Alert.AlertType.ERROR);
                    a.setTitle("Firestore Error");
                    a.setHeaderText("Seller listener failed");
                    a.setContentText(err.getMessage() == null ? "(no message)" : err.getMessage());
                    a.showAndWait();
                })
        );
    }

    private void onRequestsUpdate(QuerySnapshot snap) {
        List<RequestItem> items = new ArrayList<>();
        for (QueryDocumentSnapshot d : snap.getDocuments()) {
            String requestId = safe(d.getString("requestId"));
            String preview = safe(d.getString("latestBuyerText"));
            if (preview.isBlank()) preview = safe(d.getString("buyerText"));
            long buyerNo = 0L;
            Long n = d.getLong("buyerRequestNo");
            if (n != null) buyerNo = n;
            if (!requestId.isBlank()) items.add(new RequestItem(requestId, preview, buyerNo));
        }

        requestList.getItems().setAll(items);
        status.setText(items.isEmpty() ? "Waiting..." : "New requests available ✅");
        if (items.isEmpty()) return;

        RequestItem newest = items.get(0);
        if (autoFollowLatest) {
            if (selectedRequestId == null || selectedRequestId.isBlank()
                    || !newest.requestId.equals(selectedRequestId)
                    || lastAutoSelectedRequestId == null
                    || !newest.requestId.equals(lastAutoSelectedRequestId)) {
                lastAutoSelectedRequestId = newest.requestId;
                requestList.getSelectionModel().select(0);
                onSelectRequest(newest);
            }
        } else {
            if (lastAutoSelectedRequestId == null || !newest.requestId.equals(lastAutoSelectedRequestId)) {
                autoFollowLatest = true;
                lastAutoSelectedRequestId = newest.requestId;
                requestList.getSelectionModel().select(0);
                onSelectRequest(newest);
            }
        }
    }

    private void onSelectRequest(RequestItem it) {
        selectedRequestId = it.requestId;
        reqIdValue.setText(it.requestId);
        offeredKeys.clear();
        sentCountForThisRequest = 0;
        mainInput.clear(); priceInput.clear(); vendorInput.clear(); contactInput.clear();
        latestBuyerMessageId = null;
        selectedBuyerText = "";
        latestOrderItems = new ArrayList<>();
        qtyDetectedLabel.setText("");
        buyerMsgHolder.getChildren().setAll(UiKit.messagePill("Loading latest message..."));
        status.setText("Listening messages...");
        menuListView.getItems().clear();
        menuListView.setPlaceholder(UiKit.small("Listening buyer messages..."));
        attachMessagesListener(it.requestId);
    }

    // ============================================================
    // MESSAGES LISTENER
    // ============================================================

    private void attachMessagesListener(String requestId) {
        if (messagesListener != null) messagesListener.remove();
        messagesListener = fs.listenMessages(
                requestId,
                snap -> Platform.runLater(() -> onMessagesUpdateForSeller(snap)),
                err -> Platform.runLater(() -> {
                    status.setText("Error ❌");
                    info("Messages listener error: " + (err.getMessage() == null ? "(no message)" : err.getMessage()));
                })
        );
    }

    private void onMessagesUpdateForSeller(QuerySnapshot snap) {
        String latestText = "";
        String latestId = null;
        List<FirestoreService.OrderItem> latestItems = new ArrayList<>();

        for (QueryDocumentSnapshot d : snap.getDocuments()) {
            String senderType = safe(d.getString("senderType"));
            if ("BUYER".equalsIgnoreCase(senderType)) {
                latestId = d.getId();
                latestText = safe(d.getString("text"));
                // ✅ NEW: parse multi-item order from buyer message
                latestItems = FirestoreService.parseOrderItems(latestText);
            }
        }

        boolean changed = !Objects.equals(latestBuyerMessageId, latestId);
        latestBuyerMessageId = latestId;
        selectedBuyerText = latestText;
        latestOrderItems = latestItems;

        // ✅ NEW: show quantity summary
        if (!latestItems.isEmpty()) {
            int total = latestItems.stream().mapToInt(oi -> oi.qty).sum();
            if (total > 1) {
                StringBuilder qtyText = new StringBuilder("🛒 ");
                for (FirestoreService.OrderItem oi : latestItems) {
                    qtyText.append(oi.qty).append("× ").append(oi.name).append("  ");
                }
                qtyDetectedLabel.setText(qtyText.toString().trim());
            } else {
                qtyDetectedLabel.setText("");
            }
        } else {
            qtyDetectedLabel.setText("");
        }

        if (latestText.isBlank()) {
            buyerMsgHolder.getChildren().setAll(UiKit.messagePill("Waiting buyer message..."));
            status.setText("Waiting buyer message...");
            menuListView.getItems().clear();
            menuListView.setPlaceholder(UiKit.small("Waiting buyer message..."));
            return;
        }

        buyerMsgHolder.getChildren().setAll(UiKit.messagePill(latestText));

        if (changed) {
            offeredKeys.clear();
            sentCountForThisRequest = 0;
        }

        status.setText("Loading menus...");
        menuListView.setPlaceholder(UiKit.small("Loading menus..."));
        loadMenusFromFirestore(latestText);
    }

    private void loadMenusFromFirestore(String buyerText) {
        final String buyerTextFinal = buyerText;
        new Thread(() -> {
            try {
                String category = fs.mapCategoryFromText(buyerTextFinal);
                List<FirestoreService.MenuItem> menus = fs.getMenusByCategory(category);
                Platform.runLater(() -> {
                    if (menus.isEmpty()) {
                        status.setText("No menus found ❌");
                        menuListView.getItems().clear();
                        menuListView.setPlaceholder(UiKit.small("No menus found for: " + category));
                        return;
                    }
                    menuListView.getItems().setAll(menus);
                    menuListView.refresh();
                    status.setText("Pick up to 3 menus to offer.");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    status.setText("Error ❌");
                    menuListView.getItems().clear();
                    menuListView.setPlaceholder(UiKit.small("Failed to load menus: " + ex.getMessage()));
                });
            }
        }).start();
    }

    // ============================================================
    // OFFER FROM MENU LIST CLICK
    // ============================================================

    private boolean isMenuAlreadyOfferedForLatest(FirestoreService.MenuItem menu) {
        if (selectedRequestId == null) return false;
        if (latestBuyerMessageId == null || latestBuyerMessageId.isBlank()) return false;
        String key = (selectedRequestId + "::" + latestBuyerMessageId + "::menu::" + menu.getName())
                .toLowerCase(Locale.ROOT);
        return offeredKeys.contains(key);
    }

    private void onSendOfferFromList(FirestoreService.MenuItem menu) {
        if (selectedRequestId == null) { info("Select a request first."); return; }
        if (latestBuyerMessageId == null || latestBuyerMessageId.isBlank()) { info("Wait buyer message."); return; }
        if (sentCountForThisRequest >= 3) { info("Maximum 3 offers per request."); return; }

        String key = (selectedRequestId + "::" + latestBuyerMessageId + "::menu::" + menu.getName())
                .toLowerCase(Locale.ROOT);
        if (offeredKeys.contains(key)) { info("Already offered for latest message."); return; }

        offeredKeys.add(key);
        menuListView.refresh();

        // ✅ NEW: build offer lines from detected order items
        List<Map<String, Object>> offerLines = buildOfferLinesFromOrderItems(menu.getName(), menu.getPrice());
        int grandTotal = offerLines.stream()
                .mapToInt(l -> ((Long) l.get("price")).intValue() * ((Long) l.get("qty")).intValue())
                .sum();

        final String reqIdFinal = selectedRequestId;
        final String msgIdFinal = latestBuyerMessageId;
        final String contactFinal = contactInput.getText().trim();

        new Thread(() -> {
            try {
                fs.createOfferWithLines(reqIdFinal, sellerId, menu.getVendor(),
                        menu.getEtaMinutes(), menu.getRating(),
                        offerLines, grandTotal, msgIdFinal, contactFinal);

                sentCountForThisRequest++;
                Platform.runLater(() ->
                        status.setText("Sent ✅ " + menu.getName() + " (" + sentCountForThisRequest + "/3)")
                );
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    offeredKeys.remove(key);
                    menuListView.refresh();
                    status.setText("Error ❌");
                    info("Failed to send offer: " + ex.getMessage());
                });
            }
        }).start();
    }

    // ============================================================
    // AUTO SEND (typed action row)
    // ============================================================

    private void onSendAuto() {
        if (selectedRequestId == null) { info("Select a request first."); return; }

        String text = mainInput.getText().trim();
        String priceText = priceInput.getText().trim();
        String vendor = vendorInput.getText().trim();
        String contact = contactInput.getText().trim();

        if (text.isBlank()) return;

        boolean hasPrice = !priceText.isBlank();
        boolean hasVendor = !vendor.isBlank();

        if (!hasPrice) { sendChat(text); return; }

        if (latestBuyerMessageId == null || latestBuyerMessageId.isBlank()) {
            info("Wait buyer message before sending offer.");
            return;
        }
        if (sentCountForThisRequest >= 3) { info("Maximum 3 offers per request."); return; }

        int price = parsePriceOr0(priceText);

        if (hasVendor) {
            addMenuAndSend(text, price, vendor, contact);
        } else {
            sendTypedOffer(text, price, contact);
        }
    }

    private void sendChat(String text) {
        disableActions(true);
        final String reqIdFinal = selectedRequestId;
        new Thread(() -> {
            try {
                fs.sendSellerMessage(reqIdFinal, sellerId, text);
                Platform.runLater(() -> {
                    status.setText("Chat sent ✅");
                    mainInput.clear();
                    disableActions(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    status.setText("Error ❌");
                    info("Failed to send chat: " + ex.getMessage());
                    disableActions(false);
                });
            }
        }).start();
    }

    private void sendTypedOffer(String menuName, int price, String contact) {
        String key = (selectedRequestId + "::" + latestBuyerMessageId + "::typed::" + menuName)
                .toLowerCase(Locale.ROOT);
        if (offeredKeys.contains(key)) { info("Already offered this for latest message."); return; }

        disableActions(true);

        // ✅ NEW: build offer lines from detected order items
        List<Map<String, Object>> offerLines = buildOfferLinesFromOrderItems(menuName, price);
        int grandTotal = offerLines.stream()
                .mapToInt(l -> ((Long) l.get("price")).intValue() * ((Long) l.get("qty")).intValue())
                .sum();

        final String reqIdFinal = selectedRequestId;
        final String msgIdFinal = latestBuyerMessageId;

        new Thread(() -> {
            try {
                fs.createOfferWithLines(reqIdFinal, sellerId, "", 0, 0.0,
                        offerLines, grandTotal, msgIdFinal, contact);

                offeredKeys.add(key);
                sentCountForThisRequest++;
                Platform.runLater(() -> {
                    status.setText("Offer sent ✅ " + menuName + " (" + sentCountForThisRequest + "/3)");
                    mainInput.clear(); priceInput.clear(); vendorInput.clear();
                    disableActions(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    status.setText("Error ❌");
                    info("Failed to send offer: " + ex.getMessage());
                    disableActions(false);
                });
            }
        }).start();
    }

    private void addMenuAndSend(String menuName, int price, String vendor, String contact) {
        String key = (selectedRequestId + "::" + latestBuyerMessageId + "::newmenu::" + menuName)
                .toLowerCase(Locale.ROOT);
        if (offeredKeys.contains(key)) { info("Already added/sent this menu."); return; }

        disableActions(true);

        List<Map<String, Object>> offerLines = buildOfferLinesFromOrderItems(menuName, price);
        int grandTotal = offerLines.stream()
                .mapToInt(l -> ((Long) l.get("price")).intValue() * ((Long) l.get("qty")).intValue())
                .sum();

        final String reqIdFinal = selectedRequestId;
        final String msgIdFinal = latestBuyerMessageId;
        final String buyerTextFinal = selectedBuyerText;

        new Thread(() -> {
            try {
                // create menu in Firestore
                fs.createMenuEntry(buyerTextFinal, menuName, price, vendor, sellerId);
                // send offer
                fs.createOfferWithLines(reqIdFinal, sellerId, vendor, 0, 0.0,
                        offerLines, grandTotal, msgIdFinal, contact);

                offeredKeys.add(key);
                sentCountForThisRequest++;
                Platform.runLater(() -> {
                    status.setText("Added & Sent ✅ " + menuName + " (" + sentCountForThisRequest + "/3)");
                    mainInput.clear(); priceInput.clear(); vendorInput.clear();
                    disableActions(false);
                    loadMenusFromFirestore(buyerTextFinal);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    status.setText("Error ❌");
                    info("Failed to add menu: " + ex.getMessage());
                    disableActions(false);
                });
            }
        }).start();
    }

    // ✅ NEW: build offerLines list from detected latestOrderItems
    // If multi-item detected: creates one line per order item using the given unit price
    // If single item: creates one line with qty=1
    private List<Map<String, Object>> buildOfferLinesFromOrderItems(String menuName, int price) {
        List<Map<String, Object>> lines = new ArrayList<>();

        if (latestOrderItems != null && !latestOrderItems.isEmpty()) {
            for (FirestoreService.OrderItem oi : latestOrderItems) {
                Map<String, Object> line = new HashMap<>();
                // use buyer's item name if available, else use seller's menu name
                line.put("menuName", oi.name.isBlank() ? menuName : oi.name);
                line.put("qty", (long) oi.qty);
                line.put("price", (long) price);
                lines.add(line);
            }
        } else {
            Map<String, Object> line = new HashMap<>();
            line.put("menuName", menuName);
            line.put("qty", 1L);
            line.put("price", (long) price);
            lines.add(line);
        }

        return lines;
    }

    // ============================================================
    // UTILS
    // ============================================================

    private void disableActions(boolean disabled) {
        mainInput.setDisable(disabled);
        priceInput.setDisable(disabled);
        vendorInput.setDisable(disabled);
        contactInput.setDisable(disabled);
        sendBtn.setDisable(disabled);
    }

    private int parsePriceOr0(String ptxt) {
        if (ptxt == null) return 0;
        ptxt = ptxt.trim();
        if (ptxt.isBlank()) return 0;
        try {
            return Integer.parseInt(ptxt.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            info("Invalid price. Numbers only (e.g. 9000).");
            return 0;
        }
    }

    private void info(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Info");
        a.setHeaderText(null);
        a.setContentText(msg);
        a.showAndWait();
    }

    private void cleanup() {
        if (requestsListener != null) requestsListener.remove();
        if (messagesListener != null) messagesListener.remove();
    }

    private String safe(String s) { return s == null ? "" : s; }

    private static class RequestItem {
        final String requestId, previewText;
        final long buyerRequestNo;
        RequestItem(String requestId, String previewText, long buyerRequestNo) {
            this.requestId = requestId;
            this.previewText = previewText == null ? "" : previewText;
            this.buyerRequestNo = buyerRequestNo;
        }
    }
}