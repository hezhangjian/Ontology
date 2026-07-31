package com.hezhangjian.ontology.repo;

import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Named-parameter SQL repository used by persistence implementations with dynamic
 * statements.
 */
@Repository
public class SqlClientRepository implements JdbcClient {
    private final JdbcClient delegate;

    public SqlClientRepository(DataSource dataSource) {
        delegate = JdbcClient.create(dataSource);
    }

    @Override
    public StatementSpec sql(String sql) {
        return delegate.sql(sql);
    }
}
