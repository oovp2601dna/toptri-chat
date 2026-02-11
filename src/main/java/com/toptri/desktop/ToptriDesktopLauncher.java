package com.toptri.desktop;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.PrintWriter;
import java.io.StringWriter;

public class ToptriDesktopLauncher extends Application {

    @Override
    public void start(Stage stage) {
        try {
            FirestoreService fs = new FirestoreService();

            Button buyerBtn = new Button("Open Buyer");
            buyerBtn.setOnAction(e -> BuyerWindow.open(fs));

            Button sellerA = new Button("Open Seller A");
            sellerA.setOnAction(e -> SellerWindow.open(fs, "Seller A"));

            Button sellerB = new Button("Open Seller B");
            sellerB.setOnAction(e -> SellerWindow.open(fs, "Seller B"));

            VBox root = new VBox(12, buyerBtn, sellerA, sellerB);
            root.setStyle("-fx-padding: 20;");
            stage.setScene(new Scene(root, 320, 180));
            stage.setTitle("Toptri Chat - Desktop");
            stage.show();

        } catch (Exception ex) {
            showCrash(ex);
        }
    }

    private void showCrash(Exception ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));

        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle("App Crash");
        a.setHeaderText(ex.getClass().getSimpleName() + ": " + ex.getMessage());

        TextArea area = new TextArea(sw.toString());
        area.setEditable(false);
        area.setPrefWidth(900);
        area.setPrefHeight(500);

        a.getDialogPane().setContent(area);
        a.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
