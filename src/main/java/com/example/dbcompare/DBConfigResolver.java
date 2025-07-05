package com.example.dbcompare;

import java.sql.*;
import java.util.*;

public class DBConfigResolver {

    private Connection oracleConnection;

    public DBConfigResolver(Connection oracleConnection) {
        this.oracleConnection = oracleConnection;
    }

    public List<QueryModel> loadQueries() throws SQLException {
        List<QueryModel> queries = new ArrayList<>();
        String sql = "SELECT SQL_TEXT, DB_KUERZEL FROM ABFRAGEN";
        try (Statement stmt = oracleConnection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String query = rs.getString("SQL_TEXT");
                String kuerzelList = rs.getString("DB_KUERZEL");
                List<String> kuerzel = Arrays.asList(kuerzelList.split(","));
                queries.add(new QueryModel(query, kuerzel));
            }
        }
        return queries;
    }

    public Map<String, String> resolveConnections() throws SQLException {
        Map<String, String> connMap = new HashMap<>();
        String sql = "SELECT KUERZEL, HOST, PORT, SID, USERNAME, PASS FROM DB_CONFIG";
        try (Statement stmt = oracleConnection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {

                String url = "jdbc:oracle:thin:@" + rs.getString("HOST") + ":" +
                             rs.getString("PORT") + ":" + rs.getString("SID");
                String user = rs.getString("USERNAME");
                String pass = rs.getString("PASS");
                String connStr = url + ";" + user + ";" + pass;
                connMap.put(rs.getString("KUERZEL"), connStr);
            }
        }
        return connMap;
    }
}