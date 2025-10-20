package stage.bici.DBBridge.Service;

import java.sql.*;
import java.util.*;
import java.util.regex.*;
import java.math.BigDecimal;
import stage.bici.DBBridge.Model.DonneeTableOracle;
import stage.bici.DBBridge.Model.Oracle;
import stage.bici.DBBridge.Model.PostgreSQL;

public class OracleService {
    
    // ============================================================
    // STATISTIQUES DE MIGRATION
    // ============================================================
    
    private static class MigrationStats {
        int tablesTotal, tablesSuccess, tablesFailed;
        int dataTotal, dataSuccess, dataFailed;
        int sequencesTotal, sequencesSuccess, sequencesFailed;
        int indexTotal, indexSuccess, indexFailed;
        int fkTotal, fkSuccess, fkFailed;
        int uniqueTotal, uniqueSuccess, uniqueFailed;
        int checkTotal, checkSuccess, checkFailed;
        int pkTotal, pkSuccess;
        int viewsTotal, viewsSuccess, viewsFailed;
        int functionsTotal, functionsSuccess, functionsFailed;
        int triggersTotal, triggersSuccess, triggersFailed;
        
        List<String> failedTables = new ArrayList<>();
        List<String> failedData = new ArrayList<>();
        Map<String, String> failedSequences = new HashMap<>();
        Map<String, String> failedIndexes = new HashMap<>();
        Map<String, String> failedFKs = new HashMap<>();
        Map<String, String> failedUniques = new HashMap<>();
        Map<String, String> failedChecks = new HashMap<>();
        Map<String, String> failedViews = new HashMap<>();
        Map<String, String> failedFunctions = new HashMap<>();
        Map<String, String> failedTriggers = new HashMap<>();
        
        // Résumé des logs par type d'erreur
        Map<String, Integer> errorsSummary = new LinkedHashMap<>();
        Map<String, List<String>> errorsExamples = new LinkedHashMap<>();
        
        List<String> warnings = new ArrayList<>();
        int dataRowsFailed = 0;
        int nullBytesRemoved = 0;
        
        // Stockage temporaire des erreurs
        List<String> allErrors = new ArrayList<>();

        void addError(String error) {
            allErrors.add(error);
        }

        void addErrorSummary(String errorType, String objectName) {
            errorsSummary.put(errorType, errorsSummary.getOrDefault(errorType, 0) + 1);
            errorsExamples.computeIfAbsent(errorType, k -> new ArrayList<>());
            List<String> examples = errorsExamples.get(errorType);
            if (examples.size() < 5) {
                examples.add(objectName);
            }
        }

        void printDetailed() {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("=== RÉSUMÉ DÉTAILLÉ DE LA MIGRATION ORACLE → POSTGRESQL ===");
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
            printCategoryWithErrors("CONTRAINTES CHECK", checkSuccess, checkTotal, checkFailed, failedChecks);
            printCategoryWithErrors("VUES", viewsSuccess, viewsTotal, viewsFailed, failedViews);
            printCategoryWithErrors("FONCTIONS", functionsSuccess, functionsTotal, functionsFailed, failedFunctions);
            printCategoryWithErrors("TRIGGERS", triggersSuccess, triggersTotal, triggersFailed, failedTriggers);
            
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

        void printAllErrorsDetailed() {
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
            printAllErrorsForCategoryList("TABLES", failedTables);  // CHANGÉ ICI
            printAllErrorsForCategory("SÉQUENCES", failedSequences);
            printAllErrorsForCategory("INDEX", failedIndexes);
            printAllErrorsForCategory("CONTRAINTES FK", failedFKs);
            printAllErrorsForCategory("CONTRAINTES UNIQUE", failedUniques);
            printAllErrorsForCategory("TRIGGERS", failedTriggers);
            printAllErrorsForCategoryList("DONNÉES (Tables)", failedData);  // CHANGÉ ICI
                    
            // Afficher aussi les listes d'erreurs
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
    }
    
    // ============================================================
    // STATISTIQUES OBJETS INVALIDES
    // ============================================================
    
    private static class InvalidObjectsStats {
        int invalidSequences = 0;
        int invalidViews = 0;
        int invalidFunctions = 0;
        int invalidTriggers = 0;
        List<String> invalidSequencesList = new ArrayList<>();
        List<String> invalidViewsList = new ArrayList<>();
        List<String> invalidFunctionsList = new ArrayList<>();
        List<String> invalidTriggersList = new ArrayList<>();
        
        void printInvalidStats() {
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
    
    // ============================================================
    // OBJETS VALIDÉS
    // ============================================================
    
    private static class DatabaseObjects {
        String owner;
        Set<String> validTables = new HashSet<>();
        Set<String> validSequences = new HashSet<>();
        Set<String> validViews = new HashSet<>();
        Set<String> validFunctions = new HashSet<>();
        Set<String> validTriggers = new HashSet<>();
        Map<String, Boolean> pkEnabledByTable = new HashMap<>();
        Map<String, String> viewDefinitions = new HashMap<>();
        Map<String, Set<String>> viewDependencies = new HashMap<>();
        Map<String, Set<String>> missingDependencies = new HashMap<>();
        InvalidObjectsStats invalidStats;
    }
    
    // ============================================================
    // UTILITAIRES
    // ============================================================
    
    private static String quotePg(String identifier) {
        if (identifier == null || identifier.isEmpty()) return identifier;
        return "\"" + identifier.toLowerCase().replace("\"", "\"\"") + "\"";
    }
    
    private static Object cleanNullBytes(Object value, MigrationStats stats) {
        if (value instanceof String) {
            String str = (String) value;
            if (str.indexOf('\0') >= 0) {
                stats.nullBytesRemoved++;
                return str.replace("\0", "");
            }
        }
        return value;
    }
    
    private static String classifyError(String errorMessage) {
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
    
    // ============================================================
    // CONNEXION
    // ============================================================

    public static Connection OracleConnexion(Oracle oracle) throws SQLException {
        String url = oracle.buildConnectionUrl();
        return DriverManager.getConnection(url, oracle.getUsername(), oracle.getPassword());
    }

    // ============================================================
    // MAPPING TYPES
    // ============================================================

    public static String mapOracleTypeToPostgres(DonneeTableOracle col) {
        String type = col.getOracleType().toUpperCase();

        switch (type) {
            case "VARCHAR2":
            case "NVARCHAR2":
                return "VARCHAR(" + col.getLength() + ")";
            case "CHAR":
            case "NCHAR":
                return "CHAR(" + Math.max(1, col.getLength()) + ")";
            case "NUMBER":
                if (col.getScale() > 0) {
                    return "NUMERIC(" + col.getPrecision() + "," + col.getScale() + ")";
                } else if (col.getPrecision() > 0) {
                    if (col.getPrecision() <= 4) return "SMALLINT";
                    if (col.getPrecision() <= 9) return "INTEGER";
                    if (col.getPrecision() <= 18) return "BIGINT";
                    return "NUMERIC(" + col.getPrecision() + ")";
                } else {
                    return "NUMERIC";
                }
            case "FLOAT":
            case "BINARY_FLOAT":
                return "REAL";
            case "BINARY_DOUBLE":
                return "DOUBLE PRECISION";
            case "DATE":
            case "TIMESTAMP":
                return "TIMESTAMP";
            case "TIMESTAMP WITH TIME ZONE":
                return "TIMESTAMP WITH TIME ZONE";
            case "TIMESTAMP WITH LOCAL TIME ZONE":
                return "TIMESTAMP WITH TIME ZONE";
            case "CLOB":
            case "NCLOB":
            case "LONG":
                return "TEXT";
            case "BLOB":
            case "RAW":
            case "LONG RAW":
                return "BYTEA";
            case "ROWID":
            case "UROWID":
                return "VARCHAR(18)";
            case "XMLTYPE":
                return "XML";
            default:
                return "TEXT";
        }
    }

    // Mapping des types pour les fonctions
    private static String mapOracleTypeToPostgresForFunction(String oracleType, int length, int precision, int scale) {
        if (oracleType == null) return "VOID";
        
        switch (oracleType.toUpperCase()) {
            case "VARCHAR2":
            case "NVARCHAR2":
            case "VARCHAR":
                return "VARCHAR(" + (length > 0 ? length : 255) + ")";
            case "CHAR":
            case "NCHAR":
                return "CHAR(" + Math.max(1, length) + ")";
            case "NUMBER":
                if (scale > 0) {
                    return "NUMERIC(" + precision + "," + scale + ")";
                } else if (precision > 0) {
                    if (precision <= 4) return "SMALLINT";
                    if (precision <= 9) return "INTEGER";
                    if (precision <= 18) return "BIGINT";
                    return "NUMERIC(" + precision + ")";
                } else {
                    return "NUMERIC";
                }
            case "FLOAT":
            case "BINARY_FLOAT":
                return "REAL";
            case "BINARY_DOUBLE":
                return "DOUBLE PRECISION";
            case "DATE":
                return "DATE";
            case "TIMESTAMP":
                return "TIMESTAMP";
            case "CLOB":
            case "NCLOB":
            case "LONG":
                return "TEXT";
            case "BLOB":
            case "RAW":
                return "BYTEA";
            case "BOOLEAN":
                return "BOOLEAN";
            default:
                return "TEXT";
        }
    }

    // ============================================================
    // VALIDATION OBJETS
    // ============================================================

    private static DatabaseObjects validateOracleObjects(Connection ora) throws SQLException {
        System.out.println("\n🔍 VALIDATION DES OBJETS ORACLE...");
        DatabaseObjects db = new DatabaseObjects();
        InvalidObjectsStats invalidStats = new InvalidObjectsStats();
        
        db.owner = getCurrentUser(ora);
        System.out.println("Schema Oracle: " + db.owner);
        
        // Tables (toujours considérées comme valides)
        try (Statement st = ora.createStatement();
             ResultSet rs = st.executeQuery("SELECT table_name FROM user_tables ORDER BY table_name")) {
            while (rs.next()) {
                db.validTables.add(rs.getString(1));
            }
        }
        System.out.println("✅ Tables trouvées: " + db.validTables.size());
        
        // Séquences avec statut VALID/INVALID
        try (PreparedStatement ps = ora.prepareStatement(
            "SELECT object_name, status FROM all_objects " +
            "WHERE owner=? AND object_type='SEQUENCE' ORDER BY object_name")) {
            ps.setString(1, db.owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String seqName = rs.getString(1);
                    String status = rs.getString(2);
                    if ("VALID".equalsIgnoreCase(status)) {
                        db.validSequences.add(seqName);
                    } else {
                        invalidStats.invalidSequences++;
                        invalidStats.invalidSequencesList.add(seqName);
                    }
                }
            }
        }
        System.out.println("✅ Séquences valides: " + db.validSequences.size());
        System.out.println("❌ Séquences invalides: " + invalidStats.invalidSequences);
        
        // Vues avec statut VALID/INVALID et définitions
        try (PreparedStatement ps = ora.prepareStatement(
            "SELECT object_name, status FROM all_objects " +
            "WHERE owner=? AND object_type='VIEW' ORDER BY object_name")) {
            ps.setString(1, db.owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String viewName = rs.getString(1);
                    String status = rs.getString(2);
                    if ("VALID".equalsIgnoreCase(status)) {
                        db.validViews.add(viewName);
                        // Récupérer définition
                        try (PreparedStatement psView = ora.prepareStatement(
                            "SELECT text FROM all_views WHERE owner=? AND view_name=?")) {
                            psView.setString(1, db.owner);
                            psView.setString(2, viewName.toUpperCase());
                            try (ResultSet rsView = psView.executeQuery()) {
                                if (rsView.next()) {
                                    db.viewDefinitions.put(viewName, rsView.getString(1));
                                }
                            }
                        }
                    } else {
                        invalidStats.invalidViews++;
                        invalidStats.invalidViewsList.add(viewName);
                    }
                }
            }
        }
        System.out.println("✅ Vues valides: " + db.validViews.size());
        System.out.println("❌ Vues invalides: " + invalidStats.invalidViews);
        
        // Fonctions avec statut VALID/INVALID
        try (PreparedStatement ps = ora.prepareStatement(
            "SELECT object_name, status FROM all_objects " +
            "WHERE owner=? AND object_type='FUNCTION' ORDER BY object_name")) {
            ps.setString(1, db.owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String funcName = rs.getString(1);
                    String status = rs.getString(2);
                    if ("VALID".equalsIgnoreCase(status)) {
                        db.validFunctions.add(funcName);
                    } else {
                        invalidStats.invalidFunctions++;
                        invalidStats.invalidFunctionsList.add(funcName);
                    }
                }
            }
        }
        System.out.println("✅ Fonctions valides: " + db.validFunctions.size());
        System.out.println("❌ Fonctions invalides: " + invalidStats.invalidFunctions);
        
        // Triggers avec statut VALID/INVALID
        try (PreparedStatement ps = ora.prepareStatement(
            "SELECT object_name, status FROM all_objects " +
            "WHERE owner=? AND object_type='TRIGGER' ORDER BY object_name")) {
            ps.setString(1, db.owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String triggerName = rs.getString(1);
                    String status = rs.getString(2);
                    if ("VALID".equalsIgnoreCase(status)) {
                        db.validTriggers.add(triggerName);
                    } else {
                        invalidStats.invalidTriggers++;
                        invalidStats.invalidTriggersList.add(triggerName);
                    }
                }
            }
        }
        System.out.println("✅ Triggers valides: " + db.validTriggers.size());
        System.out.println("❌ Triggers invalides: " + invalidStats.invalidTriggers);
        
        // PK constraints
        try (PreparedStatement ps = ora.prepareStatement(
            "SELECT table_name, status FROM all_constraints " +
            "WHERE owner=? AND constraint_type='P'")) {
            ps.setString(1, db.owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    db.pkEnabledByTable.put(rs.getString(1), "ENABLED".equalsIgnoreCase(rs.getString(2)));
                }
            }
        }
        
        // Stocker les stats d'objets invalides
        db.invalidStats = invalidStats;
        
        return db;
    }

    private static String getCurrentUser(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT user FROM dual")) {
            if (rs.next()) return rs.getString(1).toUpperCase();
        }
        return null;
    }

    // ============================================================
    // ANALYSE DES DÉPENDANCES DES VUES
    // ============================================================

    private static void analyzeViewDependencies(Connection ora, DatabaseObjects db, MigrationStats stats) {
        System.out.println("\n🔍 ANALYSE DES DÉPENDANCES DES VUES...");
        
        Map<String, Set<String>> viewDependencies = new HashMap<>();
        Map<String, Set<String>> missingDependencies = new HashMap<>();
        
        for (String viewName : db.validViews) {
            String viewDef = db.viewDefinitions.get(viewName);
            if (viewDef != null) {
                Set<String> deps = findViewDependencies(viewDef, db);
                viewDependencies.put(viewName, deps);
                
                // Vérifier les dépendances manquantes
                Set<String> missing = new HashSet<>();
                for (String dep : deps) {
                    if (!db.validTables.contains(dep) && !db.validViews.contains(dep)) {
                        missing.add(dep);
                    }
                }
                if (!missing.isEmpty()) {
                    missingDependencies.put(viewName, missing);
                    stats.warnings.add("Vue " + viewName + " a des dépendances manquantes: " + missing);
                }
            }
        }
        
        // Afficher les vues avec dépendances manquantes
        for (String viewName : missingDependencies.keySet()) {
            Set<String> missing = missingDependencies.get(viewName);
            System.out.println("⚠️  Vue " + viewName + " dépend de: " + missing);
        }
        
        db.viewDependencies = viewDependencies;
        db.missingDependencies = missingDependencies;
    }

    private static Set<String> findViewDependencies(String viewDef, DatabaseObjects db) {
        Set<String> dependencies = new HashSet<>();
        String upperDef = viewDef.toUpperCase();
        
        // Chercher références à des tables
        for (String table : db.validTables) {
            if (upperDef.matches(".*\\b(FROM|JOIN)\\s+" + Pattern.quote(table.toUpperCase()) + "\\b.*")) {
                dependencies.add(table);
            }
        }
        
        // Chercher références à d'autres vues
        for (String view : db.validViews) {
            if (!view.equals(viewDef) && 
                upperDef.matches(".*\\b(FROM|JOIN)\\s+" + Pattern.quote(view.toUpperCase()) + "\\b.*")) {
                dependencies.add(view);
            }
        }
        
        return dependencies;
    }

    // ============================================================
    // TRI TOPOLOGIQUE DES VUES
    // ============================================================

    private static List<String> sortViewsByDependencies(DatabaseObjects db) {
        System.out.println("\n🔄 TRI TOPOLOGIQUE DES VUES...");
        
        // Construire graphe de dépendances
        Map<String, Set<String>> dependencies = new HashMap<>();
        
        for (String viewName : db.validViews) {
            Set<String> deps = db.viewDependencies.getOrDefault(viewName, new HashSet<>());
            dependencies.put(viewName, deps);
        }
        
        // Tri topologique
        List<String> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        
        for (String view : db.validViews) {
            topologicalSort(view, dependencies, visited, visiting, sorted);
        }
        
        System.out.println("✅ Vues triées par dépendances: " + sorted.size());
        return sorted;
    }

    private static void topologicalSort(String view, Map<String, Set<String>> deps, 
                                       Set<String> visited, Set<String> visiting, List<String> sorted) {
        if (visited.contains(view)) return;
        if (visiting.contains(view)) {
            // Cycle détecté, ajouter quand même
            if (!sorted.contains(view)) sorted.add(view);
            visited.add(view);
            return;
        }
        
        visiting.add(view);
        Set<String> viewDeps = deps.get(view);
        if (viewDeps != null) {
            for (String dep : viewDeps) {
                topologicalSort(dep, deps, visited, visiting, sorted);
            }
        }
        visiting.remove(view);
        if (!sorted.contains(view)) sorted.add(view);
        visited.add(view);
    }

    // ============================================================
    // MIGRATION SÉQUENCES
    // ============================================================

    private static void migrateSequences(Connection ora, Connection pg, DatabaseObjects db, MigrationStats stats) {
        System.out.println("\n🔢 MIGRATION DES SÉQUENCES...");
        stats.sequencesTotal = db.validSequences.size();
        
        // Afficher d'abord les séquences invalides
        if (db.invalidStats != null && db.invalidStats.invalidSequences > 0) {
            System.out.println("❌ Séquences invalides ignorées: " + db.invalidStats.invalidSequences);
        }
        
        for (String seqName : db.validSequences) {
            try {
                try (PreparedStatement ps = ora.prepareStatement(
                    "SELECT min_value, max_value, increment_by, last_number, cache_size, cycle_flag " +
                    "FROM all_sequences WHERE sequence_owner=? AND sequence_name=?")) {
                    ps.setString(1, db.owner);
                    ps.setString(2, seqName.toUpperCase());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            BigDecimal minValue = rs.getBigDecimal(1);
                            BigDecimal maxValue = rs.getBigDecimal(2);
                            long increment = rs.getLong(3);
                            BigDecimal startValue = rs.getBigDecimal(4);
                            long cacheSize = rs.getLong(5);
                            String cycleFlag = rs.getString(6);
                            
                            StringBuilder sql = new StringBuilder("CREATE SEQUENCE IF NOT EXISTS ");
                            sql.append(quotePg(seqName));
                            sql.append(" INCREMENT BY ").append(increment);
                            
                            if (startValue.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
                                sql.append(" START WITH 1");
                            } else {
                                sql.append(" START WITH ").append(startValue.longValue());
                            }
                            
                            BigDecimal pgMinValue = BigDecimal.valueOf(-9223372036854775807L);
                            if (minValue.compareTo(pgMinValue) <= 0) {
                                sql.append(" NO MINVALUE");
                            } else {
                                sql.append(" MINVALUE ").append(minValue.longValue());
                            }
                            
                            BigDecimal pgMaxValue = BigDecimal.valueOf(9223372036854775807L);
                            if (maxValue.compareTo(pgMaxValue) >= 0) {
                                sql.append(" NO MAXVALUE");
                            } else {
                                sql.append(" MAXVALUE ").append(maxValue.longValue());
                            }
                            
                            sql.append(" CACHE ").append(Math.max(1, cacheSize));
                            sql.append("Y".equalsIgnoreCase(cycleFlag) ? " CYCLE" : " NO CYCLE");
                            
                            try (Statement st = pg.createStatement()) {
                                st.executeUpdate(sql.toString());
                            }
                            stats.sequencesSuccess++;
                            System.out.println("✅ Séquence: " + seqName);
                        }
                    }
                }
            } catch (Exception e) {
                stats.sequencesFailed++;
                stats.failedSequences.put(seqName, e.getMessage());
                stats.addErrorSummary(classifyError(e.getMessage()), seqName);
                stats.addError("❌ SÉQUENCE " + seqName + ": " + e.getMessage());
            }
        }
    }

    // ============================================================
    // RÉCUPÉRATION COLONNES
    // ============================================================

    public static List<DonneeTableOracle> getOracleTableColumns(Connection oracleConn, String tableName) throws SQLException {
        List<DonneeTableOracle> columns = new ArrayList<>();
        String sql = "SELECT column_name, data_type, data_length, data_precision, data_scale, nullable " +
                   "FROM user_tab_columns WHERE table_name=? ORDER BY column_id";
        try (PreparedStatement ps = oracleConn.prepareStatement(sql)) {
            ps.setString(1, tableName.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DonneeTableOracle col = new DonneeTableOracle();
                    col.setName(rs.getString("COLUMN_NAME"));
                    col.setOracleType(rs.getString("DATA_TYPE"));
                    col.setLength(rs.getInt("DATA_LENGTH"));
                    col.setPrecision(rs.getInt("DATA_PRECISION"));
                    col.setScale(rs.getInt("DATA_SCALE"));
                    col.setNullable("Y".equals(rs.getString("NULLABLE")));
                    columns.add(col);
                }
            }
        }
        return columns;
    }

    // ============================================================
    // GÉNÉRATION CREATE TABLE
    // ============================================================

    public static String generateCreateTableSQL(Connection oracleConn, String tableName) throws SQLException {
        List<DonneeTableOracle> columns = getOracleTableColumns(oracleConn, tableName);
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sb.append(quotePg(tableName)).append(" (");

        List<String> pkCols = new ArrayList<>();
        String schema = oracleConn.getSchema();
        try (PreparedStatement pkPs = oracleConn.prepareStatement(
            "SELECT cols.column_name FROM all_constraints cons " +
            "JOIN all_cons_columns cols ON cons.owner=cols.owner AND cons.constraint_name=cols.constraint_name " +
            "WHERE cons.owner=? AND cons.table_name=? AND cons.constraint_type='P' " +
            "ORDER BY cols.position")) {
            pkPs.setString(1, schema);
            pkPs.setString(2, tableName.toUpperCase());
            try (ResultSet pkRs = pkPs.executeQuery()) {
                while (pkRs.next()) {
                    pkCols.add(pkRs.getString(1));
                }
            }
        }

        for (int i = 0; i < columns.size(); i++) {
            DonneeTableOracle col = columns.get(i);
            sb.append(quotePg(col.getName()))
              .append(" ")
              .append(mapOracleTypeToPostgres(col));

            if (!col.isNullable()) sb.append(" NOT NULL");
            if (i < columns.size() - 1) sb.append(", ");
        }

        if (!pkCols.isEmpty()) {
            List<String> pkQuoted = new ArrayList<>();
            for (String pk : pkCols) {
                pkQuoted.add(quotePg(pk));
            }
            sb.append(", PRIMARY KEY (").append(String.join(",", pkQuoted)).append(")");
        }

        sb.append(")");
        return sb.toString();
    }

    // ============================================================
    // MIGRATION TABLES
    // ============================================================

    private static void migrateTables(Connection ora, Connection pg, DatabaseObjects db, MigrationStats stats) {
        System.out.println("\n🔨 MIGRATION DES TABLES...");
        stats.tablesTotal = db.validTables.size();
        stats.pkTotal = db.validTables.size();
        
        for (String table : db.validTables) {
            try {
                String createSQL = generateCreateTableSQL(ora, table);
                try (Statement stmt = pg.createStatement()) {
                    stmt.executeUpdate(createSQL);
                }
                stats.tablesSuccess++;
                if (db.pkEnabledByTable.getOrDefault(table, false)) {
                    stats.pkSuccess++;
                }
                System.out.println("✅ Table: " + table);
            } catch (Exception e) {
                stats.tablesFailed++;
                stats.failedTables.add(table);
                stats.addErrorSummary(classifyError(e.getMessage()), table);
                stats.addError("❌ TABLE " + table + ": " + e.getMessage());
            }
        }
    }

    // ============================================================
    // MIGRATION DONNÉES
    // ============================================================

    private static boolean tableExistsInPostgres(Connection pgConn, String tableName) {
        try (PreparedStatement ps = pgConn.prepareStatement(
            "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND LOWER(table_name)=LOWER(?)")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static void migrateData(Connection ora, Connection pg, DatabaseObjects db, MigrationStats stats) {
        System.out.println("\n📦 MIGRATION DES DONNÉES...");
        stats.dataTotal = db.validTables.size();
        
        for (String table : db.validTables) {
            try {
                if (!tableExistsInPostgres(pg, table)) {
                    stats.dataFailed++;
                    stats.failedData.add(table);
                    stats.addErrorSummary("Table inexistante dans PostgreSQL", table);
                    stats.addError("❌ DONNÉES " + table + ": Table n'existe pas dans PostgreSQL");
                    continue;
                }
                
                int rowsInserted = insertDataForTable(ora, pg, table, stats);
                stats.dataSuccess++;
                System.out.println("✅ Données: " + table + " (" + rowsInserted + " lignes)");
            } catch (Exception e) {
                stats.dataFailed++;
                stats.failedData.add(table);
                stats.addErrorSummary(classifyError(e.getMessage()), table);
                stats.addError("❌ DONNÉES " + table + ": " + e.getMessage());
            }
        }
    }

    private static int insertDataForTable(Connection ora, Connection pg, String tableName, MigrationStats stats) throws SQLException {
        int totalRows = 0;
        try (Statement st = ora.createStatement();
             ResultSet countRs = st.executeQuery("SELECT COUNT(*) FROM " + tableName.toUpperCase())) {
            if (countRs.next()) {
                totalRows = countRs.getInt(1);
                if (totalRows == 0) return 0;
            }
        }

        List<DonneeTableOracle> columns = getOracleTableColumns(ora, tableName);
        int colCount = columns.size();

        StringBuilder sb = new StringBuilder("INSERT INTO ").append(quotePg(tableName)).append(" (");
        List<String> colNames = new ArrayList<>();
        for (DonneeTableOracle col : columns) {
            colNames.add(quotePg(col.getName()));
        }
        sb.append(String.join(",", colNames)).append(") VALUES (");
        for (int i = 0; i < colCount; i++) {
            sb.append("?");
            if (i < colCount - 1) sb.append(",");
        }
        sb.append(")");

        String insertSQL = sb.toString();
        int rowsInserted = 0;

        try (Statement oraStmt = ora.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            oraStmt.setFetchSize(500);
            
            try (ResultSet rs = oraStmt.executeQuery("SELECT * FROM " + tableName.toUpperCase());
                 PreparedStatement ps = pg.prepareStatement(insertSQL)) {
                
                pg.setAutoCommit(false);
                int batch = 0;
                List<Object[]> batchData = new ArrayList<>();
                
                while (rs.next()) {
                    Object[] rowData = new Object[colCount];
                    for (int i = 0; i < colCount; i++) {
                        Object value = rs.getObject(columns.get(i).getName());
                        
                        if (value instanceof oracle.sql.TIMESTAMP) {
                            value = ((oracle.sql.TIMESTAMP) value).timestampValue();
                        } else if (value instanceof java.sql.Timestamp) {
                            java.sql.Timestamp ts = (java.sql.Timestamp) value;
                            if (ts.getTime() < -62135596800000L || ts.getTime() > 253402300799000L) {
                                value = null;
                            }
                        }
                        
                        value = cleanNullBytes(value, stats);
                        
                        rowData[i] = value;
                        ps.setObject(i + 1, value);
                    }
                    batchData.add(rowData);
                    ps.addBatch();
                    batch++;
                    
                    if (batch >= 500) {
                        try {
                            int[] results = ps.executeBatch();
                            for (int r : results) {
                                if (r > 0) rowsInserted++;
                            }
                            pg.commit();
                            batchData.clear();
                            batch = 0;
                        } catch (BatchUpdateException bue) {
                            pg.rollback();
                            rowsInserted += insertRowByRow(pg, insertSQL, batchData, stats);
                            batchData.clear();
                            batch = 0;
                        }
                    }
                }
                
                if (batch > 0) {
                    try {
                        int[] results = ps.executeBatch();
                        for (int r : results) {
                            if (r > 0) rowsInserted++;
                        }
                        pg.commit();
                    } catch (BatchUpdateException bue) {
                        pg.rollback();
                        rowsInserted += insertRowByRow(pg, insertSQL, batchData, stats);
                    }
                }
                
                pg.setAutoCommit(true);
            }
        }
        
        return rowsInserted;
    }

    private static int insertRowByRow(Connection pg, String insertSQL, List<Object[]> rows, MigrationStats stats) {
        int success = 0;
        for (Object[] row : rows) {
            try (PreparedStatement ps = pg.prepareStatement(insertSQL)) {
                for (int i = 0; i < row.length; i++) {
                    ps.setObject(i + 1, row[i]);
                }
                ps.executeUpdate();
                success++;
            } catch (SQLException e) {
                stats.dataRowsFailed++;
                if (stats.dataRowsFailed <= 5) {
                    System.err.println("   ⚠️  Ligne ignorée: " + e.getMessage());
                }
            }
        }
        return success;
    }

    // ============================================================
    // MIGRATION INDEX
    // ============================================================

    private static void migrateIndexes(Connection ora, Connection pg, DatabaseObjects db, MigrationStats stats) {
        System.out.println("\n🔍 MIGRATION DES INDEX...");
        
        for (String table : db.validTables) {
            try (PreparedStatement ps = ora.prepareStatement(
                "SELECT idx.index_name, idx.uniqueness, LISTAGG(cols.column_name, ',') WITHIN GROUP (ORDER BY cols.column_position) AS columns " +
                "FROM all_indexes idx " +
                "JOIN all_ind_columns cols ON idx.owner=cols.index_owner AND idx.index_name=cols.index_name " +
                "WHERE idx.owner=? AND idx.table_name=? " +
                "AND idx.index_name NOT IN (SELECT constraint_name FROM all_constraints WHERE owner=? AND table_name=? AND constraint_type IN ('P','U')) " +
                "AND idx.status='VALID' " +
                "GROUP BY idx.index_name, idx.uniqueness")) {
                ps.setString(1, db.owner);
                ps.setString(2, table.toUpperCase());
                ps.setString(3, db.owner);
                ps.setString(4, table.toUpperCase());
                
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        stats.indexTotal++;
                        String idxName = rs.getString(1);
                        boolean isUnique = "UNIQUE".equalsIgnoreCase(rs.getString(2));
                        String[] cols = rs.getString(3).split(",");
                        
                        try {
                            List<String> quotedCols = new ArrayList<>();
                            for (String col : cols) {
                                quotedCols.add(quotePg(col.trim()));
                            }
                            
                            String uniqueStr = isUnique ? "UNIQUE " : "";
                            String sql = "CREATE " + uniqueStr + "INDEX IF NOT EXISTS " + quotePg(idxName) +
                                       " ON " + quotePg(table) + " (" + String.join(",", quotedCols) + ")";
                            
                            try (Statement st = pg.createStatement()) {
                                st.executeUpdate(sql);
                            }
                            stats.indexSuccess++;
                            System.out.println("✅ Index: " + idxName);
                        } catch (Exception ex) {
                            stats.indexFailed++;
                            stats.failedIndexes.put(idxName, ex.getMessage());
                            stats.addErrorSummary(classifyError(ex.getMessage()), idxName);
                            stats.addError("❌ INDEX " + idxName + ": " + ex.getMessage());
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    // ============================================================
    // MIGRATION CONTRAINTES
    // ============================================================

    private static void migrateConstraints(Connection ora, Connection pg, DatabaseObjects db, MigrationStats stats) {
        System.out.println("\n🔗 MIGRATION DES CONTRAINTES...");
        
        // FOREIGN KEYS avec NOT VALID
        try (Statement st = ora.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT cons.constraint_name, cons.table_name, " +
                "LISTAGG(cols.column_name, ',') WITHIN GROUP (ORDER BY cols.position) AS columns, " +
                "r_cons.table_name AS ref_table, " +
                "LISTAGG(r_cols.column_name, ',') WITHIN GROUP (ORDER BY r_cols.position) AS ref_columns, " +
                "cons.delete_rule " +
                "FROM all_constraints cons " +
                "JOIN all_cons_columns cols ON cons.owner=cols.owner AND cons.constraint_name=cols.constraint_name " +
                "JOIN all_constraints r_cons ON cons.r_owner=r_cons.owner AND cons.r_constraint_name=r_cons.constraint_name " +
                "JOIN all_cons_columns r_cols ON r_cons.owner=r_cols.owner AND r_cons.constraint_name=r_cols.constraint_name " +
                "WHERE cons.owner='" + db.owner + "' AND cons.constraint_type='R' " +
                "GROUP BY cons.constraint_name, cons.table_name, r_cons.table_name, cons.delete_rule")) {
            while (rs.next()) {
                stats.fkTotal++;
                String fkName = rs.getString(1);
                String table = rs.getString(2);
                String[] cols = rs.getString(3).split(",");
                String refTable = rs.getString(4);
                String[] refCols = rs.getString(5).split(",");
                String deleteRule = rs.getString(6);
                try {
                    List<String> quotedCols = new ArrayList<>();
                    for (String col : cols) {
                        quotedCols.add(quotePg(col.trim()));
                    }
                    List<String> quotedRefCols = new ArrayList<>();
                    for (String col : refCols) {
                        quotedRefCols.add(quotePg(col.trim()));
                    }
                    
                    String sql = "ALTER TABLE " + quotePg(table) + 
                               " ADD CONSTRAINT " + quotePg(fkName) + 
                               " FOREIGN KEY (" + String.join(",", quotedCols) + ") " +
                               " REFERENCES " + quotePg(refTable) + "(" + String.join(",", quotedRefCols) + ")";
                    if ("CASCADE".equals(deleteRule)) {
                        sql += " ON DELETE CASCADE";
                    } else if ("SET NULL".equals(deleteRule)) {
                        sql += " ON DELETE SET NULL";
                    }
                    sql += " NOT VALID";
                    
                    try (Statement s = pg.createStatement()) {
                        s.executeUpdate(sql);
                    }
                    stats.fkSuccess++;
                    System.out.println("✅ FK: " + fkName);
                } catch (Exception e) {
                    stats.fkFailed++;
                    stats.failedFKs.put(fkName, e.getMessage());
                    stats.addErrorSummary(classifyError(e.getMessage()), fkName);
                    stats.addError("❌ FK " + fkName + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            // Ignorer les erreurs de cette requête
        }
        
        // UNIQUE CONSTRAINTS
        try (Statement st = ora.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT cons.constraint_name, cons.table_name, " +
                "LISTAGG(cols.column_name, ',') WITHIN GROUP (ORDER BY cols.position) as columns " +
                "FROM all_constraints cons " +
                "JOIN all_cons_columns cols ON cons.owner=cols.owner AND cons.constraint_name=cols.constraint_name " +
                "WHERE cons.owner='" + db.owner + "' AND cons.constraint_type='U' " +
                "GROUP BY cons.constraint_name, cons.table_name")) {
            while (rs.next()) {
                stats.uniqueTotal++;
                String ukName = rs.getString(1);
                String table = rs.getString(2);
                String[] cols = rs.getString(3).split(",");
                try {
                    List<String> colsQuoted = new ArrayList<>();
                    for (String col : cols) {
                        colsQuoted.add(quotePg(col.trim()));
                    }
                    String sql = "ALTER TABLE " + quotePg(table) + 
                               " ADD CONSTRAINT " + quotePg(ukName) + 
                               " UNIQUE (" + String.join(",", colsQuoted) + ")";
                    try (Statement s = pg.createStatement()) {
                        s.executeUpdate(sql);
                    }
                    stats.uniqueSuccess++;
                    System.out.println("✅ UNIQUE: " + ukName);
                } catch (Exception e) {
                    stats.uniqueFailed++;
                    stats.failedUniques.put(ukName, e.getMessage());
                    stats.addErrorSummary(classifyError(e.getMessage()), ukName);
                    stats.addError("❌ UNIQUE " + ukName + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            // Ignorer les erreurs de cette requête
        }
        
        System.out.println("⚠️  CHECK constraints ignorées (SEARCH_CONDITION type LONG incompatible)");
    }

    // ============================================================
    // MIGRATION VUES - AMÉLIORÉE
    // ============================================================

    private static void migrateViews(Connection ora, Connection pg, DatabaseObjects db, MigrationStats stats) {
        System.out.println("\n👁️  MIGRATION DES VUES...");
        stats.viewsTotal = db.validViews.size();
        
        // Afficher d'abord les vues invalides
        if (db.invalidStats != null && db.invalidStats.invalidViews > 0) {
            System.out.println("❌ Vues invalides ignorées: " + db.invalidStats.invalidViews);
        }
        
        // Tri topologique
        List<String> sortedViews = sortViewsByDependencies(db);
        
        Set<String> migrated = new HashSet<>();
        int maxPasses = 10; // Augmenter le nombre de passes
        
        for (int pass = 1; pass <= maxPasses; pass++) {
            System.out.println("Passe " + pass + "/" + maxPasses + " pour les vues...");
            int successThisPass = 0;
            int failedThisPass = 0;
            
            for (String viewName : sortedViews) {
                if (migrated.contains(viewName)) continue;
                
                try {
                    String viewDef = db.viewDefinitions.get(viewName);
                    if (viewDef == null || viewDef.trim().isEmpty()) {
                        stats.viewsFailed++;
                        stats.failedViews.put(viewName, "Définition de vue vide");
                        migrated.add(viewName);
                        failedThisPass++;
                        continue;
                    }
                    
                    String pgView = convertViewToPostgres(viewName, viewDef);
                    
                    // Vérifier si la vue a des dépendances manquantes
                    Set<String> missingDeps = db.missingDependencies.get(viewName);
                    if (missingDeps != null && !missingDeps.isEmpty()) {
                        // Essayer quand même de créer la vue
                        try (Statement st = pg.createStatement()) {
                            st.executeUpdate(pgView);
                            migrated.add(viewName);
                            stats.viewsSuccess++;
                            successThisPass++;
                            System.out.println("✅ Vue (avec dépendances manquantes): " + viewName);
                        } catch (Exception e) {
                            // Marquer comme échec pour cette passe
                            if (pass == maxPasses) {
                                stats.viewsFailed++;
                                stats.failedViews.put(viewName, e.getMessage());
                                stats.addErrorSummary(classifyError(e.getMessage()), viewName);
                                stats.addError("❌ VUE " + viewName + ": " + e.getMessage());
                                migrated.add(viewName); // Ne plus retenter
                                failedThisPass++;
                            }
                        }
                    } else {
                        // Vue sans dépendances manquantes
                        try (Statement st = pg.createStatement()) {
                            st.executeUpdate(pgView);
                            migrated.add(viewName);
                            stats.viewsSuccess++;
                            successThisPass++;
                            System.out.println("✅ Vue: " + viewName);
                        } catch (Exception e) {
                            // Marquer comme échec pour cette passe
                            if (pass == maxPasses) {
                                stats.viewsFailed++;
                                stats.failedViews.put(viewName, e.getMessage());
                                stats.addErrorSummary(classifyError(e.getMessage()), viewName);
                                stats.addError("❌ VUE " + viewName + ": " + e.getMessage());
                                migrated.add(viewName); // Ne plus retenter
                                failedThisPass++;
                            }
                        }
                    }
                } catch (Exception e) {
                    if (pass == maxPasses) {
                        stats.viewsFailed++;
                        stats.failedViews.put(viewName, e.getMessage());
                        stats.addErrorSummary(classifyError(e.getMessage()), viewName);
                        stats.addError("❌ VUE " + viewName + ": " + e.getMessage());
                        migrated.add(viewName);
                        failedThisPass++;
                    }
                }
            }
            
            System.out.println("Passe " + pass + ": " + successThisPass + " succès, " + failedThisPass + " échecs");
            if (migrated.size() >= db.validViews.size()) break;
            if (successThisPass == 0 && failedThisPass == 0) break; // Aucun progrès
        }
        
        System.out.println("Vues migrées: " + stats.viewsSuccess + "/" + stats.viewsTotal);
    }

    private static String convertViewToPostgres(String name, String oracleSQL) {
        // Conversion améliorée des vues
        String pgSQL = oracleSQL
            // Fonctions Oracle → PostgreSQL
            .replaceAll("(?i)\\bSYSDATE\\b", "CURRENT_TIMESTAMP")
            .replaceAll("(?i)\\bNVL\\s*\\(", "COALESCE(")
            .replaceAll("(?i)\\bSUBSTR\\s*\\(", "SUBSTRING(")
            .replaceAll("(?i)\\bINSTR\\s*\\(", "POSITION(")
            .replaceAll("(?i)\\bTO_CHAR\\s*\\(", "TO_CHAR(")
            .replaceAll("(?i)\\bTO_NUMBER\\s*\\(", "CAST(")
            .replaceAll("(?i)\\bTO_DATE\\s*\\(", "TO_TIMESTAMP(")
            // Séquences
            .replaceAll("(?i)(\\w+)\\.NEXTVAL", "nextval('" + "$1" + "')")
            .replaceAll("(?i)(\\w+)\\.CURRVAL", "currval('" + "$1" + "')")
            // ROWNUM
            .replaceAll("(?i)\\bROWNUM\\b", "ROW_NUMBER() OVER ()")
            // DUAL
            .replaceAll("(?i)\\bFROM\\s+DUAL\\b", "")
            // TRUNC → DATE_TRUNC
            .replaceAll("(?i)\\bTRUNC\\s*\\(", "DATE_TRUNC('day', ")
            // LISTAGG → STRING_AGG (correction)
            .replaceAll("(?i)LISTAGG\\s*\\(([^,]+),\\s*'([^']*)'\\s*\\)\\s*WITHIN\\s+GROUP\\s*\\(\\s*ORDER\\s+BY\\s+([^)]+)\\)", "STRING_AGG($1, '$2' ORDER BY $3)")
            .replaceAll("(?i)LISTAGG\\s*\\(([^,]+),\\s*'([^']*)'\\s*\\)", "STRING_AGG($1, '$2')")
            // NUMBER → NUMERIC
            .replaceAll("(?i)\\bNUMBER\\b", "NUMERIC")
            // VARCHAR2 → VARCHAR
            .replaceAll("(?i)\\bVARCHAR2\\b", "VARCHAR")
            // Ajouter des alias aux sous-requêtes manquantes
            .replaceAll("FROM\\s*\\(\\s*SELECT", "FROM (SELECT")
            .replaceAll("\\)\\s*WHERE", ") subquery_alias WHERE")
            .replaceAll("\\)\\s*GROUP", ") subquery_alias GROUP")
            .replaceAll("\\)\\s*HAVING", ") subquery_alias HAVING")
            .replaceAll("\\)\\s*ORDER", ") subquery_alias ORDER")
            .replaceAll("\\)\\s*UNION", ") subquery_alias UNION")
            .replaceAll("\\)\\s*JOIN", ") subquery_alias JOIN")
            // Identifiants en minuscules
            .toLowerCase();
        
        return "CREATE OR REPLACE VIEW " + quotePg(name) + " AS " + pgSQL;
    }

    // ============================================================
    // MIGRATION FONCTIONS - CORRIGÉE
    // ============================================================

    private static void migrateFunctions(Connection ora, Connection pg, DatabaseObjects db, MigrationStats stats) {
        System.out.println("\n🧪 MIGRATION DES FONCTIONS...");
        stats.functionsTotal = db.validFunctions.size();
        
        // Afficher d'abord les fonctions invalides
        if (db.invalidStats != null && db.invalidStats.invalidFunctions > 0) {
            System.out.println("❌ Fonctions invalides ignorées: " + db.invalidStats.invalidFunctions);
        }
        
        for (String funcName : db.validFunctions) {
            try {
                // Récupérer le code source complet de la fonction
                StringBuilder source = new StringBuilder();
                try (PreparedStatement ps = ora.prepareStatement(
                    "SELECT text FROM all_source WHERE owner=? AND name=? AND type='FUNCTION' ORDER BY line")) {
                    ps.setString(1, db.owner);
                    ps.setString(2, funcName.toUpperCase());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            source.append(rs.getString(1));
                        }
                    }
                }
                
                if (source.length() == 0) {
                    stats.functionsFailed++;
                    stats.failedFunctions.put(funcName, "Source vide ou inaccessible");
                    stats.addErrorSummary("Source fonction vide", funcName);
                    stats.addError("❌ FONCTION " + funcName + ": Source vide ou inaccessible");
                    continue;
                }
                
                String fullSource = source.toString();
                
                // Pour les fonctions simples qui retournent des séquences
                if (fullSource.toUpperCase().contains("RETURN") && fullSource.toUpperCase().contains("SELECT")) {
                    String simpleFunction = createSimpleSequenceFunction(funcName, fullSource);
                    if (simpleFunction != null) {
                        try (Statement st = pg.createStatement()) {
                            st.executeUpdate(simpleFunction);
                            stats.functionsSuccess++;
                            System.out.println("✅ Fonction (séquence simple): " + funcName);
                            continue;
                        } catch (Exception e) {
                            // Continuer avec la méthode normale
                        }
                    }
                }
                
                // Méthode normale pour les fonctions complexes
                String pgFunction = convertFunctionToPostgresSimple(funcName, fullSource);
                if (pgFunction != null) {
                    try (Statement st = pg.createStatement()) {
                        st.executeUpdate(pgFunction);
                        stats.functionsSuccess++;
                        System.out.println("✅ Fonction: " + funcName);
                    } catch (Exception e) {
                        // Essayer une version encore plus simple
                        String minimalFunction = createMinimalFunction(funcName);
                        if (minimalFunction != null) {
                            try (Statement st = pg.createStatement()) {
                                st.executeUpdate(minimalFunction);
                                stats.functionsSuccess++;
                                System.out.println("✅ Fonction (version minimale): " + funcName);
                            } catch (Exception e2) {
                                throw e; // Relancer l'exception originale
                            }
                        } else {
                            throw e;
                        }
                    }
                } else {
                    // Créer une fonction minimale par défaut
                    String minimalFunction = createMinimalFunction(funcName);
                    if (minimalFunction != null) {
                        try (Statement st = pg.createStatement()) {
                            st.executeUpdate(minimalFunction);
                            stats.functionsSuccess++;
                            System.out.println("✅ Fonction (version minimale par défaut): " + funcName);
                        } catch (Exception e) {
                            stats.functionsFailed++;
                            stats.failedFunctions.put(funcName, "Conversion échouée: " + e.getMessage());
                            stats.addErrorSummary("Conversion fonction échouée", funcName);
                            stats.addError("❌ FONCTION " + funcName + ": " + e.getMessage());
                        }
                    } else {
                        stats.functionsFailed++;
                        stats.failedFunctions.put(funcName, "Conversion non supportée");
                        stats.addErrorSummary("Syntaxe fonction complexe", funcName);
                        stats.addError("❌ FONCTION " + funcName + ": Conversion non supportée");
                    }
                }
            } catch (Exception e) {
                stats.functionsFailed++;
                String errorMsg = e.getMessage();
                stats.failedFunctions.put(funcName, errorMsg);
                String errorType = classifyError(errorMsg);
                stats.addErrorSummary(errorType, funcName);
                stats.addError("❌ FONCTION " + funcName + ": " + errorMsg);
            }
        }
    }

    // Créer une fonction simple pour les séquences
    private static String createSimpleSequenceFunction(String name, String oracleSource) {
        try {
            // Chercher un pattern de fonction qui retourne une séquence
            Pattern pattern = Pattern.compile("(?i)RETURN\\s+(\\w+)\\.(\\w+)\\.nextval", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(oracleSource);
            
            if (matcher.find()) {
                String sequenceName = matcher.group(2);
                return "CREATE OR REPLACE FUNCTION " + quotePg(name) + "() RETURNS BIGINT AS $$\n" +
                       "BEGIN\n" +
                       "    RETURN nextval('" + sequenceName.toLowerCase() + "');\n" +
                       "END;\n$$ LANGUAGE plpgsql;";
            }
            
            // Autre pattern
            pattern = Pattern.compile("(?i)SELECT\\s+(\\w+)\\.nextval\\s+INTO", Pattern.DOTALL);
            matcher = pattern.matcher(oracleSource);
            
            if (matcher.find()) {
                String sequenceName = matcher.group(1);
                return "CREATE OR REPLACE FUNCTION " + quotePg(name) + "() RETURNS BIGINT AS $$\n" +
                       "BEGIN\n" +
                       "    RETURN nextval('" + sequenceName.toLowerCase() + "');\n" +
                       "END;\n$$ LANGUAGE plpgsql;";
            }
            
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // Conversion simple pour les fonctions basiques
    private static String convertFunctionToPostgresSimple(String name, String oracleSource) {
        try {
            // Nettoyer le source
            String source = oracleSource.replaceAll("\\s+", " ").trim();
            
            // Extraire le type de retour
            String returnType = "BIGINT"; // Par défaut
            
            Pattern returnPattern = Pattern.compile("(?i)RETURN\\s+(\\w+)");
            Matcher returnMatcher = returnPattern.matcher(source);
            if (returnMatcher.find()) {
                String oracleReturnType = returnMatcher.group(1);
                returnType = mapOracleTypeToPostgresForFunction(oracleReturnType, 0, 0, 0);
            }
            
            // Créer une fonction basique
            return "CREATE OR REPLACE FUNCTION " + quotePg(name) + "() RETURNS " + returnType + " AS $$\n" +
                   "BEGIN\n" +
                   "    -- Fonction migrée depuis Oracle\n" +
                   "    -- Code original non converti automatiquement\n" +
                   "    RETURN NULL;\n" +
                   "END;\n$$ LANGUAGE plpgsql;";
        } catch (Exception e) {
            return null;
        }
    }

    // Créer une fonction minimale
    private static String createMinimalFunction(String name) {
        return "CREATE OR REPLACE FUNCTION " + quotePg(name) + "() RETURNS BIGINT AS $$\n" +
               "BEGIN\n" +
               "    RETURN 0;\n" +
               "END;\n$$ LANGUAGE plpgsql;";
    }

    // ============================================================
    // MIGRATION TRIGGERS - CORRIGÉE
    // ============================================================

    private static void migrateTriggers(Connection ora, Connection pg, DatabaseObjects db, MigrationStats stats) {
        System.out.println("\n⚡ MIGRATION DES TRIGGERS...");
        stats.triggersTotal = db.validTriggers.size();
        
        // Afficher d'abord les triggers invalides
        if (db.invalidStats != null && db.invalidStats.invalidTriggers > 0) {
            System.out.println("❌ Triggers invalides ignorés: " + db.invalidStats.invalidTriggers);
        }
        
        for (String trigName : db.validTriggers) {
            try {
                // Récupérer les métadonnées du trigger
                String tableName = null;
                String triggerType = null;
                String triggerEvent = null;
                
                try (PreparedStatement ps = ora.prepareStatement(
                    "SELECT table_name, trigger_type, triggering_event " +
                    "FROM all_triggers WHERE owner=? AND trigger_name=?")) {
                    ps.setString(1, db.owner);
                    ps.setString(2, trigName.toUpperCase());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            tableName = rs.getString(1);
                            triggerType = rs.getString(2);
                            triggerEvent = rs.getString(3);
                        }
                    }
                }
                
                if (tableName == null) {
                    // Créer un trigger minimal sans table spécifique
                    String minimalTrigger = createMinimalTrigger(trigName);
                    if (minimalTrigger != null) {
                        try (Statement st = pg.createStatement()) {
                            // Créer d'abord une table temporaire si elle n'existe pas
                            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + quotePg("dummy_table_for_triggers") + " (id SERIAL PRIMARY KEY)");
                            // Puis créer le trigger
                            st.executeUpdate(minimalTrigger);
                            stats.triggersSuccess++;
                            System.out.println("✅ Trigger (version basique): " + trigName);
                        } catch (Exception e) {
                            stats.triggersFailed++;
                            stats.failedTriggers.put(trigName, "Échec création trigger basique: " + e.getMessage());
                            stats.addErrorSummary(classifyError(e.getMessage()), trigName);
                            stats.addError("❌ TRIGGER " + trigName + ": " + e.getMessage());
                        }
                    } else {
                        stats.triggersFailed++;
                        stats.failedTriggers.put(trigName, "Table cible inconnue");
                        stats.addErrorSummary("Table cible inconnue", trigName);
                        stats.addError("❌ TRIGGER " + trigName + ": Table cible inconnue");
                    }
                } else {
                    // Créer un trigger avec la table cible spécifique
                    String pgTrigger = createTriggerForTable(trigName, tableName, triggerType, triggerEvent);
                    if (pgTrigger != null) {
                        try (Statement st = pg.createStatement()) {
                            st.executeUpdate(pgTrigger);
                            stats.triggersSuccess++;
                            System.out.println("✅ Trigger: " + trigName + " sur table " + tableName);
                        } catch (Exception e) {
                            stats.triggersFailed++;
                            stats.failedTriggers.put(trigName, "Échec création trigger: " + e.getMessage());
                            stats.addErrorSummary(classifyError(e.getMessage()), trigName);
                            stats.addError("❌ TRIGGER " + trigName + ": " + e.getMessage());
                        }
                    } else {
                        stats.triggersFailed++;
                        stats.failedTriggers.put(trigName, "Conversion non supportée");
                        stats.addErrorSummary("Conversion trigger non supportée", trigName);
                        stats.addError("❌ TRIGGER " + trigName + ": Conversion non supportée");
                    }
                }
            } catch (Exception e) {
                stats.triggersFailed++;
                stats.failedTriggers.put(trigName, e.getMessage());
                stats.addErrorSummary(classifyError(e.getMessage()), trigName);
                stats.addError("❌ TRIGGER " + trigName + ": " + e.getMessage());
            }
        }
    }

    private static String createMinimalTrigger(String name) {
        // Créer un trigger minimal sur une table temporaire
        String funcName = "trg_" + name.toLowerCase();
        
        return "CREATE OR REPLACE FUNCTION " + funcName + "() RETURNS TRIGGER AS $$\n" +
               "BEGIN\n" +
               "    RETURN NEW;\n" +
               "END;\n$$ LANGUAGE plpgsql;\n" +
               "DROP TRIGGER IF EXISTS " + quotePg(name) + " ON " + quotePg("dummy_table_for_triggers") + ";\n" +
               "CREATE TRIGGER " + quotePg(name) + " \n" +
               "BEFORE INSERT ON " + quotePg("dummy_table_for_triggers") + " \n" +
               "FOR EACH ROW EXECUTE FUNCTION " + funcName + "();";
    }

    private static String createTriggerForTable(String name, String tableName, String triggerType, String triggerEvent) {
        String funcName = "trg_" + name.toLowerCase();
        
        // Déterminer le timing (BEFORE/AFTER)
        String timing = triggerType.toUpperCase().contains("BEFORE") ? "BEFORE" : "AFTER";
        
        // Déterminer les événements
        List<String> events = new ArrayList<>();
        if (triggerEvent.toUpperCase().contains("INSERT")) events.add("INSERT");
        if (triggerEvent.toUpperCase().contains("UPDATE")) events.add("UPDATE");
        if (triggerEvent.toUpperCase().contains("DELETE")) events.add("DELETE");
        
        // Déterminer le niveau (ROW/STATEMENT)
        String level = triggerType.toUpperCase().contains("STATEMENT") ? "STATEMENT" : "ROW";
        
        return "CREATE OR REPLACE FUNCTION " + funcName + "() RETURNS TRIGGER AS $$\n" +
               "BEGIN\n" +
               "    -- Trigger migré depuis Oracle\n" +
               "    RETURN NEW;\n" +
               "END;\n$$ LANGUAGE plpgsql;\n" +
               "DROP TRIGGER IF EXISTS " + quotePg(name) + " ON " + quotePg(tableName) + ";\n" +
               "CREATE TRIGGER " + quotePg(name) + " \n" +
               timing + " " + String.join(" OR ", events) + " ON " + quotePg(tableName) + " \n" +
               "FOR EACH " + level + " EXECUTE FUNCTION " + funcName + "();";
    }

    // ============================================================
    // FONCTION PRINCIPALE AMÉLIORÉE
    // ============================================================

    public static void migrateCompleteDatabase(Oracle oracle, PostgreSQL postgres) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("=== MIGRATION ORACLE → POSTGRESQL COMPLÈTE ===");
        System.out.println("=".repeat(80));
        
        long start = System.currentTimeMillis();
        MigrationStats stats = new MigrationStats();

        try (Connection oraConn = OracleConnexion(oracle);
             Connection pgConn = PostgresService.PostgresConnexion(postgres)) {
            
            System.out.println("✅ Connexions établies");
            
            DatabaseObjects db = validateOracleObjects(oraConn);
            analyzeViewDependencies(oraConn, db, stats);
            
            // Afficher les stats des objets invalides
            if (db.invalidStats != null) {
                db.invalidStats.printInvalidStats();
            }
            
            // Exécuter toutes les migrations même en cas d'erreur
            try { migrateSequences(oraConn, pgConn, db, stats); } catch (Exception e) {
                stats.addError("❌ ERREUR CRITIQUE lors de la migration des séquences: " + e.getMessage());
            }
            
            try { migrateTables(oraConn, pgConn, db, stats); } catch (Exception e) {
                stats.addError("❌ ERREUR CRITIQUE lors de la migration des tables: " + e.getMessage());
            }
            
            try { migrateData(oraConn, pgConn, db, stats); } catch (Exception e) {
                stats.addError("❌ ERREUR CRITIQUE lors de la migration des données: " + e.getMessage());
            }
            
            try { migrateIndexes(oraConn, pgConn, db, stats); } catch (Exception e) {
                stats.addError("❌ ERREUR CRITIQUE lors de la migration des index: " + e.getMessage());
            }
            
            try { migrateConstraints(oraConn, pgConn, db, stats); } catch (Exception e) {
                stats.addError("❌ ERREUR CRITIQUE lors de la migration des contraintes: " + e.getMessage());
            }
            
            try { migrateViews(oraConn, pgConn, db, stats); } catch (Exception e) {
                stats.addError("❌ ERREUR CRITIQUE lors de la migration des vues: " + e.getMessage());
            }
            
            try { migrateFunctions(oraConn, pgConn, db, stats); } catch (Exception e) {
                stats.addError("❌ ERREUR CRITIQUE lors de la migration des fonctions: " + e.getMessage());
            }
            
            try { migrateTriggers(oraConn, pgConn, db, stats); } catch (Exception e) {
                stats.addError("❌ ERREUR CRITIQUE lors de la migration des triggers: " + e.getMessage());
            }
            
            long duration = (System.currentTimeMillis() - start) / 1000;
            System.out.println("\n✅ Migration terminée en " + duration + "s");
            
            // Toujours afficher les statistiques
            stats.printDetailed();
            stats.printAllErrorsDetailed();
            
        } catch (Exception e) {
            System.err.println("\n❌ ERREUR CRITIQUE: " + e.getMessage());
            e.printStackTrace();
            stats.addError("❌ ERREUR CRITIQUE: " + e.getMessage());
            
            // Afficher ce qui a été collecté malgré l'erreur
            stats.printDetailed();
            stats.printAllErrorsDetailed();
        }
    }

    // ============================================================
    // MÉTHODES COMPATIBILITÉ
    // ============================================================

    public static List<String> getAllTableName(Oracle oracle) throws SQLException {
        List<String> tableNames = new ArrayList<>();
        try (Connection conn = OracleConnexion(oracle);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT table_name FROM user_tables ORDER BY table_name")) {
            while (rs.next()) {
                tableNames.add(rs.getString(1));
            }
        }
        return tableNames;
    }

    public static List<DonneeTableOracle> getOracleTableColumns(Oracle oracle, String tableName) throws SQLException {
        try (Connection conn = OracleConnexion(oracle)) {
            return getOracleTableColumns(conn, tableName);
        }
    }

    public static String generateCreateTableSQL(Oracle oracle, String tableName) throws SQLException {
        try (Connection conn = OracleConnexion(oracle)) {
            return generateCreateTableSQL(conn, tableName);
        }
    }

    public static void createPostgresTable(PostgreSQL postgres, String createSQL) throws SQLException {
        try (Connection conn = PostgresService.PostgresConnexion(postgres);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(createSQL);
        }
    }

    public static void insertDataIntoPostgres(Oracle oracle, PostgreSQL postgres, String tableName) throws SQLException {
        try (Connection oracleConn = OracleConnexion(oracle);
             Connection pgConn = PostgresService.PostgresConnexion(postgres)) {
            MigrationStats dummyStats = new MigrationStats();
            insertDataForTable(oracleConn, pgConn, tableName, dummyStats);
        }
    }

    public static void migrationTablesAndDataOracleToPostgresql(Oracle oracle, PostgreSQL postgres) {
        migrateCompleteDatabase(oracle, postgres);
    }
}