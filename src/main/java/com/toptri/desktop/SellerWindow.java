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

    private final Label status = new Label("Waiting...");
    private final ListView<RequestItem> requestList = new ListView<>();

    private final Label reqIdValue = new Label("-");
    private final StackPane buyerMsgHolder = new StackPane();

    private final VBox menuList = new VBox(10);

    private volatile String selectedRequestId = null;
    private volatile String selectedBuyerText = "";

    private final Set<String> offeredKeys = new HashSet<>();
    private int sentCountForThisRequest = 0;

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
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                VBox v = new VBox(2);
                Label t = new Label(item.buyerText);
                t.setStyle("-fx-font-weight: 800;");
                Label s = UiKit.small(item.requestId);
                v.getChildren().addAll(t, s);
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

        dash.getChildren().addAll(
                title,
                statusRow,
                UiKit.divider(),

                UiKit.small("Seller"),
                new Label(sellerId),
                UiKit.divider(),

                UiKit.small("Request ID"),
                reqIdValue,
                UiKit.divider(),

                UiKit.small("Buyer Message"),
                buyerMsgHolder,
                UiKit.divider(),

                UiKit.small("Click menu to send to buyer (max 3)"),
                menuList
        );

        showMenuHint("Select a request to load menus...");

        Region dashCard = UiKit.cardContainer(dash);

        HBox content = new HBox(18, inboxCard, dashCard);
        content.setPadding(new Insets(26));
        content.setAlignment(Pos.TOP_CENTER);

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(content);
        UiKit.applyAppBackground(root);

        stage.setTitle("Toptri Chat - Seller (" + sellerId + ")");
        stage.setScene(new Scene(root, 1020, 720));
        stage.show();

        stage.setOnCloseRequest(e -> cleanup());

        attachRequestsListener();
    }

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
            String buyerText = safe(d.getString("buyerText"));

            if (!requestId.isBlank() && !buyerText.isBlank()) {
                items.add(new RequestItem(requestId, buyerText));
            }
        }

        requestList.getItems().setAll(items);
        status.setText(items.isEmpty() ? "Waiting..." : "New requests available ✅");
    }

    private void onSelectRequest(RequestItem it) {
        selectedRequestId = it.requestId;
        selectedBuyerText = it.buyerText;

        reqIdValue.setText(it.requestId);
        buyerMsgHolder.getChildren().setAll(UiKit.messagePill(it.buyerText));

        offeredKeys.clear();
        sentCountForThisRequest = 0;

        status.setText("Loading menus...");
        showMenuHint("Loading menus from Firestore...");

        loadMenusFromFirestore(it.buyerText);
    }

    private void loadMenusFromFirestore(String buyerText) {
        new Thread(() -> {
            try {
                String category = fs.mapCategoryFromText(buyerText);
                List<FirestoreService.MenuItem> menus = fs.getMenusByCategory(category);

                Platform.runLater(() -> {
                    menuList.getChildren().clear();

                    if (menus.isEmpty()) {
                        status.setText("No menus found ❌");
                        showMenuHint("No menus found for: " + category);
                        return;
                    }

                    for (FirestoreService.MenuItem m : menus) {
                        // Subtitle includes vendor + ETA + rating
                        String subtitle = UiKit.rupiah(m.getPrice())
                                + " • " + m.vendorOrDash()
                                + " • " + m.etaText()
                                + " • " + m.ratingText();

                        VBox card = UiKit.menuCard(m.getName(), subtitle);
                        card.setOnMouseClicked(e -> onSendOffer(m, card));
                        menuList.getChildren().add(card);
                    }

                    status.setText("Pick up to 3 menus to offer.");
                });

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    status.setText("Error ❌");
                    showMenuHint("Failed to load menus: " + ex.getMessage());
                });
            }
        }).start();
    }

    private void onSendOffer(FirestoreService.MenuItem menu, VBox uiNode) {
        if (selectedRequestId == null) {
            info("Please select a request from the inbox first.");
            return;
        }
        if (sentCountForThisRequest >= 3) {
            info("Maximum 3 offers per request.");
            return;
        }

        String key = selectedRequestId + "::" + menu.getName();
        if (offeredKeys.contains(key)) {
            info("You already offered this menu for the selected request.");
            return;
        }

        uiNode.setOpacity(0.55);
        uiNode.setDisable(true);

        new Thread(() -> {
            try {
                fs.createOfferFromMenu(selectedRequestId, sellerId, menu);

                offeredKeys.add(key);
                sentCountForThisRequest++;

                Platform.runLater(() ->
                        status.setText("Sent ✅ " + menu.getName() + " (" + sentCountForThisRequest + "/3)")
                );

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    uiNode.setOpacity(1.0);
                    uiNode.setDisable(false);
                    status.setText("Error ❌");
                    info("Failed to send offer: " + ex.getMessage());
                });
            }
        }).start();
    }

    private void showMenuHint(String text) {
        menuList.getChildren().clear();
        menuList.getChildren().add(UiKit.small(text));
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
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private static class RequestItem {
        final String requestId;
        final String buyerText;

        RequestItem(String requestId, String buyerText) {
            this.requestId = requestId;
            this.buyerText = buyerText;
        }
    }
}
