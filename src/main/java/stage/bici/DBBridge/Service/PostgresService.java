package stage.bici.DBBridge.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import stage.bici.DBBridge.Model.PostgreSQL;

public class PostgresService {
    public static Connection PostgresConnexion(PostgreSQL postgres) throws SQLException {
        String url = postgres.buildConnectionUrl();
        String user = postgres.getUsername();
        String password = postgres.getPassword();

        Connection conn = DriverManager.getConnection(url, user, password);

        if (conn != null) {
            System.out.println("Connexion PostgreSQL réussie !");
        }

        return conn; // retourne la connexion pour l'utiliser ailleurs
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
        }

        for (int i = 0; i < tables.size(); i++) {
            System.out.println(tables.get(i));
        }
        return tables;
    }

}
