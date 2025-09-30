package stage.bici.DBBridge.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

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

    public static String generateCreateTableSQL(Oracle oracle,String tableName) throws SQLException {
        List<DonneeTableOracle> columns = getOracleTableColumns(oracle, tableName);
        StringBuilder sb = new StringBuilder("CREATE TABLE " + tableName.toLowerCase() + " (");

        for (int i = 0; i < columns.size(); i++) {
            DonneeTableOracle col = columns.get(i);
            sb.append(col.getName().toLowerCase())
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
                ps.setObject(i + 1, rs.getObject(columns.get(i).getName()));
            }
            ps.addBatch();
        }
        ps.executeBatch();

        rs.close();
        ps.close();
        postgresConn.close();
        oracleConn.close();
    }

}
