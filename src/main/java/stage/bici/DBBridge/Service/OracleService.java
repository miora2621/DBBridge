package stage.bici.DBBridge.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import oracle.sql.TIMESTAMP;

import javax.naming.spi.DirStateFactory.Result;

import stage.bici.DBBridge.Model.*;
import stage.bici.DBBridge.Service.PostgresService;

public class OracleService {
    public static Connection OracleConnexion(Oracle oracle) throws SQLException {
        String url = oracle.buildConnectionUrl();
        String user = oracle.getUsername();
        String password = oracle.getPassword();
        Connection conn = DriverManager.getConnection(url, user, password);
        return conn;
    }

    public static List<String> getAllTableName(Oracle oracle) throws SQLException {
        List<String> tableNames = new ArrayList<>();
        Connection conn = OracleService.OracleConnexion(oracle);
        DatabaseMetaData metaData = conn.getMetaData();
        ResultSet rs = metaData.getTables(null, conn.getSchema(), "%", new String[]{"TABLE"});

        while (rs.next()) {
            String tableName = rs.getString("TABLE_NAME");
            tableNames.add(tableName);
        }

        rs.close();
        conn.close();
        return tableNames;
    }

    public static List<DonneeTableOracle> getOracleTableColumns(Oracle oracle, String tableName) throws SQLException {
        List<DonneeTableOracle> columns = new ArrayList<>();
        Connection oracleConn = OracleService.OracleConnexion(oracle);
        String sql = "SELECT column_name, data_type, data_length, data_precision, data_scale, nullable " +
                     "FROM user_tab_columns WHERE table_name = ?";
        try (PreparedStatement ps = oracleConn.prepareStatement(sql)) {
            ps.setString(1, tableName.toUpperCase());
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                DonneeTableOracle col = new DonneeTableOracle();
                col.setName(rs.getString("COLUMN_NAME"));
                col.setOracleType(rs.getString("DATA_TYPE"));
                col.setLength(rs.getInt("DATA_LENGTH"));
                col.setPrecision(rs.getInt("DATA_PRECISION")) ;
                col.setScale(rs.getInt("DATA_SCALE"));
                col.setNullable("Y".equals(rs.getString("NULLABLE")));
                columns.add(col);
            }
            rs.close();
        }

        oracleConn.close();
        return columns;
    }

    public static String mapOracleTypeToPostgres(DonneeTableOracle col) {
        String type = col.getOracleType().toUpperCase();

        switch (type) {
            case "VARCHAR2":
            case "NVARCHAR2":
                return "VARCHAR(" + col.getLength() + ")";
            case "CHAR":
                return "CHAR(" + col.getLength() + ")";
            case "NUMBER":
                if (col.getScale() > 0) {
                    return "NUMERIC(" + col.getPrecision() + "," + col.getScale() + ")";
                } else if (col.getPrecision() > 0) {
                    return "NUMERIC(" + col.getPrecision() + ")";
                } else {
                    return "NUMERIC";
                }
            case "DATE":
                return "TIMESTAMP";
            case "CLOB":
                return "TEXT";
            case "BLOB":
                return "BYTEA";
            default:
                return "TEXT"; // fallback
        }
    }

    public static String generateCreateTableSQL(Oracle oracle, String tableName) throws SQLException {
        List<DonneeTableOracle> columns = getOracleTableColumns(oracle, tableName);
        StringBuilder sb = new StringBuilder("CREATE TABLE " + tableName.toLowerCase() + " (");

        for (int i = 0; i < columns.size(); i++) {
            DonneeTableOracle col = columns.get(i);

            String colName = col.getName();
            if (colName == null || colName.trim().isEmpty()) {
                colName = "col_" + i;
            }
            
            colName = colName.toLowerCase();
            
            // Échapper si espace ou mot réservé
            if (colName.contains(" ") || colName.equals("text") || colName.equals("user") || colName.equals("table")) {
                colName = "\"" + colName + "\"";
            }

            sb.append(colName)
            .append(" ")
            .append(mapOracleTypeToPostgres(col));

            if (!col.isNullable()) sb.append(" NOT NULL");

            if (i < columns.size() - 1) sb.append(", ");
        }

        sb.append(")");
        return sb.toString();
    }


    public static void createPostgresTable(PostgreSQL postgres, String createSQL) throws SQLException {
        Connection postgresConn = PostgresService.PostgresConnexion(postgres);
        try (Statement stmt = postgresConn.createStatement()) {
            stmt.executeUpdate(createSQL);
            stmt.close();
        }
        postgresConn.close();
        
    }

    public static ResultSet fetchOracleTableData(Connection oracleConn, String tableName) throws SQLException {
        Statement stmt = oracleConn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        ResultSet rs = stmt.executeQuery("SELECT * FROM " + tableName);
        return rs;
    }

    public static void insertDataIntoPostgres(Oracle oracle ,PostgreSQL postgres, String tableName) throws SQLException {
        Connection oracleConn = OracleService.OracleConnexion(oracle);
        ResultSet rs = fetchOracleTableData(oracleConn, tableName);
        Connection postgresConn = PostgresService.PostgresConnexion(postgres);

        postgresConn.setAutoCommit(false);

        List<DonneeTableOracle> columns = getOracleTableColumns(oracle, tableName);

        int colCount = columns.size();

        StringBuilder sb = new StringBuilder("INSERT INTO " + tableName.toLowerCase() + " VALUES (");
        for (int i = 0; i < colCount; i++) {
            sb.append("?");
            if (i < colCount - 1) sb.append(",");
        }
        sb.append(")");

        PreparedStatement ps = postgresConn.prepareStatement(sb.toString());

        while (rs.next()) {
            for (int i = 0; i < colCount; i++) {
                Object value = rs.getObject(columns.get(i).getName());

                if (value instanceof oracle.sql.TIMESTAMP) {
                    // Conversion explicite vers java.sql.Timestamp
                    value = ((oracle.sql.TIMESTAMP) value).timestampValue();
                } else if (value instanceof java.sql.Date) {
                    value = new java.sql.Date(((java.sql.Date) value).getTime());
                } else if (value instanceof java.sql.Time) {
                    value = new java.sql.Time(((java.sql.Time) value).getTime());
                } else if (value instanceof java.sql.Timestamp) {
                    java.sql.Timestamp ts = (java.sql.Timestamp) value;
                    // Vérifier les limites PostgreSQL
                    if (ts.getTime() < -631152000000L || ts.getTime() > 253402300799000L) {
                        value = null;
                    } else {
                        value = new java.sql.Timestamp(ts.getTime());
                    }
                }else if (value instanceof String) {
                    value = ((String) value).replace("\u0000", "");
                }

                ps.setObject(i + 1, value);

            }
            ps.addBatch();
        }
        ps.executeBatch();
        postgresConn.commit();
        rs.close();
        ps.close();
        postgresConn.close();
        oracleConn.close();
    }

    // Désactiver les contraintes FK Oracle
    public static void disableOracleFK(Oracle oracle,String tableName) throws SQLException {
        Connection conn = OracleService.OracleConnexion(oracle);
        String sql = "SELECT constraint_name FROM user_constraints " +
                    "WHERE table_name = ? AND constraint_type = 'R'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String constraint = rs.getString("constraint_name");
                    try (Statement stmt = conn.createStatement()) {
                        stmt.executeUpdate("ALTER TABLE " + tableName + " DISABLE CONSTRAINT " + constraint);
                    }
                }
            }
        }
        conn.close();
    }

    // Réactiver les contraintes FK Oracle
    public static void enableOracleFK(Oracle oracle, String tableName) throws SQLException {
        Connection conn = OracleService.OracleConnexion(oracle);
        String sql = "SELECT constraint_name FROM user_constraints " +
                    "WHERE table_name = ? AND constraint_type = 'R'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String constraint = rs.getString("constraint_name");
                    try (Statement stmt = conn.createStatement()) {
                        stmt.executeUpdate("ALTER TABLE " + tableName + " ENABLE CONSTRAINT " + constraint);
                    }
                }
            }
        }
        conn.close();
    }

    public static void migrationTablesAndDataOracleToPostgresql(Oracle oracle , PostgreSQL postgres) throws SQLException
    {
        List<String> tables = OracleService.getAllTableName(oracle);
        for (int i = 0; i < tables.size(); i++) {
            String script = OracleService.generateCreateTableSQL(oracle, tables.get(i));
            createPostgresTable(postgres, script);
            PostgresService.disablePostgresFK(postgres, tables.get(i));
            insertDataIntoPostgres(oracle, postgres, tables.get(i));
            PostgresService.enablePostgresFK(postgres, tables.get(i));
        }
    }

}
