package com.toptri.desktop;

import com.google.cloud.firestore.ListenerRegistration;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

import java.awt.Desktop;
import java.net.URI;
import javafx.scene.web.WebView;
import javafx.scene.web.WebEngine;
import netscape.javascript.JSObject;

public class BuyerWindow {

    // ── Design tokens — dari UiKit ────────────────────────────────
    private static final String COLOR_BG            = UiKit.COLOR_BG;
    private static final String COLOR_SURFACE       = UiKit.COLOR_SURFACE;
    private static final String COLOR_PRIMARY       = UiKit.COLOR_PRIMARY;
    private static final String COLOR_PRIMARY_DARK  = UiKit.COLOR_PRIMARY_DARK;
    private static final String COLOR_BUYER_BUBBLE  = UiKit.COLOR_BUYER_BUBBLE;
    private static final String COLOR_SELLER_BUBBLE = UiKit.COLOR_SELLER_BUBBLE;
    private static final String COLOR_OFFER_BUBBLE  = UiKit.COLOR_OFFER_BUBBLE;
    private static final String COLOR_SIDEBAR_BG    = UiKit.COLOR_SIDEBAR_BG;
    private static final String COLOR_SELECTED_ROW  = UiKit.COLOR_SELECTED_ROW;
    private static final String COLOR_TEXT_MAIN     = UiKit.COLOR_TEXT_MAIN;
    private static final String COLOR_TEXT_MUTED    = UiKit.COLOR_TEXT_MUTED;
    private static final String COLOR_DIVIDER       = UiKit.COLOR_DIVIDER;
    private static final String COLOR_SUCCESS       = UiKit.COLOR_SUCCESS;
    private static final String COLOR_DANGER        = UiKit.COLOR_DANGER;
    private static final String COLOR_WARN          = UiKit.COLOR_WARN;
    private static final String COLOR_COMPLETED_BG  = UiKit.COLOR_COMPLETED_BG;

    // ── Services / state ─────────────────────────────────────────
    private final FirestoreService fs;
    private final String buyerId = getOrCreateBuyerId();

    private static final Preferences PREFS = Preferences.userNodeForPackage(BuyerWindow.class);

    private ListenerRegistration messagesListener;
    private ListenerRegistration offersAllListener;
    private ListenerRegistration myReqListener;

    private String currentRequestId     = null;
    private String currentRequestStatus = "OPEN";

    private final List<Message>                      messages               = new ArrayList<>();
    private final Map<String, List<Offer>>           offersByBuyerMessageId = new HashMap<>();
    private final Map<String, String>                requestStatusById      = new HashMap<>();
    private String latestBuyerMessageId = null;

    // ── AI payload state ─────────────────────────────────────────
    private AiAutoReply.AiReplyPayload lastAiPayload = null;

    // ── Chat flow state — untuk inline form & menu item step ─────
    // Tracks whether the delivery form is already showing to avoid duplicates
    private boolean deliveryFormShowing = false;
    // Track selected item for the delivery form
    private AiAutoReply.YummyMenuItem pendingItem = null;
    // Flag: buyer sudah pilih kategori → jangan tampilkan widget kategori lagi
    private boolean categoryChosen   = false;
    // Flag: tampilkan widget menu Yummy di renderChat()
    private boolean pendingYummyMenu  = false;
    // Flag: tampilkan widget menu Bakery di renderChat()
    private boolean pendingBakeryMenu = false;
    // Flag: tampilkan widget menu Coffee di renderChat()
    private boolean pendingCoffeeMenu = false;
    // State step 10: data untuk widget pembayaran
    private AiAutoReply.IndomaretLocation pickedIndomaret = null;
    private AiAutoReply.DeliveryFormResult pickedDelivery  = null;
    private boolean paymentWidgetShowing = false;

    // ── UI components ────────────────────────────────────────────

    // App bar
    private final Label statusPill = new Label("Menunggu...");

    // Sidebar
    private final ListView<RequestItem> myReqList      = new ListView<>();
    private final Label                 reqCountLabel  = new Label();

    // Toast
    private VBox toastContainer;

    // Chat
    private final VBox       chatBox    = new VBox(10);
    private final ScrollPane chatScroll = new ScrollPane(chatBox);

    // Input bar
    private final TextField msgInput = new TextField();
    private final Button    sendBtn  = new Button("Kirim");
    private final Button    newBtn   = new Button("+ Request Baru");

    // ============================================================
    // CONSTRUCTOR / ENTRY POINT
    // ============================================================

    public BuyerWindow(FirestoreService fs) {
        this.fs = fs;
    }

    public static void open(FirestoreService fs) {
        new BuyerWindow(fs).show();
    }

    // ============================================================
    // BUILD UI
    // ============================================================

    public void show() {
        Stage stage = new Stage();

        HBox appBar  = buildAppBar();
        VBox sidebar = buildSidebar();
        VBox chatPanel = buildChatPanel();

        HBox body = new HBox(0, sidebar, chatPanel);
        HBox.setHgrow(chatPanel, Priority.ALWAYS);
        body.setFillHeight(true);

        toastContainer = new VBox(8);
        toastContainer.setAlignment(Pos.TOP_RIGHT);
        toastContainer.setPadding(new Insets(70, 16, 0, 0));
        toastContainer.setMouseTransparent(false);
        toastContainer.setPickOnBounds(false);

        BorderPane rootPane = new BorderPane();
        rootPane.setTop(appBar);
        rootPane.setCenter(body);
        rootPane.setStyle("-fx-background-color: " + COLOR_BG + ";");

        StackPane layered = new StackPane(rootPane, toastContainer);
        StackPane.setAlignment(toastContainer, Pos.TOP_RIGHT);

        renderChat();
        updateInputState();
        msgInput.setOnAction(e -> onSend());

        stage.setTitle("Indomaret Point — Buyer (" + buyerId + ")");
        stage.setScene(new Scene(layered, 1060, 800));
        stage.setMinWidth(800);
        stage.setMinHeight(600);
        stage.show();
        stage.setOnCloseRequest(e -> cleanup());

        attachMyRequestsListener();
    }

    // ── APP BAR ─────────────────────────────────────────────────

    private HBox buildAppBar() {
        Label logo = new Label("🏪  Indomaret Point");
        logo.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 18));
        logo.setStyle("-fx-text-fill: " + COLOR_PRIMARY + ";");

        Label role = new Label("Buyer: " + buyerId);
        role.setStyle("-fx-text-fill: " + COLOR_TEXT_MUTED + "; -fx-font-size: 13;");

        styleStatusPill(statusPill, "neutral");

        newBtn.setStyle(
            "-fx-background-color: " + COLOR_PRIMARY + ";" +
            "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12;" +
            "-fx-background-radius: 20; -fx-padding: 6 16; -fx-cursor: hand;"
        );
        newBtn.setOnMouseEntered(e -> newBtn.setStyle(newBtn.getStyle().replace(COLOR_PRIMARY, COLOR_PRIMARY_DARK)));
        newBtn.setOnMouseExited(e  -> newBtn.setStyle(newBtn.getStyle().replace(COLOR_PRIMARY_DARK, COLOR_PRIMARY)));
        newBtn.setOnAction(e -> startNewConversation());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(12, logo, role, spacer, statusPill, newBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(14, 20, 14, 20));
        bar.setStyle(
            "-fx-background-color: " + COLOR_SURFACE + ";" +
            "-fx-border-color: " + COLOR_DIVIDER + ";" +
            "-fx-border-width: 0 0 1 0;"
        );
        return bar;
    }

    // ── SIDEBAR ─────────────────────────────────────────────────

    private VBox buildSidebar() {
        Label title = new Label("Pesananku");
        title.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 15));
        title.setStyle("-fx-text-fill: " + COLOR_TEXT_MAIN + ";");

        reqCountLabel.setStyle(
            "-fx-background-color: " + COLOR_PRIMARY + ";" +
            "-fx-text-fill: white; -fx-font-size: 11; -fx-font-weight: bold;" +
            "-fx-background-radius: 10; -fx-padding: 1 7;"
        );

        HBox sidebarHeader = new HBox(8, title, reqCountLabel);
        sidebarHeader.setAlignment(Pos.CENTER_LEFT);
        sidebarHeader.setPadding(new Insets(16, 16, 10, 16));

        myReqList.setStyle(
            "-fx-background-color: transparent; -fx-border-color: transparent;" +
            "-fx-focus-color: transparent; -fx-faint-focus-color: transparent;"
        );
        myReqList.setCellFactory(lv -> new RequestCell());
        myReqList.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldV, newV) -> { if (newV != null) openConversation(newV.requestId, newV.status); });
        myReqList.setPlaceholder(buildPlaceholder("Belum ada pesanan"));
        VBox.setVgrow(myReqList, Priority.ALWAYS);

        Separator sep = new Separator();
        sep.setStyle("-fx-background-color: " + COLOR_DIVIDER + ";");

        VBox sidebar = new VBox(0, sidebarHeader, sep, myReqList);
        sidebar.setPrefWidth(270);
        sidebar.setMaxWidth(270);
        sidebar.setStyle(
            "-fx-background-color: " + COLOR_SIDEBAR_BG + ";" +
            "-fx-border-color: " + COLOR_DIVIDER + ";" +
            "-fx-border-width: 0 1 0 0;"
        );
        VBox.setVgrow(sidebar, Priority.ALWAYS);
        return sidebar;
    }

    // ── CHAT PANEL ──────────────────────────────────────────────

    private VBox buildChatPanel() {
        Label chatTitle = new Label("💬  Percakapan");
        chatTitle.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 15));
        chatTitle.setStyle("-fx-text-fill: " + COLOR_TEXT_MAIN + ";");

        HBox chatTopBar = new HBox(10, chatTitle);
        chatTopBar.setAlignment(Pos.CENTER_LEFT);
        chatTopBar.setPadding(new Insets(14, 16, 10, 16));
        chatTopBar.setStyle(
            "-fx-background-color: " + COLOR_SURFACE + ";" +
            "-fx-border-color: " + COLOR_DIVIDER + ";" +
            "-fx-border-width: 0 0 1 0;"
        );

        chatBox.setPadding(new Insets(16, 12, 12, 12));
        chatBox.setFillWidth(true);

        chatScroll.setFitToWidth(true);
        chatScroll.setStyle("-fx-background-color: " + COLOR_BG + "; -fx-background: " + COLOR_BG + ";");
        chatScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(chatScroll, Priority.ALWAYS);

        styleInput(msgInput, "Tulis pesanan kamu, contoh: '2 nasi padang 3 es teh'");
        HBox.setHgrow(msgInput, Priority.ALWAYS);

        stylePrimaryButton(sendBtn);
        sendBtn.setStyle(sendBtn.getStyle() + "-fx-background-radius: 20; -fx-padding: 8 18;");
        sendBtn.setOnAction(e -> onSend());

        HBox inputBar = new HBox(8, msgInput, sendBtn);
        inputBar.setAlignment(Pos.CENTER);
        inputBar.setPadding(new Insets(10, 16, 14, 16));
        inputBar.setStyle(
            "-fx-background-color: " + COLOR_SURFACE + ";" +
            "-fx-border-color: " + COLOR_DIVIDER + ";" +
            "-fx-border-width: 1 0 0 0;"
        );

        VBox panel = new VBox(0, chatTopBar, chatScroll, inputBar);
        VBox.setVgrow(chatScroll, Priority.ALWAYS);
        panel.setStyle("-fx-background-color: " + COLOR_BG + ";");
        return panel;
    }

    // ============================================================
    // INPUT STATE — lock setelah payment
    // ============================================================

    private boolean isCurrentCompleted() {
        return currentRequestId != null && "COMPLETED".equalsIgnoreCase(currentRequestStatus);
    }

    private void updateInputState() {
        boolean locked = isCurrentCompleted();
        sendBtn.setDisable(locked);
        msgInput.setDisable(locked);
        if (locked) {
            msgInput.setPromptText("✅ Request selesai — klik \"+ Request Baru\" untuk pesan lagi");
            setStatus("Selesai ✅", "success");
        } else {
            msgInput.setPromptText("Tulis pesanan kamu, contoh: '2 nasi padang 3 es teh'");
            setStatus("Aktif", "neutral");
        }
    }

    // ============================================================
    // SEND
    // ============================================================

    private void onSend() {
        if (isCurrentCompleted()) return;
        String text = msgInput.getText().trim();
        if (text.isBlank()) return;
        msgInput.clear();

        // ── Request baru ─────────────────────────────────────────
        if (currentRequestId == null) {
            currentRequestId     = makeRequestId();
            currentRequestStatus = "OPEN";
            messages.clear();
            offersByBuyerMessageId.clear();
            latestBuyerMessageId = null;
            lastAiPayload        = null;
            deliveryFormShowing  = false;
            pendingItem          = null;
            categoryChosen       = false;
            pendingYummyMenu     = false;
            pendingBakeryMenu    = false;
            pendingCoffeeMenu    = false;
            pickedIndomaret      = null;
            pickedDelivery       = null;
            paymentWidgetShowing = false;
            renderChat();
            updateInputState();

            final String newReqId       = currentRequestId;
            final String firstText      = text;
            final long   buyerRequestNo = nextBuyerRequestNo();

            setStatus("Membuat request...", "warn");
            new Thread(() -> {
                try {
                    fs.createConversation(newReqId, buyerId, firstText, buyerRequestNo);
                    Platform.runLater(() -> setStatus("Terkirim ✅", "success"));
                    attachMessagesListener(newReqId);
                    attachAllOffersListener(newReqId);

                    // ✅ AI auto-reply untuk pesan pertama
                    AiAutoReply.maybeAutoReply(newReqId, firstText, fs, payload ->
                        Platform.runLater(() -> {
                            if (payload != null && !categoryChosen) {
                                lastAiPayload = payload;
                                renderChat();
                            }
                        })
                    );
                } catch (Exception ex) {
                    showError("Gagal membuat request", ex.getMessage());
                    setStatus("Error ❌", "error");
                }
            }).start();
            return;
        }

        // ── Pesan lanjutan dalam request yang sudah ada ──────────
        final String reqIdFinal = currentRequestId;
        final String textFinal  = text;
        setStatus("Mengirim...", "warn");
        new Thread(() -> {
            try {
                fs.sendBuyerMessage(reqIdFinal, buyerId, textFinal);
                Platform.runLater(() -> setStatus("Terkirim ✅", "success"));

                // ✅ AI auto-reply untuk setiap pesan lanjutan
                AiAutoReply.maybeAutoReply(reqIdFinal, textFinal, fs, payload ->
                    Platform.runLater(() -> {
                        if (payload != null && !categoryChosen) {
                            lastAiPayload = payload;
                            renderChat();
                        }
                    })
                );
            } catch (Exception ex) {
                showError("Gagal kirim pesan", ex.getMessage());
                setStatus("Error ❌", "error");
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
        if (messagesListener  != null) messagesListener.remove();
        if (offersAllListener != null) offersAllListener.remove();
        currentRequestId     = null;
        currentRequestStatus = "OPEN";
        messages.clear();
        offersByBuyerMessageId.clear();
        latestBuyerMessageId = null;
        lastAiPayload        = null;
        deliveryFormShowing  = false;
        pendingItem          = null;
        categoryChosen       = false;
            pendingYummyMenu     = false;
            pickedIndomaret      = null;
            pickedDelivery       = null;
            paymentWidgetShowing = false;
        myReqList.getSelectionModel().clearSelection();
        renderChat();
        updateInputState();
        msgInput.requestFocus();
    }

    private void openConversation(String requestId, String status) {
        if (messagesListener  != null) messagesListener.remove();
        if (offersAllListener != null) offersAllListener.remove();
        currentRequestId     = requestId;
        currentRequestStatus = (status == null || status.isBlank()) ? "OPEN" : status;
        messages.clear();
        offersByBuyerMessageId.clear();
        latestBuyerMessageId = null;
        lastAiPayload        = null;
        deliveryFormShowing  = false;
        pendingItem          = null;
        categoryChosen       = false;
            pendingYummyMenu     = false;
            pickedIndomaret      = null;
            pickedDelivery       = null;
            paymentWidgetShowing = false;
        renderChat();
        updateInputState();
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
                err  -> Platform.runLater(() -> showError("Messages listener error", err.getMessage()))
        );
    }

    private void onMessagesUpdate(QuerySnapshot snap) {
        // Kumpulkan ID pesan yang sudah ada agar tidak di-reset dari awal
        Set<String> existingIds = new HashSet<>();
        for (Message m : messages) existingIds.add(m.id);

        boolean hasNew = false;
        for (QueryDocumentSnapshot d : snap.getDocuments()) {
            if (!existingIds.contains(d.getId())) {
                hasNew = true;
                break;
            }
        }

        // Kalau tidak ada pesan baru sama sekali, skip re-render
        // supaya widget (menu, form, payment) yang sudah di-inject tidak hilang
        if (!hasNew && !messages.isEmpty()) return;

        // Ada pesan baru — rebuild list tapi hanya append yang belum ada
        Set<String> currentIds = new HashSet<>();
        for (Message m : messages) currentIds.add(m.id);

        boolean addedAny = false;
        for (QueryDocumentSnapshot d : snap.getDocuments()) {
            String id = d.getId();
            if (currentIds.contains(id)) continue;
            String senderType = safe(d.getString("senderType"));
            String senderId   = safe(d.getString("senderId"));
            String text       = safe(d.getString("text"));
            List<FirestoreService.OrderItem> orderItems = FirestoreService.parseOrderItems(text);
            messages.add(new Message(id, senderType, senderId, text, orderItems));
            if ("BUYER".equalsIgnoreCase(senderType)) latestBuyerMessageId = id;
            addedAny = true;
        }

        if (addedAny) {
            renderChat();
            Platform.runLater(() -> chatScroll.setVvalue(1.0));
        }
    }

    private void attachAllOffersListener(String requestId) {
        if (offersAllListener != null) offersAllListener.remove();
        offersAllListener = fs.listenAllOffers(
                requestId,
                snap -> Platform.runLater(() -> onAllOffersUpdate(snap)),
                err  -> Platform.runLater(() -> showError("Offers listener error", err.getMessage()))
        );
    }

    @SuppressWarnings("unchecked")
    private void onAllOffersUpdate(QuerySnapshot snap) {
        // Hitung total offer sebelumnya
        int prevTotal = offersByBuyerMessageId.values().stream().mapToInt(List::size).sum();

        offersByBuyerMessageId.clear();
        for (QueryDocumentSnapshot d : snap.getDocuments()) {
            String buyerMsgId = safe(d.getString("buyerMessageId"));
            if (buyerMsgId.isBlank()) continue;

            String id            = d.getId();
            String sellerId      = safe(d.getString("sellerId"));
            String sellerContact = safe(d.getString("sellerContact"));
            String vendor        = safe(d.getString("vendor"));

            int    etaMinutes = Optional.ofNullable(d.getLong("etaMinutes")).map(Long::intValue).orElse(0);
            double rating     = Optional.ofNullable(d.getDouble("rating")).orElse(0.0);

            List<Map<String, Object>> rawLines = (List<Map<String, Object>>) d.get("offerLines");
            List<OfferLine> offerLines = new ArrayList<>();
            int grandTotal = 0;

            if (rawLines != null && !rawLines.isEmpty()) {
                for (Map<String, Object> line : rawLines) {
                    String name  = safe((String) line.get("menuName"));
                    int    qty   = line.get("qty")   instanceof Long ? ((Long) line.get("qty")).intValue()   : 1;
                    int    price = line.get("price") instanceof Long ? ((Long) line.get("price")).intValue() : 0;
                    offerLines.add(new OfferLine(name, qty, price));
                    grandTotal += qty * price;
                }
            } else {
                String menuName = safe(d.getString("menuName"));
                int    price    = Optional.ofNullable(d.getLong("price")).map(Long::intValue).orElse(0);
                int    qty      = Optional.ofNullable(d.getLong("quantity")).map(Long::intValue).filter(q -> q > 0).orElse(1);
                offerLines.add(new OfferLine(menuName, qty, price));
                grandTotal = qty * price;
            }
            Long storedTotal = d.getLong("grandTotal");
            if (storedTotal != null && storedTotal > 0) grandTotal = storedTotal.intValue();

            Offer offer = new Offer(id, sellerId, offerLines, grandTotal, vendor, etaMinutes, rating, sellerContact);
            offersByBuyerMessageId.computeIfAbsent(buyerMsgId, k -> new ArrayList<>()).add(offer);
        }
        int newTotal = offersByBuyerMessageId.values().stream().mapToInt(List::size).sum();
        // Hanya re-render kalau jumlah offer berubah
        if (newTotal != prevTotal) {
            renderChat();
            Platform.runLater(() -> chatScroll.setVvalue(1.0));
        }
    }

    private void attachMyRequestsListener() {
        if (myReqListener != null) myReqListener.remove();
        myReqListener = fs.listenBuyerRequests(
                buyerId,
                snap -> Platform.runLater(() -> {
                    List<RequestItem> items = new ArrayList<>();
                    requestStatusById.clear();

                    for (QueryDocumentSnapshot d : snap.getDocuments()) {
                        String rid     = safe(d.getString("requestId"));
                        if (rid.isBlank()) continue;
                        String preview = safe(d.getString("buyerText"));
                        String st      = safe(d.getString("status"));
                        if (st.isBlank()) st = "OPEN";
                        requestStatusById.put(rid, st);
                        long no = Optional.ofNullable(d.getLong("buyerRequestNo")).orElse(0L);
                        if (no <= 0) no = items.size() + 1;
                        items.add(new RequestItem(rid, preview, no, st));
                    }

                    items.sort(Comparator.comparingLong((RequestItem a) -> a.buyerRequestNo).reversed());
                    myReqList.getItems().setAll(items);
                    reqCountLabel.setText(String.valueOf(items.size()));

                    if (currentRequestId != null) {
                        String newStatus = requestStatusById.getOrDefault(currentRequestId, currentRequestStatus);
                        boolean statusChanged = !newStatus.equals(currentRequestStatus);
                        currentRequestStatus = newStatus;
                        updateInputState();
                        // Hanya re-render kalau status berubah (misal jadi COMPLETED)
                        // supaya widget yang sudah di-inject tidak hilang
                        if (statusChanged) renderChat();
                    }
                }),
                err -> Platform.runLater(() -> showError("Requests listener error", err.getMessage()))
        );
    }

    // ============================================================
    // RENDER CHAT
    // ============================================================

private void renderChat() {
    chatBox.getChildren().clear();
    paymentWidgetShowing = false;

    if (currentRequestId == null) {
        chatBox.getChildren().add(emptyChatHint(
            "👋 Halo! Tulis pesananmu di bawah untuk mulai.\n\n" +
            "💡 Kamu bisa pesan beberapa item sekaligus:\n" +
            "contoh: 2 nasi padang 3 es teh"
        ));
        return;
    }

    if (isCurrentCompleted()) {
        chatBox.getChildren().add(buildCompletedBanner());
    }

    if (messages.isEmpty()) {
        chatBox.getChildren().add(
            buildPendingBubble("⏳ Memuat percakapan...")
        );
        return;
    }

    boolean greetingShown = false;

    for (Message m : messages) {

        boolean isBuyer =
                "BUYER".equalsIgnoreCase(m.senderType);

        if (isBuyer) {

            // Pesan "📋 Data Pengiriman" tidak perlu ditampilkan sebagai bubble
            // karena sudah ada form widget yang lebih rapi
            if (!m.text.contains("📋 Data Pengiriman") && !m.text.contains("📍 Buyer memilih Indomaret") && !m.text.contains("💳 Buyer memilih pembayaran")) {
                chatBox.getChildren().add(
                    buildBuyerBubble(buildBuyerBubbleText(m))
                );
            }

            List<Offer> offs =
                    offersByBuyerMessageId.getOrDefault(
                            m.id,
                            Collections.emptyList()
                    );

            if (!offs.isEmpty()) {

                for (Offer o : offs) {

                    final Offer offerRef = o;

                    chatBox.getChildren().add(
                        buildOfferBubble(
                                o,
                                isCurrentCompleted(),
                                () -> onBuy(offerRef)
                        )
                    );
                }

            }

            chatBox.getChildren().add(chatDivider());

        } else {

            // Pesan [AUTO] dari AI_BOT — jangan render sebagai seller bubble biasa.
            // Widget AI (greeting + video + kategori) dirender dari lastAiPayload di bawah.
            if (m.text.contains("[AUTO]")) {
                greetingShown = true;
            } else {
                chatBox.getChildren().add(buildSellerBubble(m.text));
            }
        }
    }

    // AI widget — render dari lastAiPayload,
    // ATAU rebuild dari Firestore [AUTO] message kalau lastAiPayload null (misal app restart)
    // Jika categoryChosen, skip seluruhnya
    if (!categoryChosen) {
        AiAutoReply.AiReplyPayload widgetPayload = lastAiPayload;
        if (widgetPayload == null) {
            // Cari pesan [AUTO] PERTAMA (greeting) untuk rebuild payload
            for (Message am : messages) {
                if (am.text.contains("[AUTO]") && !"BUYER".equalsIgnoreCase(am.senderType)) {
                    String greetText = am.text
                        .replace("[AUTO] ", "").replace("[AUTO]", "").trim();
                    // Hanya rebuild kalau ini pesan greeting, bukan "Buyer tertarik..."
                    if (!greetText.contains("Buyer tertarik") && !greetText.contains("tertarik dengan")) {
                        widgetPayload = new AiAutoReply.AiReplyPayload(
                            greetText, AiAutoReply.CATEGORIES, AiAutoReply.STORE_VIDEO_URL);
                    }
                    break;
                }
            }
        }
        if (widgetPayload != null && !isCurrentCompleted()) {
            chatBox.getChildren().add(buildAiPayloadWidget(widgetPayload));
        }
    }

    // Yummy menu widget — tampil kalau buyer sudah pilih Yummy Choice
    // (hanya untuk kasus app restart / reload dari Firestore — normalnya sudah diinject langsung)
    if (pendingYummyMenu && !isCurrentCompleted() && !deliveryFormShowing && pendingItem == null) {
        chatBox.getChildren().add(buildSellerBubble(
            "😋 Berikut menu spesial Yummy Choice hari ini — pilih yang kamu mau 👇"));
        chatBox.getChildren().add(buildYummyMenuButtonsWidget());
    }

    // Bakery menu widget — tampil kalau buyer sudah pilih Bakery
    if (pendingBakeryMenu && !isCurrentCompleted() && !deliveryFormShowing && pendingItem == null) {
        chatBox.getChildren().add(buildSellerBubble(
            "🥐 Berikut pilihan roti kami — pilih yang kamu mau 👇"));
        chatBox.getChildren().add(buildBakeryMenuButtonsWidget());
    }

    // Coffee menu widget — tampil kalau buyer sudah pilih Coffee
    if (pendingCoffeeMenu && !isCurrentCompleted() && !deliveryFormShowing && pendingItem == null) {
        chatBox.getChildren().add(buildSellerBubble(
            "☕ Berikut pilihan minuman kami — pilih yang kamu mau 👇"));
        chatBox.getChildren().add(buildCoffeeMenuButtonsWidget());
    }

    // Form pengiriman
    if (pendingItem != null
            && !deliveryFormShowing
            && !isCurrentCompleted()) {

        chatBox.getChildren().add(
                buildDeliveryFormWidget(pendingItem)
        );

        deliveryFormShowing = true;
    }

    // Payment widget — hanya untuk kasus app restart (normalnya diinject langsung oleh onIndomaretPicked)
    if (pickedIndomaret != null && pickedDelivery != null
            && !paymentWidgetShowing && !isCurrentCompleted()
            && !deliveryFormShowing) {
        chatBox.getChildren().add(buildPaymentWidget(pickedIndomaret, pickedDelivery));
        paymentWidgetShowing = true;
    }

    Platform.runLater(() -> chatScroll.setVvalue(1.0));
}
    // ============================================================
    // BUBBLE BUILDERS
    // ============================================================

    private HBox buildBuyerBubble(String text) {
        Label avatar = new Label("🧑");
        avatar.setStyle("-fx-font-size: 20;");

        Label content = new Label(text);
        content.setWrapText(true);
        content.setMaxWidth(320);
        content.setStyle(
            "-fx-background-color: " + COLOR_BUYER_BUBBLE + ";" +
            "-fx-background-radius: 16 16 4 16;" +
            "-fx-padding: 10 14; -fx-font-size: 13;" +
            "-fx-text-fill: " + COLOR_TEXT_MAIN + ";"
        );

        Label time = new Label(now());
        time.setStyle("-fx-font-size: 10; -fx-text-fill: " + COLOR_TEXT_MUTED + ";");

        VBox bubble = new VBox(4, content, time);
        bubble.setAlignment(Pos.TOP_RIGHT);

        HBox row = new HBox(10, bubble, avatar);
        row.setAlignment(Pos.TOP_RIGHT);
        row.setPadding(new Insets(2, 0, 2, 60));
        return row;
    }

    private HBox buildSellerBubble(String text) {
        Label avatar = new Label("🏪");
        avatar.setStyle("-fx-font-size: 20;");

        Label content = new Label(text);
        content.setWrapText(true);
        content.setMaxWidth(380);
        content.setStyle(
            "-fx-background-color: " + COLOR_SELLER_BUBBLE + ";" +
            "-fx-background-radius: 16 16 16 4;" +
            "-fx-padding: 10 14; -fx-font-size: 13;" +
            "-fx-text-fill: " + COLOR_TEXT_MAIN + ";" +
            "-fx-effect: dropshadow(gaussian,rgba(0,0,0,0.07),6,0,0,2);"
        );

        Label time = new Label(now());
        time.setStyle("-fx-font-size: 10; -fx-text-fill: " + COLOR_TEXT_MUTED + ";");

        VBox bubble = new VBox(4, content, time);
        bubble.setAlignment(Pos.TOP_LEFT);

        HBox row = new HBox(10, avatar, bubble);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(2, 60, 2, 0));
        return row;
    }

    private HBox buildOfferBubble(Offer o, boolean completed, Runnable onBuyAction) {
        Label header = new Label("📦  Offer dari " + (o.sellerId.isBlank() ? "Seller" : o.sellerId));
        header.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 13));
        header.setStyle("-fx-text-fill: " + COLOR_PRIMARY + ";");

        VBox card = new VBox(6, header);

        if (!o.sellerContact.isBlank()) {
            Label contactLbl = new Label("📞 " + o.sellerContact);
            contactLbl.setStyle("-fx-font-size: 12; -fx-text-fill: " + COLOR_PRIMARY + "; -fx-font-weight: 600;");
            card.getChildren().add(contactLbl);
        }

        Separator sep1 = new Separator();
        sep1.setStyle("-fx-background-color: " + COLOR_DIVIDER + ";");
        card.getChildren().add(sep1);

        for (OfferLine line : o.offerLines) {
            int lineTotal = line.qty * line.price;
            String lineText = line.name + "   " + line.qty + " × " +
                    UiKit.rupiah(line.price) + "  =  " + UiKit.rupiah(lineTotal);
            Label ll = new Label(lineText);
            ll.setStyle("-fx-font-size: 12; -fx-text-fill: " + COLOR_TEXT_MAIN + ";");
            card.getChildren().add(ll);
        }

        Separator sep2 = new Separator();
        sep2.setStyle("-fx-background-color: " + COLOR_DIVIDER + ";");

        Label total = new Label("Total:  " + UiKit.rupiah(o.grandTotal));
        total.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 14));
        total.setStyle("-fx-text-fill: " + COLOR_SUCCESS + ";");
        card.getChildren().addAll(sep2, total);

        List<String> metaParts = new ArrayList<>();
        if (!o.vendor.isBlank())   metaParts.add("🏷 " + o.vendor);
        if (o.etaMinutes > 0)      metaParts.add("⏱ ETA " + o.etaMinutes + " menit");
        if (o.rating > 0)          metaParts.add("⭐ " + String.format(Locale.US, "%.1f", o.rating));
        if (!metaParts.isEmpty()) {
            Label meta = new Label(String.join("  •  ", metaParts));
            meta.setStyle("-fx-font-size: 11; -fx-text-fill: " + COLOR_TEXT_MUTED + ";");
            card.getChildren().add(meta);
        }

        Button buyBtn = new Button("🛒  Beli  " + UiKit.rupiah(o.grandTotal));
        buyBtn.setStyle(
            "-fx-background-color: " + (completed ? "#9CA3AF" : COLOR_SUCCESS) + ";" +
            "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13;" +
            "-fx-background-radius: 10; -fx-padding: 9 20; -fx-cursor: " + (completed ? "default" : "hand") + ";"
        );
        buyBtn.setDisable(completed);
        buyBtn.setMaxWidth(Double.MAX_VALUE);
        if (!completed) buyBtn.setOnAction(e -> onBuyAction.run());
        card.getChildren().add(buyBtn);

        card.setPadding(new Insets(12, 16, 12, 16));
        card.setStyle(
            "-fx-background-color: " + COLOR_OFFER_BUBBLE + ";" +
            "-fx-background-radius: 12;" +
            "-fx-border-color: " + COLOR_PRIMARY + ";" +
            "-fx-border-width: 0 0 0 3;" +
            "-fx-border-radius: 0 0 0 2;"
        );
        if (completed) card.setOpacity(0.7);

        HBox row = new HBox(card);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0, 60, 0, 46));
        return row;
    }

    private HBox buildCompletedBanner() {
        Label icon = new Label("✅");
        icon.setStyle("-fx-font-size: 20;");

        Label msg = new Label("Pesanan selesai! Gunakan tombol \"+ Request Baru\" untuk memesan lagi.");
        msg.setWrapText(true);
        msg.setStyle("-fx-font-size: 13; -fx-font-weight: bold; -fx-text-fill: " + COLOR_SUCCESS + ";");

        HBox banner = new HBox(10, icon, msg);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.setPadding(new Insets(12, 16, 12, 16));
        banner.setStyle(
            "-fx-background-color: " + COLOR_COMPLETED_BG + ";" +
            "-fx-background-radius: 10;" +
            "-fx-border-color: " + COLOR_SUCCESS + ";" +
            "-fx-border-radius: 10; -fx-border-width: 1;"
        );
        banner.setMaxWidth(Double.MAX_VALUE);
        return banner;
    }

    private HBox buildPendingBubble(String msg) {
        Label l = new Label(msg);
        l.setStyle(
            "-fx-text-fill: " + COLOR_TEXT_MUTED + ";" +
            "-fx-font-style: italic; -fx-font-size: 12;" +
            "-fx-background-color: #F9FAFB;" +
            "-fx-background-radius: 8; -fx-padding: 6 12;"
        );
        HBox row = new HBox(l);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0, 60, 0, 46));
        return row;
    }

    private Region chatDivider() {
        Region r = new Region();
        r.setMinHeight(6);
        return r;
    }

    private VBox emptyChatHint(String msg) {
        Label l = new Label(msg);
        l.setStyle("-fx-font-size: 14; -fx-text-fill: " + COLOR_TEXT_MUTED + ";");
        l.setWrapText(true);
        l.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        VBox box = new VBox(l);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(60));
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private String buildBuyerBubbleText(Message m) {
        if (m.orderItems == null || m.orderItems.size() <= 1) return m.text;
        // Skip render bullet list untuk pesan notif sistem (bukan order biasa)
        String t = m.text;
        if (t.contains("📋 Data Pengiriman") || t.contains("Buyer memilih Indomaret")
                || t.contains("📍 Buyer") || t.contains("💳 Buyer")
                || t.contains("[AUTO]")) return t;
        StringBuilder sb = new StringBuilder(t).append("\n");
        for (FirestoreService.OrderItem oi : m.orderItems) {
            sb.append("  • ").append(oi.qty).append("× ").append(oi.name).append("\n");
        }
        return sb.toString().trim();
    }

    private String now() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    // ============================================================
    // AI PAYLOAD WIDGET — kategori pilihan + link YT
    // ============================================================
    
    /**
     * Widget AI greeting awal — greeting + link video + tombol-tombol kategori.
     *
     * Saat buyer klik salah satu tombol kategori:
     *   1. Widget ini di-remove dari chatBox (swap in-place, bukan full re-render).
     *   2. Bubble buyer kecil muncul ("Saya pilih Yummy Choice").
     *   3. AI bubble + widget menu/item langsung di-inject ke chatBox.
     *
     * Dengan cara ini tidak ada flicker dan scrollPosition tidak loncat.
     */
    private HBox buildAiPayloadWidget(AiAutoReply.AiReplyPayload payload) {
        Label avatarLbl = new Label("🏪");
        avatarLbl.setStyle("-fx-font-size: 20;");

        // Greeting teks utama
        Label greetingLbl = new Label(payload.greeting);
        greetingLbl.setWrapText(true);
        greetingLbl.setMaxWidth(380);
        greetingLbl.setStyle(
            "-fx-font-size: 13; -fx-text-fill: " + COLOR_TEXT_MAIN + ";" +
            "-fx-font-weight: bold;"
        );

        Label videoTitle = new Label("📺  Link Review Produk Kami:");
        videoTitle.setStyle("-fx-font-size: 12; -fx-font-weight: bold; -fx-text-fill: " + COLOR_TEXT_MUTED + ";");

        Hyperlink ytLink = new Hyperlink(payload.videoUrl);
        ytLink.setStyle(
            "-fx-font-size: 12; -fx-text-fill: " + COLOR_PRIMARY + ";" +
            "-fx-border-color: transparent; -fx-padding: 0;"
        );
        ytLink.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(payload.videoUrl));
            } catch (Exception ex) {
                javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                cc.putString(payload.videoUrl);
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            }
        });

        VBox videoBox = new VBox(4, videoTitle, ytLink);
        videoBox.setPadding(new Insets(0, 0, 6, 0));

        Label catLabel = new Label("📂  Pilih kategori produk:");
        catLabel.setStyle("-fx-font-size: 13; -fx-font-weight: bold; -fx-text-fill: " + COLOR_TEXT_MAIN + ";");

        FlowPane categoryButtons = new FlowPane(8, 8);
        categoryButtons.setPadding(new Insets(4, 0, 4, 0));

        // aiBox & wrapper dibuat dulu supaya bisa di-remove dari chatBox di dalam lambda
        VBox aiBox = new VBox(10, greetingLbl, videoBox, catLabel, categoryButtons);
        aiBox.setPadding(new Insets(12, 16, 12, 16));
        aiBox.setStyle(
            "-fx-background-color: #EEF2FF;" +
            "-fx-background-radius: 12;" +
            "-fx-border-color: " + COLOR_PRIMARY + ";" +
            "-fx-border-width: 0 0 0 3;" +
            "-fx-border-radius: 0 0 0 2;"
        );

        HBox wrapper = new HBox(10, avatarLbl, aiBox);
        wrapper.setAlignment(Pos.TOP_LEFT);
        wrapper.setPadding(new Insets(0, 60, 0, 0));

        for (AiAutoReply.FoodCategory cat : payload.categories) {
            Button btn = new Button(cat.emoji + "  " + cat.label);
            btn.setStyle(
                "-fx-background-color: " + COLOR_PRIMARY + ";" +
                "-fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold;" +
                "-fx-background-radius: 20; -fx-padding: 6 14; -fx-cursor: hand;"
            );
            btn.setOnMouseEntered(e -> btn.setOpacity(0.85));
            btn.setOnMouseExited(e  -> btn.setOpacity(1.0));
            btn.setOnAction(e -> {
                if (categoryChosen) return; // abaikan double-click
                categoryChosen = true;
                lastAiPayload  = null;

                // ── Swap in-place: hapus widget kategori dari chatBox ──
                chatBox.getChildren().remove(wrapper);

                // ── Bubble buyer kecil ──────────────────────────────
                chatBox.getChildren().add(
                    buildBuyerBubble(cat.emoji + " Saya pilih " + cat.label));

                // ── Kirim ke Firestore (seller lihat) ───────────────
                if (currentRequestId != null) {
                    final String reqId  = currentRequestId;
                    final String catKey = cat.key;
                    new Thread(() -> AiAutoReply.replyWithMenuSuggestion(reqId, catKey, fs)).start();
                }

                // ── Inject menu widget sesuai kategori ─────────────
                if ("yummy".equals(cat.key)) {
                    onCategoryYummyInline();
                } else {
                    onCategoryOtherInline(cat);
                }
            });
            categoryButtons.getChildren().add(btn);
        }

        return wrapper;
    }

    // ============================================================
    // YUMMY CHOICE — menu item buttons
    // ============================================================

    /**
     * Dipanggil saat buyer klik "😋 Yummy Choice" — inject langsung ke chatBox
     * tanpa full re-render, sehingga widget kategori sudah hilang (di-remove
     * di buildAiPayloadWidget) dan menu muncul mulus di bawahnya.
     */
    private void onCategoryYummyInline() {
        pendingYummyMenu = true;

        // Bubble AI "pilih menu:"
        chatBox.getChildren().add(buildSellerBubble(
            "😋 Yummy Choice! Berikut menu spesial kami hari ini — pilih yang kamu mau 👇"));

        // Widget menu item buttons
        chatBox.getChildren().add(buildYummyMenuButtonsWidget());

        Platform.runLater(() -> chatScroll.setVvalue(1.0));
    }

    /**
     * Dipanggil saat buyer klik kategori selain Yummy (Bakery / Coffee / dll).
     * Inject bubble AI + tombol-tombol item dari kategori tsb.
     * Untuk kategori non-Yummy, saat ini tidak ada menu detail hardcoded,
     * jadi cukup tampil bubble konfirmasi + input text untuk buyer ketik pesanan.
     */
    private void onCategoryOtherInline(AiAutoReply.FoodCategory cat) {
        if ("bakery".equals(cat.key)) {
            pendingBakeryMenu = true;
            // Bakery: tampilkan widget tombol menu seperti Yummy
            chatBox.getChildren().add(buildSellerBubble(
                "🥐 Bakery dipilih! Berikut pilihan roti kami — pilih yang kamu mau 👇"));
            chatBox.getChildren().add(buildBakeryMenuButtonsWidget());
        } else if ("coffee".equals(cat.key)) {
            pendingCoffeeMenu = true;
            // Coffee: tampilkan widget tombol menu
            chatBox.getChildren().add(buildSellerBubble(
                "☕ Coffee dipilih! Berikut pilihan minuman kami — pilih yang kamu mau 👇"));
            chatBox.getChildren().add(buildCoffeeMenuButtonsWidget());
        } else {
            // Kategori lain: teks konfirmasi + ketik manual
            chatBox.getChildren().add(buildSellerBubble(
                cat.emoji + " " + cat.label + " dipilih!\n" +
                "Ketik pesananmu dan kirim, seller akan segera menyiapkan offer terbaik untukmu 🙏"));
            Platform.runLater(() -> {
                msgInput.setPromptText("Tulis pesanan " + cat.label + " kamu...");
                msgInput.requestFocus();
            });
        }
        Platform.runLater(() -> chatScroll.setVvalue(1.0));
    }

    /** @deprecated Diganti oleh onCategoryYummyInline() — tetap ada agar tidak ada compile error. */
    @Deprecated
    private void onCategoryYummy() {
        onCategoryYummyInline();
    }

    /**
     * Widget Yummy Choice — setiap item punya tombol individual dengan nama & harga.
     * Buyer klik item → muncul form pengiriman inline.
     */
    private HBox buildYummyMenuButtonsWidget() {
        Label header = new Label("😋  Yummy Choice — Pilih Produkmu:");
        header.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 14));
        header.setStyle("-fx-text-fill: " + COLOR_PRIMARY + ";");
        header.setWrapText(true);

        // Grid tombol item: 2 kolom
        FlowPane grid = new FlowPane(8, 8);
        grid.setPrefWrapLength(380);

        for (AiAutoReply.YummyMenuItem item : AiAutoReply.YUMMY_MENU) {
            VBox itemBtn = buildMenuItemButton(item);
            grid.getChildren().add(itemBtn);
        }

        VBox card = new VBox(12, header, grid);
        card.setPadding(new Insets(14, 16, 14, 16));
        card.setPrefWidth(440);
        card.setMaxWidth(440);
        card.setStyle(
            "-fx-background-color: #FFF8EC;" +
            "-fx-background-radius: 14;" +
            "-fx-border-color: #F59E0B;" +
            "-fx-border-width: 0 0 0 4;" +
            "-fx-border-radius: 0 0 0 2;" +
            "-fx-effect: dropshadow(gaussian,rgba(0,0,0,0.08),8,0,0,2);"
        );

        Label avatarLbl = new Label("🏪");
        avatarLbl.setStyle("-fx-font-size: 20;");

        HBox wrapper = new HBox(10, avatarLbl, card);
        wrapper.setAlignment(Pos.TOP_LEFT);
        wrapper.setPadding(new Insets(0, 40, 0, 0));
        return wrapper;
    }


    /**
     * Widget Bakery — tombol menu roti per item.
     */
    private HBox buildBakeryMenuButtonsWidget() {
        Label header = new Label("🥐  Bakery — Pilih Produkmu:");
        header.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 14));
        header.setStyle("-fx-text-fill: " + COLOR_PRIMARY + ";");
        header.setWrapText(true);

        FlowPane grid = new FlowPane(8, 8);
        grid.setPrefWrapLength(380);

        for (AiAutoReply.YummyMenuItem item : AiAutoReply.BAKERY_MENU) {
            grid.getChildren().add(buildMenuItemButton(item));
        }

        VBox card = new VBox(12, header, grid);
        card.setPadding(new Insets(14, 16, 14, 16));
        card.setPrefWidth(440);
        card.setMaxWidth(440);
        card.setStyle(
            "-fx-background-color: #FFF5F0;" +
            "-fx-background-radius: 14;" +
            "-fx-border-color: #F97316;" +
            "-fx-border-width: 0 0 0 4;" +
            "-fx-border-radius: 0 0 0 2;" +
            "-fx-effect: dropshadow(gaussian,rgba(0,0,0,0.08),8,0,0,2);"
        );

        Label avatarLbl = new Label("🏪");
        avatarLbl.setStyle("-fx-font-size: 20;");

        HBox wrapper = new HBox(10, avatarLbl, card);
        wrapper.setAlignment(Pos.TOP_LEFT);
        wrapper.setPadding(new Insets(0, 40, 0, 0));
        return wrapper;
    }

    /**
     * Widget Coffee — tombol menu minuman per item.
     */
    private HBox buildCoffeeMenuButtonsWidget() {
        Label header = new Label("☕  Coffee — Pilih Minumanmu:");
        header.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 14));
        header.setStyle("-fx-text-fill: " + COLOR_PRIMARY + ";");
        header.setWrapText(true);

        FlowPane grid = new FlowPane(8, 8);
        grid.setPrefWrapLength(380);

        for (AiAutoReply.YummyMenuItem item : AiAutoReply.COFFEE_MENU) {
            grid.getChildren().add(buildMenuItemButton(item));
        }

        VBox card = new VBox(12, header, grid);
        card.setPadding(new Insets(14, 16, 14, 16));
        card.setPrefWidth(440);
        card.setMaxWidth(440);
        card.setStyle(
            "-fx-background-color: #F0FDF4;" +
            "-fx-background-radius: 14;" +
            "-fx-border-color: #10B981;" +
            "-fx-border-width: 0 0 0 4;" +
            "-fx-border-radius: 0 0 0 2;" +
            "-fx-effect: dropshadow(gaussian,rgba(0,0,0,0.08),8,0,0,2);"
        );

        Label avatarLbl = new Label("🏪");
        avatarLbl.setStyle("-fx-font-size: 20;");

        HBox wrapper = new HBox(10, avatarLbl, card);
        wrapper.setAlignment(Pos.TOP_LEFT);
        wrapper.setPadding(new Insets(0, 40, 0, 0));
        return wrapper;
    }

    private VBox buildMenuItemButton(AiAutoReply.YummyMenuItem item) {
        Label nameLbl = new Label(item.name);
        nameLbl.setFont(Font.font("System", FontWeight.BOLD, 12));
        nameLbl.setStyle("-fx-text-fill: " + COLOR_TEXT_MAIN + ";");
        nameLbl.setWrapText(true);

        Label priceLbl = new Label(UiKit.rupiah(item.price));
        priceLbl.setStyle("-fx-font-size: 12; -fx-font-weight: bold; -fx-text-fill: " + COLOR_SUCCESS + ";");

        Label descLbl = new Label(item.description);
        descLbl.setStyle("-fx-font-size: 10; -fx-text-fill: " + COLOR_TEXT_MUTED + ";");
        descLbl.setWrapText(true);

        VBox card = new VBox(4, nameLbl, priceLbl, descLbl);
        card.setPadding(new Insets(10, 12, 10, 12));
        card.setPrefWidth(188);
        card.setMaxWidth(188);
        card.setStyle(
            "-fx-background-color: white;" +
            "-fx-background-radius: 12;" +
            "-fx-border-color: " + COLOR_DIVIDER + ";" +
            "-fx-border-radius: 12; -fx-border-width: 1;" +
            "-fx-cursor: hand;" +
            "-fx-effect: dropshadow(gaussian,rgba(0,0,0,0.05),4,0,0,1);"
        );

        // Hover effect
        card.setOnMouseEntered(e -> card.setStyle(card.getStyle()
            .replace("-fx-border-color: " + COLOR_DIVIDER, "-fx-border-color: " + COLOR_PRIMARY)
            .replace("white;", "#EEF3FF;")));
        card.setOnMouseExited(e -> card.setStyle(card.getStyle()
            .replace("-fx-border-color: " + COLOR_PRIMARY, "-fx-border-color: " + COLOR_DIVIDER)
            .replace("#EEF3FF;", "white;")));

        card.setOnMouseClicked(e -> onYummyItemSelected(item));
        return card;
    }

    /**
     * Buyer klik salah satu item Yummy Choice →
     * Bubble buyer kecil muncul, lalu AI bubble minta isi form, lalu form muncul.
     */
    private void onYummyItemSelected(AiAutoReply.YummyMenuItem item) {
        if (isCurrentCompleted()) return;

        // Bubble buyer: "Saya mau X"
        chatBox.getChildren().add(
            buildBuyerBubble("🛒 Saya mau " + item.name + " (" + UiKit.rupiah(item.price) + ")"));

        // AI bubble meminta isi form
        chatBox.getChildren().add(buildSellerBubble(
            "Pilihan bagus! 🎉\nSilakan isi data pengirimanmu di bawah ya 👇"));

        // Set state untuk form
        pendingItem         = item;
        deliveryFormShowing = false;

        // Inject form langsung (tidak re-render seluruh chat)
        HBox formWidget = buildDeliveryFormWidget(item);
        chatBox.getChildren().add(formWidget);
        deliveryFormShowing = true;

        Platform.runLater(() -> chatScroll.setVvalue(1.0));
    }

    // ============================================================
    // DELIVERY FORM — inline di chat
    // ============================================================

    /**
     * Form pengiriman inline bergaya chat bubble dari "AI/Toko".
     * Field: Nama, No HP, Alamat → tombol Kirim.
     * Setelah submit: AI balas lokasi Indomaret terdekat.
     */
    private HBox buildDeliveryFormWidget(AiAutoReply.YummyMenuItem item) {
        Label avatarLbl = new Label("🏪");
        avatarLbl.setStyle("-fx-font-size: 20;");

        // Header
        Label titleLbl = new Label("📋  Lengkapi data pengirimanmu:");
        titleLbl.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 13));
        titleLbl.setStyle("-fx-text-fill: " + COLOR_TEXT_MAIN + ";");

        Label itemInfoLbl = new Label("Pesanan: " + item.name + "  —  " + UiKit.rupiah(item.price));
        itemInfoLbl.setStyle("-fx-font-size: 12; -fx-text-fill: " + COLOR_TEXT_MUTED + "; -fx-font-style: italic;");

        // Form fields
        TextField nameField  = new TextField(PREFS.get("buyerName", ""));
        TextField phoneField = new TextField(PREFS.get("buyerPhone", ""));
        TextField addrField  = new TextField(PREFS.get("buyerAddress", ""));

        styleFormField(nameField,  "👤 Nama lengkap");
        styleFormField(phoneField, "📞 No. HP / WhatsApp");
        styleFormField(addrField,  "📍 Alamat lengkap (untuk cari Indomaret terdekat)");

        Label nameLbl  = fieldLabel("Nama");
        Label phoneLbl = fieldLabel("No. HP");
        Label addrLbl  = fieldLabel("Alamat");

        // Submit button
        Button submitBtn = new Button("🚀  Cari Indomaret Terdekat");
        submitBtn.setStyle(
            "-fx-background-color: " + COLOR_PRIMARY + ";" +
            "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13;" +
            "-fx-background-radius: 12; -fx-padding: 10 20; -fx-cursor: hand;"
        );
        submitBtn.setMaxWidth(Double.MAX_VALUE);
        submitBtn.setOnMouseEntered(e -> submitBtn.setStyle(submitBtn.getStyle().replace(COLOR_PRIMARY, COLOR_PRIMARY_DARK)));
        submitBtn.setOnMouseExited(e  -> submitBtn.setStyle(submitBtn.getStyle().replace(COLOR_PRIMARY_DARK, COLOR_PRIMARY)));

        // Loading indicator
        Label loadingLbl = new Label("⏳ Mencari lokasi terdekat...");
        loadingLbl.setStyle("-fx-font-size: 12; -fx-text-fill: " + COLOR_TEXT_MUTED + "; -fx-font-style: italic;");
        loadingLbl.setVisible(false);
        loadingLbl.setManaged(false);

        VBox formCard = new VBox(10,
            titleLbl, itemInfoLbl,
            new Separator(),
            nameLbl,  nameField,
            phoneLbl, phoneField,
            addrLbl,  addrField,
            new Separator(),
            submitBtn, loadingLbl
        );
        formCard.setPadding(new Insets(14, 16, 14, 16));
        formCard.setPrefWidth(400);
        formCard.setMaxWidth(400);
        formCard.setStyle(
            "-fx-background-color: " + COLOR_SURFACE + ";" +
            "-fx-background-radius: 14;" +
            "-fx-border-color: " + COLOR_PRIMARY + ";" +
            "-fx-border-width: 1;" +
            "-fx-border-radius: 14;" +
            "-fx-effect: dropshadow(gaussian,rgba(95,91,255,0.12),12,0,0,3);"
        );

        submitBtn.setOnAction(e -> {
            String name  = nameField.getText().trim();
            String phone = phoneField.getText().trim();
            String addr  = addrField.getText().trim();

            if (addr.isBlank()) {
                addrField.setStyle(addrField.getStyle().replace(COLOR_DIVIDER, COLOR_DANGER));
                addrField.setPromptText("⚠️ Alamat wajib diisi!");
                return;
            }

            // Simpan ke prefs
            PREFS.put("buyerName",    name);
            PREFS.put("buyerPhone",   phone);
            PREFS.put("buyerAddress", addr);

            // Lock form
            submitBtn.setDisable(true);
            nameField.setDisable(true);
            phoneField.setDisable(true);
            addrField.setDisable(true);
            loadingLbl.setVisible(true);
            loadingLbl.setManaged(true);

            // Kirim ke Firestore (catatan ke seller) + tampil balasan AI
            onDeliveryFormSubmit(new AiAutoReply.DeliveryFormResult(name, phone, addr, item.name, item.price), formCard, loadingLbl);
        });

        // Enter juga submit
        addrField.setOnAction(e -> submitBtn.fire());
        phoneField.setOnAction(e -> addrField.requestFocus());
        nameField.setOnAction(e  -> phoneField.requestFocus());

        HBox wrapper = new HBox(10, avatarLbl, formCard);
        wrapper.setAlignment(Pos.TOP_LEFT);
        wrapper.setPadding(new Insets(4, 40, 4, 0));
        return wrapper;
    }

    private Label fieldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11; -fx-font-weight: bold; -fx-text-fill: " + COLOR_TEXT_MUTED + ";");
        return l;
    }

    private void styleFormField(TextField tf, String prompt) {
        tf.setPromptText(prompt);
        tf.setStyle(
            "-fx-background-color: #F9FAFB;" +
            "-fx-border-color: " + COLOR_DIVIDER + ";" +
            "-fx-border-radius: 8; -fx-background-radius: 8;" +
            "-fx-padding: 8 12; -fx-font-size: 13;"
        );
        tf.setMaxWidth(Double.MAX_VALUE);
        tf.focusedProperty().addListener((o, ov, nv) -> {
            if (nv) tf.setStyle(tf.getStyle().replace(COLOR_DIVIDER, COLOR_PRIMARY));
            else    tf.setStyle(tf.getStyle().replace(COLOR_PRIMARY, COLOR_DIVIDER));
        });
    }

    // ============================================================
    // AFTER FORM SUBMIT — kirim ke Firestore + tampil lokasi AI
    // ============================================================

    private void onDeliveryFormSubmit(AiAutoReply.DeliveryFormResult result,
                                      VBox formCard, Label loadingLbl) {
        final String reqId = currentRequestId;

        new Thread(() -> {
            try {
                // 1. Kirim data pengiriman ke Firestore — seller baca ini
                String buyerMsg = "📋 Data Pengiriman:\n" +
                    "Nama: "    + (result.name.isBlank()  ? "-" : result.name)  + "\n" +
                    "HP: "      + (result.phone.isBlank() ? "-" : result.phone) + "\n" +
                    "Alamat: "  + result.address + "\n" +
                    "Pesanan: " + result.itemName + " (" + UiKit.rupiah(result.price) + ")";
                if (reqId != null) fs.sendBuyerMessage(reqId, buyerId, buyerMsg);

                // 2. Kirim notif ringkas ke seller
                if (reqId != null) {
                    fs.sendSellerMessage(reqId, "AI_BOT",
                        "[AUTO] 📍 Buyer sedang memilih Indomaret terdekat dari: " + result.address);
                }

                // 3. Geocode alamat buyer → dapat koordinat real
                double[] coords = AiAutoReply.geocodeAddress(result.address);

                if (coords == null) {
                    // Geocoding gagal — minta buyer perjelas alamat
                    Platform.runLater(() -> {
                        loadingLbl.setVisible(false);
                        loadingLbl.setManaged(false);
                        // Kembalikan form agar bisa diedit ulang
                        formCard.getChildren().stream()
                            .filter(n -> n instanceof Button || n instanceof TextField)
                            .forEach(n -> n.setDisable(false));
                        chatBox.getChildren().add(buildSellerBubble(
                            "⚠️ Alamat kamu belum bisa kami temukan di peta.\n\n" +
                            "Pastikan nama tempat / kelurahan / kota sudah tertulis, contoh:\n" +
                            "• \"Jl. Sudirman No. 10, Jakarta Pusat\"\n" +
                            "• \"Perumahan Cikarang Baru, Bekasi\"\n" +
                            "• \"Kec. Cikarang Barat, Kab. Bekasi\"\n\n" +
                            "Jangan khawatir, kamu bisa coba lagi di form di atas 👆"));
                        Platform.runLater(() -> chatScroll.setVvalue(1.0));
                    });
                    return;
                }

                // 4. Cari Indomaret real via Overpass API
                List<AiAutoReply.IndomaretLocation> nearby =
                    AiAutoReply.findNearbyIndomaret(coords[0], coords[1]);

                // Fallback ke data statis kalau Overpass gagal / kosong
                if (nearby == null || nearby.isEmpty()) {
                    nearby = AiAutoReply.INDOMARET_LOCATIONS;
                }
                AiAutoReply.INDOMARET_LOCATIONS_RESOLVED = nearby;

                final double[] buyerCoords  = coords;
                final List<AiAutoReply.IndomaretLocation> finalLocs = nearby;

                Platform.runLater(() -> {
                    loadingLbl.setVisible(false);
                    loadingLbl.setManaged(false);
                    pendingItem = null;

                    // Collapse form card → konfirmasi ringkas
                    formCard.getChildren().clear();
                    Label doneLbl = new Label("✅  Data terkirim!");
                    doneLbl.setStyle("-fx-font-size: 12; -fx-font-weight: bold; -fx-text-fill: " + COLOR_SUCCESS + ";");
                    formCard.getChildren().add(doneLbl);
                    formCard.setStyle(
                        "-fx-background-color: " + COLOR_COMPLETED_BG + ";" +
                        "-fx-background-radius: 14;" +
                        "-fx-border-color: " + COLOR_SUCCESS + ";" +
                        "-fx-border-width: 1; -fx-border-radius: 14;"
                    );

                    // AI bubble sebelum peta
                    chatBox.getChildren().add(buildSellerBubble(
                        "📍 Oke! Berikut pilihan Indomaret terdekat dari alamatmu.\n" +
                        "Pilih yang paling nyaman buat kamu 👇"));

                    // 5. Render widget peta dengan koordinat buyer yang real
                    chatBox.getChildren().add(
                        buildLocationReplyWidget(finalLocs, result, buyerCoords));
                    Platform.runLater(() -> chatScroll.setVvalue(1.0));
                });

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    loadingLbl.setVisible(false);
                    loadingLbl.setManaged(false);
                    showError("Gagal mengirim data", ex.getMessage());
                });
            }
        }).start();
    }

    /**
     * Widget balasan lokasi — GMaps-style card per Indomaret.
     */
    // ============================================================
    // MAP WIDGET — Leaflet OpenStreetMap + panel pilih Indomaret
    // ============================================================

    private HBox buildLocationReplyWidget(List<AiAutoReply.IndomaretLocation> locs,
                                           AiAutoReply.DeliveryFormResult result,
                                           double[] buyerCoords) {
        Label avatarLbl = new Label("\uD83C\uDFEA");
        avatarLbl.setStyle("-fx-font-size: 20;");

        Label headerLbl = new Label("\uD83D\uDCCD  Pilih Indomaret terdekat:");
        headerLbl.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 13));
        headerLbl.setStyle("-fx-text-fill: " + COLOR_TEXT_MAIN + ";");

        // Center peta di lokasi BUYER, bukan rata-rata Indomaret
        double centerLat = (buyerCoords != null) ? buyerCoords[0]
            : locs.stream().mapToDouble(l -> l.lat).average().orElse(-6.21);
        double centerLng = (buyerCoords != null) ? buyerCoords[1]
            : locs.stream().mapToDouble(l -> l.lng).average().orElse(106.82);

        StringBuilder markers = new StringBuilder("[");
        for (int i = 0; i < locs.size(); i++) {
            AiAutoReply.IndomaretLocation loc = locs.get(i);
            markers.append(String.format(java.util.Locale.US,
                "{idx:%d,lat:%f,lng:%f,name:\"%s\",addr:\"%s\",walk:%d,rating:%.1f,jam:\"%s\"}",
                i, loc.lat, loc.lng,
                loc.name.replace("\"","'"),
                loc.address.replace("\"","'"),
                loc.distanceMinutes, loc.rating, loc.operationalHours));
            if (i < locs.size() - 1) markers.append(",");
        }
        markers.append("]");

        String html = "<!DOCTYPE html><html><head>" +
            "<meta charset='utf-8'/>" +
            "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>" +
            "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>" +
            "<style>body{margin:0}#map{width:100%;height:260px}" +
            ".pb{margin-top:6px;background:#4F46E5;color:white;border:none;" +
            "padding:5px 12px;border-radius:6px;cursor:pointer;font-size:12px;width:100%}" +
            ".pb:hover{background:#3730A3}.pn{font-weight:bold;font-size:13px;color:#4F46E5}" +
            "</style></head><body><div id='map'></div><script>" +
            "var map=L.map('map').setView([" + String.format(java.util.Locale.US, "%f,%f", centerLat, centerLng) + "],15);" +

            // Tile layer OSM atau Google Maps
            (AiAutoReply.GOOGLE_API_KEY.isBlank()
                ? "L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'," +
                  "{attribution:'\\u00a9 OSM',maxZoom:19}).addTo(map);"
                : "L.tileLayer('https://mt{s}.google.com/vt/lyrs=m&x={x}&y={y}&z={z}'," +
                  "{subdomains:['0','1','2','3'],attribution:'\\u00a9 Google Maps',maxZoom:20}).addTo(map);"
            ) +

            // Marker biru untuk lokasi BUYER
            (buyerCoords != null
                ? "L.circleMarker([" + String.format(java.util.Locale.US, "%f,%f", centerLat, centerLng) + "]," +
                  "{radius:8,color:'#2563EB',fillColor:'#3B82F6',fillOpacity:1,weight:2})" +
                  ".addTo(map).bindPopup('\\uD83D\\uDCCD Lokasi kamu').openPopup();"
                : ""
            ) +

            "var ico=L.divIcon({className:'',html:'<div style=\\'background:#4F46E5;color:white;" +
            "border-radius:50% 50% 50% 0;width:32px;height:32px;display:flex;align-items:center;" +
            "justify-content:center;font-size:17px;box-shadow:0 2px 6px rgba(0,0,0,.4);" +
            "transform:rotate(-45deg)\\'><span style=\\'transform:rotate(45deg)\\'>\\uD83C\\uDFEA</span></div>'," +
            "iconSize:[32,32],iconAnchor:[16,32],popupAnchor:[0,-34]});" +
            "var d=" + markers.toString() + ";" +
            "d.forEach(function(p){" +
            "L.marker([p.lat,p.lng],{icon:ico}).addTo(map)" +
            ".bindPopup('<div><div class=\\'pn\\'>'+p.name+'</div>" +
            "<div style=\\'font-size:11px\\'>'+p.addr+'</div>" +
            "<div style=\\'font-size:11px\\'>\\uD83D\\uDEB6 '+p.walk+' mnt \\u00b7 \\u2B50 '+p.rating+' \\u00b7 \\uD83D\\uDD50 '+p.jam+'</div>" +
            "<button class=\\'pb\\' onclick=\\'pilih('+p.idx+')\\'>\\u2705 Pilih ini</button></div>',{maxWidth:220});});" +
            "function pilih(i){window.javaConnector.onPilih(i);}" +
            "</script></body></html>";

        WebView webView = new WebView();
        webView.setPrefSize(420, 260);
        webView.setMaxWidth(420);
        WebEngine engine = webView.getEngine();

        VBox listPanel = new VBox(8);
        listPanel.setPadding(new Insets(8, 0, 0, 0));
        for (int i = 0; i < locs.size(); i++) {
            final AiAutoReply.IndomaretLocation loc = locs.get(i);
            listPanel.getChildren().add(buildLocationCard(loc, i + 1, result,
                () -> onIndomaretPicked(loc, result, webView)));
        }

        VBox card = new VBox(10, headerLbl, webView, listPanel);
        card.setPadding(new Insets(14, 16, 14, 16));
        card.setPrefWidth(450);
        card.setMaxWidth(450);
        card.setStyle(
            "-fx-background-color: " + COLOR_SURFACE + ";" +
            "-fx-background-radius: 14;" +
            "-fx-border-color: #10B981;" +
            "-fx-border-width: 0 0 0 4;" +
            "-fx-border-radius: 0 0 0 2;" +
            "-fx-effect: dropshadow(gaussian,rgba(0,0,0,0.08),8,0,0,2);"
        );

        engine.loadContent(html);
        engine.getLoadWorker().stateProperty().addListener((obs, oldS, newS) -> {
            if (newS == javafx.concurrent.Worker.State.SUCCEEDED) {
                JSObject win = (JSObject) engine.executeScript("window");
                win.setMember("javaConnector", new Object() {
                    @SuppressWarnings("unused")
                    public void onPilih(int idx) {
                        if (idx >= 0 && idx < locs.size()) {
                            Platform.runLater(() -> onIndomaretPicked(locs.get(idx), result, webView));
                        }
                    }
                });
            }
        });

        HBox wrapper = new HBox(10, avatarLbl, card);
        wrapper.setAlignment(Pos.TOP_LEFT);
        wrapper.setPadding(new Insets(4, 10, 4, 0));
        return wrapper;
    }

    private void onIndomaretPicked(AiAutoReply.IndomaretLocation loc,
                                    AiAutoReply.DeliveryFormResult result, WebView mapView) {
        final String reqId = currentRequestId;

        // Kirim info ke Firestore (seller lihat)
        new Thread(() -> {
            try {
                String msg = "📍 Buyer memilih Indomaret: " + loc.name +
                    "\n📌 " + loc.address +
                    "\n🚶 " + loc.distanceMinutes + " menit  ·  ⭐ " +
                    String.format(java.util.Locale.US, "%.1f", loc.rating) +
                    "\nPesanan: " + result.itemName + " (" + UiKit.rupiah(result.price) + ")" +
                    "\nNama: " + result.name + "  |  HP: " + result.phone;
                if (reqId != null) fs.sendBuyerMessage(reqId, buyerId, msg);
            } catch (Exception ignored) {}
        }).start();

        // Bubble buyer konfirmasi + widget payment langsung
        Platform.runLater(() -> {
            if (mapView != null) mapView.setDisable(true);

            // Bubble buyer konfirmasi pilihan
            chatBox.getChildren().add(buildBuyerBubble(
                "✅ Saya pilih " + loc.name + "\n📌 " + loc.address));

            // Simpan state
            pickedIndomaret      = loc;
            pickedDelivery       = result;
            paymentWidgetShowing = false;

            // Widget pembayaran langsung (greeting ada di dalam widget)
            chatBox.getChildren().add(buildPaymentWidget(loc, result));
            paymentWidgetShowing = true;

            Platform.runLater(() -> chatScroll.setVvalue(1.0));
        });
    }

    // ============================================================
    // PAYMENT WIDGET — step 10: pilih metode bayar
    // ============================================================

    private HBox buildPaymentWidget(AiAutoReply.IndomaretLocation loc,
                                    AiAutoReply.DeliveryFormResult result) {
        Label avatarLbl = new Label("🏪");
        avatarLbl.setStyle("-fx-font-size: 20;");

        Label titleLbl = new Label("💳  Pilih metode pembayaran:");
        titleLbl.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 13));
        titleLbl.setStyle("-fx-text-fill: " + COLOR_TEXT_MAIN + ";");

        Label summaryLbl = new Label(
            "📦 " + result.itemName + " — " + UiKit.rupiah(result.price) +
            "\n🏪 " + loc.name);
        summaryLbl.setStyle("-fx-font-size: 12; -fx-text-fill: " + COLOR_TEXT_MUTED + "; -fx-font-style: italic;");
        summaryLbl.setWrapText(true);

        // 4 tombol metode pembayaran
        record PayMethod(String emoji, String label, String key) {}
        List<PayMethod> methods = List.of(
            new PayMethod("🔲", "QRIS",    "qris"),
            new PayMethod("🏦", "Transfer Bank", "bank"),
            new PayMethod("💚", "E-Wallet",      "ewallet"),
            new PayMethod("💵", "COD (Tunai)",   "cod")
        );

        FlowPane btnPane = new FlowPane(10, 10);
        btnPane.setPadding(new Insets(6, 0, 0, 0));

        for (PayMethod m : methods) {
            Button btn = new Button(m.emoji + "  " + m.label);
            btn.setStyle(
                "-fx-background-color: white;" +
                "-fx-text-fill: " + COLOR_TEXT_MAIN + "; -fx-font-size: 13; -fx-font-weight: bold;" +
                "-fx-background-radius: 12; -fx-padding: 10 18; -fx-cursor: hand;" +
                "-fx-border-color: " + COLOR_PRIMARY + "; -fx-border-width: 2; -fx-border-radius: 12;"
            );
            btn.setOnMouseEntered(e -> btn.setStyle(btn.getStyle()
                .replace("white", "#EEF2FF")));
            btn.setOnMouseExited(e -> btn.setStyle(btn.getStyle()
                .replace("#EEF2FF", "white")));
            btn.setOnAction(e -> {
                // Disable semua tombol setelah dipilih
                btnPane.getChildren().forEach(node -> node.setDisable(true));
                btn.setStyle(btn.getStyle()
                    .replace("white", "#4F46E5")
                    .replace(COLOR_TEXT_MAIN, "white"));
                onPaymentSelected(m.key, m.label, loc, result);
            });
            btnPane.getChildren().add(btn);
        }

        // Greeting AI langsung di atas tombol
        Label greetLbl = new Label("🎉 Mantap! Sekarang pilih metode pembayaranmu:");
        greetLbl.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 13));
        greetLbl.setStyle("-fx-text-fill: " + COLOR_TEXT_MAIN + ";");
        greetLbl.setWrapText(true);

        VBox card = new VBox(10, greetLbl, new Separator(), titleLbl, summaryLbl, new Separator(), btnPane);
        card.setPadding(new Insets(14, 16, 14, 16));
        card.setPrefWidth(420);
        card.setMaxWidth(420);
        card.setStyle(
            "-fx-background-color: " + COLOR_SURFACE + ";" +
            "-fx-background-radius: 14;" +
            "-fx-border-color: #F59E0B;" +
            "-fx-border-width: 0 0 0 4;" +
            "-fx-border-radius: 0 0 0 2;" +
            "-fx-effect: dropshadow(gaussian,rgba(0,0,0,0.08),8,0,0,2);"
        );

        HBox wrapper = new HBox(10, avatarLbl, card);
        wrapper.setAlignment(Pos.TOP_LEFT);
        wrapper.setPadding(new Insets(4, 30, 4, 0));
        return wrapper;
    }

    /** Step 11: buyer pilih metode bayar → step 12: AI konfirmasi + complete request */
    private void onPaymentSelected(String methodKey, String methodLabel,
                                   AiAutoReply.IndomaretLocation loc,
                                   AiAutoReply.DeliveryFormResult result) {
        final String reqId = currentRequestId;

        // Bubble buyer: "Saya bayar pakai X"
        chatBox.getChildren().add(buildBuyerBubble("💳 Bayar pakai " + methodLabel));

        // Bubble AI konfirmasi langsung
        String konfirmasi = "✅ Baik, pesananmu sudah dikonfirmasi! Mohon ditunggu ya 🙏\n\n" +
            "📦 " + result.itemName + " — " + UiKit.rupiah(result.price) + "\n" +
            "🏪 " + loc.name + "\n" +
            "💳 Metode: " + methodLabel + "\n" +
            ("cod".equals(methodKey)
                ? "📞 Seller segera menghubungi " + (result.phone.isBlank() ? "kamu" : result.phone) + "."
                : "📞 Seller akan mengirim instruksi pembayaran ke " +
                  (result.phone.isBlank() ? "kamu" : result.phone) + " segera.");
        chatBox.getChildren().add(buildSellerBubble(konfirmasi));
        Platform.runLater(() -> chatScroll.setVvalue(1.0));

        setStatus("Memproses pesanan...", "warn");

        // Kirim ke Firestore + complete request di background
        new Thread(() -> {
            try {
                if (reqId == null) return;

                // Ringkasan ke seller
                String sellerNote = "💳 Buyer memilih pembayaran: " + methodLabel +
                    "\n📦 " + result.itemName + " (" + UiKit.rupiah(result.price) + ")" +
                    "\n🏪 " + loc.name +
                    "\n👤 " + result.name + " | 📞 " + result.phone +
                    "\n📌 Alamat: " + result.address;
                fs.sendBuyerMessage(reqId, buyerId, sellerNote);

                fs.sendSellerMessage(reqId, "AI_BOT", "[AUTO] 🧾 RINGKASAN PESANAN:\n" +
                    "Pembayaran: " + methodLabel + "\n" +
                    "Item: " + result.itemName + " (" + UiKit.rupiah(result.price) + ")\n" +
                    "Indomaret: " + loc.name + "\n" +
                    "Buyer: " + result.name + " | " + result.phone + "\n" +
                    "Alamat: " + result.address);

                // Tandai request COMPLETED
                fs.completeRequestWithQuantity(reqId, "", result.name, result.address, result.price);

                Platform.runLater(() -> {
                    requestStatusById.put(reqId, "COMPLETED");
                    currentRequestStatus = "COMPLETED";
                    updateInputState();
                    renderChat();
                    showCompletedToast(result.price);
                });

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    setStatus("Error ❌", "error");
                    showError("Gagal menyelesaikan pesanan", ex.getMessage());
                });
            }
        }).start();
    }

    private VBox buildLocationCard(AiAutoReply.IndomaretLocation loc, int rank,
                                   AiAutoReply.DeliveryFormResult result, Runnable onPick) {
        String starsStr = "\u2B50 " + String.format(java.util.Locale.US, "%.1f", loc.rating);
        String rankText = rank == 1 ? "\uD83E\uDD47 Terdekat" : rank == 2 ? "\uD83E\uDD48 Alternatif" : "\uD83E\uDD49 Pilihan lain";
        String rankBg   = rank == 1 ? "#FEF3C7" : "#F3F4F6";
        String rankFg   = rank == 1 ? "#92400E" : COLOR_TEXT_MUTED;

        Label rankLbl = new Label(rankText);
        rankLbl.setStyle("-fx-background-color:" + rankBg + ";-fx-text-fill:" + rankFg +
            ";-fx-font-size:10;-fx-font-weight:bold;-fx-background-radius:8;-fx-padding:2 8;");

        Label nameLbl = new Label("\uD83C\uDFEA  " + loc.name);
        nameLbl.setFont(Font.font("System", FontWeight.BOLD, 12));
        nameLbl.setStyle("-fx-text-fill:" + COLOR_TEXT_MAIN + ";");
        nameLbl.setWrapText(true);

        Label addrLbl = new Label("\uD83D\uDCCC  " + loc.address);
        addrLbl.setStyle("-fx-font-size:11;-fx-text-fill:" + COLOR_TEXT_MUTED + ";");
        addrLbl.setWrapText(true);

        Label metaLbl = new Label("\uD83D\uDEB6 " + loc.distanceMinutes + " mnt  \u00b7  " +
            starsStr + "  \u00b7  \uD83D\uDD50 " + loc.operationalHours);
        metaLbl.setStyle("-fx-font-size:11;-fx-text-fill:" + COLOR_TEXT_MUTED + ";");

        Button pickBtn = new Button("\u2705  Pilih Indomaret ini");
        pickBtn.setStyle("-fx-background-color:#4F46E5;-fx-text-fill:white;" +
            "-fx-font-weight:bold;-fx-font-size:12;-fx-background-radius:8;" +
            "-fx-padding:7 16;-fx-cursor:hand;");
        pickBtn.setMaxWidth(Double.MAX_VALUE);
        pickBtn.setOnMouseEntered(e -> pickBtn.setStyle(pickBtn.getStyle().replace("#4F46E5","#3730A3")));
        pickBtn.setOnMouseExited(e  -> pickBtn.setStyle(pickBtn.getStyle().replace("#3730A3","#4F46E5")));
        pickBtn.setOnAction(e -> { pickBtn.setDisable(true); onPick.run(); });

        VBox card = new VBox(5, rankLbl, nameLbl, addrLbl, metaLbl, pickBtn);
        card.setPadding(new Insets(10, 12, 10, 12));
        card.setStyle(
            "-fx-background-color:" + (rank == 1 ? "#F0FDF4" : "#F9FAFB") + ";" +
            "-fx-background-radius:10;" +
            "-fx-border-color:" + (rank == 1 ? "#86EFAC" : COLOR_DIVIDER) + ";" +
            "-fx-border-radius:10;-fx-border-width:1;"
        );
        return card;
    }

    // ============================================================
    // BUY / PAYMENT
    // ============================================================

    private void onBuy(Offer offer) {
        if (currentRequestId == null || currentRequestId.isBlank()) return;
        if (isCurrentCompleted()) return;

        String savedName    = PREFS.get("buyerName",    "");
        String savedAddress = PREFS.get("buyerAddress", "");

        TextInputDialog nameDlg = new TextInputDialog(savedName);
        nameDlg.setTitle("Info Pembeli");
        nameDlg.setHeaderText("Nama kamu (opsional)");
        String name = nameDlg.showAndWait().orElse(savedName);

        TextInputDialog addrDlg = new TextInputDialog(savedAddress);
        addrDlg.setTitle("Info Pembeli");
        addrDlg.setHeaderText("Alamat pengiriman (opsional)");
        String address = addrDlg.showAndWait().orElse(savedAddress);

        PREFS.put("buyerName",    name    == null ? "" : name.trim());
        PREFS.put("buyerAddress", address == null ? "" : address.trim());

        final String reqIdFinal   = currentRequestId;
        final String offerIdFinal = offer.id;
        final String nameFinal    = name;
        final String addrFinal    = address;

        setStatus("Memproses pembayaran...", "warn");

        new Thread(() -> {
            try {
                fs.completeRequestWithQuantity(reqIdFinal, offerIdFinal, nameFinal, addrFinal, offer.grandTotal);

                Platform.runLater(() -> {
                    requestStatusById.put(reqIdFinal, "COMPLETED");
                    currentRequestStatus = "COMPLETED";
                    updateInputState();
                    renderChat();

                    StringBuilder sb = new StringBuilder();
                    for (OfferLine line : offer.offerLines) {
                        sb.append(line.name)
                          .append("   ").append(line.qty).append("×").append(UiKit.rupiah(line.price))
                          .append("  =  ").append(UiKit.rupiah(line.qty * line.price)).append("\n");
                    }
                    sb.append("─────────────────────\n");
                    sb.append("Total:  ").append(UiKit.rupiah(offer.grandTotal)).append("\n");
                    sb.append("Alamat: ").append(addrFinal == null || addrFinal.isBlank() ? "-" : addrFinal);

                    Alert ok = new Alert(Alert.AlertType.INFORMATION);
                    ok.setTitle("Pesanan Dikonfirmasi");
                    ok.setHeaderText("✅ Pesananmu berhasil!");
                    ok.setContentText(sb.toString());
                    ok.showAndWait();

                    showCompletedToast(offer.grandTotal);
                });
            } catch (Exception ex) {
                setStatus("Error ❌", "error");
                showError("Gagal menyelesaikan pesanan", ex.getMessage());
            }
        }).start();
    }

    // ============================================================
    // TOAST NOTIFICATION
    // ============================================================

    private void showCompletedToast(int grandTotal) {
        Label icon = new Label("🎉");
        icon.setStyle("-fx-font-size: 22;");

        Label msg = new Label("Pembayaran berhasil!\nTotal: " + UiKit.rupiah(grandTotal));
        msg.setStyle("-fx-text-fill: white; -fx-font-size: 13; -fx-font-weight: bold;");
        msg.setWrapText(true);

        HBox content = new HBox(12, icon, msg);
        content.setAlignment(Pos.CENTER_LEFT);

        ProgressBar bar = new ProgressBar(1.0);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setStyle("-fx-accent: " + COLOR_SUCCESS + "; -fx-pref-height: 3;");

        VBox toast = new VBox(8, content, bar);
        toast.setPadding(new Insets(14, 18, 12, 16));
        toast.setMaxWidth(280);
        toast.setStyle(
            "-fx-background-color: #1A1A2E;" +
            "-fx-background-radius: 12;" +
            "-fx-effect: dropshadow(gaussian,rgba(0,0,0,0.25),12,0,0,4);"
        );

        toastContainer.getChildren().add(0, toast);

        Timeline tl = new Timeline(
            new KeyFrame(Duration.millis(0),  new KeyValue(bar.progressProperty(), 1.0)),
            new KeyFrame(Duration.seconds(5), new KeyValue(bar.progressProperty(), 0.0))
        );
        tl.setOnFinished(e -> {
            FadeTransition ft = new FadeTransition(Duration.millis(300), toast);
            ft.setFromValue(1); ft.setToValue(0);
            ft.setOnFinished(ev -> toastContainer.getChildren().remove(toast));
            ft.play();
        });
        tl.play();
    }

    private void showToast(String message) {
        Label msg = new Label(message);
        msg.setStyle("-fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold;");

        VBox toast = new VBox(msg);
        toast.setPadding(new Insets(10, 16, 10, 16));
        toast.setStyle(
            "-fx-background-color: #1A1A2E;" +
            "-fx-background-radius: 10;" +
            "-fx-effect: dropshadow(gaussian,rgba(0,0,0,0.20),8,0,0,2);"
        );

        toastContainer.getChildren().add(0, toast);
        PauseTransition pause = new PauseTransition(Duration.seconds(3));
        pause.setOnFinished(e -> {
            FadeTransition ft = new FadeTransition(Duration.millis(250), toast);
            ft.setFromValue(1); ft.setToValue(0);
            ft.setOnFinished(ev -> toastContainer.getChildren().remove(toast));
            ft.play();
        });
        pause.play();
    }

    // ============================================================
    // STYLE HELPERS — didelegasikan ke UiKit
    // ============================================================

    private void stylePrimaryButton(Button btn)             { UiKit.stylePrimaryButton(btn); }
    private void styleInput(TextField tf, String prompt)    { UiKit.styleInput(tf, prompt); }
    private void styleStatusPill(Label lbl, String type)    { UiKit.styleStatusPill(lbl, type); }
    private Label buildPlaceholder(String text)             { return UiKit.buildPlaceholder(text); }
    private void setStatus(String msg, String type)         { UiKit.setStatus(statusPill, msg, type); }

    // ============================================================
    // INNER CELL — sidebar request list
    // ============================================================

    private class RequestCell extends ListCell<RequestItem> {
        private final VBox  root    = new VBox(3);
        private final HBox  topRow  = new HBox(6);
        private final Label title   = new Label();
        private final Label badge   = new Label("SELESAI");
        private final Label preview = new Label();

        RequestCell() {
            root.setPadding(new Insets(10, 14, 10, 14));
            title.setFont(Font.font("System", FontWeight.BOLD, 13));
            title.setStyle("-fx-text-fill: " + COLOR_TEXT_MAIN + ";");

            badge.setStyle(
                "-fx-background-color: " + COLOR_SUCCESS + ";" +
                "-fx-text-fill: white; -fx-font-size: 9; -fx-font-weight: bold;" +
                "-fx-background-radius: 6; -fx-padding: 1 5;"
            );
            badge.setVisible(false);
            badge.setManaged(false);

            preview.setStyle("-fx-font-size: 11; -fx-text-fill: " + COLOR_TEXT_MUTED + ";");
            preview.setWrapText(false);

            topRow.setAlignment(Pos.CENTER_LEFT);
            topRow.getChildren().addAll(title, badge);
            root.getChildren().addAll(topRow, preview);

            setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
            setOnMouseEntered(e -> { if (!isSelected()) root.setStyle("-fx-background-color: " + COLOR_BG + "; -fx-background-radius: 8;"); });
            setOnMouseExited(e  -> { if (!isSelected()) root.setStyle("-fx-background-color: transparent;"); });
        }

        @Override
        protected void updateItem(RequestItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) { setGraphic(null); return; }

            boolean completed = "COMPLETED".equalsIgnoreCase(item.status);
            title.setText("Pesanan #" + item.buyerRequestNo);

            badge.setVisible(completed);
            badge.setManaged(completed);

            String prev = item.previewText.length() > 38
                    ? item.previewText.substring(0, 38) + "…"
                    : item.previewText;
            preview.setText(prev.isBlank() ? "Pesan baru..." : prev);

            if (isSelected()) {
                root.setStyle(
                    "-fx-background-color: " + COLOR_SELECTED_ROW + ";" +
                    "-fx-background-radius: 8;" +
                    "-fx-border-color: " + COLOR_PRIMARY + ";" +
                    "-fx-border-width: 0 0 0 3;" +
                    "-fx-border-radius: 0;"
                );
                title.setStyle("-fx-text-fill: " + COLOR_PRIMARY + "; -fx-font-weight: bold;");
            } else {
                root.setStyle("-fx-background-color: transparent;");
                title.setStyle("-fx-text-fill: " + COLOR_TEXT_MAIN + "; -fx-font-weight: bold;");
            }
            setOpacity(completed ? 0.85 : 1.0);
            setGraphic(root);
            setText(null);
        }
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
        if (messagesListener  != null) messagesListener.remove();
        if (offersAllListener != null) offersAllListener.remove();
        if (myReqListener     != null) myReqListener.remove();
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
    // INNER DATA CLASSES
    // ============================================================

    private static class RequestItem {
        final String requestId, previewText, status;
        final long buyerRequestNo;
        RequestItem(String requestId, String previewText, long buyerRequestNo, String status) {
            this.requestId      = requestId;
            this.previewText    = previewText == null ? "" : previewText;
            this.buyerRequestNo = buyerRequestNo;
            this.status         = status == null ? "OPEN" : status;
        }
    }

    private static class Message {
        final String id, senderType, senderId, text;
        final List<FirestoreService.OrderItem> orderItems;
        Message(String id, String senderType, String senderId, String text,
                List<FirestoreService.OrderItem> orderItems) {
            this.id         = id;
            this.senderType = senderType == null ? "" : senderType;
            this.senderId   = senderId   == null ? "" : senderId;
            this.text       = text       == null ? "" : text;
            this.orderItems = orderItems == null ? new ArrayList<>() : orderItems;
        }
    }

    static class OfferLine {
        final String name;
        final int qty, price;
        OfferLine(String name, int qty, int price) {
            this.name  = name == null ? "" : name;
            this.qty   = Math.max(qty, 1);
            this.price = price;
        }
    }

    private static class Offer {
        final String id, sellerId, vendor, sellerContact;
        final List<OfferLine> offerLines;
        final int grandTotal, etaMinutes;
        final double rating;
        Offer(String id, String sellerId, List<OfferLine> offerLines, int grandTotal,
              String vendor, int etaMinutes, double rating, String sellerContact) {
            this.id            = id;
            this.sellerId      = sellerId      == null ? "" : sellerId;
            this.offerLines    = offerLines    == null ? new ArrayList<>() : offerLines;
            this.grandTotal    = grandTotal;
            this.vendor        = vendor        == null ? "" : vendor;
            this.etaMinutes    = etaMinutes;
            this.rating        = rating;
            this.sellerContact = sellerContact == null ? "" : sellerContact;
        }
    }
}