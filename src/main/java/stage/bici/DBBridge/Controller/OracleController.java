package stage.bici.DBBridge.Controller;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpSession;
import stage.bici.DBBridge.Model.DonneeTableOracle;
import stage.bici.DBBridge.Model.Oracle;
import stage.bici.DBBridge.Model.PostgreSQL;
import stage.bici.DBBridge.Service.CompleteMigrationService;
import stage.bici.DBBridge.Service.CompletePostgresToOracleMigration;
import stage.bici.DBBridge.Service.OracleService;


@Controller
public class OracleController {
    @GetMapping("/tablesOracle")
    public String getMethodName(HttpSession session) {
        Oracle oracle = session.getAttribute("dbOracle") != null ? (Oracle) session.getAttribute("dbOracle") : null;
        try {
            List<String> tables = OracleService.getAllTableName(oracle);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return "pages/Accueil";
    }

    @GetMapping("/columnsOracle")
    public String getColumns(HttpSession session) {
        Oracle oracle = session.getAttribute("dbOracle") != null ? (Oracle) session.getAttribute("dbOracle") : null;
        try {
            List<DonneeTableOracle> columns = OracleService.getOracleTableColumns(oracle, "menudynamique");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "pages/Accueil";
    }

    @GetMapping("/changementTypeOracle")
    public String changementType(HttpSession session) {
        Oracle oracle = session.getAttribute("dbOracle") != null ? (Oracle) session.getAttribute("dbOracle") : null;
        try {
            List<DonneeTableOracle> columns = OracleService.getOracleTableColumns(oracle, "menudynamique");
            for (int i = 0; i < columns.size(); i++) {
                String changement = OracleService.mapOracleTypeToPostgres(columns.get(i));
                System.out.println("Changement de " + columns.get(i).getOracleType() + " à " + changement);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "pages/Accueil";
    }

    @GetMapping("/scriptTablePostgres")
    public String scriptTable(HttpSession session) {
        Oracle oracle = session.getAttribute("dbOracle") != null ? (Oracle) session.getAttribute("dbOracle") : null;
        try {
            String script = OracleService.generateCreateTableSQL(oracle, "menudynamique");
            System.out.println(script);
        } catch (Exception e) {
            e.printStackTrace();    
        }
        return "pages/Accueil";
    }

    @GetMapping("/creerTablePostgres")
    public String creerTablePostgres(HttpSession session) {
        Oracle oracle = session.getAttribute("dbOracle") != null ? (Oracle) session.getAttribute("dbOracle") : null;
        PostgreSQL postgreSQL = session.getAttribute("dbPostgres") != null ? (PostgreSQL) session.getAttribute("dbPostgres") : null;
        try {
            String script = OracleService.generateCreateTableSQL(oracle, "menudynamique");
            OracleService.createPostgresTable(postgreSQL, script);
        } catch (Exception e) {
            e.printStackTrace();    
        }
        return "pages/Accueil";
    }

    @GetMapping("/insertionDonnees")
    public String insert(HttpSession session) {
        Oracle oracle = session.getAttribute("dbOracle") != null ? (Oracle) session.getAttribute("dbOracle") : null;
        PostgreSQL postgreSQL = session.getAttribute("dbPostgres") != null ? (PostgreSQL) session.getAttribute("dbPostgres") : null;
        try {
            // String script = OracleService.generateCreateTableSQL(oracle, "menudynamique");
            // OracleService.createPostgresTable(postgreSQL, script);
            OracleService.insertDataIntoPostgres(oracle, postgreSQL, "menudynamique");
        } catch (Exception e) {
            e.printStackTrace();    
        }
        return "pages/Accueil";
    }

    @GetMapping("/testOracle")
    public String testOracle(HttpSession session) {
        Oracle oracle = session.getAttribute("dbOracle") != null ? (Oracle) session.getAttribute("dbOracle") : null;
        PostgreSQL postgreSQL = session.getAttribute("dbPostgres") != null ? (PostgreSQL) session.getAttribute("dbPostgres") : null;
        try {
            // String script = OracleService.generateCreateTableSQL(oracle, "menudynamique");
            // OracleService.createPostgresTable(postgreSQL, script);
            OracleService.migrationTablesAndDataOracleToPostgresql(oracle, postgreSQL);
        } catch (Exception e) {
            e.printStackTrace();    
        }
        return "pages/Accueil";
    }

    @GetMapping("/testViewOracle")
    public String insertView(HttpSession session) {
        Oracle oracle = session.getAttribute("dbOracle") != null ? (Oracle) session.getAttribute("dbOracle") : null;
        PostgreSQL postgreSQL = session.getAttribute("dbPostgres") != null ? (PostgreSQL) session.getAttribute("dbPostgres") : null;
        try {
            CompleteMigrationService.migrateCompleteDatabase(oracle, postgreSQL);
        } catch (Exception e) {
            e.printStackTrace();    
        }
        return "pages/Accueil";
    }
}
