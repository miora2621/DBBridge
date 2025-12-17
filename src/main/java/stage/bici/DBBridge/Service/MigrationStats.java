package stage.bici.DBBridge.Service;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import stage.bici.DBBridge.Model.*;

public class MigrationStats {
    // ============================================================
    // STATISTIQUES DE MIGRATION
    // ============================================================
    
    public int tablesTotal, tablesSuccess, tablesFailed;
    public int dataTotal, dataSuccess, dataFailed;
    public int sequencesTotal, sequencesSuccess, sequencesFailed;
    public int indexTotal, indexSuccess, indexFailed;
    public int fkTotal, fkSuccess, fkFailed;
    public int uniqueTotal, uniqueSuccess, uniqueFailed;
    public int checkTotal, checkSuccess, checkFailed;
    public int pkTotal, pkSuccess,pkFailed;
    public int viewsTotal, viewsSuccess, viewsFailed;
    public int functionsTotal, functionsSuccess, functionsFailed;
    public int triggersTotal, triggersSuccess, triggersFailed;
    public String logs;
    
    public List<String> failedTables = new ArrayList<>();
    public List<String> failedData = new ArrayList<>();
    public Map<String, String> failedSequences = new HashMap<>();
    public Map<String, String> failedIndexes = new HashMap<>();
    public Map<String, String> failedFKs = new HashMap<>();
    public Map<String, String> failedUniques = new HashMap<>();
    public Map<String, String> failedChecks = new HashMap<>();
    public Map<String, String> failedViews = new HashMap<>();
    public Map<String, String> failedFunctions = new HashMap<>();
    public Map<String, String> failedTriggers = new HashMap<>();
    
    // Résumé des logs par type d'erreur
    public Map<String, Integer> errorsSummary = new LinkedHashMap<>();
    public Map<String, List<String>> errorsExamples = new LinkedHashMap<>();
    
    public List<String> warnings = new ArrayList<>();
    public int dataRowsFailed = 0;
    public int nullBytesRemoved = 0;
    
    // Stockage temporaire des erreurs
    public List<String> allErrors = new ArrayList<>();

    public List<MigrationScript> getScriptsByType(MigrationScript.ScriptType type) {
        return migrationScripts.stream()
            .filter(s -> s.getType() == type)
            .collect(Collectors.toList());
    }

    public List<MigrationScript> getSuccessfulScripts() {
        return migrationScripts.stream()
            .filter(MigrationScript::isSuccess)
            .collect(Collectors.toList());
    }

    public List<MigrationScript> getFailedScripts() {
        return migrationScripts.stream()
            .filter(s -> !s.isSuccess())
            .collect(Collectors.toList());
    }

    private List<MigrationScript> migrationScripts = new ArrayList<>();

    public void addMigrationScript(MigrationScript script) {
        this.migrationScripts.add(script);
    }

    public List<MigrationScript> getMigrationScripts() {
        return migrationScripts;
    }

    public void addError(String error) {
        allErrors.add(error);
    }

    public void addErrorSummary(String errorType, String objectName) {
        errorsSummary.put(errorType, errorsSummary.getOrDefault(errorType, 0) + 1);
        errorsExamples.computeIfAbsent(errorType, k -> new ArrayList<>());
        List<String> examples = errorsExamples.get(errorType);
        if (examples.size() < 5) {
            examples.add(objectName);
        }
    }

    public String printDetailed() {
        String reponse = "";
        reponse += "\n" + "=".repeat(80) + "\n";
        reponse += "=== RÉSUMÉ DÉTAILLÉ DE LA MIGRATION ORACLE → POSTGRESQL ===\n";
        reponse += "=".repeat(80) + "\n";
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("=== RÉSUMÉ DÉTAILLÉ DE LA MIGRATION ORACLE → POSTGRESQL ===");
        System.out.println("=".repeat(80));
        
        reponse += printCategory("TABLES", tablesSuccess, tablesTotal, tablesFailed, failedTables);
        reponse += printCategory("DONNÉES", dataSuccess, dataTotal, dataFailed, failedData);
        
        if (dataRowsFailed > 0) {
            reponse += "   ⚠️  Lignes individuelles échouées: " + dataRowsFailed + "\n";
            System.out.println("   ⚠️  Lignes individuelles échouées: " + dataRowsFailed);
        }
        if (nullBytesRemoved > 0) {
            reponse += "   🔧 NULL bytes nettoyés: " + nullBytesRemoved + "\n";
            System.out.println("   🔧 NULL bytes nettoyés: " + nullBytesRemoved);
        }
        
        reponse += printCategoryWithErrors("SÉQUENCES", sequencesSuccess, sequencesTotal, sequencesFailed, failedSequences);
        reponse += printCategoryWithErrors("INDEX", indexSuccess, indexTotal, indexFailed, failedIndexes);
        reponse += printCategory("CONTRAINTES PK", pkSuccess, pkTotal, pkFailed, Collections.emptyList());
        reponse += printCategoryWithErrors("CONTRAINTES FK", fkSuccess, fkTotal, fkFailed, failedFKs);
        reponse += printCategoryWithErrors("CONTRAINTES UNIQUE", uniqueSuccess, uniqueTotal, uniqueFailed, failedUniques);
        reponse += printCategoryWithErrors("CONTRAINTES CHECK", checkSuccess, checkTotal, checkFailed, failedChecks);
        reponse += printCategoryWithErrors("VUES", viewsSuccess, viewsTotal, viewsFailed, failedViews);
        reponse += printCategoryWithErrors("FONCTIONS", functionsSuccess, functionsTotal, functionsFailed, failedFunctions);
        reponse += printCategoryWithErrors("TRIGGERS", triggersSuccess, triggersTotal, triggersFailed, failedTriggers);
        
        if (!warnings.isEmpty()) {
            reponse += "\n⚠️  AVERTISSEMENTS:\n";
            System.out.println("\n⚠️  AVERTISSEMENTS:");
            for (String w : warnings) {
                reponse += "   " + w + "\n";
                System.out.println("   " + w);
            }
        }
        
        // RÉSUMÉ DES LOGS GROUPÉ PAR TYPE D'ERREUR
        if (!errorsSummary.isEmpty()) {
            reponse += "\n" + "=".repeat(80) + "\n";
            reponse += "=== RÉSUMÉ DES ERREURS PAR TYPE ===\n";
            reponse += "=".repeat(80) + "\n";
            
            System.out.println("\n" + "=".repeat(80));
            System.out.println("=== RÉSUMÉ DES ERREURS PAR TYPE ===");
            System.out.println("=".repeat(80));
            
            for (Map.Entry<String, Integer> entry : errorsSummary.entrySet()) {
                String errorType = entry.getKey();
                int count = entry.getValue();
                String line = String.format("🔴 %-40s : %d occurrence(s)", errorType, count);
                reponse += line + "\n";
                System.out.println(line);
                
                List<String> examples = errorsExamples.get(errorType);
                if (examples != null && !examples.isEmpty()) {
                    String examplesLine = "   Exemples: " + String.join(", ", examples.subList(0, Math.min(3, examples.size())));
                    reponse += examplesLine + "\n";
                    System.out.println(examplesLine);
                }
            }
            reponse += "=".repeat(80) + "\n";
            System.out.println("=".repeat(80));
        }
        
        reponse += "\n" + "=".repeat(80) + "\n";
        System.out.println("\n" + "=".repeat(80));
        
        int totalObjets = tablesTotal + dataTotal + sequencesTotal + pkTotal + 
                        fkTotal + uniqueTotal + checkTotal + indexTotal +
                        viewsTotal + functionsTotal + triggersTotal;
        int totalSuccess = tablesSuccess + dataSuccess + sequencesSuccess + pkSuccess + 
                         fkSuccess + uniqueSuccess + checkSuccess + indexSuccess +
                         viewsSuccess + functionsSuccess + triggersSuccess;
        int totalFailed = tablesFailed + dataFailed + sequencesFailed + 
                        fkFailed + uniqueFailed + checkFailed + indexFailed +
                        viewsFailed + functionsFailed + triggersFailed;
        
        double globalScore = totalObjets > 0 ? (totalSuccess * 100.0 / totalObjets) : 0;
        
        String finalLine = String.format("🎯 SCORE GLOBAL : %.1f%% (%d/%d objets migrés avec succès, %d échecs)", 
            globalScore, totalSuccess, totalObjets, totalFailed);
        reponse += finalLine + "\n";
        reponse += "=".repeat(80) + "\n\n";
        
        System.out.println(finalLine);
        System.out.println("=".repeat(80) + "\n");
        
        return reponse;
    }
    
    private String printCategory(String name, int success, int total, int failed, List<String> failedList) {
        String result = "";
        double pct = total > 0 ? (success * 100.0 / total) : 0;
        String line = String.format("📊 %-20s : %d/%d migrés (%.1f%%) - %d échecs", 
            name, success, total, pct, failed);
        result += line + "\n";
        System.out.println(line);
        
        if (!failedList.isEmpty() && failedList.size() <= 5) {
            String failedLine = "   ❌ Échecs: " + String.join(", ", failedList);
            result += failedLine + "\n";
            System.out.println(failedLine);
        } else if (failedList.size() > 5) {
            String failedLine = "   ❌ " + failed + " échecs (voir résumé ci-dessus)";
            result += failedLine + "\n";
            System.out.println(failedLine);
        }
        
        return result;
    }
    
    private String printCategoryWithErrors(String name, int success, int total, int failed, Map<String, String> errors) {
        String result = "";
        double pct = total > 0 ? (success * 100.0 / total) : 0;
        String line = String.format("📊 %-20s : %d/%d migrés (%.1f%%) - %d échecs", 
            name, success, total, pct, failed);
        result += line + "\n";
        System.out.println(line);
        
        if (!errors.isEmpty() && errors.size() <= 3) {
            for (Map.Entry<String, String> entry : errors.entrySet()) {
                String msg = entry.getValue().length() > 80 ? entry.getValue().substring(0, 80) + "..." : entry.getValue();
                String errorLine = "   ❌ " + entry.getKey() + ": " + msg;
                result += errorLine + "\n";
                System.out.println(errorLine);
            }
        } else if (errors.size() > 3) {
            String errorLine = "   ❌ " + failed + " échecs (voir résumé ci-dessus)";
            result += errorLine + "\n";
            System.out.println(errorLine);
        }
        
        return result;
    }

    public String printAllErrorsDetailed() {
        String reponse = "";
        
        if (!allErrors.isEmpty()) {
            reponse += "\n" + "=".repeat(100) + "\n";
            reponse += "=== TOUTES LES ERREURS DÉTAILLÉES ===\n";
            reponse += "=".repeat(100) + "\n";
            
            System.out.println("\n" + "=".repeat(100));
            System.out.println("=== TOUTES LES ERREURS DÉTAILLÉES ===");
            System.out.println("=".repeat(100));
            
            for (String error : allErrors) {
                reponse += error + "\n";
                reponse += "-".repeat(100) + "\n";
                System.out.println(error);
                System.out.println("-".repeat(100));
            }
        }
        
        // Afficher aussi les erreurs par catégorie
        reponse += printAllErrorsForCategory("FONCTIONS", failedFunctions);
        reponse += printAllErrorsForCategory("VUES", failedViews);
        reponse += printAllErrorsForCategoryList("TABLES", failedTables);
        reponse += printAllErrorsForCategory("SÉQUENCES", failedSequences);
        reponse += printAllErrorsForCategory("INDEX", failedIndexes);
        reponse += printAllErrorsForCategory("CONTRAINTES FK", failedFKs);
        reponse += printAllErrorsForCategory("CONTRAINTES UNIQUE", failedUniques);
        reponse += printAllErrorsForCategory("TRIGGERS", failedTriggers);
        reponse += printAllErrorsForCategoryList("DONNÉES (Tables)", failedData);
                
        return reponse;
    }

    private String printAllErrorsForCategory(String category, Map<String, String> errors) {
        String result = "";
        if (!errors.isEmpty()) {
            result += "\n--- " + category + " (" + errors.size() + " erreurs) ---\n";
            System.out.println("\n--- " + category + " (" + errors.size() + " erreurs) ---");
            
            for (Map.Entry<String, String> entry : errors.entrySet()) {
                result += "🔴 " + entry.getKey() + ":\n";
                result += "   Message: " + (entry.getValue().length() > 200 ? entry.getValue().substring(0, 200) + "..." : entry.getValue()) + "\n";
                result += "   Type: " + classifyError(entry.getValue()) + "\n\n";
                
                System.out.println("🔴 " + entry.getKey() + ":");
                System.out.println("   Message: " + (entry.getValue().length() > 200 ? entry.getValue().substring(0, 200) + "..." : entry.getValue()));
                System.out.println("   Type: " + classifyError(entry.getValue()));
                System.out.println();
            }
        }
        return result;
    }
    
    private String printAllErrorsForCategoryList(String category, List<String> errors) {
        String result = "";
        if (!errors.isEmpty()) {
            result += "\n--- " + category + " (" + errors.size() + " erreurs) ---\n";
            System.out.println("\n--- " + category + " (" + errors.size() + " erreurs) ---");
            
            for (String error : errors) {
                result += "🔴 " + error + "\n";
                System.out.println("🔴 " + error);
            }
            result += "\n";
            System.out.println();
        }
        return result;
    }
    
    static String classifyError(String errorMessage) {
        if (errorMessage == null) return "Erreur inconnue";
        
        errorMessage = errorMessage.toLowerCase();
        if (errorMessage.contains("relation") && errorMessage.contains("n'existe pas")) {
            return "Relation inexistante (vue dépendante)";
        } else if (errorMessage.contains("erreur de syntaxe") || errorMessage.contains("syntax error")) {
            return "Erreur de syntaxe SQL";
        } else if (errorMessage.contains("fonction") && errorMessage.contains("n'existe pas")) {
            return "Fonction inexistante";
        } else if (errorMessage.contains("colonne") && errorMessage.contains("plus d'une fois")) {
            return "Colonne dupliquée";
        } else if (errorMessage.contains("colonne") && errorMessage.contains("n'existe pas")) {
            return "Colonne inexistante";
        } else if (errorMessage.contains("type") && errorMessage.contains("n'existe pas")) {
            return "Type inexistant";
        } else if (errorMessage.contains("alias")) {
            return "Alias manquant dans sous-requête";
        } else if (errorMessage.contains("invalid") || errorMessage.contains("invalide")) {
            return "Objet invalide dans Oracle";
        } else if (errorMessage.contains("source vide")) {
            return "Source code inaccessible";
        } else if (errorMessage.contains("conversion non supportée")) {
            return "Syntaxe complexe non supportée";
        } else if (errorMessage.contains("sous-requête du from doit avoir un alias")) {
            return "Alias manquant dans sous-requête";
        } else {
            return "Autre erreur";
        }
    }
}