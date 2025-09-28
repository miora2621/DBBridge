package stage.bici.DBBridge.Controller;

import java.sql.Connection;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

import jakarta.servlet.http.HttpSession;
import stage.bici.DBBridge.Model.*;
import stage.bici.DBBridge.Service.*;

@Controller
public class ConnexionController {
    
    @PostMapping("/Connexion")
    public String connecter(@ModelAttribute Oracle oracle, Model model , HttpSession session) {
        try {
            Connection conn = OracleService.OracleConnexion(oracle);
            // Connexion réussie, rediriger avec un message de succès
            model.addAttribute("message", "Connexion réussie !");
            session.setAttribute("dbOracle", oracle);
            conn.close();
        } catch (Exception e) {
            // En cas d'erreur, rester sur la page avec le message d'erreur
            model.addAttribute("oracle", oracle);
            model.addAttribute("error", "Erreur de connexion : " + e.getMessage());
            return "pages/InfoConnexion";
        }
        
        model.addAttribute("oracle", new Oracle());
        return "pages/InfoConnexion";
    }

    @PostMapping("/ConnexionPostgres")
    public String connecterPostgres(@ModelAttribute PostgreSQL postgres, Model model, HttpSession session) {
        try {
            Connection conn = PostgresService.PostgresConnexion(postgres);
            // Connexion réussie, rediriger avec un message de succès
            model.addAttribute("message", "Connexion réussie !");
            session.setAttribute("dbPostgres", postgres);
            conn.close();
        } catch (Exception e) {
            // En cas d'erreur, rester sur la page avec le message d'erreur
            model.addAttribute("postgres", postgres);
            model.addAttribute("error", "Erreur de connexion : " + e.getMessage());
            return "pages/InfoConnexionPostgres";
        }
        
        model.addAttribute("postgres", new PostgreSQL());
        return "pages/InfoConnexionPostgres";
    }
}
