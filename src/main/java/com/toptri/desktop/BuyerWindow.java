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

public class BuyerWindow {

    private final FirestoreService fs;

    private String currentRequestId = null;
    private String currentRequestText = null;

    private ListenerRegistration offersListener;

    private final Label ridValue = new Label("-");
    private final TextField input = new TextField();
    private final VBox chatBox = new VBox(12);
    private final ScrollPane chatScroll = new ScrollPane(chatBox);

    private final List<Offer> offers = new ArrayList<>();

    public BuyerWindow(FirestoreService fs) {
        this.fs = fs;
    }

    public static void open(FirestoreService fs) {
        new BuyerWindow(fs).show();
    }

    public void show() {
        Stage stage = new Stage();

        Region header = UiKit.headerBar("Toptri Chat - Buyer");

        VBox card = new VBox(12);
        card.setPadding(new Insets(6));

        Label title = UiKit.h1("Buyer");

        HBox sendRow = new HBox(10);
        input.setPromptText("Example: nasi padang");
        HBox.setHgrow(input, Priority.ALWAYS);

        Button sendBtn = UiKit.primaryButton("Send");
        sendBtn.setOnAction(e -> onSend());

        sendRow.getChildren().addAll(input, sendBtn);

        HBox ridRow = new HBox(6);
        ridRow.setAlignment(Pos.CENTER_LEFT);
        ridRow.getChildren().addAll(UiKit.small("Request ID:"), ridValue);

        chatScroll.setFitToWidth(true);
        chatScroll.setStyle("-fx-background-color: transparent;");
        chatBox.setPadding(new Insets(10));
        chatBox.setFillWidth(true);

        card.getChildren().addAll(title, sendRow, ridRow, UiKit.divider(), chatScroll);

        Region cardWrap = UiKit.cardContainer(card);

        StackPane center = new StackPane(cardWrap);
        center.setPadding(new Insets(26));
        center.setAlignment(Pos.TOP_CENTER);

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(center);
        UiKit.applyAppBackground(root);

        renderChat();

        stage.setTitle("Toptri Chat - Buyer");
        stage.setScene(new Scene(root, 700, 720));
        stage.show();

        stage.setOnCloseRequest(e -> cleanup());
    }

    private void onSend() {
        String text = input.getText().trim();
        if (text.isBlank()) return;

        currentRequestId = makeRequestId();
        currentRequestText = text;

        ridValue.setText(currentRequestId);
        input.clear();

        offers.clear();
        renderChat();

        new Thread(() -> {
            try {
                fs.createRequest(currentRequestId, currentRequestText);
                attachOffersListener(currentRequestId);
            } catch (Exception ex) {
                showError("Failed to send request", ex.getMessage());
            }
        }).start();
    }

    private void attachOffersListener(String requestId) {
        if (offersListener != null) offersListener.remove();

        offersListener = fs.listenOffers(
                requestId,
                snap -> Platform.runLater(() -> onOffersUpdate(snap)),
                err -> Platform.runLater(() -> showError("Listener error", err.getMessage()))
        );
    }

    private void onOffersUpdate(QuerySnapshot snap) {
        offers.clear();

        for (QueryDocumentSnapshot d : snap.getDocuments()) {
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

            offers.add(new Offer(id, sellerId, menuName, price, vendor, etaMinutes, rating));
        }

        renderChat();
        Platform.runLater(() -> chatScroll.setVvalue(1.0));
    }

    private void renderChat() {
        chatBox.getChildren().clear();

        if (currentRequestText == null) {
            chatBox.getChildren().add(UiKit.bubbleWait("Type a request and press Send."));
            return;
        }

        chatBox.getChildren().add(UiKit.bubbleRight(currentRequestText));

        if (offers.isEmpty()) {
            chatBox.getChildren().add(UiKit.bubbleWait("⏳ Waiting for seller offers..."));
            return;
        }

        for (Offer o : offers) {
            String sellerPart = o.sellerId.isBlank() ? "-" : o.sellerId;
            String vendorPart = o.vendor.isBlank() ? "-" : o.vendor;
            String etaPart = (o.etaMinutes > 0) ? ("ETA " + o.etaMinutes + " min") : "ETA -";
            String ratingPart = (o.rating > 0) ? ("⭐ " + String.format(Locale.US, "%.1f", o.rating)) : "⭐ -";

            String subtitle = UiKit.rupiah(o.price)
                    + " • " + sellerPart
                    + " • " + vendorPart
                    + " • " + etaPart
                    + " • " + ratingPart;

            chatBox.getChildren().add(
                    UiKit.offerCard(o.menuName, subtitle, () -> onBuy(o))
            );
        }
    }

    private void onBuy(Offer offer) {
        TextInputDialog nameDlg = new TextInputDialog("");
        nameDlg.setTitle("Buyer Info");
        nameDlg.setHeaderText("Buyer name (optional)");
        String name = nameDlg.showAndWait().orElse("");

        TextInputDialog addrDlg = new TextInputDialog("");
        addrDlg.setTitle("Buyer Info");
        addrDlg.setHeaderText("Address (optional)");
        String address = addrDlg.showAndWait().orElse("");

        Alert ok = new Alert(Alert.AlertType.INFORMATION);
        ok.setTitle("Purchase");
        ok.setHeaderText("✅ Purchase simulated");
        ok.setContentText(
                "You selected:\n" +
                        offer.menuName + " (" + UiKit.rupiah(offer.price) + ")\n" +
                        "Seller: " + (offer.sellerId.isBlank() ? "-" : offer.sellerId) + "\n" +
                        "Vendor: " + (offer.vendor.isBlank() ? "-" : offer.vendor) + "\n" +
                        "ETA: " + ((offer.etaMinutes > 0) ? (offer.etaMinutes + " min") : "-") + "\n" +
                        "Rating: " + ((offer.rating > 0) ? String.format(Locale.US, "%.1f", offer.rating) : "-") + "\n\n" +
                        "Name: " + name + "\nAddress: " + address
        );
        ok.showAndWait();
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
        if (offersListener != null) offersListener.remove();
    }

    private String makeRequestId() {
        return "req_" + Integer.toHexString(new Random().nextInt()).replace("-", "")
                + "_" + System.currentTimeMillis();
    }

    private String safe(String s) {
        return s == null ? "" : s;
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
            this.sellerId = sellerId;
            this.menuName = menuName;
            this.price = price;
            this.vendor = vendor == null ? "" : vendor;
            this.etaMinutes = etaMinutes;
            this.rating = rating;
        }
    }
}
