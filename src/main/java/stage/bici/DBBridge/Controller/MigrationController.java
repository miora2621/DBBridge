package stage.bici.DBBridge.Controller;

import org.springframework.web.bind.annotation.*;
import stage.bici.DBBridge.Model.Oracle;
import stage.bici.DBBridge.Model.PostgreSQL;
import stage.bici.DBBridge.Service.MigrationStats;
import stage.bici.DBBridge.Service.OracleService;
import stage.bici.DBBridge.Service.PostgresMigrationStats;
import stage.bici.DBBridge.Service.PostgresService;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // autorise les appels depuis ton front local
public class MigrationController {

    public boolean checkOracleConn(Oracle oracle){
        try {
            Connection conn = OracleService.OracleConnexion(oracle);
            conn.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean checkPostgresConn(PostgreSQL postgres){
        try {
            Connection conn = PostgresService.PostgresConnexion(postgres);
            conn.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @GetMapping("/Migration")
    public Map<String, Object> migrate(
            @RequestParam String hostOracle,
            @RequestParam String portOracle,
            @RequestParam String serviceOracle,
            @RequestParam String sidOracle,
            @RequestParam String userOracle,
            @RequestParam String mdpOracle,

            @RequestParam String hostPostgres,
            @RequestParam String portPostgres,
            @RequestParam String bddPostgres,
            @RequestParam String userPostgres,
            @RequestParam String mdpPostgres,

            @RequestParam int typeMigration
    ) {
        Map<String, Object> response = new HashMap<>();

        Oracle oracle = new Oracle();
        oracle.setHost(hostOracle);
        oracle.setPort(portOracle);
        oracle.setServiceName(serviceOracle);
        oracle.setSid(sidOracle);
        oracle.setUsername(userOracle);
        oracle.setPassword(mdpOracle);

        PostgreSQL postgreSQL = new PostgreSQL();
        postgreSQL.setHost(hostPostgres);
        postgreSQL.setPort(portPostgres);
        postgreSQL.setDatabase(bddPostgres);
        postgreSQL.setUsername(userPostgres);
        postgreSQL.setPassword(mdpPostgres);

        if (checkOracleConn(oracle)==false){
            response.put("status","error");
            response.put("message","impossible de se connecter a la base de donnees Oracle");
            return response;
        }
        if (checkPostgresConn(postgreSQL)==false){
            response.put("status","error");
            response.put("message","impossible de se connecter a la base de donnees Postgresql");
            return response;
        }

        Map<String,Integer[]> statistiques = new HashMap<>();
        String logs = "";
        if (typeMigration==1){
            OracleService oracleService = new OracleService();
            MigrationStats stats = oracleService.migrateCompleteDatabase(oracle, postgreSQL);
            /* le indice 0 : Total */
            /* le indice 1 : Succes */
            /* le indice 2 : Echec */
            logs = stats.logs;
            statistiques.put("tables",new Integer[]{stats.tablesTotal,stats.tablesSuccess, stats.tablesFailed});
            statistiques.put("views",new Integer[]{stats.viewsTotal,stats.viewsSuccess,stats.viewsFailed});
            statistiques.put("sequences",new Integer[]{stats.sequencesTotal,stats.sequencesSuccess,stats.sequencesFailed});
            statistiques.put("fonctions",new Integer[]{stats.functionsTotal,stats.functionsSuccess, stats.functionsFailed});
        }
        if (typeMigration==2){
            /* Void le izy de ts nataoko */
            PostgresService postgresMigrationStats = new PostgresService();
            PostgresMigrationStats stats = postgresMigrationStats.migrateCompleteDatabase(postgreSQL,oracle);
            logs = stats.logs;
            /* le indice 0 : Total */
            /* le indice 1 : Succes */
            /* le indice 2 : Echec */
            statistiques.put("tables",new Integer[]{stats.tablesTotal,stats.tablesSuccess, stats.tablesFailed});
            statistiques.put("views",new Integer[]{stats.viewsTotal,stats.viewsSuccess,stats.viewsFailed});
            statistiques.put("sequences",new Integer[]{stats.sequencesTotal,stats.sequencesSuccess,stats.sequencesFailed});
            statistiques.put("fonctions",new Integer[]{stats.functionsTotal,stats.functionsSuccess, stats.functionsFailed});

        }

//        statistiques.put("tables",new Integer[]{4,2, 1});
//        statistiques.put("views",new Integer[]{10,8,2});
//        statistiques.put("sequences",new Integer[]{3,3,0});
//        statistiques.put("fonctions",new Integer[]{14,6, 8});
        int totalSucces = 0;
        int totalEchec = 0;
        for (Map.Entry<String, Integer[]> entry : statistiques.entrySet()) {
            Integer [] chiffre = entry.getValue();
            totalSucces += chiffre[1];
            totalEchec += chiffre[2];
        }
        int total = totalSucces+totalEchec;

        response.put("status", "success");
        response.put("message", "Migration effectuée avec succès !");
        response.put("statistiques", statistiques);
        response.put("total", total);
        response.put("totalSucces", totalSucces);
        response.put("totalEchec", totalEchec);
        response.put("tauxReussite", (totalSucces/total)*100);
        response.put("tauxEchec", (totalEchec/total)*100);
        response.put("log",logs);
        return response;
    }
}
