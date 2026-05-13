package com.toptri.desktop;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

public class UiKit {

    // ── Shared design tokens ─────────────────────────────────────
    public static final String COLOR_BG            = "#F0F2F5";
    public static final String COLOR_SURFACE       = "#FFFFFF";
    public static final String COLOR_PRIMARY       = "#5F5BFF";
    public static final String COLOR_PRIMARY_DARK  = "#4A47CC";
    public static final String COLOR_BUYER_BUBBLE  = "#DCF8C6";
    public static final String COLOR_SELLER_BUBBLE = "#FFFFFF";
    public static final String COLOR_OFFER_BUBBLE  = "#EEF3FF";
    public static final String COLOR_SIDEBAR_BG    = "#FFFFFF";
    public static final String COLOR_SELECTED_ROW  = "#EEF3FF";
    public static final String COLOR_TEXT_MAIN     = "#1A1A2E";
    public static final String COLOR_TEXT_MUTED    = "#6B7280";
    public static final String COLOR_DIVIDER       = "#E5E7EB";
    public static final String COLOR_SUCCESS       = "#16A34A";
    public static final String COLOR_DANGER        = "#DC2626";
    public static final String COLOR_WARN          = "#D97706";
    public static final String COLOR_NOTIF_BG      = "#1A1A2E";
    public static final String COLOR_COMPLETED_BG  = "#F0FDF4";

    // ── Legacy aliases ───────────────────────────────────────────
    public static final String BG         = COLOR_BG;
    public static final String CARD       = COLOR_SURFACE;
    public static final String PURPLE     = COLOR_PRIMARY;
    public static final String BUYER_BUBBLE = COLOR_BUYER_BUBBLE;
    public static final String OFFER_BG   = "#D9FBE6";
    public static final String WAIT_BG    = "#FFF2B8";
    public static final String GREEN_BTN  = COLOR_SUCCESS;
    public static final String PILL_GREEN = "#D9FBE6";

    // ============================================================
    // STYLE HELPERS — dipindahkan dari BuyerWindow & SellerWindow
    // ============================================================

    /** Style tombol primary (purple) dengan hover effect. */
    public static void stylePrimaryButton(Button btn) {
        btn.setStyle(
            "-fx-background-color: " + COLOR_PRIMARY + ";" +
            "-fx-text-fill: white;" +
            "-fx-font-weight: bold; -fx-font-size: 13;" +
            "-fx-background-radius: 8; -fx-padding: 8 20;" +
            "-fx-cursor: hand;"
        );
        btn.setOnMouseEntered(e -> btn.setStyle(btn.getStyle().replace(COLOR_PRIMARY, COLOR_PRIMARY_DARK)));
        btn.setOnMouseExited(e  -> btn.setStyle(btn.getStyle().replace(COLOR_PRIMARY_DARK, COLOR_PRIMARY)));
    }

    /** Style input field standar dengan focus highlight. */
    public static void styleInput(TextField tf, String prompt) {
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

    /** Terapkan warna status pill: "success", "error", "warn", atau default (neutral). */
    public static void styleStatusPill(Label lbl, String type) {
        String bg, fg;
        switch (type) {
            case "success" -> { bg = "#DCFCE7"; fg = COLOR_SUCCESS; }
            case "error"   -> { bg = "#FEE2E2"; fg = COLOR_DANGER; }
            case "warn"    -> { bg = "#FEF3C7"; fg = COLOR_WARN; }
            default        -> { bg = "#EEF3FF"; fg = COLOR_PRIMARY; }
        }
        lbl.setStyle(
            "-fx-background-color: " + bg + ";" +
            "-fx-text-fill: " + fg + ";" +
            "-fx-background-radius: 20; -fx-padding: 4 14;" +
            "-fx-font-size: 12; -fx-font-weight: bold;"
        );
    }

    /** Update status pill di UI thread (thread-safe). */
    public static void setStatus(Label statusPill, String msg, String type) {
        Platform.runLater(() -> {
            statusPill.setText(msg);
            styleStatusPill(statusPill, type);
        });
    }

    /** Buat label placeholder italic untuk ListView kosong. */
    public static Label buildPlaceholder(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: " + COLOR_TEXT_MUTED + "; -fx-font-style: italic;");
        return l;
    }

    /** Format angka ke rupiah: 9000 -> "Rp9.000". */
    public static String rupiah(int n) {
        if (n <= 0) return "Rp0";
        return "Rp" + String.format("%,d", n).replace(',', '.');
    }

    // ============================================================
    // EXISTING HELPERS
    // ============================================================

    public static Region headerBar(String title) {
        HBox bar = new HBox();
        bar.setPrefHeight(56);
        bar.setPadding(new Insets(0, 18, 0, 18));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color: " + COLOR_PRIMARY + ";");

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
}
