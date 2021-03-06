package org.javers.repository.sql.schema;

import org.javers.repository.sql.ConnectionProvider;
import org.polyjdbc.core.PolyJDBC;
import org.polyjdbc.core.dialect.*;
import org.polyjdbc.core.schema.SchemaInspector;
import org.polyjdbc.core.schema.SchemaManager;
import org.polyjdbc.core.schema.model.Schema;
import org.polyjdbc.core.util.TheCloser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.Map;

/**
 * @author bartosz walacik
 */
public class JaversSchemaManager extends SchemaNameAware {
    private static final Logger logger = LoggerFactory.getLogger(JaversSchemaManager.class);

    private SchemaInspector schemaInspector;
    private SchemaManager schemaManager;
    private final Dialect dialect;
    private final FixedSchemaFactory schemaFactory;
    private final PolyJDBC polyJDBC;
    private final ConnectionProvider connectionProvider;

    public JaversSchemaManager(Dialect dialect, FixedSchemaFactory schemaFactory, PolyJDBC polyJDBC, ConnectionProvider connectionProvider, TableNameProvider tableNameProvider) {
        super(tableNameProvider);
        this.dialect = dialect;
        this.schemaFactory = schemaFactory;
        this.polyJDBC = polyJDBC;
        this.connectionProvider = connectionProvider;
    }

    public void ensureSchema() {
        this.schemaInspector = polyJDBC.schemaInspector();
        this.schemaManager = polyJDBC.schemaManager();

        for (Map.Entry<String, Schema> e : schemaFactory.allTablesSchema(dialect).entrySet()) {
            ensureTable(e.getKey(), e.getValue());
        }

        alterCommitIdColumnIfNeeded(); // JaVers 2.5 to 2.6 schema migration

        TheCloser.close(schemaManager, schemaInspector);
    }

    /**
     * JaVers 2.5 to 2.6 schema migration
     */
    private void alterCommitIdColumnIfNeeded() {
        ColumnType commitIdColType = getTypeOf(getCommitTableNameWithSchema(), "commit_id");

        if (commitIdColType.precision == 12) {
            logger.info("migrating db schema from JaVers 2.5 to 2.6 ...");
            if (dialect instanceof PostgresDialect) {
                executeSQL("ALTER TABLE " + getCommitTableNameWithSchema() + " ALTER COLUMN commit_id TYPE numeric(22,2)");
            } else if (dialect instanceof H2Dialect) {
                executeSQL("ALTER TABLE " + getCommitTableNameWithSchema() + " ALTER COLUMN commit_id numeric(22,2)");
            } else if (dialect instanceof MysqlDialect) {
                executeSQL("ALTER TABLE " + getCommitTableNameWithSchema() + " MODIFY commit_id numeric(22,2)");
            } else if (dialect instanceof OracleDialect) {
                executeSQL("ALTER TABLE " + getCommitTableNameWithSchema() + " MODIFY commit_id number(22,2)");
            } else if (dialect instanceof MsSqlDialect) {
                executeSQL("drop index jv_commit_commit_id_idx on " + getCommitTableNameWithSchema());
                executeSQL("ALTER TABLE " + getCommitTableNameWithSchema() + " ALTER COLUMN commit_id numeric(22,2)");
                executeSQL("CREATE INDEX jv_commit_commit_id_idx ON " + getCommitTableNameWithSchema() + " (commit_id)");
            } else {
                handleUnsupportedDialect();
            }
        }
    }

    private void handleUnsupportedDialect() {
        logger.error("\nno DB schema migration script for {} :(\nplease contact with JaVers team, javers@javers.org",
                dialect.getCode());
    }

    private boolean executeSQL(String sql) {
        try {
            Statement stmt = connectionProvider.getConnection().createStatement();

            boolean b = stmt.execute(sql);
            stmt.close();

            return b;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private int executeUpdate(String sql) {
        try {
            Statement stmt = connectionProvider.getConnection().createStatement();

            int cnt = stmt.executeUpdate(sql);
            stmt.close();

            return cnt;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private ColumnType getTypeOf(String tableName, String colName) {
        try {
            Statement stmt = connectionProvider.getConnection().createStatement();

            ResultSet res = stmt.executeQuery("select " + colName + " from " + tableName + " where 1<0");
            int colType = res.getMetaData().getColumnType(1);
            int colPrec = res.getMetaData().getPrecision(1);

            stmt.close();
            res.close();

            return new ColumnType(colType, colPrec);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean columnExists(String tableName, String colName) {
        try {
            Statement stmt = connectionProvider.getConnection().createStatement();

            ResultSet res = stmt.executeQuery("select * from " + tableName + " where 1<0");
            ResultSetMetaData metaData = res.getMetaData();

            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                if (metaData.getColumnName(i).equalsIgnoreCase(colName)) {
                    return true;
                }
            }

            res.close();
            stmt.close();

            return false;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void ensureTable(String tableName, Schema schema) {

        if (schemaInspector.relationExists(tableName)) {
            return;
        }
        logger.info("creating javers table {} ...", tableName);
        schemaManager.create(schema);

    }

    private void addStringColumn(String tableName, String colName, int len) {
        logger.warn("column " + tableName + "." + colName + " not exists, running ALTER TABLE ...");

        String sqlType = dialect.types().string(len);

        if (dialect instanceof OracleDialect ||
                dialect instanceof MsSqlDialect) {
            executeSQL("ALTER TABLE " + tableName + " ADD " + colName + " " + sqlType);
        } else {
            executeSQL("ALTER TABLE " + tableName + " ADD COLUMN " + colName + " " + sqlType);
        }
    }

    private void addLongColumn(String tableName, String colName) {
        logger.warn("column " + tableName + "." + colName + " not exists, running ALTER TABLE ...");

        String sqlType = dialect.types().bigint(0);

        if (dialect instanceof OracleDialect ||
                dialect instanceof MsSqlDialect) {
            executeSQL("ALTER TABLE " + tableName + " ADD " + colName + " " + sqlType);
        } else {
            executeSQL("ALTER TABLE " + tableName + " ADD COLUMN " + colName + " " + sqlType);
        }
    }

    public void dropSchema() {
        throw new RuntimeException("not implemented");
    }

    static class ColumnType {
        final int type;
        final int precision;

        ColumnType(int type, int precision) {
            this.type = type;
            this.precision = precision;
        }
    }
}
