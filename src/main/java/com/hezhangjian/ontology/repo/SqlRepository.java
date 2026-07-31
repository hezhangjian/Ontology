package com.hezhangjian.ontology.repo;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * Shared SQL repository for control-plane operations that require dynamic queries or
 * multi-table transactional updates.
 */
@Repository
public class SqlRepository extends JdbcTemplate {
    public SqlRepository(DataSource dataSource) {
        super(dataSource);
    }

    public void queryEach(String sql, SqlRowConsumer consumer, Object... arguments) {
        query(sql, (RowCallbackHandler) consumer::accept, arguments);
    }
}
