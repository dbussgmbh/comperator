package com.example.dbcompare;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class DBConfigEntry {
    private final StringProperty kuerzel = new SimpleStringProperty();
    private final StringProperty dbUrl   = new SimpleStringProperty();
    private final StringProperty username= new SimpleStringProperty();
    private final StringProperty pass    = new SimpleStringProperty();

    public DBConfigEntry() {}
    public DBConfigEntry(String kuerzel, String dbUrl, String username, String pass) {
        setKuerzel(kuerzel);
        setDbUrl(dbUrl);
        setUsername(username);
        setPass(pass);
    }

    public String getKuerzel() { return kuerzel.get(); }
    public void setKuerzel(String v) { kuerzel.set(v); }
    public StringProperty kuerzelProperty() { return kuerzel; }

    public String getDbUrl() { return dbUrl.get(); }
    public void setDbUrl(String v) { dbUrl.set(v); }
    public StringProperty dbUrlProperty() { return dbUrl; }

    public String getUsername() { return username.get(); }
    public void setUsername(String v) { username.set(v); }
    public StringProperty usernameProperty() { return username; }

    public String getPass() { return pass.get(); }
    public void setPass(String v) { pass.set(v); }
    public StringProperty passProperty() { return pass; }
}
