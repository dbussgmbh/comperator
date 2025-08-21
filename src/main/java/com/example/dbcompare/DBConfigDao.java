// src/main/java/com/example/dbcompare/DBConfigDao.java
package com.example.dbcompare;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DBConfigDao {
    private final Connection conn;

    public DBConfigDao(Connection conn) {
        this.conn = conn;
    }

    public List<DBConfigEntry> listAll() throws SQLException {
        final String sql = "SELECT KUERZEL, DB_URL, USERNAME, PASS FROM DB_CONFIG ORDER BY KUERZEL";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<DBConfigEntry> list = new ArrayList<>();
            while (rs.next()) {
                String encPass = rs.getString("PASS");
                String plainPass = CryptoUtil.decryptToString(encPass); // <<< NEU
                list.add(new DBConfigEntry(
                        trim(rs.getString("KUERZEL")),
                        trim(rs.getString("DB_URL")),
                        trim(rs.getString("USERNAME")),
                        plainPass == null ? "" : plainPass
                ));
            }
            return list;
        }
    }

    /** @return Anzahl betroffener Zeilen (1 bei Update/Insert) */
    public int upsert(DBConfigEntry e) throws SQLException {
        String encPass = CryptoUtil.encryptToString(nv(e.getPass())); // <<< NEU

        int changed;
        try (PreparedStatement upd = conn.prepareStatement(
                "UPDATE DB_CONFIG SET DB_URL=?, USERNAME=?, PASS=? WHERE KUERZEL=?")) {
            upd.setString(1, nv(e.getDbUrl()));
            upd.setString(2, nv(e.getUsername()));
            upd.setString(3, encPass);
            upd.setString(4, nv(e.getKuerzel()));
            changed = upd.executeUpdate();
        }

        if (changed == 0) {
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO DB_CONFIG (KUERZEL, DB_URL, USERNAME, PASS) VALUES (?,?,?,?)")) {
                ins.setString(1, nv(e.getKuerzel()));
                ins.setString(2, nv(e.getDbUrl()));
                ins.setString(3, nv(e.getUsername()));
                ins.setString(4, encPass);
                changed = ins.executeUpdate();
            }
        }
        return changed;
    }

    public void deleteByKuerzel(String kuerzel) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM DB_CONFIG WHERE KUERZEL=?")) {
            ps.setString(1, kuerzel);
            ps.executeUpdate();
        }
    }

    private static String trim(String s) { return s == null ? null : s.trim(); }
    private static String nv(String s) { return s == null ? "" : s; }
}
