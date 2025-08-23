package com.example.dbcompare;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.sql.*;
import java.util.*;

/**
 * Editor fuer die Oracle-Tabelle ABFRAGEN (Spalten: QUERY_ID, SQL_TEXT, DB_KUERZEL).
 * - Laedt bestehende Zeilen (via ROWID) und merkt sich MAX(QUERY_ID)
 * - Neue Zeilen werden mit MAX(QUERY_ID)+1 vorbelegt
 * - Editieren mit Commit bei Fokusverlust/Enter/Tab (bidirektionale Bindung)
 * - Speichern fuehrt INSERT/UPDATE/DELETE in einer Transaktion aus
 */
public class AbfragenEditor {

    // Merker fuer die aktuell groesste QUERY_ID (wird bei jedem Laden neu bestimmt)
    private static int lastMaxId = 0;

    public static void show(Stage owner, Connection oracleConn) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle("ABFRAGEN bearbeiten");
        stage.initModality(Modality.WINDOW_MODAL);

        TableView<Row> table = new TableView<>();
        table.setEditable(true);

        ObservableList<Row> data = FXCollections.observableArrayList();

        // QUERY_ID (read-only im UI)
        TableColumn<Row, String> idCol = new TableColumn<>("QUERY_ID");
        idCol.setPrefWidth(100);
        idCol.setEditable(false);
        idCol.setCellValueFactory(c -> c.getValue().queryIdProperty);

        // SQL_TEXT
        TableColumn<Row, String> sqlCol = new TableColumn<>("SQL_TEXT");
        sqlCol.setPrefWidth(600);
        sqlCol.setEditable(true);
        sqlCol.setCellValueFactory(c -> c.getValue().sqlTextProperty);
        sqlCol.setCellFactory(col -> new WrappingTextFieldCell());
        sqlCol.setOnEditCommit(ev -> {
            Row r = ev.getTableView().getItems().get(ev.getTablePosition().getRow());
            r.sqlTextProperty.set(ev.getNewValue());
            r.dirty = true;
        });

        // DB_KUERZEL
        TableColumn<Row, String> dbCol = new TableColumn<>("DB_KUERZEL");
        dbCol.setPrefWidth(250);
        dbCol.setEditable(true);
        dbCol.setCellValueFactory(c -> c.getValue().dbKuerzelProperty);
        dbCol.setCellFactory(col -> new FocusCommitTextFieldCell());
        dbCol.setOnEditCommit(ev -> {
            Row r = ev.getTableView().getItems().get(ev.getTablePosition().getRow());
            r.dbKuerzelProperty.set(ev.getNewValue());
            r.dirty = true;
        });

       // ROWID (read-only)
//        TableColumn<Row, String> ridCol = new TableColumn<>("ROWID");
//        ridCol.setPrefWidth(260);
//        ridCol.setEditable(false);
//        ridCol.setCellValueFactory(c -> c.getValue().rowIdProperty);

      //  table.getColumns().addAll(idCol, sqlCol, dbCol, ridCol);
        table.getColumns().addAll(idCol, sqlCol, dbCol);
        table.setItems(data);

        // Toolbar
        Button addBtn = new Button("Neu");
        Button delBtn = new Button("Loeschen");
        Button saveBtn = new Button("Speichern");
        Button reloadBtn = new Button("Neu laden");

        HBox toolbar = new HBox(8, addBtn, delBtn, new Separator(), saveBtn, reloadBtn);
        toolbar.setPadding(new Insets(8));

        BorderPane root = new BorderPane(table);
        root.setTop(toolbar);

        Set<String> toDeleteRowIds = new HashSet<>();

        addBtn.setOnAction(e -> {
            int newId = lastMaxId + 1;
            lastMaxId = newId; // direkt hochzaehlen, damit mehrere neue Zeilen fortlaufend sind
            Row r = Row.newRow(newId);
            data.add(r);
            table.getSelectionModel().select(r);
            table.scrollTo(r);
        });

        delBtn.setOnAction(e -> {
            Row r = table.getSelectionModel().getSelectedItem();
            if (r == null) return;
            String rid = r.rowIdProperty.get();
            if (rid != null && !rid.isEmpty()) {
                toDeleteRowIds.add(rid);
            }
            data.remove(r);
        });

        saveBtn.setOnAction(e -> {
            try {
                saveChanges(oracleConn, data, toDeleteRowIds);
                toDeleteRowIds.clear();
                loadData(oracleConn, data);
                new Alert(Alert.AlertType.INFORMATION, "Aenderungen gespeichert.").showAndWait();
            } catch (Exception ex) {
                ex.printStackTrace();
                new Alert(Alert.AlertType.ERROR, "Fehler beim Speichern: " + ex.getMessage()).showAndWait();
            }
        });

        reloadBtn.setOnAction(e -> {
            try {
                toDeleteRowIds.clear();
                loadData(oracleConn, data);
            } catch (Exception ex) {
                ex.printStackTrace();
                new Alert(Alert.AlertType.ERROR, "Fehler beim Laden: " + ex.getMessage()).showAndWait();
            }
        });

        // Tastaturkuerzel
        Scene scene = new Scene(root, 1150, 600);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN), saveBtn::fire);
        scene.setOnKeyPressed(ke -> {
            if (ke.getCode() == KeyCode.DELETE) {
                delBtn.fire();
            }
        });

        // Initial laden
        try {
            loadData(oracleConn, data);
        } catch (Exception ex) {
            ex.printStackTrace();
            new Alert(Alert.AlertType.ERROR, "Fehler beim Initial-Laden: " + ex.getMessage()).showAndWait();
        }

        stage.setScene(scene);
        stage.showAndWait();
    }

    private static void loadData(Connection conn, ObservableList<Row> data) throws SQLException {
        data.clear();
        lastMaxId = 0;
        String sql = "SELECT QUERY_ID, ROWID AS RID, SQL_TEXT, DB_KUERZEL FROM ABFRAGEN ORDER BY QUERY_ID";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String qid = rs.getString("QUERY_ID");
                String rid = rs.getString("RID");
                String sqlText = rs.getString("SQL_TEXT");
                String db = rs.getString("DB_KUERZEL");
                data.add(Row.fromDb(qid, rid, sqlText, db));

                int idVal = rs.getInt("QUERY_ID");
                if (!rs.wasNull() && idVal > lastMaxId) lastMaxId = idVal;
            }
        }
    }

    private static void saveChanges(Connection conn, List<Row> rows, Set<String> toDeleteRowIds) throws SQLException {
        conn.setAutoCommit(false);
        try {
            // DELETEs
            if (!toDeleteRowIds.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ABFRAGEN WHERE ROWID = ?")) {
                    for (String rid : toDeleteRowIds) {
                        ps.setString(1, rid);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }

            // UPDATEs (nur geaenderte, nicht-neue) - QUERY_ID bleibt unveraendert
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ABFRAGEN SET SQL_TEXT = ?, DB_KUERZEL = ? WHERE ROWID = ?")) {
                for (Row r : rows) {
                    if (!r.isNew && r.dirty) {
                        ps.setString(1, nullIfBlank(r.sqlTextProperty.get()));
                        ps.setString(2, nullIfBlank(r.dbKuerzelProperty.get()));
                        ps.setString(3, r.rowIdProperty.get());
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }

            // INSERTs (neue)
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ABFRAGEN (QUERY_ID, SQL_TEXT, DB_KUERZEL) VALUES (?, ?, ?)")) {
                for (Row r : rows) {
                    if (r.isNew) {
                        int qid = safeParseInt(r.queryIdProperty.get(), lastMaxId + 1);
                        ps.setInt(1, qid);
                        ps.setString(2, nullIfBlank(r.sqlTextProperty.get()));
                        ps.setString(3, nullIfBlank(r.dbKuerzelProperty.get()));
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }

            conn.commit();
        } catch (SQLException ex) {
            conn.rollback();
            throw ex;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private static int safeParseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    // --- Datenmodell ---
    public static class Row {
        final SimpleStringProperty queryIdProperty = new SimpleStringProperty("");
        final SimpleStringProperty rowIdProperty   = new SimpleStringProperty("");
        final SimpleStringProperty sqlTextProperty = new SimpleStringProperty("");
        final SimpleStringProperty dbKuerzelProperty = new SimpleStringProperty("");
        boolean isNew = false;
        boolean dirty = false;

        static Row fromDb(String queryId, String rowId, String sqlText, String db) {
            Row r = new Row();
            r.queryIdProperty.set(queryId == null ? "" : queryId);
            r.rowIdProperty.set(rowId);
            r.sqlTextProperty.set(sqlText == null ? "" : sqlText);
            r.dbKuerzelProperty.set(db == null ? "" : db);
            r.isNew = false;
            r.dirty = false;
            return r;
        }

        static Row newRow(int newId) {
            Row r = new Row();
            r.queryIdProperty.set(String.valueOf(newId));
            r.rowIdProperty.set("");
            r.sqlTextProperty.set("");
            r.dbKuerzelProperty.set("");
            r.isNew = true;
            r.dirty = true;
            return r;
        }
    }

    /** TextArea-Zelle fuer SQL_TEXT: bidirektionale Bindung + Commit bei Enter/Tab/Fokusverlust */
    private static class WrappingTextFieldCell extends TableCell<Row, String> {
        private TextArea textArea;
        private boolean bound = false;

        @Override
        public void startEdit() {
            if (isEmpty()) return;
            super.startEdit();
            if (textArea == null) createTextArea();

            Row r = getTableView().getItems().get(getIndex());
            if (!bound && r != null) {
                textArea.textProperty().bindBidirectional(r.sqlTextProperty);
                bound = true;
            }

            setGraphic(textArea);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            textArea.requestFocus();
            String txt = textArea.getText();
            textArea.positionCaret(txt == null ? 0 : txt.length());
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            unbindIfNeeded();
            setText(getItem());
            setContentDisplay(ContentDisplay.TEXT_ONLY);
            setGraphic(null);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setText(null);
                setGraphic(null);
            } else if (isEditing()) {
                setGraphic(textArea);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            } else {
                setText(item);
                setContentDisplay(ContentDisplay.TEXT_ONLY);
            }
            setWrapText(true);
        }

        private void createTextArea() {
            textArea = new TextArea();
            textArea.setWrapText(true);
            textArea.setPrefRowCount(4);
            textArea.setOnKeyPressed(ke -> {
                if (ke.getCode() == KeyCode.ENTER && ke.isShiftDown()) {
                    int caret = textArea.getCaretPosition();
                    textArea.insertText(caret, "\n");
                    ke.consume();
                } else if (ke.getCode() == KeyCode.ENTER) {
                    commitEdit(textArea.getText());
                    ke.consume();
                } else if (ke.getCode() == KeyCode.TAB) {
                    commitEdit(textArea.getText());
                    TableView<Row> tv = getTableView();
                    if (tv != null) tv.getSelectionModel().selectRightCell();
                    ke.consume();
                } else if (ke.getCode() == KeyCode.ESCAPE) {
                    cancelEdit();
                    ke.consume();
                }
            });
            textArea.focusedProperty().addListener((obs, had, has) -> {
                if (!has && isEditing()) commitEdit(textArea.getText());
            });
        }

        private void unbindIfNeeded() {
            if (bound) {
                Row r = getTableView().getItems().get(getIndex());
                if (r != null) {
                    textArea.textProperty().unbindBidirectional(r.sqlTextProperty);
                }
                bound = false;
            }
        }

        @Override
        public void commitEdit(String newValue) {
            super.commitEdit(newValue);
            unbindIfNeeded();
            setText(newValue);
            setContentDisplay(ContentDisplay.TEXT_ONLY);
            setGraphic(null);
        }
    }

    /** TextField-Zelle fuer DB_KUERZEL: bidirektionale Bindung + Commit bei Enter/Tab/Fokusverlust */
    private static class FocusCommitTextFieldCell extends TableCell<Row, String> {
        private TextField tf;
        private boolean bound = false;

        @Override
        public void startEdit() {
            if (isEmpty()) return;
            super.startEdit();
            if (tf == null) createTf();

            Row r = getTableView().getItems().get(getIndex());
            if (!bound && r != null) {
                tf.textProperty().bindBidirectional(r.dbKuerzelProperty);
                bound = true;
            }

            setGraphic(tf);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            tf.requestFocus();
            tf.end();
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            unbindIfNeeded();
            setText(getItem());
            setContentDisplay(ContentDisplay.TEXT_ONLY);
            setGraphic(null);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setText(null);
                setGraphic(null);
            } else if (isEditing()) {
                setGraphic(tf);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            } else {
                setText(item);
                setContentDisplay(ContentDisplay.TEXT_ONLY);
            }
        }

        private void createTf() {
            tf = new TextField();
            tf.setOnAction(e -> commitEdit(tf.getText()));
            tf.focusedProperty().addListener((o, had, has) -> {
                if (!has && isEditing()) commitEdit(tf.getText());
            });
            tf.setOnKeyPressed(ke -> {
                if (ke.getCode() == KeyCode.TAB) {
                    commitEdit(tf.getText());
                    TableView<Row> tv = getTableView();
                    if (tv != null) tv.getSelectionModel().selectRightCell();
                    ke.consume();
                } else if (ke.getCode() == KeyCode.ESCAPE) {
                    cancelEdit();
                    ke.consume();
                }
            });
        }

        private void unbindIfNeeded() {
            if (bound) {
                Row r = getTableView().getItems().get(getIndex());
                if (r != null) {
                    tf.textProperty().unbindBidirectional(r.dbKuerzelProperty);
                }
                bound = false;
            }
        }

        @Override
        public void commitEdit(String newValue) {
            super.commitEdit(newValue);
            unbindIfNeeded();
            setText(newValue);
            setContentDisplay(ContentDisplay.TEXT_ONLY);
            setGraphic(null);
        }
    }
}
