package stage.bici.DBBridge.Controller;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpSession;
import stage.bici.DBBridge.Model.Oracle;
import stage.bici.DBBridge.Service.OracleService;


@Controller
public class OracleController {
    @GetMapping("/tablesOracle")
    public void getMethodName(HttpSession session) {
        Oracle oracle = session.getAttribute("dbOracle") != null ? (Oracle) session.getAttribute("dbOracle") : null;
        try {
            List<String> tables = OracleService.getAllTableName(oracle);
        } catch (Exception e) {
            // TODO: handle exception
        }
       
    }
    
}
