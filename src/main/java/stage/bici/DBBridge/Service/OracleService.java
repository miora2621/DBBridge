package stage.bici.DBBridge.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import java.util.*;

import stage.bici.DBBridge.Model.Oracle;

public class OracleService {
    public static Connection OracleConnexion(Oracle oracle) throws SQLException {
        String url = oracle.buildConnectionUrl();
        String user = oracle.getUsername();
        String password = oracle.getPassword();

        Connection conn = DriverManager.getConnection(url, user, password);

        if (conn != null) {
            System.out.println("Connexion réussie !");
        }

        return conn; // retourne la connexion pour l'utiliser ailleurs
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
        for (int i = 0; i < tableNames.size(); i++) {
            System.out.println(tableNames.get(i));
        }
        return tableNames;
    }
}
