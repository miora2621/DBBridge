package stage.bici.DBBridge.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import stage.bici.DBBridge.Model.DonneeTablePostgres;
import stage.bici.DBBridge.Model.Oracle;
import stage.bici.DBBridge.Model.PostgreSQL;

public class PostgresService {
    public static Connection PostgresConnexion(PostgreSQL postgres) throws SQLException {
        String url = postgres.buildConnectionUrl();
        String user = postgres.getUsername();
        String password = postgres.getPassword();

        Connection conn = DriverManager.getConnection(url, user, password);
        return conn; 
    }

    public static List<String> getTables(PostgreSQL postgreSQL) throws SQLException {

        List<String> tables = new ArrayList<>();
        Connection connection = PostgresConnexion(postgreSQL);

        // Récupération des métadonnées
        DatabaseMetaData metaData = connection.getMetaData();

        // On récupère les tables (types = "TABLE")
        try (ResultSet rs = metaData.getTables(null, "public", "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                tables.add(tableName);
            }
            rs.close();
        }
        connection.close();

        for (int i = 0; i < tables.size(); i++) {
            System.out.println(tables.get(i));
        }
        return tables;
    }

    public static List<DonneeTablePostgres> getPostgresTableColumns(PostgreSQL postgreSQL, String tableName) throws SQLException {
        Connection postgresConn = PostgresConnexion(postgreSQL);
        List<DonneeTablePostgres> columns = new ArrayList<>();

        String sql = "SELECT column_name, data_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable " +
                     "FROM information_schema.columns " +
                     "WHERE table_name = ? AND table_schema = 'public' " +
                     "ORDER BY ordinal_position";
        try (PreparedStatement ps = postgresConn.prepareStatement(sql)) {
            ps.setString(1, tableName.toLowerCase());
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                DonneeTablePostgres col = new DonneeTablePostgres();
                col.setName(rs.getString("column_name"));
                col.setPgType(rs.getString("data_type"));
                col.setLength(rs.getInt("character_maximum_length"));
                col.setPrecision(rs.getInt("numeric_precision"));
                col.setScale(rs.getInt("numeric_scale"));
                col.setNullable("YES".equals(rs.getString("is_nullable")));
                columns.add(col);
            }
            rs.close();
            postgresConn.close();
        }
        for (int i = 0; i < columns.size(); i++) {
            System.out.println(columns.get(i).getName() + " - " + columns.get(i).getPgType());
            System.out.println("Length: " + columns.get(i).getLength() + ", Precision: " + columns.get(i).getPrecision() + ", Scale: " + columns.get(i).getScale() + ", Nullable: " + columns.get(i).isNullable()); 

        }
        return columns;
    }

    public static String mapPostgresTypeToOracle(DonneeTablePostgres col) {
        String type = col.getPgType().toLowerCase();

        switch (type) {
            case "character varying":
            case "varchar":
                return "VARCHAR2(" + (col.getLength() > 0 ? col.getLength() : 255) + ")";
            case "character":
            case "char":
                return "CHAR(" + (col.getLength() > 0 ? col.getLength() : 1) + ")";
            case "integer":
            case "int4":
                return "NUMBER(10)";
            case "bigint":
            case "int8":
                return "NUMBER(19)";
            case "smallint":
            case "int2":
                return "NUMBER(5)";
            case "numeric":
            case "decimal":
                if (col.getPrecision() > 0 && col.getScale() >= 0) {
                    return "NUMBER(" + col.getPrecision() + "," + col.getScale() + ")";
                } else {
                    return "NUMBER";
                }
            case "double precision":
            case "float8":
                return "FLOAT(126)";
            case "real":
            case "float4":
                return "FLOAT(63)";
            case "boolean":
                return "CHAR(1)";
            case "timestamp without time zone":
            case "timestamp with time zone":
            case "date":
                return "DATE";
            case "text":
                return "CLOB";
            case "bytea":
                return "BLOB";
            default:
                return "CLOB";
        }
    }

    public static String generateCreateTableSQL(PostgreSQL postgreSQL,String tableName) throws SQLException {
        List<DonneeTablePostgres> columns = getPostgresTableColumns(postgreSQL, tableName);
        StringBuilder sb = new StringBuilder("CREATE TABLE " + tableName.toUpperCase() + " (");

        Set<String> oracleReserved = new HashSet<>(Arrays.asList(
            "FILE", "SIZE", "DATE", "USER", "TABLE", "INDEX", "ORDER", "GROUP"
        ));

        for (int i = 0; i < columns.size(); i++) {
            DonneeTablePostgres col = columns.get(i);
            
            String colName = col.getName().toUpperCase();
            
            if (oracleReserved.contains(colName) || colName.contains(" ") || colName.matches(".*[^A-Z0-9_].*")) {
                colName = "\"" + colName + "\"";
            }
            
            sb.append(colName)
            .append(" ")
            .append(mapPostgresTypeToOracle(col));

            if (!col.isNullable()) sb.append(" NOT NULL");

            if (i < columns.size() - 1) sb.append(", ");
        }

        sb.append(")");
        return sb.toString();
    }

    public static void createOracleTable(Oracle oracle , String createSQL) throws SQLException {
        Connection oracleConn = OracleService.OracleConnexion(oracle);
        try (Statement stmt = oracleConn.createStatement()) {
            stmt.executeUpdate(createSQL);
        }
        oracleConn.close();

    }

    public static ResultSet fetchPostgresTableData(PostgreSQL postgreSQL,String tableName) throws SQLException {
        Connection postgresConn = PostgresConnexion(postgreSQL);
        Statement stmt = postgresConn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        return stmt.executeQuery("SELECT * FROM " + tableName);
    }

    public static void insertDataIntoOracle(PostgreSQL postgreSQL, Oracle oracle,String tableName) throws SQLException {
        Connection oracleConn = OracleService.OracleConnexion(oracle);
        ResultSet rs = fetchPostgresTableData(postgreSQL, tableName);
        List<DonneeTablePostgres> columns = getPostgresTableColumns(postgreSQL, tableName);
        int colCount = columns.size();

        StringBuilder sb = new StringBuilder("INSERT INTO " + tableName.toUpperCase() + " VALUES (");
        for (int i = 0; i < colCount; i++) {
            sb.append("?");
            if (i < colCount - 1) sb.append(",");
        }
        sb.append(")");

        PreparedStatement ps = oracleConn.prepareStatement(sb.toString());

        while (rs.next()) {
            for (int i = 0; i < colCount; i++) {
                ps.setObject(i + 1, rs.getObject(columns.get(i).getName()));
            }
            ps.addBatch();
        }
        ps.executeBatch();
        rs.close();
        ps.close();
        oracleConn.close();
    }

    // Désactiver les contraintes FK PostgreSQL
    public static void disablePostgresFK(PostgreSQL postgreSQL, String tableName) throws SQLException {
        Connection conn = PostgresConnexion(postgreSQL);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("ALTER TABLE " + tableName + " DISABLE TRIGGER ALL");
        }
    }

    // Réactiver les contraintes FK PostgreSQL
    public static void enablePostgresFK(PostgreSQL postgreSQL, String tableName) throws SQLException {
        Connection conn = PostgresConnexion(postgreSQL);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("ALTER TABLE " + tableName + " ENABLE TRIGGER ALL");
        }
    }

    public static void migrationTablesAndDataPostgresToOracle(Oracle oracle , PostgreSQL postgres)throws SQLException
    {
        List<String> tables = getTables(postgres);
        for (String table : tables) {
            String createSQL = generateCreateTableSQL(postgres, table);
            createOracleTable(oracle, createSQL);
            PostgresService.disablePostgresFK(postgres, table);
            insertDataIntoOracle(postgres, oracle, table);
            PostgresService.enablePostgresFK(postgres, table);
        }
        System.out.println("Migration de PostgreSQL vers Oracle terminée.");
    }
}
