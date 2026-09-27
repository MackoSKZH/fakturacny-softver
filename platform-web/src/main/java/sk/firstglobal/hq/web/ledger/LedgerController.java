package sk.firstglobal.hq.web.ledger;

import sk.firstglobal.hq.web.project.ProjectRepository;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
class LedgerController {
    /** Kategorie blizke vykazu o prijmoch a vydavkoch neziskovej JU - dopisat sa da aj vlastna. */
    static final List<String> SUGGESTED_CATEGORIES = List.of(
            "Materiál", "Služby", "Cestovné", "Ubytovanie", "Registračné poplatky", "Nájom", "Bankové poplatky",
            "Dary", "Granty a dotácie", "Podiel zaplatenej dane (2 % / 3 %)", "Príjmy z reklamy",
            "Členské príspevky", "Úhrada faktúry", "Vrátenie");

    private final ProjectRepository projects;

    LedgerController(ProjectRepository projects) {
        this.projects = projects;
    }

    @GetMapping("/polozky")
    String page(Model model) {
        model.addAttribute("projects", this.projects.findAll());
        model.addAttribute("categories", SUGGESTED_CATEGORIES);
        return "ledger";
    }
}
