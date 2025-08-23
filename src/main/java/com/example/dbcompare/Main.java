package com.example.dbcompare;

import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public class Main extends Application {

    private TableView<Map<String, String>> tableView = new TableView<>();
    private DBConfigResolver resolver;
    private Map<String, String> dbMap;
    private Connection oracleConn;

    // UI-Elemente für Busy-Overlay
    private ProgressIndicator busy;
    private Label busyLabel;
    private StackPane overlay;
    private HBox topBar;

    @Override
    public void start(Stage primaryStage) throws Exception {
        // 1) Konfig laden
        Properties props = loadConfig();

        String keyB64 = getRequired(props, "crypto.key");
        byte[] keyBytes = java.util.Base64.getDecoder().decode(keyB64);
        CryptoUtil.init(keyBytes);

        String jdbcUrl = getRequired(props, "oracle.url");
        String user    = getRequired(props, "oracle.user");
        String pass    = getRequired(props, "oracle.password");

        // 2) Oracle-Connection
        oracleConn = DriverManager.getConnection(jdbcUrl, user, pass);

        resolver = new DBConfigResolver(oracleConn);
        dbMap = resolver.resolveConnections();

        // --- Layout ---
        VBox content = new VBox();

        // Buttons
        Button refreshButton = new Button("🔄 Refresh");
        refreshButton.setOnAction(e -> refreshTableAsync()); // <-- asynchron

        Button exportButton = new Button("📄 Als Excel exportieren");
        exportButton.setOnAction(e -> exportTableToExcel(tableView));

        Button configButton = new Button("⚙ DB-Config");
        configButton.setOnAction(e -> openDbConfigWindow());

        Button editAbfragenBtn = new Button("📝 ABFRAGEN bearbeiten");
        editAbfragenBtn.setOnAction(e ->
                AbfragenEditor.show((Stage) tableView.getScene().getWindow(), oracleConn)
        );

        topBar = new HBox(8, refreshButton, exportButton, configButton, editAbfragenBtn);
        topBar.setPadding(new Insets(8));

        content.getChildren().addAll(topBar, tableView);
        VBox.setVgrow(tableView, Priority.ALWAYS);

        // Busy-Overlay
        busy = new ProgressIndicator();
        busy.setMaxSize(90, 90);
        busyLabel = new Label("Abfragen werden ausgeführt …");
        busyLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px;");

        VBox overlayBox = new VBox(12, busy, busyLabel);
        overlayBox.setAlignment(Pos.CENTER);

        overlay = new StackPane(overlayBox);
        overlay.setStyle("-fx-background-color: rgba(0,0,0,0.35);");
        overlay.setVisible(false);
        overlay.setMouseTransparent(false); // blockiert Interaktionen darunter

        // Root als StackPane: content + overlay
        StackPane root = new StackPane(content, overlay);

        // initial laden (async)
        refreshTableAsync();

        Scene scene = new Scene(root, 1000, 600);
        primaryStage.setTitle("Datenbank Vergleich");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void openDbConfigWindow() {
        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader();
            java.net.URL fxml = Main.class.getResource("/com/example/dbcompare/DbConfigView.fxml");
            if (fxml == null) {
                throw new IllegalStateException(
                        "FXML nicht gefunden unter /com/example/dbcompare/DbConfigView.fxml.\n" +
                                "Liegt die Datei in src/main/resources/com/example/dbcompare/ ?");
            }
            loader.setLocation(fxml);
            javafx.scene.Parent root = loader.load();

            DbConfigController controller = loader.getController();
            controller.setConnection(oracleConn);

            Stage stage = new Stage();
            stage.setTitle("DB_CONFIG verwalten");
            stage.setScene(new Scene(root, 900, 500));
            stage.initOwner(tableView.getScene().getWindow());
            stage.showAndWait();

            // Nach eventuellen Änderungen: DB-Mapping & Haupttabelle neu laden
            resolver = new DBConfigResolver(oracleConn);
            dbMap = resolver.resolveConnections();
            refreshTableAsync();
        } catch (Exception ex) {
            ex.printStackTrace();
            new Alert(Alert.AlertType.ERROR, "Konnte DB-Config nicht öffnen:\n" + ex.getMessage()).showAndWait();
        }
    }

    private static boolean isNullOrBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Lädt Konfiguration aus externer Datei (-Ddbcompare.config=...) oder Klassenpfad (dbcompare.properties). */
    private static Properties loadConfig() throws IOException {
        Properties p = new Properties();

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
        throw new FileNotFoundException(
                "Keine Konfigurationsdatei gefunden. " +
                        "Lege 'dbcompare.properties' auf den Klassenpfad ODER starte mit -Ddbcompare.config=/pfad/zu/datei.properties.");
    }

    /** Holt einen Pflicht-Property-Wert, sonst Exception. */
    private static String getRequired(Properties p, String key) {
        String v = p.getProperty(key);
        if (isNullOrBlank(v)) {
            throw new IllegalArgumentException("Fehlender Konfigurationsschlüssel: " + key);
        }
        return v.trim();
    }

    // ======================
    // Async-Refresh mit Overlay + Connection-Reuse + Spaltenreihenfolge
    // ======================

    private void refreshTableAsync() {
        setBusy(true, "Abfragen werden ausgeführt …");

        Task<LoadResult> task = new Task<LoadResult>() {
            @Override
            protected LoadResult call() throws Exception {
                // 1) Oracle lesen
                List<QueryModel> queries = resolver.loadQueries(); // enthält SQL + db-Kürzel-Liste
                Map<String, String> localDbMap = new LinkedHashMap<String, String>(dbMap);

                // Reihenfolge der DB-Spalten (gemäß erstem Auftreten in DB_KUERZEL)
                List<String> orderedDbKeys = buildOrderedDbKeys(queries, localDbMap);

                // Welche DBs werden überhaupt gebraucht?
                Set<String> usedDbs = new LinkedHashSet<String>(orderedDbKeys);

                // 2) EINMAL pro DB verbinden und Connection wiederverwenden
                Map<String, Connection> connections = new LinkedHashMap<String, Connection>();
                try {
                    openConnections(localDbMap, usedDbs, connections);

                    // Fortschritt kalkulieren (pro DB-Ausführung ein Schritt)
                    int totalSteps = 0;
                    for (QueryModel qm : queries) totalSteps += qm.getDbKuerzel().size();
                    if (totalSteps == 0) totalSteps = 1;
                    int step = 0;

                    // 3) Items zusammenbauen (keine UI-Zugriffe!)
                    List<Map<String, String>> items = new ArrayList<Map<String, String>>();
                    for (QueryModel qm : queries) {
                        Map<String, String> row = new LinkedHashMap<String, String>();
                        row.put("SQL", qm.getSql());

                        // Ergebnisse in der gewünschten Spaltenreihenfolge befüllen
                        for (String dbKey : orderedDbKeys) {
                            String value;
                            if (qm.getDbKuerzel().contains(dbKey)) {
                                Connection c = connections.get(dbKey);
                                if (c != null) {
                                    try {
                                        System.out.println("Ausführen SQL: " + qm.getSql() + " (User: " + c.getSchema() + " at " + c.getMetaData().getURL() + ")");
                                        value = executeSql(c, qm.getSql());
                                    } catch (Exception ex) {
                                        value = "Fehler: " + ex.getMessage();
                                    }
                                } else {
                                    value = "Unbekannt";
                                }
                                step++;
                                updateProgress(step, totalSteps);
                                if ((step & 3) == 0) {
                                    updateMessage("Lese DB-Werte … (" + step + "/" + totalSteps + ")");
                                }
                            } else {
                                // diese Query ist für diese DB nicht vorgesehen -> leer lassen
                                value = "";
                            }
                            row.put(dbKey, value);
                        }
                        items.add(row);
                    }
                    return new LoadResult(items, orderedDbKeys);
                } finally {
                    closeConnections(connections);
                }
            }
        };

        // Overlay-Bindings
        busy.progressProperty().bind(task.progressProperty());
        busyLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(e -> {
            busy.progressProperty().unbind();
            busyLabel.textProperty().unbind();
            LoadResult res = task.getValue();
            applyTableData(res.items, res.orderedDbKeys);
            setBusy(false, null);
        });

        task.setOnFailed(e -> {
            busy.progressProperty().unbind();
            busyLabel.textProperty().unbind();
            setBusy(false, null);

            Throwable ex = task.getException();
            ex.printStackTrace();
            new Alert(Alert.AlertType.ERROR,
                    "Fehler beim Laden:\n" + (ex != null ? ex.getMessage() : "unbekannt")).showAndWait();
        });

        new Thread(task, "refreshTableAsync").start();
    }

    /** Spalten-Reihenfolge anhand des ersten Auftretens in DB_KUERZEL über alle Queries. */
    private List<String> buildOrderedDbKeys(List<QueryModel> queries, Map<String, String> localDbMap) {
        LinkedHashSet<String> order = new LinkedHashSet<String>(); // behält Einfügereihenfolge
        for (QueryModel qm : queries) {
            for (String k : qm.getDbKuerzel()) {
                if (localDbMap.containsKey(k)) {
                    order.add(k);
                }
            }
        }
        // Optional: aus dbMap bekannte, aber nie referenzierte DBs hinten anhängen
        for (String k : localDbMap.keySet()) {
            if (!order.contains(k)) order.add(k);
        }
        return new ArrayList<String>(order);
    }

    /** Öffnet pro verwendeter DB genau EINE Connection und legt sie in 'out' ab. */
    private void openConnections(Map<String, String> localDbMap,
                                 Set<String> usedDbs,
                                 Map<String, Connection> out) {
        for (String dbKey : usedDbs) {
            String def = localDbMap.get(dbKey);
            if (def == null) continue;
            String[] parts = def.split(";", -1);
            String jdbcUrl = parts[0];
            String user = parts.length > 1 ? parts[1] : "";
            String pass = parts.length > 2 ? parts[2] : "";
            try {
                Connection c = DriverManager.getConnection(jdbcUrl, user, pass);
                try { c.setReadOnly(true); } catch (Throwable ignore) {}
                out.put(dbKey, c);
            } catch (SQLException ex) {
                out.put(dbKey, null); // als "nicht verfügbar" markieren
            }
        }
    }

    private void closeConnections(Map<String, Connection> connections) {
        for (Map.Entry<String, Connection> e : connections.entrySet()) {
            Connection c = e.getValue();
            if (c != null) {
                try { c.close(); } catch (Exception ignore) {}
            }
        }
    }

    /** Führt das SQL auf der bestehenden Connection aus und liefert ein String-Ergebnis. */
    private String executeSql(Connection conn, String sql) throws SQLException {
        sql = (sql == null) ? "" : sql.trim();
        try (Statement st = conn.createStatement()) {
            boolean hasRs = st.execute(sql);
            if (hasRs) {
                try (ResultSet rs = st.getResultSet()) {
                    if (rs.next()) {
                        Object v = rs.getObject(1);
                        return (v == null) ? "NULL" : String.valueOf(v);
                    } else {
                        return "(keine Zeilen)";
                    }
                }
            } else {
                int upd = st.getUpdateCount();
                return "OK (" + upd + ")";
            }
        }
    }

    private void setBusy(boolean on, String message) {
        overlay.setVisible(on);
        if (topBar != null) topBar.setDisable(on);
        tableView.setDisable(on);
        if (message != null) busyLabel.setText(message);
    }

    /** Baut die TableView-Spalten auf und setzt Items (nur im FX-Thread aufrufen). */
    private void applyTableData(List<Map<String, String>> items, List<String> orderedDbKeys) {
        tableView.getItems().clear();
        tableView.getColumns().clear();

        // SQL-Spalte
        final TableColumn<Map<String, String>, String> sqlCol = new TableColumn<>("SQL");
        sqlCol.setCellValueFactory(data ->
                new javafx.beans.property.SimpleStringProperty(data.getValue().getOrDefault("SQL", "")));
        sqlCol.setPrefWidth(200);
        sqlCol.setMaxWidth(Double.MAX_VALUE);
        sqlCol.setCellFactory(tc -> new TableCell<Map<String, String>, String>() {
            private final Text text = new Text();
            {
                text.wrappingWidthProperty().bind(sqlCol.widthProperty().subtract(16));
                setGraphic(text);
                setPrefHeight(Control.USE_COMPUTED_SIZE);
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                text.setText((empty || item == null) ? "" : item);
            }
        });
        tableView.getColumns().add(sqlCol);

        // DB-Spalten in gewünschter Reihenfolge
        for (final String db : orderedDbKeys) {
            TableColumn<Map<String, String>, String> col = new TableColumn<Map<String, String>, String>(db);
            col.setCellValueFactory(data ->
                    new javafx.beans.property.SimpleStringProperty(data.getValue().getOrDefault(db, "")));
            col.setPrefWidth(220);
            col.setCellFactory(column -> new TableCell<Map<String, String>, String>() {
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
            });
            tableView.getColumns().add(col);
        }

        tableView.getItems().addAll(items);
        tableView.setFixedCellSize(-1);
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

            // Kopfzeile
            org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
            for (int c = 0; c < tableView.getColumns().size(); c++) {
                TableColumn<Map<String, String>, ?> tc = tableView.getColumns().get(c);
                org.apache.poi.ss.usermodel.Cell cell = headerRow.createCell(c);
                cell.setCellValue(tc.getText() != null ? tc.getText() : "");
                cell.setCellStyle(headerStyle);
            }

            // Daten
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

            // Breite
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

    // --------- DTO für Task-Ergebnis ----------
    private static class LoadResult {
        final List<Map<String, String>> items;
        final List<String> orderedDbKeys;
        LoadResult(List<Map<String, String>> items, List<String> orderedDbKeys) {
            this.items = items;
            this.orderedDbKeys = orderedDbKeys;
        }
    }
}
