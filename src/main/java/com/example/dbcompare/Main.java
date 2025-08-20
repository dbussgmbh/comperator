package com.example.dbcompare;

import de.dbuss.util.DB_Functions;
import de.dbuss.util.StringUtils;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.FileNotFoundException;
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


/*
        String original = "Hallo Welt!";
        String reversed = StringUtils.reverse(original);
        System.out.println("Original: " + original);
        System.out.println("Umgedreht: " + reversed);

        System.out.println(DB_Functions.sagHallo("Michael"));
*/

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
        Button refreshButton = new Button("🔄 Refresh");
        refreshButton.setOnAction(e -> refreshTable());

        root.getChildren().addAll(refreshButton, tableView);
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
        if (!isNullOrBlank(externalPath)) {         // statt: externalPath != null && !externalPath.isBlank()
            Path path = Paths.get(externalPath);
            if (!Files.exists(path)) {
                throw new FileNotFoundException("Konfigdatei nicht gefunden: " + path);
            }
            try (InputStream in = Files.newInputStream(path)) {
                p.load(in);
                return p;
            }
        }

        // 2) Klassenpfad-Ressource
//        try (InputStream in = Main.class.getClassLoader().getResourceAsStream("dbcompare.properties")) {
//            if (in != null) {
//
//                p.load(in);
//                return p;
//            }
//        }

        throw new FileNotFoundException(
                "Keine Konfigurationsdatei gefunden. " +
                        "Lege 'dbcompare.properties' auf den Klassenpfad ODER starte mit -Ddbcompare.config=/pfad/zu/datei.properties."
        );
    }

    /** Holt einen Pflicht-Property-Wert, sonst Exception. */
    private static String getRequired(Properties p, String key) {
        String v = p.getProperty(key);
        if (isNullOrBlank(v)) {                 // statt: v == null || v.isBlank()
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
                Map<String, String> row = new HashMap<>();
                row.put("SQL", qm.getSql());
                for (String db : qm.getDbKuerzel()) {
                    if (dbMap.containsKey(db)) {
                        String[] parts = dbMap.get(db).split(";");
                        String jdbcUrl = parts[0];
                        String user = parts[1];
                        String pass = parts[2];

                        System.out.printf("==> Kürzel: %s → URL: %s | USER: %s | PASS: %s%n",
                                db, jdbcUrl, user, pass);

                        String result = DBQueryExecutor.execute(jdbcUrl, user, pass, qm.getSql());
                        row.put(db, result);
                    } else {
                        row.put(db, "Unbekannt");
                    }
                }
                tableView.getItems().add(row);
            }

            Set<String> allKeys = new HashSet<>();
            for (Map<String, String> row : tableView.getItems()) {
                allKeys.addAll(row.keySet());
            }

            for (String key : allKeys) {
                TableColumn<Map<String, String>, String> col = new TableColumn<>(key);
                col.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                        data.getValue().getOrDefault(key, "")
                ));

                // Highlight differences (only for DB columns, not SQL)
                if (!key.equals("SQL")) {
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
                }



                tableView.getColumns().add(col);
            }

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}