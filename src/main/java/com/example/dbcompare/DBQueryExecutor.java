package com.example.dbcompare;

import java.sql.*;

public class DBQueryExecutor {

    public static String execute(String jdbcUrl, String user, String pass, String sql) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, pass);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData meta = rs.getMetaData();
            StringBuilder sb = new StringBuilder();

            int rowCount = 0;
            while (rs.next()) {
                rowCount++;
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    sb.append(rs.getString(i));
                    if (i < meta.getColumnCount()) sb.append(" | ");
                }
                if (rowCount < rs.getFetchSize()) sb.append("\n");
            }

            return sb.length() > 0 ? sb.toString() : "<leer>";
        } catch (Exception e) {
            return "Fehler: " + e.getMessage();
        }
    }
}