package com.toptri.desktop;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

public class UiKit {
    public static final String PURPLE = "#5F5BFF";
    public static final String BG = "#EEF3FF";
    public static final String CARD = "#FFFFFF";

    public static final String BUYER_BUBBLE = "#DCE7FF";
    public static final String OFFER_BG = "#D9FBE6";
    public static final String WAIT_BG = "#FFF2B8";
    public static final String GREEN_BTN = "#16A34A";
    public static final String PILL_GREEN = "#D9FBE6";

    public static Region headerBar(String title) {
        HBox bar = new HBox();
        bar.setPrefHeight(56);
        bar.setPadding(new Insets(0, 18, 0, 18));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color: " + PURPLE + ";");

        Label t = new Label(title);
        t.setTextFill(Color.web("#FFB020"));
        t.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 18));

        bar.getChildren().add(t);
        return bar;
    }

    public static Region cardContainer(Node content) {
        StackPane wrap = new StackPane(content);
        wrap.setPadding(new Insets(18));
        wrap.setStyle(
                "-fx-background-color: " + CARD + ";" +
                "-fx-background-radius: 18;" +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 18, 0.2, 0, 6);"
        );
        return wrap;
    }

    public static void applyAppBackground(Pane root) {
        root.setStyle("-fx-background-color: " + BG + ";");
    }

    public static Label h1(String text) {
        Label l = new Label(text);
        l.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 28));
        return l;
    }

    public static Label small(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-opacity: 0.75;");
        return l;
    }

    public static Region divider() {
        Region r = new Region();
        r.setPrefHeight(1);
        r.setStyle("-fx-background-color: rgba(0,0,0,0.08);");
        return r;
    }

    public static Button primaryButton(String text) {
        Button b = new Button(text);
        b.setStyle(
                "-fx-background-color: " + GREEN_BTN + ";" +
                "-fx-text-fill: white;" +
                "-fx-font-weight: 800;" +
                "-fx-padding: 10 18;" +
                "-fx-background-radius: 14;"
        );
        return b;
    }

    public static Region bubbleRight(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setPadding(new Insets(10, 14, 10, 14));
        l.setStyle("-fx-background-color: " + BUYER_BUBBLE + "; -fx-background-radius: 16;");
        HBox row = new HBox(l);
        row.setAlignment(Pos.CENTER_RIGHT);
        return row;
    }

    public static Region bubbleWait(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setPadding(new Insets(10, 14, 10, 14));
        l.setStyle("-fx-background-color: " + WAIT_BG + "; -fx-background-radius: 16;");
        HBox row = new HBox(l);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    public static Region offerCard(String title, String subtitle, Runnable onBuy) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(14));
        box.setStyle("-fx-background-color: " + OFFER_BG + "; -fx-background-radius: 18;");

        Label t = new Label(title);
        t.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 22));

        Label s = new Label(subtitle);
        s.setStyle("-fx-opacity: 0.75;");

        Button buy = new Button("Buy");
        buy.setOnAction(e -> onBuy.run());
        buy.setStyle(
                "-fx-background-color: " + GREEN_BTN + ";" +
                "-fx-text-fill: white;" +
                "-fx-font-weight: 800;" +
                "-fx-padding: 8 18;" +
                "-fx-background-radius: 14;"
        );

        box.getChildren().addAll(t, s, buy);

        HBox row = new HBox(box);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    public static Region messagePill(String text) {
        Label l = new Label(text == null || text.isBlank() ? "-" : text);
        l.setWrapText(true);
        l.setPadding(new Insets(10, 14, 10, 14));
        l.setStyle("-fx-background-color: " + PILL_GREEN + "; -fx-background-radius: 16;");

        HBox row = new HBox(l);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    public static VBox menuCard(String name, String subtitle) {
        VBox box = new VBox(2);
        box.setPadding(new Insets(12));
        box.setStyle(
                "-fx-background-color: white;" +
                "-fx-background-radius: 14;" +
                "-fx-border-radius: 14;" +
                "-fx-border-color: rgba(0,0,0,0.08);"
        );

        Label t = new Label(name);
        t.setStyle("-fx-font-weight: 800; -fx-font-size: 16;");

        Label s = small(subtitle);

        box.getChildren().addAll(t, s);
        return box;
    }

    public static String rupiah(int n) {
        return "Rp" + String.format("%,d", n).replace(',', '.');
    }
}