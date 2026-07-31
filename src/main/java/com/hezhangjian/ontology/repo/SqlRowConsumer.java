package com.hezhangjian.ontology.repo;

import java.sql.ResultSet;
import java.sql.SQLException;

@FunctionalInterface
public interface SqlRowConsumer {
    void accept(ResultSet row) throws SQLException;
}
