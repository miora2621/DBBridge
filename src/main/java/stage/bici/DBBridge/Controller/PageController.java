package stage.bici.DBBridge.Controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import stage.bici.DBBridge.Model.*;

@Controller
public class PageController {
   @GetMapping("/")
    public String loginForm() {
        return "pages/Accueil";
    }

    @GetMapping("/home")
    public String home() {
        return "pages/front/Accueil";
    }

    @GetMapping("/infoConnexion")
    public String infoConnexion(Model model) {
        model.addAttribute("oracle", new Oracle());
        return "pages/InfoConnexion";
    }

     @GetMapping("/infoConnexionPostrges")
    public String infoConnexionPostgres(Model model) {
        model.addAttribute("postgres", new PostgreSQL());
        return "pages/InfoConnexionPostgres";
    }
}
