package io.github.nameof.schemaloom.dialect;

import java.util.*;

/** Parameterized metadata query for retrieving a view SELECT definition. */
public final class ViewDefinitionQuery {
    private final String sql;
    private final List<Object> parameters;

    public ViewDefinitionQuery(String sql, List<Object> parameters) {
        if (sql == null || sql.trim().isEmpty()) throw new IllegalArgumentException("sql is required");
        this.sql = sql;
        this.parameters = Collections.unmodifiableList(new ArrayList<Object>(parameters));
    }

    public String getSql() { return sql; }
    public List<Object> getParameters() { return parameters; }
}
