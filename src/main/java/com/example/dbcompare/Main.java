package com.example.dbcompare;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;

public class Main extends Application {

    private TableView<Map<String, String>> tableView = new TableView<>();
    private DBConfigResolver resolver;
    private Map<String, String> dbMap;
    private Connection oracleConn; //

    @Override
    public void start(Stage primaryStage) throws Exception {

        // 1) Konfig laden (extern via -Ddbcompare.config=... oder aus dem Klassenpfad)
        Properties props = loadConfig();

        String jdbcUrl = getRequired(props, "oracle.url");
        String user    = getRequired(props, "oracle.user");
        String pass    = getRequired(props, "oracle.password");

        // 3) Connection aufbauen
        oracleConn = DriverManager.getConnection(jdbcUrl, user, pass);

        resolver = new DBConfigResolver(oracleConn);
        dbMap = resolver.resolveConnections();

        VBox root = new VBox();

        // Buttons
        Button refreshButton = new Button("🔄 Refresh");
        refreshButton.setOnAction(e -> refreshTable());

        Button exportButton = new Button("📄 Als Excel exportieren");
        exportButton.setOnAction(e -> exportTableToExcel(tableView));

        // Top-Leiste
        HBox topBar = new HBox(8, refreshButton, exportButton);
        topBar.setPadding(new Insets(8));

        root.getChildren().addAll(topBar, tableView);

        // TableView soll mitwachsen
        VBox.setVgrow(tableView, Priority.ALWAYS);

        // initial laden
        refreshTable();

        Scene scene = new Scene(root, 1000, 600);
        primaryStage.setTitle("Datenbank Vergleich");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private static boolean isNullOrBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Lädt Konfiguration aus externer Datei (-Ddbcompare.config=...) oder Klassenpfad (dbcompare.properties). */
    private static Properties loadConfig() throws IOException {
        Properties p = new Properties();

        // 1) Externe Datei via JVM-Property
        String externalPath = System.getProperty("dbcompare.config");
        System.out.println("externalPath: " + externalPath);
        if (!isNullOrBlank(externalPath)) {
            Path path = Paths.get(externalPath);
            if (!Files.exists(path)) {
                throw new FileNotFoundException("Konfigdatei nicht gefunden: " + path);
            }
            try (InputStream in = Files.newInputStream(path)) {
                p.load(in);
                return p;
            }
        }

        // 2) Klassenpfad-Ressource (hier deaktiviert)
        // try (InputStream in = Main.class.getClassLoader().getResourceAsStream("dbcompare.properties")) {
        //     if (in != null) {
        //         p.load(in);
        //         return p;
        //     }
        // }

        throw new FileNotFoundException(
                "Keine Konfigurationsdatei gefunden. " +
                        "Lege 'dbcompare.properties' auf den Klassenpfad ODER starte mit -Ddbcompare.config=/pfad/zu/datei.properties."
        );
    }

    /** Holt einen Pflicht-Property-Wert, sonst Exception. */
    private static String getRequired(Properties p, String key) {
        String v = p.getProperty(key);
        if (isNullOrBlank(v)) {
            throw new IllegalArgumentException("Fehlender Konfigurationsschlüssel: " + key);
        }
        return v.trim();
    }

    private void refreshTable() {
        tableView.getItems().clear();
        tableView.getColumns().clear();

        try {
            List<QueryModel> queries = resolver.loadQueries();

            for (QueryModel qm : queries) {
                Map<String, String> row = new HashMap<String, String>();
                row.put("SQL", qm.getSql());
                for (String db : qm.getDbKuerzel()) {
                    if (dbMap.containsKey(db)) {
                        String[] parts = dbMap.get(db).split(";");
                        String jdbcUrl = parts[0];
                        String user = parts[1];
                        String pass = parts[2];

                        String result = DBQueryExecutor.execute(jdbcUrl, user, pass, qm.getSql());
                        row.put(db, result);
                    } else {
                        row.put(db, "Unbekannt");
                    }
                }
                tableView.getItems().add(row);
            }

            // variable Zeilenhöhe für Wrapping
            tableView.setFixedCellSize(-1);

            // 1) SQL-Spalte (breit + Wrapping)
            final TableColumn<Map<String, String>, String> sqlCol =
                    new TableColumn<Map<String, String>, String>("SQL");
            sqlCol.setCellValueFactory(new javafx.util.Callback<TableColumn.CellDataFeatures<Map<String, String>, String>, javafx.beans.value.ObservableValue<String>>() {
                @Override
                public javafx.beans.value.ObservableValue<String> call(TableColumn.CellDataFeatures<Map<String, String>, String> data) {
                    return new javafx.beans.property.SimpleStringProperty(data.getValue().getOrDefault("SQL", ""));
                }
            });
            // Breite (anpassbar, mit Max auf unendlich)
            sqlCol.setPrefWidth(200);
            sqlCol.setMaxWidth(Double.MAX_VALUE);

            // Wrapping-CellFactory für SQL (Java 8 kompatibel)
            sqlCol.setCellFactory(new javafx.util.Callback<TableColumn<Map<String, String>, String>, TableCell<Map<String, String>, String>>() {
                @Override
                public TableCell<Map<String, String>, String> call(TableColumn<Map<String, String>, String> tc) {
                    return new TableCell<Map<String, String>, String>() {
                        private final javafx.scene.text.Text text = new javafx.scene.text.Text();
                        {
                            text.wrappingWidthProperty().bind(sqlCol.widthProperty().subtract(16));
                            setGraphic(text);
                            setPrefHeight(javafx.scene.control.Control.USE_COMPUTED_SIZE);
                        }
                        @Override
                        protected void updateItem(String item, boolean empty) {
                            super.updateItem(item, empty);
                            text.setText((empty || item == null) ? "" : item);
                        }
                    };
                }
            });

            tableView.getColumns().add(sqlCol);

            // 2) DB-Spalten in Reihenfolge von dbMap (feste Breite + Diff-Highlight)
            for (final String db : dbMap.keySet()) {
                TableColumn<Map<String, String>, String> col =
                        new TableColumn<Map<String, String>, String>(db);

                col.setCellValueFactory(new javafx.util.Callback<TableColumn.CellDataFeatures<Map<String, String>, String>, javafx.beans.value.ObservableValue<String>>() {
                    @Override
                    public javafx.beans.value.ObservableValue<String> call(TableColumn.CellDataFeatures<Map<String, String>, String> data) {
                        return new javafx.beans.property.SimpleStringProperty(data.getValue().getOrDefault(db, ""));
                    }
                });

                // feste Breite (anpassbar)
                col.setPrefWidth(220);

                col.setCellFactory(new javafx.util.Callback<TableColumn<Map<String, String>, String>, TableCell<Map<String, String>, String>>() {
                    @Override
                    public TableCell<Map<String, String>, String> call(TableColumn<Map<String, String>, String> column) {
                        return new TableCell<Map<String, String>, String>() {
                            @Override
                            protected void updateItem(String item, boolean empty) {
                                super.updateItem(item, empty);
                                setText(item);
                                setStyle("");

                                if (!empty && item != null) {
                                    Map<String, String> row = getTableView().getItems().get(getIndex());
                                    String referenceValue = null;

                                    for (Map.Entry<String, String> entry : row.entrySet()) {
                                        if (!entry.getKey().equals("SQL")) {
                                            referenceValue = entry.getValue();
                                            break;
                                        }
                                    }

                                    if (referenceValue != null && !referenceValue.equals(item)) {
                                        setStyle("-fx-background-color: lightcoral; -fx-text-fill: black;");
                                    }
                                }
                            }
                        };
                    }
                });

                tableView.getColumns().add(col);
            }

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void exportTableToExcel(TableView<Map<String, String>> tableView) {
        if (tableView.getItems().isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "Keine Daten in der Tabelle.");
            alert.setHeaderText(null);
            alert.showAndWait();
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Excel-Datei speichern");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel-Datei (*.xlsx)", "*.xlsx"));
        fileChooser.setInitialFileName("export.xlsx");
        File file = fileChooser.showSaveDialog(tableView.getScene().getWindow());
        if (file == null) return;

        org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = null;
        FileOutputStream fos = null;
        try {
            workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Tabelle");

            // Header-Stil
            org.apache.poi.ss.usermodel.CellStyle headerStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            // Kopfzeile (sichtbare Reihenfolge)
            org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
            for (int c = 0; c < tableView.getColumns().size(); c++) {
                TableColumn<Map<String, String>, ?> tc = tableView.getColumns().get(c);
                org.apache.poi.ss.usermodel.Cell cell = headerRow.createCell(c);
                cell.setCellValue(tc.getText() != null ? tc.getText() : "");
                cell.setCellStyle(headerStyle);
            }

            // Datenzeilen
            for (int r = 0; r < tableView.getItems().size(); r++) {
                Map<String, String> rowMap = tableView.getItems().get(r);
                org.apache.poi.ss.usermodel.Row excelRow = sheet.createRow(r + 1);

                for (int c = 0; c < tableView.getColumns().size(); c++) {
                    TableColumn<Map<String, String>, ?> tc = tableView.getColumns().get(c);
                    String colKey = tc.getText();
                    String value = rowMap.get(colKey);
                    excelRow.createCell(c).setCellValue(value != null ? value : "");
                }
            }

            // Spaltenbreite an Inhalt anpassen (optional)
            for (int c = 0; c < tableView.getColumns().size(); c++) {
                sheet.autoSizeColumn(c);
            }

            fos = new FileOutputStream(file);
            workbook.write(fos);

            Alert ok = new Alert(Alert.AlertType.INFORMATION, "Export erfolgreich:\n" + file.getAbsolutePath());
            ok.setHeaderText(null);
            ok.showAndWait();

        } catch (Exception ex) {
            ex.printStackTrace();
            Alert err = new Alert(Alert.AlertType.ERROR, "Fehler beim Export: " + ex.getMessage());
            err.setHeaderText(null);
            err.showAndWait();
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignore) {}
            try { if (workbook != null) workbook.close(); } catch (Exception ignore) {}
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
