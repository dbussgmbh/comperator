package com.example.dbcompare;

import java.util.List;

public class QueryModel {
    private String sql;
    private List<String> dbKuerzel;

    public QueryModel(String sql, List<String> dbKuerzel) {
        this.sql = sql;
        this.dbKuerzel = dbKuerzel;
    }

    public String getSql() {
        return sql;
    }

    public List<String> getDbKuerzel() {
        return dbKuerzel;
    }
}