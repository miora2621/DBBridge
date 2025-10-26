package stage.bici.DBBridge.Service;

import java.util.*;

public class InvalidObjectsStats {
    public int invalidSequences = 0;
    public int invalidViews = 0;
    public int invalidFunctions = 0;
    public int invalidTriggers = 0;
    public List<String> invalidSequencesList = new ArrayList<>();
    public List<String> invalidViewsList = new ArrayList<>();
    public List<String> invalidFunctionsList = new ArrayList<>();
    public List<String> invalidTriggersList = new ArrayList<>();
    
    public void printInvalidStats() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("=== OBJETS INVALIDES DÉTECTÉS ===");
        System.out.println("=".repeat(80));
        
        printInvalidCategory("SÉQUENCES", invalidSequences, invalidSequencesList);
        printInvalidCategory("VUES", invalidViews, invalidViewsList);
        printInvalidCategory("FONCTIONS", invalidFunctions, invalidFunctionsList);
        printInvalidCategory("TRIGGERS", invalidTriggers, invalidTriggersList);
        
        int totalInvalid = invalidSequences + invalidViews + invalidFunctions + invalidTriggers;
        System.out.println("=".repeat(80));
        System.out.println(String.format("📊 TOTAL OBJETS INVALIDES : %d", totalInvalid));
        System.out.println("=".repeat(80));
    }
    
    private void printInvalidCategory(String name, int count, List<String> examples) {
        if (count > 0) {
            System.out.println(String.format("❌ %-20s : %d objets invalides", name, count));
            if (!examples.isEmpty()) {
                System.out.println("   Exemples: " + 
                    examples.subList(0, Math.min(5, examples.size())));
            }
        }
    }
}