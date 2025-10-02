package stage.bici.DBBridge.Controller;

import java.util.List;

import org.springframework.boot.autoconfigure.graphql.GraphQlProperties.Http;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpSession;
import stage.bici.DBBridge.Model.*;
import stage.bici.DBBridge.Service.*;


@Controller
public class PostgresqlController {
    
    @GetMapping("/tablesPostgres")
    public void getMethodName(HttpSession session) {
        PostgreSQL postgres = session.getAttribute("dbPostgres") != null ? (PostgreSQL) session.getAttribute("dbPostgres") : null;
        try {   
            List<String> tables = PostgresService.getTables(postgres);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }   

    @GetMapping("/columnsPostgres")
    public String getcolumn(HttpSession session) {
         PostgreSQL postgres = session.getAttribute("dbPostgres") != null ? (PostgreSQL) session.getAttribute("dbPostgres") : null;
        try {   
            List<DonneeTablePostgres> columns = PostgresService.getPostgresTableColumns(postgres, "avion");
            for (int i = 0; i < columns.size(); i++) {
                System.out.println(PostgresService.mapPostgresTypeToOracle(columns.get(i)));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "pages/Accueil";
    }

    @GetMapping("/ScriptTablepost_to_oracle")
    public String creatScript(HttpSession session) {
        PostgreSQL postgres = session.getAttribute("dbPostgres") != null ? (PostgreSQL) session.getAttribute("dbPostgres") : null;
        try {   
            String script = PostgresService.generateCreateTableSQL(postgres, "avion");
            System.out.println(script);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "pages/Accueil";
    }

    @GetMapping("/createTablePost_to_oracle")
    public String executescript(HttpSession session) {
        PostgreSQL postgres = session.getAttribute("dbPostgres") != null ? (PostgreSQL) session.getAttribute("dbPostgres") : null;
        Oracle oracle = session.getAttribute("dbOracle") != null ? (Oracle) session.getAttribute("dbOracle") : null;
         try {   
            String script = PostgresService.generateCreateTableSQL(postgres, "avion");
            PostgresService.createOracleTable(oracle, script);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "pages/Accueil";
    }

    @GetMapping("/insertDataPost_to_oracle")
    public String insert(HttpSession session) {
        PostgreSQL postgres = session.getAttribute("dbPostgres") != null ? (PostgreSQL) session.getAttribute("dbPostgres") : null;
        Oracle oracle = session.getAttribute("dbOracle") != null ? (Oracle) session.getAttribute("dbOracle") : null;
         try {   
            PostgresService.insertDataIntoOracle(postgres, oracle, "avion");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "pages/Accueil";
    }

    @GetMapping("/migrationPost_to_oracle")
    public String migre(HttpSession session) {
        PostgreSQL postgres = session.getAttribute("dbPostgres") != null ? (PostgreSQL) session.getAttribute("dbPostgres") : null;
        Oracle oracle = session.getAttribute("dbOracle") != null ? (Oracle) session.getAttribute("dbOracle") : null;
         try {   
            PostgresService.migrationTablesAndDataPostgresToOracle(oracle,postgres);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "pages/Accueil";
    }
}
