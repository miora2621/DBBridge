package stage.bici.DBBridge.Service;

import java.util.*;

public class PostgresMigrationStats {
    // ============================================================
    // STATISTIQUES DE MIGRATION
    // ============================================================
    
    public int tablesTotal, tablesSuccess, tablesFailed;
    public int dataTotal, dataSuccess, dataFailed;
    public int sequencesTotal, sequencesSuccess, sequencesFailed;
    public int functionsTotal, functionsSuccess, functionsFailed;
    public int viewsTotal, viewsSuccess, viewsFailed;
    public int pkTotal, pkSuccess;
    public int fkTotal, fkSuccess, fkFailed;
    public int uniqueTotal, uniqueSuccess, uniqueFailed;
    public int indexTotal, indexSuccess, indexFailed;
    public int triggersTotal, triggersSuccess, triggersFailed;
    public String logs;
    
    public List<String> failedTables = new ArrayList<>();
    public List<String> failedData = new ArrayList<>();
    public Map<String, String> failedSequences = new HashMap<>();
    public Map<String, String> failedFunctions = new HashMap<>();
    public Map<String, String> failedViews = new HashMap<>();
    public Map<String, String> failedIndexes = new HashMap<>();
    public Map<String, String> failedFKs = new HashMap<>();
    public Map<String, String> failedUniques = new HashMap<>();
    public Map<String, String> failedTriggers = new HashMap<>();
    
    // Résumé des logs par type d'erreur
    public Map<String, Integer> errorsSummary = new LinkedHashMap<>();
    public Map<String, List<String>> errorsExamples = new LinkedHashMap<>();
    
    public List<String> warnings = new ArrayList<>();
    public int dataRowsFailed = 0;
    public int nullBytesRemoved = 0;
    
    // Stockage temporaire des erreurs
    public List<String> allErrors = new ArrayList<>();

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

    public void printDetailed() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("=== RÉSUMÉ DÉTAILLÉ DE LA MIGRATION POSTGRESQL → ORACLE ===");
        System.out.println("=".repeat(80));
        
        printCategory("TABLES", tablesSuccess, tablesTotal, tablesFailed, failedTables);
        printCategory("DONNÉES", dataSuccess, dataTotal, dataFailed, failedData);
        if (dataRowsFailed > 0) {
            System.out.println("   ⚠️  Lignes individuelles échouées: " + dataRowsFailed);
        }
        if (nullBytesRemoved > 0) {
            System.out.println("   🔧 NULL bytes nettoyés: " + nullBytesRemoved);
        }
        printCategoryWithErrors("SÉQUENCES", sequencesSuccess, sequencesTotal, sequencesFailed, failedSequences);
        printCategoryWithErrors("INDEX", indexSuccess, indexTotal, indexFailed, failedIndexes);
        printCategory("CONTRAINTES PK", pkSuccess, pkTotal, 0, Collections.emptyList());
        printCategoryWithErrors("CONTRAINTES FK", fkSuccess, fkTotal, fkFailed, failedFKs);
        printCategoryWithErrors("CONTRAINTES UNIQUE", uniqueSuccess, uniqueTotal, uniqueFailed, failedUniques);
        printCategoryWithErrors("FONCTIONS", functionsSuccess, functionsTotal, functionsFailed, failedFunctions);
        printCategoryWithErrors("TRIGGERS", triggersSuccess, triggersTotal, triggersFailed, failedTriggers);
        printCategoryWithErrors("VUES", viewsSuccess, viewsTotal, viewsFailed, failedViews);
        
        if (!warnings.isEmpty()) {
            System.out.println("\n⚠️  AVERTISSEMENTS:");
            warnings.forEach(w -> System.out.println("   " + w));
        }
        
        // RÉSUMÉ DES LOGS GROUPÉ PAR TYPE D'ERREUR
        if (!errorsSummary.isEmpty()) {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("=== RÉSUMÉ DES ERREURS PAR TYPE ===");
            System.out.println("=".repeat(80));
            errorsSummary.forEach((errorType, count) -> {
                System.out.println(String.format("🔴 %-40s : %d occurrence(s)", errorType, count));
                List<String> examples = errorsExamples.get(errorType);
                if (examples != null && !examples.isEmpty()) {
                    System.out.println("   Exemples: " + String.join(", ", examples.subList(0, Math.min(3, examples.size()))));
                }
            });
            System.out.println("=".repeat(80));
        }
        
        System.out.println("\n" + "=".repeat(80));
        
        int totalObjets = tablesTotal + dataTotal + sequencesTotal + functionsTotal + 
                        viewsTotal + pkTotal + fkTotal + uniqueTotal + indexTotal + triggersTotal;
        int totalSuccess = tablesSuccess + dataSuccess + sequencesSuccess + functionsSuccess + 
                         viewsSuccess + pkSuccess + fkSuccess + uniqueSuccess + indexSuccess + triggersSuccess;
        int totalFailed = tablesFailed + dataFailed + sequencesFailed + functionsFailed + 
                        viewsFailed + fkFailed + uniqueFailed + indexFailed + triggersFailed;
        
        double globalScore = totalObjets > 0 ? (totalSuccess * 100.0 / totalObjets) : 0;
        
        System.out.println(String.format("🎯 SCORE GLOBAL : %.1f%% (%d/%d objets migrés avec succès, %d échecs)", 
            globalScore, totalSuccess, totalObjets, totalFailed));
        System.out.println("=".repeat(80) + "\n");
    }
    
    private void printCategory(String name, int success, int total, int failed, List<String> failedList) {
        double pct = total > 0 ? (success * 100.0 / total) : 0;
        System.out.println(String.format("📊 %-20s : %d/%d migrés (%.1f%%) - %d échecs", 
            name, success, total, pct, failed));
        if (!failedList.isEmpty() && failedList.size() <= 5) {
            System.out.println("   ❌ Échecs: " + String.join(", ", failedList));
        } else if (failedList.size() > 5) {
            System.out.println("   ❌ " + failed + " échecs (voir résumé ci-dessus)");
        }
    }
    
    private void printCategoryWithErrors(String name, int success, int total, int failed, Map<String, String> errors) {
        double pct = total > 0 ? (success * 100.0 / total) : 0;
        System.out.println(String.format("📊 %-20s : %d/%d migrés (%.1f%%) - %d échecs", 
            name, success, total, pct, failed));
        if (!errors.isEmpty() && errors.size() <= 3) {
            errors.forEach((k, v) -> {
                String msg = v.length() > 80 ? v.substring(0, 80) + "..." : v;
                System.out.println("   ❌ " + k + ": " + msg);
            });
        } else if (errors.size() > 3) {
            System.out.println("   ❌ " + failed + " échecs (voir résumé ci-dessus)");
        }
    }

    public void printAllErrorsDetailed() {
        if (!allErrors.isEmpty()) {
            System.out.println("\n" + "=".repeat(100));
            System.out.println("=== TOUTES LES ERREURS DÉTAILLÉES ===");
            System.out.println("=".repeat(100));
            
            for (String error : allErrors) {
                System.out.println(error);
                System.out.println("-".repeat(100));
            }
        }
        
        // Afficher aussi les erreurs par catégorie
        printAllErrorsForCategory("FONCTIONS", failedFunctions);
        printAllErrorsForCategory("VUES", failedViews);
        printAllErrorsForCategoryList("TABLES", failedTables);
        printAllErrorsForCategory("SÉQUENCES", failedSequences);
        printAllErrorsForCategory("INDEX", failedIndexes);
        printAllErrorsForCategory("CONTRAINTES FK", failedFKs);
        printAllErrorsForCategory("CONTRAINTES UNIQUE", failedUniques);
        printAllErrorsForCategory("TRIGGERS", failedTriggers);
        printAllErrorsForCategoryList("DONNÉES (Tables)", failedData);
    }

    private void printAllErrorsForCategory(String category, Map<String, String> errors) {
        if (!errors.isEmpty()) {
            System.out.println("\n--- " + category + " (" + errors.size() + " erreurs) ---");
            errors.forEach((name, error) -> {
                System.out.println("🔴 " + name + ":");
                System.out.println("   Message: " + (error.length() > 200 ? error.substring(0, 200) + "..." : error));
                System.out.println("   Type: " + classifyError(error));
                System.out.println();
            });
        }
    }
    
    private void printAllErrorsForCategoryList(String category, List<String> errors) {
        if (!errors.isEmpty()) {
            System.out.println("\n--- " + category + " (" + errors.size() + " erreurs) ---");
            for (String error : errors) {
                System.out.println("🔴 " + error);
            }
            System.out.println();
        }
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
            return "Objet invalide";
        } else if (errorMessage.contains("source vide")) {
            return "Source code inaccessible";
        } else if (errorMessage.contains("conversion non supportée")) {
            return "Syntaxe complexe non supportée";
        } else if (errorMessage.contains("sous-requête du from doit avoir un alias")) {
            return "Alias manquant dans sous-requête";
        } else if (errorMessage.contains("identifier is too long")) {
            return "Nom trop long pour Oracle";
        } else if (errorMessage.contains("table or view does not exist")) {
            return "Table ou vue inexistante";
        } else if (errorMessage.contains("name already used")) {
            return "Nom de contrainte dupliqué";
        } else {
            return "Autre erreur";
        }
    }
}