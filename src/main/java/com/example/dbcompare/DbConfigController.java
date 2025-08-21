package com.example.dbcompare;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.Region;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class DbConfigController {

    @FXML private TableView<DBConfigEntry> table;
    @FXML private TableColumn<DBConfigEntry, String> colKuerzel;
    @FXML private TableColumn<DBConfigEntry, String> colUrl;
    @FXML private TableColumn<DBConfigEntry, String> colUser;
    @FXML private TableColumn<DBConfigEntry, String> colPass;
    @FXML private CheckBox showPasswords;

    private final ObservableList<DBConfigEntry> data = FXCollections.observableArrayList();
    private Connection oracleConnection;
    private DBConfigDao dao;

    public void setConnection(Connection oracleConnection) {
        this.oracleConnection = oracleConnection;
        this.dao = new DBConfigDao(oracleConnection);
        onRefresh();
    }

    @FXML
    public void initialize() {
        table.setItems(data);
        table.setEditable(true);
        table.getSelectionModel().setCellSelectionEnabled(true);

        // KÜRZEL – commit on focus loss
        colKuerzel.setEditable(true);
        colKuerzel.setCellValueFactory(c -> c.getValue().kuerzelProperty());
        colKuerzel.setCellFactory(col ->
                new EditingTextCell(
                        row -> row.getKuerzel(),
                        (row, val) -> row.setKuerzel(val)
                )
        );
        colKuerzel.setOnEditCommit(e -> e.getRowValue().setKuerzel(e.getNewValue()));

        // DB_URL – commit on focus loss
        colUrl.setEditable(true);
        colUrl.setCellValueFactory(c -> c.getValue().dbUrlProperty());
        colUrl.setCellFactory(col ->
                new EditingTextCell(
                        row -> row.getDbUrl(),
                        (row, val) -> row.setDbUrl(val)
                )
        );
        colUrl.setOnEditCommit(e -> e.getRowValue().setDbUrl(e.getNewValue()));

        // USERNAME – commit on focus loss
        colUser.setEditable(true);
        colUser.setCellValueFactory(c -> c.getValue().usernameProperty());
        colUser.setCellFactory(col ->
                new EditingTextCell(
                        row -> row.getUsername(),
                        (row, val) -> row.setUsername(val)
                )
        );
        colUser.setOnEditCommit(e -> e.getRowValue().setUsername(e.getNewValue()));

        // PASS – maskiert, commit on focus loss
        colPass.setEditable(true);
        colPass.setCellValueFactory(c -> c.getValue().passProperty());
        colPass.setCellFactory(col -> new PasswordEditingCell(() -> showPasswords.isSelected()));
        colPass.setOnEditCommit(e -> e.getRowValue().setPass(e.getNewValue()));

        // Context-Menü
        table.setRowFactory(tv -> {
            TableRow<DBConfigEntry> row = new TableRow<>();
            ContextMenu cm = new ContextMenu();
            MenuItem miAdd = new MenuItem("Neu");
            miAdd.setOnAction(a -> onAddRow());
            MenuItem miDel = new MenuItem("Löschen");
            miDel.setOnAction(a -> onDeleteSelected());
            cm.getItems().addAll(miAdd, miDel);
            row.contextMenuProperty().bind(javafx.beans.binding.Bindings
                    .when(row.emptyProperty()).then((ContextMenu)null).otherwise(cm));
            return row;
        });
    }

    @FXML
    public void onAddRow() {
        DBConfigEntry e = new DBConfigEntry("NEU", "jdbc:oracle:thin:@host:1521:SID", "USER", "");
        data.add(0, e);
        table.getSelectionModel().select(e);
        table.scrollTo(e);
    }

    @FXML
    public void onDeleteSelected() {
        DBConfigEntry sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Eintrag '" + sel.getKuerzel() + "' wirklich löschen?");
        confirm.setHeaderText("Löschen bestätigen");
        Optional<ButtonType> res = confirm.showAndWait();
        if (res.isPresent() && res.get() == ButtonType.OK) {
            try {
                if (dao != null && sel.getKuerzel() != null && !sel.getKuerzel().trim().isEmpty()) {
                    dao.deleteByKuerzel(sel.getKuerzel());
                }
                data.remove(sel);
            } catch (SQLException ex) {
                showError("Fehler beim Löschen", ex);
            }
        }
    }

    @FXML
    public void onSaveAll() {
        commitOngoingEdits();

        if (dao == null) return;
        try {
            oracleConnection.setReadOnly(false);
            boolean oldAuto = oracleConnection.getAutoCommit();
            oracleConnection.setAutoCommit(false);

            int updated = 0;
            StringBuilder log = new StringBuilder();

            try {
                // Info: in welches Schema schreiben wir?
                try (Statement st = oracleConnection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT USER FROM DUAL")) {
                    if (rs.next()) {
                        log.append("Aktuelles DB-User-Schema: ").append(rs.getString(1)).append("\n");
                    }
                }

                for (DBConfigEntry e : data) {
                    if (e.getKuerzel() == null || e.getKuerzel().trim().isEmpty()) continue;
                    int changed = dao.upsert(e);
                    updated += changed;
                    log.append(String.format("UPSERT KUERZEL='%s' URL='%s' USER='%s' -> changed=%d%n",
                            safe(e.getKuerzel()), safe(e.getDbUrl()), safe(e.getUsername()), changed));
                }

                oracleConnection.commit();
                onRefresh();

                Alert ok = new Alert(Alert.AlertType.INFORMATION,
                        "Gespeichert: " + updated + " Eintrag(e).\n\nDetails:\n" + log);
                ok.setHeaderText("Speichern erfolgreich");
                ok.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
                ok.showAndWait();

            } catch (Exception ex) {
                oracleConnection.rollback();
                throw ex;
            } finally {
                oracleConnection.setAutoCommit(oldAuto);
            }
        } catch (Exception ex) {
            showError("Fehler beim Speichern", ex);
        }
    }

    @FXML
    public void onRefresh() {
        if (dao == null) return;
        try {
            data.setAll(dao.listAll());
        } catch (SQLException ex) {
            showError("Fehler beim Laden", ex);
        }
    }

    @FXML
    public void onTogglePasswords() {
        table.refresh();
    }

    private void commitOngoingEdits() {
        // 1) Fokus weg → committet offene TextField-Edits
        if (table.getScene() != null && table.getScene().getRoot() != null) {
            table.getScene().getRoot().requestFocus();
        }
        // 2) Edit-Modus sicher verlassen
        try { table.edit(-1, null); } catch (Exception ignore) {}
    }

    private void showError(String title, Exception ex) {
        ex.printStackTrace();
        Alert a = new Alert(Alert.AlertType.ERROR, ex.getMessage());
        a.setHeaderText(title);
        a.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        a.showAndWait();
    }

    private static String safe(String s) { return s == null ? "" : s; }

    /* ---------- Generische Text-Editing-Zelle (commit on focus loss + ENTER) ---------- */

    /**
     * Generische Textzelle, die beim ENTER und bei Fokus-Verlust
     * den Wert DIREKT ins Modell schreibt und zusätzlich commitEdit(...) aufruft.
     * Verhindert das „Änderung wird verworfen“-Problem.
     */
    private static class EditingTextCell extends TableCell<DBConfigEntry, String> {
        private final Function<DBConfigEntry, String> getter;
        private final BiConsumer<DBConfigEntry, String> setter;
        private TextField textField;

        EditingTextCell(Function<DBConfigEntry, String> getter,
                        BiConsumer<DBConfigEntry, String> setter) {
            this.getter = getter;
            this.setter = setter;
        }

        @Override
        public void startEdit() {
            if (!isEditable() || !getTableView().isEditable() || !getTableColumn().isEditable()) return;
            super.startEdit();
            if (textField == null) createTextField();
            textField.setText(currentModelValue());
            setGraphic(textField);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            textField.selectAll();
            textField.requestFocus();
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            setText(currentModelValue());
            setGraphic(null);
            setContentDisplay(ContentDisplay.TEXT_ONLY);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setText(null);
                setGraphic(null);
                setContentDisplay(ContentDisplay.TEXT_ONLY);
            } else if (isEditing()) {
                if (textField == null) createTextField();
                textField.setText(currentModelValue());
                setGraphic(textField);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            } else {
                setText(currentModelValue());
                setGraphic(null);
                setContentDisplay(ContentDisplay.TEXT_ONLY);
            }
        }

        private void createTextField() {
            textField = new TextField(currentModelValue());
            // ENTER -> commit
            textField.setOnAction(e -> commitFromTextField());
            // ESC -> cancel
            textField.setOnKeyPressed(e -> {
                switch (e.getCode()) {
                    case ESCAPE:
                        cancelEdit();
                        e.consume();
                        break;
                    default:
                        // no-op
                }
            });
            // Fokus verloren -> commit
            textField.focusedProperty().addListener((obs, was, is) -> {
                if (!is) {
                    commitFromTextField();
                }
            });
        }

        private void commitFromTextField() {
            String newVal = textField.getText();
            DBConfigEntry row = getRowModel();
            if (row != null) {
                setter.accept(row, newVal); // direkt ins Modell schreiben
            }
            try {
                commitEdit(newVal);         // TableView-internen Zustand aktualisieren
            } catch (Exception ignore) {}
            setText(newVal);
            setGraphic(null);
            setContentDisplay(ContentDisplay.TEXT_ONLY);
        }

        private DBConfigEntry getRowModel() {
            int idx = getIndex();
            if (idx < 0 || idx >= getTableView().getItems().size()) return null;
            return getTableView().getItems().get(idx);
        }

        private String currentModelValue() {
            DBConfigEntry row = getRowModel();
            String raw = (row == null) ? "" : getter.apply(row);
            return raw == null ? "" : raw;
        }
    }

    /* ---------- Passwort-Zelle (Maskierung + commit on focus loss + ENTER) ---------- */

    private static class PasswordEditingCell extends TableCell<DBConfigEntry, String> {
        private final Supplier<Boolean> showPlain;
        private TextField textField;

        PasswordEditingCell(Supplier<Boolean> showPlain) {
            this.showPlain = showPlain;
        }

        @Override
        public void startEdit() {
            if (!isEditable() || !getTableView().isEditable() || !getTableColumn().isEditable()) return;
            super.startEdit();
            if (textField == null) createTextField();
            textField.setText(currentModelValue());
            setGraphic(textField);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            textField.selectAll();
            textField.requestFocus();
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            setText(renderValue(currentModelValue()));
            setGraphic(null);
            setContentDisplay(ContentDisplay.TEXT_ONLY);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setText(null);
                setGraphic(null);
                setContentDisplay(ContentDisplay.TEXT_ONLY);
            } else if (isEditing()) {
                if (textField == null) createTextField();
                textField.setText(currentModelValue());
                setGraphic(textField);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            } else {
                setText(renderValue(currentModelValue()));
                setGraphic(null);
                setContentDisplay(ContentDisplay.TEXT_ONLY);
            }
        }

        private void createTextField() {
            textField = new TextField(currentModelValue());
            textField.setOnAction(e -> commitFromTextField()); // ENTER
            textField.setOnKeyPressed(e -> {
                switch (e.getCode()) {
                    case ESCAPE:
                        cancelEdit();
                        e.consume();
                        break;
                    default:
                        // no-op
                }
            });
            textField.focusedProperty().addListener((obs, was, is) -> {
                if (!is) {
                    commitFromTextField(); // Fokus verloren -> commit
                }
            });
        }

        private void commitFromTextField() {
            String newVal = textField.getText();
            DBConfigEntry row = getRowModel();
            if (row != null) {
                row.setPass(newVal);   // direkt ins Modell
            }
            try { commitEdit(newVal); } catch (Exception ignore) {}
            setText(renderValue(newVal));
            setGraphic(null);
            setContentDisplay(ContentDisplay.TEXT_ONLY);
        }

        private DBConfigEntry getRowModel() {
            int idx = getIndex();
            if (idx < 0 || idx >= getTableView().getItems().size()) return null;
            return getTableView().getItems().get(idx);
        }

        private String currentModelValue() {
            DBConfigEntry row = getRowModel();
            String raw = (row == null) ? "" : row.getPass();
            return raw == null ? "" : raw;
        }

        private String renderValue(String raw) {
            if (raw == null) raw = "";
            if (showPlain != null && showPasswordsEnabled()) return raw;
            return maskBullet(raw, 12);
        }

        private boolean showPasswordsEnabled() {
            try { return showPlain.get(); } catch (Exception e) { return false; }
        }

        private static String maskBullet(String s, int maxLen) {
            int len = Math.min(s.length(), maxLen);
            char[] arr = new char[len];
            java.util.Arrays.fill(arr, '•');
            return new String(arr);
        }
    }
}
