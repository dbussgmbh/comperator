package com.example.dbcompare;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.sql.*;
import java.util.*;



public class Main extends Application {

    private TableView<Map<String, String>> tableView = new TableView<>();
    private DBConfigResolver resolver;
    private Map<String, String> dbMap;

    @Override
    public void start(Stage primaryStage) throws Exception {
        Connection oracleConn = DriverManager.getConnection(
                "jdbc:oracle:thin:@37.120.189.200:1521:xe", "EKP_MONITOR", "xxxxxx");

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