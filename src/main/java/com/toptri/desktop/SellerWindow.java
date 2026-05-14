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
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.*;

public class SellerWindow {

    // ── Services / state ─────────────────────────────────────────
    private final FirestoreService fs;
    private final String sellerId;

    private ListenerRegistration requestsListener;
    private ListenerRegistration messagesListener;
    private ListenerRegistration offersAllListener;

    private final List<ChatMessage>          chatMessages        = new ArrayList<>();
    private final Map<String, List<SentOffer>> offersByBuyerMsgId = new HashMap<>();

    private volatile String selectedRequestId    = null;
    private volatile String selectedBuyerText    = "";
    private volatile String latestBuyerMessageId = null;
    private volatile List<FirestoreService.OrderItem> latestOrderItems = new ArrayList<>();

    private final Set<String> offeredKeys         = new HashSet<>();
    private int sentCountForThisRequest           = 0;
    private boolean autoFollowLatest              = true;
    private String  lastAutoSelectedRequestId     = null;

    // ── Notification state ───────────────────────────────────────
    private final Set<String> knownRequestIds     = new HashSet<>();
    private boolean initialLoadDone               = false;

    // ── UI components ────────────────────────────────────────────

    // Sidebar – inbox
    private final ListView<RequestItem> requestList = new ListView<>();
    private final Label inboxCountLabel = new Label();

    // Status / header-right
    private final Label statusPill    = new Label("Menunggu...");

    // Notification toast container (overlaid on root)
    private VBox toastContainer;

    // Chat area
    private final VBox       chatBox    = new VBox(10);
    private final ScrollPane chatScroll = new ScrollPane(chatBox);

    // Qty badge above chat input
    private final Label qtyBadge = new Label();

    // Chat reply bar
    private final TextField chatReplyInput = new TextField();
    private final Button    chatSendBtn    = new Button("Kirim");

    // Offer / action panel (right column)
    private final TextField mainInput    = new TextField();
    private final TextField priceInput   = new TextField();
    private final TextField vendorInput  = new TextField();
    private final TextField contactInput = new TextField();
    private final Button    sendBtn      = new Button("Kirim");

    // Menu list
    private final ListView<FirestoreService.MenuItem> menuListView = new ListView<>();
    private final Label menuCountLabel = new Label();

    // ============================================================
    // CONSTRUCTOR / ENTRY POINT
    // ============================================================

    public SellerWindow(FirestoreService fs, String sellerId) {
        this.fs       = fs;
        this.sellerId = sellerId;
    }

    public static void open(FirestoreService fs, String sellerId) {
        new SellerWindow(fs, sellerId).show();
    }

    // ============================================================
    // BUILD UI
    // ============================================================

    public void show() {
        Stage stage = new Stage();

        // ── Top app-bar ──────────────────────────────────────────
        HBox appBar = buildAppBar();

        // ── LEFT sidebar: inbox list ──────────────────────────────
        VBox sidebar = buildSidebar();

        // ── CENTER: Chat panel ────────────────────────────────────
        VBox chatPanel = buildChatPanel();

        // ── RIGHT: Offer & menu panel ─────────────────────────────
        VBox offerPanel = buildOfferPanel();

        // ── Root layout ───────────────────────────────────────────
        HBox body = new HBox(0, sidebar, chatPanel, offerPanel);
        HBox.setHgrow(chatPanel, Priority.ALWAYS);
        body.setFillHeight(true);

        // Toast container (top-right overlay)
        toastContainer = new VBox(8);
        toastContainer.setAlignment(Pos.TOP_RIGHT);
        toastContainer.setPadding(new Insets(70, 16, 0, 0));
        toastContainer.setMouseTransparent(false);
        toastContainer.setPickOnBounds(false);

        StackPane layered = new StackPane();
        BorderPane rootPane = new BorderPane();
        rootPane.setTop(appBar);
        rootPane.setCenter(body);
        rootPane.setStyle("-fx-background-color: " + UiKit.COLOR_BG + ";");

        layered.getChildren().addAll(rootPane, toastContainer);
        StackPane.setAlignment(toastContainer, Pos.TOP_RIGHT);

        renderChat();

        stage.setTitle("Toptri Chat — Seller (" + sellerId + ")");
        stage.setScene(new Scene(layered, 1400, 800));
        stage.setMinWidth(1100);
        stage.setMinHeight(600);
        stage.show();
        stage.setOnCloseRequest(e -> cleanup());

        attachRequestsListener();
    }

    // ── APP BAR ─────────────────────────────────────────────────

    private HBox buildAppBar() {
        Label logo = new Label("🍱  Toptri Chat");
        logo.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 18));
        logo.setStyle("-fx-text-fill: " + UiKit.COLOR_PRIMARY + ";");

        Label role = new Label("Seller: " + sellerId);
        role.setStyle("-fx-text-fill: " + UiKit.COLOR_TEXT_MUTED + "; -fx-font-size: 13;");

        styleStatusPill(statusPill, "neutral");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(12, logo, role, spacer, statusPill);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(14, 20, 14, 20));
        bar.setStyle(
            "-fx-background-color: " + UiKit.COLOR_SURFACE + ";" +
            "-fx-border-color: " + UiKit.COLOR_DIVIDER + ";" +
            "-fx-border-width: 0 0 1 0;"
        );
        return bar;
    }

    // ── SIDEBAR ─────────────────────────────────────────────────

    private VBox buildSidebar() {
        Label title = new Label("Inbox");
        title.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 15));
        title.setStyle("-fx-text-fill: " + UiKit.COLOR_TEXT_MAIN + ";");

        inboxCountLabel.setStyle(
            "-fx-background-color: " + UiKit.COLOR_PRIMARY + ";" +
            "-fx-text-fill: white; -fx-font-size: 11; -fx-font-weight: bold;" +
            "-fx-background-radius: 10; -fx-padding: 1 7;"
        );

        HBox sidebarHeader = new HBox(8, title, inboxCountLabel);
        sidebarHeader.setAlignment(Pos.CENTER_LEFT);
        sidebarHeader.setPadding(new Insets(16, 16, 10, 16));

        requestList.setStyle(
            "-fx-background-color: transparent; -fx-border-color: transparent;" +
            "-fx-focus-color: transparent; -fx-faint-focus-color: transparent;"
        );
        requestList.setCellFactory(lv -> new InboxCell());
        requestList.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldV, newV) -> { if (newV != null) onSelectRequest(newV); });
        VBox.setVgrow(requestList, Priority.ALWAYS);

        Separator sep = new Separator();
        sep.setStyle("-fx-background-color: " + UiKit.COLOR_DIVIDER + ";");

        VBox sidebar = new VBox(0, sidebarHeader, sep, requestList);
        sidebar.setPrefWidth(270);
        sidebar.setMaxWidth(270);
        sidebar.setStyle(
            "-fx-background-color: " + UiKit.COLOR_SIDEBAR_BG + ";" +
            "-fx-border-color: " + UiKit.COLOR_DIVIDER + ";" +
            "-fx-border-width: 0 1 0 0;"
        );
        VBox.setVgrow(sidebar, Priority.ALWAYS);
        return sidebar;
    }

    // ── CHAT PANEL ──────────────────────────────────────────────

    private VBox buildChatPanel() {
        Label chatTitle = new Label("💬  Percakapan");
        chatTitle.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 15));
        chatTitle.setStyle("-fx-text-fill: " + UiKit.COLOR_TEXT_MAIN + ";");

        qtyBadge.setStyle(
            "-fx-background-color: #FEF3C7;" +
            "-fx-text-fill: " + UiKit.COLOR_WARN + ";" +
            "-fx-font-size: 12; -fx-font-weight: bold;" +
            "-fx-background-radius: 6; -fx-padding: 2 8;"
        );

        HBox chatTopBar = new HBox(10, chatTitle, qtyBadge);
        chatTopBar.setAlignment(Pos.CENTER_LEFT);
        chatTopBar.setPadding(new Insets(14, 16, 10, 16));
        chatTopBar.setStyle(
            "-fx-background-color: " + UiKit.COLOR_SURFACE + ";" +
            "-fx-border-color: " + UiKit.COLOR_DIVIDER + ";" +
            "-fx-border-width: 0 0 1 0;"
        );

        chatBox.setPadding(new Insets(16, 12, 12, 12));
        chatBox.setFillWidth(true);

        chatScroll.setFitToWidth(true);
        chatScroll.setStyle("-fx-background-color: " + UiKit.COLOR_BG + "; -fx-background: " + UiKit.COLOR_BG + ";");
        chatScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(chatScroll, Priority.ALWAYS);

        chatReplyInput.setPromptText("Ketik pesan ke buyer...");
        chatReplyInput.setStyle(
            "-fx-background-color: " + UiKit.COLOR_SURFACE + ";" +
            "-fx-border-color: " + UiKit.COLOR_DIVIDER + ";" +
            "-fx-border-radius: 20; -fx-background-radius: 20;" +
            "-fx-padding: 8 14; -fx-font-size: 13;"
        );
        HBox.setHgrow(chatReplyInput, Priority.ALWAYS);
        chatReplyInput.setOnAction(e -> onSendFromChat());

        stylePrimaryButton(chatSendBtn);
        chatSendBtn.setOnAction(e -> onSendFromChat());
        chatSendBtn.setStyle(chatSendBtn.getStyle() +
            "-fx-background-radius: 20; -fx-padding: 8 18;");

        HBox replyBar = new HBox(8, chatReplyInput, chatSendBtn);
        replyBar.setAlignment(Pos.CENTER);
        replyBar.setPadding(new Insets(10, 16, 14, 16));
        replyBar.setStyle(
            "-fx-background-color: " + UiKit.COLOR_SURFACE + ";" +
            "-fx-border-color: " + UiKit.COLOR_DIVIDER + ";" +
            "-fx-border-width: 1 0 0 0;"
        );

        VBox panel = new VBox(0, chatTopBar, chatScroll, replyBar);
        VBox.setVgrow(chatScroll, Priority.ALWAYS);
        panel.setStyle("-fx-background-color: " + UiKit.COLOR_BG + ";");
        return panel;
    }

    // ── OFFER / MENU PANEL ───────────────────────────────────────

    private VBox buildOfferPanel() {
        Label offerTitle = new Label("📦  Kirim Offer");
        offerTitle.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 15));
        offerTitle.setStyle("-fx-text-fill: " + UiKit.COLOR_TEXT_MAIN + ";");

        HBox offerHeader = new HBox(offerTitle);
        offerHeader.setAlignment(Pos.CENTER_LEFT);
        offerHeader.setPadding(new Insets(14, 16, 10, 16));
        offerHeader.setStyle(
            "-fx-background-color: " + UiKit.COLOR_SURFACE + ";" +
            "-fx-border-color: " + UiKit.COLOR_DIVIDER + ";" +
            "-fx-border-width: 0 0 1 0;"
        );

        Label formLabel = new Label("Ketik manual:");
        formLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
        formLabel.setStyle("-fx-text-fill: " + UiKit.COLOR_TEXT_MUTED + ";");

        styleInput(mainInput,    "Nama menu / teks chat");
        styleInput(priceInput,   "Harga (kosong = chat)");
        styleInput(vendorInput,  "Vendor (opsional)");
        styleInput(contactInput, "📞 Kontak (opsional)");

        priceInput.setPrefWidth(Double.MAX_VALUE);
        vendorInput.setPrefWidth(Double.MAX_VALUE);
        contactInput.setPrefWidth(Double.MAX_VALUE);

        stylePrimaryButton(sendBtn);
        sendBtn.setMaxWidth(Double.MAX_VALUE);
        sendBtn.setOnAction(e -> onSendAuto());
        mainInput.setOnAction(e -> onSendAuto());
        priceInput.setOnAction(e -> onSendAuto());
        vendorInput.setOnAction(e -> onSendAuto());
        contactInput.setOnAction(e -> onSendAuto());

        Label hint = new Label("💡  Kosong harga = chat  •  Isi harga = offer\n    Harga + Vendor = simpan menu baru");
        hint.setStyle("-fx-text-fill: " + UiKit.COLOR_TEXT_MUTED + "; -fx-font-size: 11;");
        hint.setWrapText(true);

        VBox formBox = new VBox(8,
                formLabel,
                mainInput, priceInput, vendorInput, contactInput,
                sendBtn, hint
        );
        formBox.setPadding(new Insets(12, 16, 12, 16));
        formBox.setStyle(
            "-fx-background-color: " + UiKit.COLOR_SURFACE + ";" +
            "-fx-border-color: " + UiKit.COLOR_DIVIDER + ";" +
            "-fx-border-width: 0 0 1 0;"
        );

        Label menuTitle = new Label("🍱  Menu (klik = kirim offer)");
        menuTitle.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 13));
        menuTitle.setStyle("-fx-text-fill: " + UiKit.COLOR_TEXT_MAIN + ";");

        menuCountLabel.setStyle("-fx-text-fill: " + UiKit.COLOR_TEXT_MUTED + "; -fx-font-size: 12;");

        HBox menuHeader = new HBox(8, menuTitle, menuCountLabel);
        menuHeader.setAlignment(Pos.CENTER_LEFT);
        menuHeader.setPadding(new Insets(10, 16, 6, 16));

        menuListView.setStyle(
            "-fx-background-color: transparent; -fx-border-color: transparent;" +
            "-fx-focus-color: transparent; -fx-faint-focus-color: transparent;"
        );
        menuListView.setPlaceholder(buildPlaceholder("Menunggu pesan buyer..."));
        menuListView.setFixedCellSize(86);
        menuListView.setCellFactory(lv -> new MenuCell());
        VBox.setVgrow(menuListView, Priority.ALWAYS);

        VBox menuSection = new VBox(0, menuHeader, menuListView);
        VBox.setVgrow(menuListView, Priority.ALWAYS);

        VBox panel = new VBox(0, offerHeader, formBox, menuSection);
        VBox.setVgrow(menuSection, Priority.ALWAYS);
        panel.setPrefWidth(320);
        panel.setMaxWidth(340);
        panel.setStyle(
            "-fx-background-color: " + UiKit.COLOR_BG + ";" +
            "-fx-border-color: " + UiKit.COLOR_DIVIDER + ";" +
            "-fx-border-width: 0 0 0 1;"
        );
        return panel;
    }

    // ============================================================
    // NOTIFICATION TOAST SYSTEM
    // ============================================================

    /**
     * Shows a toast notification for a new buyer order.
     * Auto-dismisses after 6 seconds. Clicking navigates to that request.
     */
    private void showNewOrderToast(RequestItem item) {
        // ── Toast card ───────────────────────────────────────────
        Label bell = new Label("🔔");
        bell.setStyle("-fx-font-size: 20;");

        String buyerLabel = item.buyerRequestNo > 0
                ? "Buyer #" + item.buyerRequestNo
                : "Buyer baru";

        Label titleLbl = new Label("Pesanan masuk!");
        titleLbl.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 13));
        titleLbl.setStyle("-fx-text-fill: white;");

        String preview = item.previewText.length() > 40
                ? item.previewText.substring(0, 40) + "…"
                : item.previewText;
        Label buyerLbl = new Label(buyerLabel + (preview.isBlank() ? "" : " — " + preview));
        buyerLbl.setStyle("-fx-text-fill: rgba(255,255,255,0.80); -fx-font-size: 12;");
        buyerLbl.setWrapText(true);
        buyerLbl.setMaxWidth(220);

        Label dismissLbl = new Label("✕");
        dismissLbl.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 13; -fx-cursor: hand;");

        VBox textCol = new VBox(3, titleLbl, buyerLbl);
        HBox.setHgrow(textCol, Priority.ALWAYS);

        HBox inner = new HBox(10, bell, textCol, dismissLbl);
        inner.setAlignment(Pos.CENTER_LEFT);
        inner.setPadding(new Insets(14, 16, 14, 14));

        VBox toast = new VBox(inner);
        toast.setMaxWidth(300);
        toast.setStyle(
            "-fx-background-color: " + UiKit.COLOR_NOTIF_BG + ";" +
            "-fx-background-radius: 14;" +
            "-fx-effect: dropshadow(gaussian,rgba(0,0,0,0.40),18,0,0,6);" +
            "-fx-cursor: hand;"
        );

        // ── Animate in (slide from right) ────────────────────────
        toast.setTranslateX(320);
        toast.setOpacity(0);

        toastContainer.getChildren().add(0, toast);

        Timeline slideIn = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(toast.translateXProperty(), 320),
                new KeyValue(toast.opacityProperty(), 0)),
            new KeyFrame(Duration.millis(300),
                new KeyValue(toast.translateXProperty(), 0, Interpolator.EASE_OUT),
                new KeyValue(toast.opacityProperty(), 1, Interpolator.EASE_OUT))
        );
        slideIn.play();

        // ── Auto-dismiss after 6s ────────────────────────────────
        PauseTransition pause = new PauseTransition(Duration.seconds(6));
        pause.setOnFinished(ev -> dismissToast(toast));
        pause.play();

        // ── Click to navigate + dismiss ──────────────────────────
        Runnable navigate = () -> {
            dismissToast(toast);
            pause.stop();
            // Select this request in the list
            for (RequestItem ri : requestList.getItems()) {
                if (ri.requestId.equals(item.requestId)) {
                    requestList.getSelectionModel().select(ri);
                    onSelectRequest(ri);
                    break;
                }
            }
        };

        toast.setOnMouseClicked(ev -> navigate.run());
        dismissLbl.setOnMouseClicked(ev -> {
            ev.consume();
            pause.stop();
            dismissToast(toast);
        });
    }

    private void dismissToast(VBox toast) {
        Timeline slideOut = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(toast.opacityProperty(), toast.getOpacity())),
            new KeyFrame(Duration.millis(250),
                new KeyValue(toast.translateXProperty(), 320, Interpolator.EASE_IN),
                new KeyValue(toast.opacityProperty(), 0, Interpolator.EASE_IN))
        );
        slideOut.setOnFinished(e -> toastContainer.getChildren().remove(toast));
        slideOut.play();
    }

    // ============================================================
    // RENDER CHAT
    // ============================================================

    private void renderChat() {
        chatBox.getChildren().clear();

        if (selectedRequestId == null) {
            chatBox.getChildren().add(emptyChatHint("📥  Pilih percakapan dari inbox di kiri"));
            return;
        }

        if (chatMessages.isEmpty()) {
            chatBox.getChildren().add(emptyChatHint("⏳  Memuat percakapan..."));
            return;
        }

        for (ChatMessage m : chatMessages) {
            boolean isBuyer = "BUYER".equalsIgnoreCase(m.senderType);
            if (isBuyer) {
                chatBox.getChildren().add(buildBuyerBubble(buildBuyerBubbleText(m)));

                List<SentOffer> offs = offersByBuyerMsgId.getOrDefault(m.id, Collections.emptyList());
                for (SentOffer o : offs) {
                    chatBox.getChildren().add(buildOfferBubble(o));
                }

                if (offs.isEmpty() && m.id.equals(latestBuyerMessageId)) {
                    chatBox.getChildren().add(buildPendingBubble("Belum ada offer dikirim..."));
                }

                chatBox.getChildren().add(chatDivider());
            } else if (m.text.contains("[AUTO]")) {
                // Pesan otomatis AI — tampilkan sebagai notifikasi khusus
                String cleanText = m.text.replace("[AUTO] ", "").replace("[AUTO]", "").trim();
                chatBox.getChildren().add(buildAiNotifBubble(cleanText));
            } else {
                chatBox.getChildren().add(buildSellerBubble(m.text));
            }
        }

        Platform.runLater(() -> chatScroll.setVvalue(1.0));
    }

    // ── BUBBLE BUILDERS ─────────────────────────────────────────

    private HBox buildBuyerBubble(String text)    { return UiKit.buildBuyerBubble(text); }
    private HBox buildSellerBubble(String text)   { return UiKit.buildSellerBubble(text); }
    private HBox buildAiNotifBubble(String text)  { return UiKit.buildAiNotifBubble(text); }

    private HBox buildOfferBubble(SentOffer o) {
        Label header = new Label("📦  Offer dari " + (o.sellerId.equals(sellerId) ? "kamu" : o.sellerId));
        header.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 13));
        header.setStyle("-fx-text-fill: " + UiKit.COLOR_PRIMARY + ";");

        VBox linesBox = new VBox(3);
        for (SentOffer.Line line : o.lines) {
            String lineText = line.name + "   " + line.qty + " × " +
                    rupiah(line.price) + "  =  " + rupiah(line.qty * line.price);  // GANTI
            Label ll = new Label(lineText);
            ll.setStyle("-fx-font-size: 12; -fx-text-fill: " + UiKit.COLOR_TEXT_MAIN + ";");
            linesBox.getChildren().add(ll);
        }

        Separator sep = new Separator();
        sep.setStyle("-fx-background-color: " + UiKit.COLOR_DIVIDER + ";");

        Label total = new Label("Total:  " + rupiah(o.grandTotal));  // GANTI
        total.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 14));
        total.setStyle("-fx-text-fill: " + UiKit.COLOR_SUCCESS + ";");

        VBox card = new VBox(6, header, linesBox, sep, total);
        if (!o.vendor.isBlank()) {
            Label vendorLbl = new Label("🏷  " + o.vendor);
            vendorLbl.setStyle("-fx-font-size: 11; -fx-text-fill: " + UiKit.COLOR_TEXT_MUTED + ";");
            card.getChildren().add(vendorLbl);
        }
        card.setPadding(new Insets(12, 16, 12, 16));
        card.setStyle(
            "-fx-background-color: " + UiKit.COLOR_OFFER_BUBBLE + ";" +
            "-fx-background-radius: 12;" +
            "-fx-border-color: " + UiKit.COLOR_PRIMARY + ";" +
            "-fx-border-width: 0 0 0 3;" +
            "-fx-border-radius: 0 0 0 2;"
        );

        HBox row = new HBox(card);
        row.setAlignment(Pos.CENTER_RIGHT);
        row.setPadding(new Insets(0, 0, 0, 60));
        return row;
    }

    private HBox buildPendingBubble(String msg)   { return UiKit.buildPendingBubble(msg); }
    private Region chatDivider()                   { return UiKit.chatDivider(); }
    private VBox emptyChatHint(String msg)         { return UiKit.emptyChatHint(msg); }

    private String buildBuyerBubbleText(ChatMessage m) {
        if (m.orderItems == null || m.orderItems.size() <= 1) return m.text;
        StringBuilder sb = new StringBuilder(m.text).append("\n");
        for (FirestoreService.OrderItem oi : m.orderItems) {
            sb.append("  • ").append(oi.qty).append("× ").append(oi.name).append("\n");
        }
        return sb.toString().trim();
    }

    private String now() { return UiKit.now(); }

    // ============================================================
    // INNER CELL CLASSES
    // ============================================================

    private class InboxCell extends ListCell<RequestItem> {
        private final VBox  root    = new VBox(3);
        private final HBox  topRow  = new HBox(6);
        private final Label title   = new Label();
        private final Label newBadge= new Label("BARU");
        private final Label preview = new Label();

        InboxCell() {
            root.setPadding(new Insets(10, 14, 10, 14));
            title.setFont(Font.font("System", FontWeight.BOLD, 13));
            title.setStyle("-fx-text-fill: " + UiKit.COLOR_TEXT_MAIN + ";");

            newBadge.setStyle(
                "-fx-background-color: " + UiKit.COLOR_DANGER + ";" +
                "-fx-text-fill: white; -fx-font-size: 9; -fx-font-weight: bold;" +
                "-fx-background-radius: 6; -fx-padding: 1 5;"
            );
            newBadge.setVisible(false);
            newBadge.setManaged(false);

            preview.setStyle("-fx-font-size: 11; -fx-text-fill: " + UiKit.COLOR_TEXT_MUTED + ";");
            preview.setWrapText(false);

            topRow.setAlignment(Pos.CENTER_LEFT);
            topRow.getChildren().addAll(title, newBadge);
            root.getChildren().addAll(topRow, preview);

            setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
            setOnMouseEntered(e -> {
                if (!isSelected()) root.setStyle("-fx-background-color: " + UiKit.COLOR_BG + "; -fx-background-radius: 8;");
            });
            setOnMouseExited(e -> {
                if (!isSelected()) root.setStyle("-fx-background-color: transparent;");
            });
        }

        @Override
        protected void updateItem(RequestItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) { setGraphic(null); return; }
            String t = item.buyerRequestNo > 0 ? "Buyer #" + item.buyerRequestNo : "Buyer";
            title.setText(t);

            // Show NEW badge for unread/new requests (not yet selected)
            boolean isNew = item.isNew;
            newBadge.setVisible(isNew);
            newBadge.setManaged(isNew);

            String prev = item.previewText.length() > 38
                    ? item.previewText.substring(0, 38) + "…"
                    : item.previewText;
            preview.setText(prev.isBlank() ? "Pesan baru..." : prev);

            if (isSelected()) {
                root.setStyle(
                    "-fx-background-color: " + UiKit.COLOR_SELECTED_ROW + ";" +
                    "-fx-background-radius: 8;" +
                    "-fx-border-color: " + UiKit.COLOR_PRIMARY + ";" +
                    "-fx-border-width: 0 0 0 3;" +
                    "-fx-border-radius: 0;"
                );
                title.setStyle("-fx-text-fill: " + UiKit.COLOR_PRIMARY + "; -fx-font-weight: bold;");
            } else {
                root.setStyle("-fx-background-color: transparent;");
                title.setStyle("-fx-text-fill: " + (isNew ? UiKit.COLOR_TEXT_MAIN : UiKit.COLOR_TEXT_MAIN) + "; -fx-font-weight: bold;");
            }
            setGraphic(root);
            setText(null);
        }
    }

    private class MenuCell extends ListCell<FirestoreService.MenuItem> {
        @Override
        protected void updateItem(FirestoreService.MenuItem m, boolean empty) {
            super.updateItem(m, empty);
            if (empty || m == null) {
                setGraphic(null); setText(null);
                setOnMouseClicked(null);
                return;
            }

            boolean alreadySent = isMenuAlreadyOfferedForLatest(m);
            int totalQty = latestOrderItems.stream().mapToInt(oi -> oi.qty).sum();
            if (totalQty < 1) totalQty = 1;

            String priceStr = rupiah(m.getPrice());  // GANTI
            String totalStr = totalQty > 1 ? "  ×" + totalQty + " = " + rupiah(m.getPrice() * totalQty) : "";  // GANTI
            String subtitle = priceStr + totalStr + " • " + m.vendorOrDash()
                    + " • " + m.etaText() + " • " + m.ratingText();

            Label nameLabel = new Label(m.getName() + (alreadySent ? "  ✅" : ""));
            nameLabel.setFont(Font.font("System", FontWeight.BOLD, 13));
            nameLabel.setStyle("-fx-text-fill: " + (alreadySent ? UiKit.COLOR_TEXT_MUTED : UiKit.COLOR_TEXT_MAIN) + ";");

            Label subLabel = new Label(subtitle);
            subLabel.setStyle("-fx-font-size: 11; -fx-text-fill: " + UiKit.COLOR_TEXT_MUTED + ";");
            subLabel.setWrapText(true);

            Label badge = new Label(alreadySent ? "Terkirim" : "Kirim →");
            badge.setStyle(
                "-fx-background-color: " + (alreadySent ? "#F3F4F6" : UiKit.COLOR_PRIMARY) + ";" +
                "-fx-text-fill: " + (alreadySent ? UiKit.COLOR_TEXT_MUTED : "white") + ";" +
                "-fx-background-radius: 6; -fx-padding: 3 10; -fx-font-size: 11;"
            );

            VBox textCol = new VBox(3, nameLabel, subLabel);
            HBox.setHgrow(textCol, Priority.ALWAYS);

            HBox card = new HBox(10, textCol, badge);
            card.setAlignment(Pos.CENTER_LEFT);
            card.setPadding(new Insets(10, 12, 10, 12));
            card.setMaxWidth(Double.MAX_VALUE);
            card.setStyle(
                "-fx-background-color: " + (alreadySent ? "#FAFAFA" : UiKit.COLOR_SURFACE) + ";" +
                "-fx-background-radius: 10;" +
                "-fx-effect: dropshadow(gaussian,rgba(0,0,0,0.06),4,0,0,1);"
            );

            if (alreadySent) card.setOpacity(0.65);

            VBox wrapper = new VBox(card);
            wrapper.setPadding(new Insets(4, 8, 0, 8));

            setGraphic(wrapper);
            setText(null);
            setStyle("-fx-background-color: transparent;");

            setOnMouseClicked(ev -> {
                if (alreadySent || getItem() == null) return;
                onSendOfferFromList(getItem());
                Platform.runLater(() -> menuListView.getSelectionModel().clearSelection());
            });
        }
    }

    // ============================================================
    // STYLE HELPERS — didelegasikan ke UiKit
    // ============================================================

    private void stylePrimaryButton(Button btn)             { UiKit.stylePrimaryButton(btn); }
    private void styleInput(TextField tf, String prompt)    { UiKit.styleInput(tf, prompt); }
    private void styleStatusPill(Label lbl, String type)    { UiKit.styleStatusPill(lbl, type); }
    private Label buildPlaceholder(String text)             { return UiKit.buildPlaceholder(text); }
    private void setStatus(String msg, String type)         { UiKit.setStatus(statusPill, msg, type); }
    private String rupiah(int amount)                       { return UiKit.rupiah(amount); }

    // ============================================================
    // REQUESTS LISTENER + AUTO FOLLOW
    // ============================================================

    private void attachRequestsListener() {
        if (requestsListener != null) requestsListener.remove();
        // Gunakan listenOpenRequests (sudah ada di FirestoreService)
        requestsListener = fs.listenOpenRequests(
            snap -> Platform.runLater(() -> onRequestsUpdate(snap)),
            err  -> Platform.runLater(() -> {
                Alert a = new Alert(Alert.AlertType.ERROR);
                a.setTitle("Firestore Error");
                a.setHeaderText("Seller listener gagal");
                a.setContentText(err.getMessage() == null ? "(no message)" : err.getMessage());
                a.showAndWait();
            })
        );
    }

    private void onRequestsUpdate(QuerySnapshot snap) {
        List<RequestItem> items = new ArrayList<>();
        Set<String> currentIds = new HashSet<>();

        for (QueryDocumentSnapshot d : snap.getDocuments()) {
            String requestId = safe(d.getString("requestId"));
            String preview   = safe(d.getString("latestBuyerText"));
            if (preview.isBlank()) preview = safe(d.getString("buyerText"));
            long buyerNo = Optional.ofNullable(d.getLong("buyerRequestNo")).orElse(0L);
            String status = safe(d.getString("status"));
            if (status.isBlank()) status = "OPEN";
            if (!requestId.isBlank()) {
                // Hanya notif toast untuk request OPEN yang benar-benar baru
                boolean isNew = initialLoadDone && !knownRequestIds.contains(requestId)
                        && "OPEN".equalsIgnoreCase(status);
                items.add(new RequestItem(requestId, preview, buyerNo, isNew, status));
                currentIds.add(requestId);

                // Show toast for truly new requests
                if (isNew) {
                    RequestItem newItem = items.get(items.size() - 1);
                    showNewOrderToast(newItem);
                }
            }
        }

        // Urutkan: OPEN di atas, COMPLETED di bawah; masing-masing urut buyerRequestNo desc
        items.sort((a, b) -> {
            boolean aOpen = "OPEN".equalsIgnoreCase(a.status);
            boolean bOpen = "OPEN".equalsIgnoreCase(b.status);
            if (aOpen && !bOpen) return -1;
            if (!aOpen && bOpen) return 1;
            return Long.compare(b.buyerRequestNo, a.buyerRequestNo);
        });

        // After first load, all current IDs are "known"
        knownRequestIds.addAll(currentIds);
        initialLoadDone = true;

        requestList.getItems().setAll(items);
        inboxCountLabel.setText(String.valueOf(items.size()));
        inboxCountLabel.setVisible(!items.isEmpty());

        if (items.isEmpty()) {
            setStatus("Menunggu request...", "neutral");
            return;
        }
        setStatus("Ada " + items.size() + " request masuk", "neutral");

        RequestItem newest = items.get(0);
        if (autoFollowLatest) {
            if (selectedRequestId == null || !newest.requestId.equals(lastAutoSelectedRequestId)) {
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
        selectedRequestId     = it.requestId;
        offeredKeys.clear();
        sentCountForThisRequest = 0;
        mainInput.clear(); priceInput.clear(); vendorInput.clear(); contactInput.clear();
        latestBuyerMessageId  = null;
        selectedBuyerText     = "";
        latestOrderItems      = new ArrayList<>();
        qtyBadge.setText("");
        qtyBadge.setVisible(false);
        setStatus("Memuat percakapan...", "neutral");
        menuListView.getItems().clear();
        chatMessages.clear();
        offersByBuyerMsgId.clear();
        renderChat();
        attachMessagesListener(it.requestId);
        attachOffersListener(it.requestId);

        // Mark as read (clear NEW badge)
        knownRequestIds.add(it.requestId);
        refreshSidebarNewBadges();
    }

    /** Re-render sidebar cells to clear NEW badges after selection */
    private void refreshSidebarNewBadges() {
        List<RequestItem> items = new ArrayList<>(requestList.getItems());
        List<RequestItem> updated = new ArrayList<>();
        for (RequestItem ri : items) {
            if (knownRequestIds.contains(ri.requestId) && ri.isNew) {
                updated.add(new RequestItem(ri.requestId, ri.previewText, ri.buyerRequestNo, false, ri.status));
            } else {
                updated.add(ri);
            }
        }
        int selectedIdx = requestList.getSelectionModel().getSelectedIndex();
        requestList.getItems().setAll(updated);
        requestList.getSelectionModel().select(selectedIdx);
    }
    
    // ============================================================
    // MESSAGES LISTENER
    // ============================================================

    private void attachMessagesListener(String requestId) {
        if (messagesListener != null) messagesListener.remove();
        messagesListener = fs.listenMessages(
                requestId,
                snap -> Platform.runLater(() -> onMessagesUpdate(snap)),
                err  -> Platform.runLater(() -> {
                    setStatus("Error ❌", "error");
                    info("Messages listener error: " + (err.getMessage() == null ? "(no message)" : err.getMessage()));
                })
        );
    }

    private void onMessagesUpdate(QuerySnapshot snap) {
        chatMessages.clear();
        String latestText   = "";
        String latestId     = null;
        List<FirestoreService.OrderItem> latestItems = new ArrayList<>();

        for (QueryDocumentSnapshot d : snap.getDocuments()) {
            String senderType = safe(d.getString("senderType"));
            String senderId   = safe(d.getString("senderId"));
            String text       = safe(d.getString("text"));
            List<FirestoreService.OrderItem> orderItems = FirestoreService.parseOrderItems(text);
            chatMessages.add(new ChatMessage(d.getId(), senderType, senderId, text, orderItems));

            if ("BUYER".equalsIgnoreCase(senderType)) {
                latestId    = d.getId();
                latestText  = text;
                latestItems = orderItems;
            }
        }

        boolean changed = !Objects.equals(latestBuyerMessageId, latestId);
        latestBuyerMessageId = latestId;
        selectedBuyerText    = latestText;
        latestOrderItems     = latestItems;

        if (!latestItems.isEmpty()) {
            int total = latestItems.stream().mapToInt(oi -> oi.qty).sum();
            if (total > 1) {
                StringBuilder qtyText = new StringBuilder("🛒 ");
                for (FirestoreService.OrderItem oi : latestItems) {
                    qtyText.append(oi.qty).append("× ").append(oi.name).append("  ");
                }
                qtyBadge.setText(qtyText.toString().trim());
                qtyBadge.setVisible(true);
            } else {
                qtyBadge.setText("");
                qtyBadge.setVisible(false);
            }
        } else {
            qtyBadge.setText("");
            qtyBadge.setVisible(false);
        }

        if (latestText.isBlank()) {
            setStatus("Menunggu pesan buyer...", "neutral");
            menuListView.getItems().clear();
            menuListView.setPlaceholder(buildPlaceholder("Menunggu pesan buyer..."));
            renderChat();
            return;
        }

        if (changed) {
            offeredKeys.clear();
            sentCountForThisRequest = 0;
        }

        setStatus("Memuat menu...", "neutral");
        menuListView.setPlaceholder(buildPlaceholder("Memuat menu..."));
        loadMenusFromFirestore(latestText);
        renderChat();
    }

    // ============================================================
    // OFFERS LISTENER
    // ============================================================

    private void attachOffersListener(String requestId) {
        if (offersAllListener != null) offersAllListener.remove();
        offersAllListener = fs.listenAllOffers(
                requestId,
                snap -> Platform.runLater(() -> onOffersUpdate(snap)),
                err  -> Platform.runLater(() -> setStatus("Offers listener error ❌", "error"))
        );
    }

    @SuppressWarnings("unchecked")
    private void onOffersUpdate(QuerySnapshot snap) {
        offersByBuyerMsgId.clear();
        for (QueryDocumentSnapshot d : snap.getDocuments()) {
            String buyerMsgId = safe(d.getString("buyerMessageId"));
            if (buyerMsgId.isBlank()) continue;

            String sellerIdDoc = safe(d.getString("sellerId"));
            String vendor      = safe(d.getString("vendor"));

            List<Map<String, Object>> rawLines = (List<Map<String, Object>>) d.get("offerLines");
            List<SentOffer.Line> lines = new ArrayList<>();
            int grandTotal = 0;

            if (rawLines != null && !rawLines.isEmpty()) {
                for (Map<String, Object> line : rawLines) {
                    String name = safe((String) line.get("menuName"));
                    int qty   = line.get("qty")   instanceof Long ? ((Long) line.get("qty")).intValue()   : 1;
                    int price = line.get("price") instanceof Long ? ((Long) line.get("price")).intValue() : 0;
                    lines.add(new SentOffer.Line(name, qty, price));
                    grandTotal += qty * price;
                }
            } else {
                String menuName = safe(d.getString("menuName"));
                int price = Optional.ofNullable(d.getLong("price")).orElse(0L).intValue();
                lines.add(new SentOffer.Line(menuName, 1, price));
                grandTotal = price;
            }

            Long storedTotal = d.getLong("grandTotal");
            if (storedTotal != null && storedTotal > 0) grandTotal = storedTotal.intValue();

            SentOffer offer = new SentOffer(sellerIdDoc, vendor, lines, grandTotal);
            offersByBuyerMsgId.computeIfAbsent(buyerMsgId, k -> new ArrayList<>()).add(offer);
        }
        renderChat();
    }

    // ============================================================
    // MENUS
    // ============================================================

    private void loadMenusFromFirestore(String buyerText) {
        new Thread(() -> {
            try {
                String category = fs.mapCategoryFromText(buyerText);
                List<FirestoreService.MenuItem> menus = fs.getMenusByCategory(category);
                Platform.runLater(() -> {
                    if (menus.isEmpty()) {
                        setStatus("Tidak ada menu ❌", "error");
                        menuListView.getItems().clear();
                        menuListView.setPlaceholder(buildPlaceholder("Menu tidak ditemukan: " + category));
                        return;
                    }
                    menuListView.getItems().setAll(menus);
                    menuListView.refresh();
                    menuCountLabel.setText(menus.size() + " item");
                    setStatus("Pilih hingga 3 menu untuk dikirim", "neutral");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    setStatus("Error load menu ❌", "error");
                    menuListView.getItems().clear();
                    menuListView.setPlaceholder(buildPlaceholder("Gagal load menu: " + ex.getMessage()));
                });
            }
        }).start();
    }

    // ============================================================
    // OFFER FROM MENU LIST CLICK
    // ============================================================

    private boolean isMenuAlreadyOfferedForLatest(FirestoreService.MenuItem menu) {
        if (selectedRequestId == null || latestBuyerMessageId == null || latestBuyerMessageId.isBlank()) return false;
        String key = (selectedRequestId + "::" + latestBuyerMessageId + "::menu::" + menu.getName())
                .toLowerCase(Locale.ROOT);
        return offeredKeys.contains(key);
    }

    private void onSendOfferFromList(FirestoreService.MenuItem menu) {
        if (selectedRequestId == null) { info("Pilih request terlebih dahulu."); return; }
        if (latestBuyerMessageId == null || latestBuyerMessageId.isBlank()) { info("Tunggu pesan dari buyer."); return; }
        if (sentCountForThisRequest >= 3) { info("Maksimal 3 offer per request."); return; }

        String key = (selectedRequestId + "::" + latestBuyerMessageId + "::menu::" + menu.getName())
                .toLowerCase(Locale.ROOT);
        if (offeredKeys.contains(key)) { info("Sudah dikirim untuk pesan ini."); return; }

        offeredKeys.add(key);
        menuListView.refresh();

        List<Map<String, Object>> offerLines = buildOfferLinesFromOrderItems(menu.getName(), menu.getPrice());
        int grandTotal = offerLines.stream()
                .mapToInt(l -> ((Long) l.get("price")).intValue() * ((Long) l.get("qty")).intValue())
                .sum();

        final String reqIdFinal   = selectedRequestId;
        final String msgIdFinal   = latestBuyerMessageId;
        final String contactFinal = contactInput.getText().trim();

        new Thread(() -> {
            try {
                fs.createOfferWithLines(reqIdFinal, sellerId, menu.getVendor(),
                        menu.getEtaMinutes(), menu.getRating(),
                        offerLines, grandTotal, msgIdFinal, contactFinal);
                sentCountForThisRequest++;
                setStatus("Terkirim ✅  " + menu.getName() + "  (" + sentCountForThisRequest + "/3)", "success");
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    offeredKeys.remove(key);
                    menuListView.refresh();
                    setStatus("Error ❌", "error");
                    info("Gagal kirim offer: " + ex.getMessage());
                });
            }
        }).start();
    }

    // ============================================================
    // AUTO SEND (typed offer / chat)
    // ============================================================

    private void onSendAuto() {
        if (selectedRequestId == null) { info("Pilih request terlebih dahulu."); return; }
        String text      = mainInput.getText().trim();
        String priceText = priceInput.getText().trim();
        String vendor    = vendorInput.getText().trim();
        String contact   = contactInput.getText().trim();

        if (text.isBlank()) return;

        boolean hasPrice  = !priceText.isBlank();
        boolean hasVendor = !vendor.isBlank();

        if (!hasPrice) { sendChat(text); return; }
        if (latestBuyerMessageId == null || latestBuyerMessageId.isBlank()) { info("Tunggu pesan dari buyer."); return; }
        if (sentCountForThisRequest >= 3) { info("Maksimal 3 offer per request."); return; }

        int price = parsePriceOr0(priceText);
        if (hasVendor) addMenuAndSend(text, price, vendor, contact);
        else           sendTypedOffer(text, price, contact);
    }

    private void onSendFromChat() {
        if (selectedRequestId == null) { info("Pilih request terlebih dahulu."); return; }
        String text = chatReplyInput.getText().trim();
        if (text.isBlank()) return;
        chatReplyInput.clear();
        sendChat(text);
    }

    private void sendChat(String text) {
        disableActions(true);
        final String reqIdFinal = selectedRequestId;
        new Thread(() -> {
            try {
                fs.sendSellerMessage(reqIdFinal, sellerId, text);
                setStatus("Chat terkirim ✅", "success");
                Platform.runLater(() -> {
                    mainInput.clear();
                    disableActions(false);
                });
            } catch (Exception ex) {
                setStatus("Error ❌", "error");
                Platform.runLater(() -> {
                    info("Gagal kirim chat: " + ex.getMessage());
                    disableActions(false);
                });
            }
        }).start();
    }

    private void sendTypedOffer(String menuName, int price, String contact) {
        String key = (selectedRequestId + "::" + latestBuyerMessageId + "::typed::" + menuName)
                .toLowerCase(Locale.ROOT);
        if (offeredKeys.contains(key)) { info("Sudah dikirim untuk pesan ini."); return; }

        disableActions(true);
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
                setStatus("Offer terkirim ✅  " + menuName + " (" + sentCountForThisRequest + "/3)", "success");
                Platform.runLater(() -> {
                    mainInput.clear(); priceInput.clear(); vendorInput.clear();
                    disableActions(false);
                });
            } catch (Exception ex) {
                setStatus("Error ❌", "error");
                Platform.runLater(() -> {
                    info("Gagal kirim offer: " + ex.getMessage());
                    disableActions(false);
                });
            }
        }).start();
    }

    private void addMenuAndSend(String menuName, int price, String vendor, String contact) {
        String key = (selectedRequestId + "::" + latestBuyerMessageId + "::newmenu::" + menuName)
                .toLowerCase(Locale.ROOT);
        if (offeredKeys.contains(key)) { info("Sudah ditambahkan untuk pesan ini."); return; }

        disableActions(true);
        List<Map<String, Object>> offerLines = buildOfferLinesFromOrderItems(menuName, price);
        int grandTotal = offerLines.stream()
                .mapToInt(l -> ((Long) l.get("price")).intValue() * ((Long) l.get("qty")).intValue())
                .sum();

        final String reqIdFinal     = selectedRequestId;
        final String msgIdFinal     = latestBuyerMessageId;
        final String buyerTextFinal = selectedBuyerText;

        new Thread(() -> {
            try {
                fs.createMenuEntry(buyerTextFinal, menuName, price, vendor, sellerId);
                fs.createOfferWithLines(reqIdFinal, sellerId, vendor, 0, 0.0,
                        offerLines, grandTotal, msgIdFinal, contact);
                offeredKeys.add(key);
                sentCountForThisRequest++;
                setStatus("Ditambah & Terkirim ✅  " + menuName + " (" + sentCountForThisRequest + "/3)", "success");
                Platform.runLater(() -> {
                    mainInput.clear(); priceInput.clear(); vendorInput.clear();
                    disableActions(false);
                    loadMenusFromFirestore(buyerTextFinal);
                });
            } catch (Exception ex) {
                setStatus("Error ❌", "error");
                Platform.runLater(() -> {
                    info("Gagal tambah menu: " + ex.getMessage());
                    disableActions(false);
                });
            }
        }).start();
    }

    private List<Map<String, Object>> buildOfferLinesFromOrderItems(String menuName, int price) {
        List<Map<String, Object>> lines = new ArrayList<>();
        if (latestOrderItems != null && !latestOrderItems.isEmpty()) {
            for (FirestoreService.OrderItem oi : latestOrderItems) {
                Map<String, Object> line = new HashMap<>();
                line.put("menuName", oi.name.isBlank() ? menuName : oi.name);
                line.put("qty",   (long) oi.qty);
                line.put("price", (long) price);
                lines.add(line);
            }
        } else {
            Map<String, Object> line = new HashMap<>();
            line.put("menuName", menuName);
            line.put("qty",   1L);
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
        chatReplyInput.setDisable(disabled);
        chatSendBtn.setDisable(disabled);
    }

    private int parsePriceOr0(String ptxt) {
        if (ptxt == null || ptxt.isBlank()) return 0;
        try { return Integer.parseInt(ptxt.replaceAll("[^0-9]", "")); }
        catch (Exception e) { info("Harga tidak valid. Gunakan angka (contoh: 9000)."); return 0; }
    }

    private void info(String msg) {
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.INFORMATION);
            a.setTitle("Info"); a.setHeaderText(null); a.setContentText(msg); a.showAndWait();
        });
    }

    private void cleanup() {
        if (requestsListener  != null) requestsListener.remove();
        if (messagesListener  != null) messagesListener.remove();
        if (offersAllListener != null) offersAllListener.remove();
    }

    private String safe(String s) { return s == null ? "" : s; }
    
    // ============================================================
    // INNER CLASSES
    // ============================================================

    private static class RequestItem {
        final String requestId, previewText, status;
        final long buyerRequestNo;
        final boolean isNew;

        RequestItem(String requestId, String previewText, long buyerRequestNo, boolean isNew, String status) {
            this.requestId      = requestId;
            this.previewText    = previewText == null ? "" : previewText;
            this.buyerRequestNo = buyerRequestNo;
            this.isNew          = isNew;
            this.status         = status == null ? "OPEN" : status;
        }
    }

    private static class ChatMessage {
        final String id, senderType, senderId, text;
        final List<FirestoreService.OrderItem> orderItems;
        ChatMessage(String id, String senderType, String senderId, String text,
                    List<FirestoreService.OrderItem> orderItems) {
            this.id         = id;
            this.senderType = senderType == null ? "" : senderType;
            this.senderId   = senderId   == null ? "" : senderId;
            this.text       = text       == null ? "" : text;
            this.orderItems = orderItems == null ? new ArrayList<>() : orderItems;
        }
    }

    private static class SentOffer {
        final String sellerId, vendor;
        final List<Line> lines;
        final int grandTotal;

        SentOffer(String sellerId, String vendor, List<Line> lines, int grandTotal) {
            this.sellerId   = sellerId == null ? "" : sellerId;
            this.vendor     = vendor   == null ? "" : vendor;
            this.lines      = lines    == null ? new ArrayList<>() : lines;
            this.grandTotal = grandTotal;
        }

        static class Line {
            final String name;
            final int qty, price;
            Line(String name, int qty, int price) {
                this.name  = name == null ? "" : name;
                this.qty   = Math.max(qty, 1);
                this.price = price;
            }
        }
    }
}