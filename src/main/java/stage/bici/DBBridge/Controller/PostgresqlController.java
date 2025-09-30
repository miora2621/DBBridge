package stage.bici.DBBridge.Controller;

import java.util.List;

import org.springframework.boot.autoconfigure.graphql.GraphQlProperties.Http;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpSession;
import stage.bici.DBBridge.Model.PostgreSQL;
import stage.bici.DBBridge.Service.PostgresService;


@Controller
public class PostgresqlController {
    
    @GetMapping("/tablesPostgres")
    public void getMethodName(HttpSession session) {
        PostgreSQL postgres = session.getAttribute("dbPostgres") != null ? (PostgreSQL) session.getAttribute("dbPostgres") : null;
        try {   
            List<String> tables = PostgresService.getTables(postgres);
        } catch (Exception e) {
        }
    }   

    
}
