package stage.bici.DBBridge.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import stage.bici.DBBridge.Model.Oracle;
import stage.bici.DBBridge.Model.PostgreSQL;
import stage.bici.DBBridge.Model.SequenceInfo;

public class CompleteMigrationService {
    
    // Statistiques de migration
    private static class MigrationStats {
        int tablesTotal, tablesSuccess, tablesFailed;
        int dataTotal, dataSuccess, dataFailed;
        int sequencesTotal, sequencesSuccess, sequencesFailed;
        int viewsTotal, viewsSuccess, viewsFailed;
        int functionsTotal, functionsSuccess, functionsFailed;
        int constraintsTotal, constraintsSuccess, constraintsFailed;
        int indexesTotal, indexesSuccess, indexesFailed;
        int triggersTotal, triggersSuccess, triggersFailed;
        
        void printSummary() {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("=== RÉSUMÉ DE LA MIGRATION ===");
            System.out.println("=".repeat(80));
            System.out.println(String.format("📊 TABLES       : %d/%d migrées (%d échecs)", 
                tablesSuccess, tablesTotal, tablesFailed));
            System.out.println(String.format("📊 DONNÉES      : %d/%d migrées (%d échecs)", 
                dataSuccess, dataTotal, dataFailed));
            System.out.println(String.format("📊 SÉQUENCES    : %d/%d migrées (%d échecs)", 
                sequencesSuccess, sequencesTotal, sequencesFailed));
            System.out.println(String.format("📊 VUES         : %d/%d migrées (%d échecs)", 
                viewsSuccess, viewsTotal, viewsFailed));
            System.out.println(String.format("📊 FONCTIONS    : %d/%d migrées (%d échecs)", 
                functionsSuccess, functionsTotal, functionsFailed));
            System.out.println(String.format("📊 CONTRAINTES  : %d/%d migrées (%d échecs)", 
                constraintsSuccess, constraintsTotal, constraintsFailed));
            System.out.println(String.format("📊 INDEX        : %d/%d migrés (%d échecs)", 
                indexesSuccess, indexesTotal, indexesFailed));
            System.out.println(String.format("📊 TRIGGERS     : %d/%d migrés (%d échecs)", 
                triggersSuccess, triggersTotal, triggersFailed));
            System.out.println("=".repeat(80));
        }
    }
    
    // ========== MIGRATION COMPLÈTE ==========
    public static void migrateCompleteDatabase(Oracle oracle, PostgreSQL postgres) throws SQLException {
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("=== MIGRATION COMPLÈTE ORACLE → POSTGRESQL ===");
        System.out.println("=".repeat(80));
        
        long startTime = System.currentTimeMillis();
        MigrationStats stats = new MigrationStats();
        
        try {
            // ÉTAPE 1 : VALIDATION DES OBJETS ORACLE
            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 1/9 : VALIDATION DES OBJETS ORACLE");
            System.out.println("=".repeat(80));
            DatabaseObjects dbObjects = validateOracleObjects(oracle);
            
            // ÉTAPE 2 : TABLES (structure seulement)
            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 2/9 : MIGRATION DES TABLES (structure)");
            System.out.println("=".repeat(80));
            migrateTables(oracle, postgres, dbObjects, stats);
            
            // ÉTAPE 3 : DONNÉES
            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 3/9 : MIGRATION DES DONNÉES");
            System.out.println("=".repeat(80));
            migrateData(oracle, postgres, dbObjects, stats);
            
            // ÉTAPE 4 : SÉQUENCES
            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 4/9 : MIGRATION DES SÉQUENCES");
            System.out.println("=".repeat(80));
            migrateSequences(oracle, postgres, dbObjects, stats);
            
            // ÉTAPE 5 : FONCTIONS
            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 5/9 : MIGRATION DES FONCTIONS");
            System.out.println("=".repeat(80));
            migrateFunctions(oracle, postgres, dbObjects, stats);
            
            // ÉTAPE 6 : VUES
            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 6/9 : MIGRATION DES VUES");
            System.out.println("=".repeat(80));
            migrateViews(oracle, postgres, dbObjects, stats);
            
            // ÉTAPE 7 : CONTRAINTES
            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 7/9 : MIGRATION DES CONTRAINTES");
            System.out.println("=".repeat(80));
            migrateConstraints(oracle, postgres, dbObjects, stats);
            
            // ÉTAPE 8 : INDEX
            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 8/9 : MIGRATION DES INDEX");
            System.out.println("=".repeat(80));
            migrateIndexes(oracle, postgres, dbObjects, stats);
            
            // ÉTAPE 9 : TRIGGERS
            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 9/9 : MIGRATION DES TRIGGERS");
            System.out.println("=".repeat(80));
            migrateTriggers(oracle, postgres, dbObjects, stats);
            
            long endTime = System.currentTimeMillis();
            long duration = (endTime - startTime) / 1000;
            
            System.out.println("\n" + "=".repeat(80));
            System.out.println("=== MIGRATION TERMINÉE ===");
            System.out.println("=".repeat(80));
            System.out.println("⏱️  Durée totale: " + duration + " secondes");
            
            stats.printSummary();
            
        } catch (Exception e) {
            System.err.println("\n❌ ERREUR CRITIQUE LORS DE LA MIGRATION:");
            e.printStackTrace();
            stats.printSummary();
            throw new SQLException("Migration échouée: " + e.getMessage(), e);
        }
    }

    // Classe pour stocker les objets de base de données
    private static class DatabaseObjects {
        Set<String> validTables = new HashSet<>();
        Set<String> validSequences = new HashSet<>();
        Set<String> validFunctions = new HashSet<>();
        Set<String> validViews = new HashSet<>();
        Set<String> validTriggers = new HashSet<>();
        
        Map<String, Set<String>> viewDependencies = new HashMap<>();
        Map<String, Set<String>> functionDependencies = new HashMap<>();
    }

    // ========== VALIDATION DES OBJETS ORACLE ==========
    private static DatabaseObjects validateOracleObjects(Oracle oracle) throws SQLException {
        DatabaseObjects db = new DatabaseObjects();
        Connection conn = OracleService.OracleConnexion(oracle);
        
        try {
            // Récupérer toutes les tables valides
            System.out.println("🔍 Validation des tables...");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT table_name FROM user_tables")) {
                while (rs.next()) {
                    db.validTables.add(rs.getString(1));
                }
            }
            System.out.println("✅ Tables valides: " + db.validTables.size());
            
            // Récupérer toutes les séquences valides
            System.out.println("🔍 Validation des séquences...");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT sequence_name FROM user_sequences")) {
                while (rs.next()) {
                    String seqName = rs.getString(1);
                    if (isSequenceValid(conn, seqName)) {
                        db.validSequences.add(seqName);
                    }
                }
            }
            System.out.println("✅ Séquences valides: " + db.validSequences.size());
            
            // Récupérer toutes les fonctions valides
            System.out.println("🔍 Validation des fonctions...");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT object_name FROM user_objects WHERE object_type = 'FUNCTION'")) {
                while (rs.next()) {
                    db.validFunctions.add(rs.getString(1));
                }
            }
            System.out.println("✅ Fonctions valides: " + db.validFunctions.size());
            
            // Récupérer toutes les vues et leurs dépendances
            System.out.println("🔍 Validation des vues et dépendances...");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT view_name FROM user_views")) {
                while (rs.next()) {
                    String viewName = rs.getString(1);
                    // Vérifier si la vue est valide en vérifiant qu'elle a une définition
                    if (isViewValid(conn, viewName)) {
                        Set<String> deps = getViewDependencies(conn, viewName, db);
                        if (deps != null) {
                            db.validViews.add(viewName);
                            db.viewDependencies.put(viewName, deps);
                        }
                    }
                }
            }
            System.out.println("✅ Vues valides: " + db.validViews.size());
            
            // Récupérer les triggers valides
            System.out.println("🔍 Validation des triggers...");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT trigger_name FROM user_triggers WHERE status = 'ENABLED'")) {
                while (rs.next()) {
                    db.validTriggers.add(rs.getString(1));
                }
            }
            System.out.println("✅ Triggers valides: " + db.validTriggers.size());
            
        } finally {
            conn.close();
        }
        
        return db;
    }

    // Vérifier si une séquence est valide
    private static boolean isSequenceValid(Connection conn, String seqName) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT last_number, increment_by, min_value, max_value FROM user_sequences WHERE sequence_name = ?")) {
            ps.setString(1, seqName.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long lastNumber = rs.getLong(1);
                    long increment = rs.getLong(2);
                    long minValue = rs.getLong(3);
                    long maxValue = rs.getLong(4);
                    return lastNumber >= minValue && lastNumber <= maxValue && increment != 0;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    // Vérifier si une vue est valide
    private static boolean isViewValid(Connection conn, String viewName) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT text FROM user_views WHERE view_name = ?")) {
            ps.setString(1, viewName.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String text = rs.getString(1);
                    return text != null && !text.trim().isEmpty();
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    // Récupérer les dépendances d'une vue
    private static Set<String> getViewDependencies(Connection conn, String viewName, DatabaseObjects db) {
        Set<String> deps = new HashSet<>();
        
        try {
            // Récupérer la définition de la vue
            String viewDef = getViewDefinition(conn, viewName);
            if (viewDef == null || viewDef.trim().isEmpty()) {
                return null;
            }
            
            String upperDef = viewDef.toUpperCase();
            
            // Vérifier les dépendances sur les tables
            for (String table : db.validTables) {
                if (upperDef.contains(table.toUpperCase())) {
                    deps.add("TABLE:" + table);
                }
            }
            
            // Vérifier les dépendances sur les fonctions
            for (String func : db.validFunctions) {
                if (upperDef.contains(func.toUpperCase() + "(")) {
                    deps.add("FUNCTION:" + func);
                }
            }
            
            // Vérifier les dépendances sur d'autres vues
            for (String view : db.validViews) {
                if (!view.equals(viewName) && upperDef.contains(view.toUpperCase())) {
                    deps.add("VIEW:" + view);
                }
            }
            
            // Vérifier que toutes les dépendances existent
            for (String dep : deps) {
                String[] parts = dep.split(":", 2);
                String type = parts[0];
                String name = parts[1];
                
                if (type.equals("TABLE") && !db.validTables.contains(name)) {
                    System.out.println("⚠️  Vue " + viewName + " dépend de la table manquante: " + name);
                    return null;
                }
                if (type.equals("FUNCTION") && !db.validFunctions.contains(name)) {
                    System.out.println("⚠️  Vue " + viewName + " dépend de la fonction manquante: " + name);
                    return null;
                }
            }
            
            return deps;
            
        } catch (Exception e) {
            System.err.println("❌ Erreur analyse dépendances vue " + viewName + ": " + e.getMessage());
            return null;
        }
    }

    // ========== 1. MIGRATION DES TABLES ==========
    private static void migrateTables(Oracle oracle, PostgreSQL postgres, DatabaseObjects db, MigrationStats stats) throws SQLException {
        stats.tablesTotal = db.validTables.size();
        System.out.println("📊 Nombre de tables à migrer: " + stats.tablesTotal);
        
        for (String tableName : db.validTables) {
            try {
                String createSQL = OracleService.generateCreateTableSQL(oracle, tableName);
                OracleService.createPostgresTable(postgres, createSQL);
                System.out.println("✅ Table créée: " + tableName);
                stats.tablesSuccess++;
            } catch (Exception e) {
                System.err.println("❌ Erreur table " + tableName + ": " + e.getMessage());
                stats.tablesFailed++;
            }
        }
    }

    // ========== 2. MIGRATION DES DONNÉES ==========
    private static void migrateData(Oracle oracle, PostgreSQL postgres, DatabaseObjects db, MigrationStats stats) throws SQLException {
        stats.dataTotal = db.validTables.size();
        System.out.println("📊 Nombre de tables à remplir: " + stats.dataTotal);
        
        for (String tableName : db.validTables) {
            try {
                OracleService.insertDataIntoPostgres(oracle, postgres, tableName);
                System.out.println("✅ Données migrées: " + tableName);
                stats.dataSuccess++;
            } catch (Exception e) {
                System.err.println("❌ Erreur données " + tableName + ": " + e.getMessage());
                stats.dataFailed++;
            }
        }
    }

    // ========== 3. MIGRATION DES SÉQUENCES ==========
    private static void migrateSequences(Oracle oracle, PostgreSQL postgres, DatabaseObjects db, MigrationStats stats) throws SQLException {
        stats.sequencesTotal = db.validSequences.size();
        System.out.println("📊 Nombre de séquences à migrer: " + stats.sequencesTotal);
        
        if (db.validSequences.isEmpty()) return;
        
        final long PG_MAX_VALUE = 9223372036854775807L;
        
        for (String seqName : db.validSequences) {
            try {
                SequenceInfo info = getSequenceInfo(oracle, seqName);
                
                // Ajuster maxValue si nécessaire
                if (info.getMaxValue() > PG_MAX_VALUE || info.getMaxValue() <= 0) {
                    System.out.println("⚠️  Séquence " + seqName + ": maxValue ajusté de " + 
                        info.getMaxValue() + " à " + PG_MAX_VALUE);
                    info.setMaxValue(PG_MAX_VALUE);
                }
                
                String createSQL = generateSequenceSQL(info);
                executeSQL(postgres, createSQL);
                syncSequenceValue(postgres, seqName, info.getLastValue());
                
                System.out.println("✅ Séquence créée: " + seqName + " (valeur: " + info.getLastValue() + ")");
                stats.sequencesSuccess++;
                
            } catch (Exception e) {
                System.err.println("❌ Erreur séquence " + seqName + ": " + e.getMessage());
                stats.sequencesFailed++;
            }
        }
    }

    // ========== 4. MIGRATION DES FONCTIONS ==========
    private static void migrateFunctions(Oracle oracle, PostgreSQL postgres, DatabaseObjects db, MigrationStats stats) throws SQLException {
        List<String> orderedFunctions = sortFunctionsByDependencies(oracle, db);
        stats.functionsTotal = orderedFunctions.size();
        System.out.println("📊 Nombre de fonctions à migrer: " + stats.functionsTotal);
        
        if (orderedFunctions.isEmpty()) return;
        
        Connection oracleConn = OracleService.OracleConnexion(oracle);
        Set<String> created = new HashSet<>();
        
        for (int pass = 1; pass <= 5; pass++) {
            int passSuccess = 0;
            
            for (String funcName : orderedFunctions) {
                if (created.contains(funcName)) continue;
                
                try {
                    String oracleSQL = getFunctionDefinition(oracleConn, funcName);
                    if (oracleSQL == null || oracleSQL.trim().isEmpty()) {
                        System.err.println("⚠️  Fonction " + funcName + ": définition vide");
                        continue;
                    }
                    
                    String pgSQL = convertFunctionToPostgres(oracleSQL);
                    if (pgSQL == null || pgSQL.trim().isEmpty()) {
                        System.err.println("⚠️  Fonction " + funcName + ": conversion échouée");
                        continue;
                    }
                    
                    executeSQL(postgres, pgSQL);
                    created.add(funcName);
                    passSuccess++;
                    System.out.println("✅ Fonction créée: " + funcName);
                } catch (Exception e) {
                    if (pass == 5) {
                        System.err.println("❌ Fonction échouée: " + funcName + " - " + e.getMessage());
                    }
                }
            }
            
            if (passSuccess == 0) break;
        }
        
        oracleConn.close();
        stats.functionsSuccess = created.size();
        stats.functionsFailed = stats.functionsTotal - stats.functionsSuccess;
    }

    // ========== 5. MIGRATION DES VUES ==========
    private static void migrateViews(Oracle oracle, PostgreSQL postgres, DatabaseObjects db, MigrationStats stats) throws SQLException {
        List<String> orderedViews = sortViewsByDependencies(db);
        stats.viewsTotal = orderedViews.size();
        System.out.println("📊 Nombre de vues à migrer: " + stats.viewsTotal);
        
        if (orderedViews.isEmpty()) return;
        
        Connection oracleConn = OracleService.OracleConnexion(oracle);
        Set<String> created = new HashSet<>();
        
        for (int pass = 1; pass <= 5; pass++) {
            int passSuccess = 0;
            
            for (String viewName : orderedViews) {
                if (created.contains(viewName)) continue;
                
                // Vérifier que toutes les dépendances sont créées
                Set<String> deps = db.viewDependencies.get(viewName);
                boolean allDepsReady = true;
                
                if (deps != null) {
                    for (String dep : deps) {
                        if (dep.startsWith("VIEW:")) {
                            String depView = dep.substring(5);
                            if (!created.contains(depView)) {
                                allDepsReady = false;
                                break;
                            }
                        }
                    }
                }
                
                if (!allDepsReady) continue;
                
                try {
                    String oracleSQL = getViewDefinition(oracleConn, viewName);
                    if (oracleSQL == null || oracleSQL.trim().isEmpty()) continue;
                    
                    String pgSQL = convertViewToPostgres(oracleSQL);
                    String createSQL = "CREATE OR REPLACE VIEW " + viewName.toLowerCase() + " AS " + pgSQL;
                    executeSQL(postgres, createSQL);
                    created.add(viewName);
                    passSuccess++;
                    System.out.println("✅ Vue créée: " + viewName);
                } catch (Exception e) {
                    if (pass == 5) {
                        System.err.println("❌ Vue échouée: " + viewName + " - " + e.getMessage());
                    }
                }
            }
            
            if (passSuccess == 0) break;
        }
        
        oracleConn.close();
        stats.viewsSuccess = created.size();
        stats.viewsFailed = stats.viewsTotal - stats.viewsSuccess;
    }

    // ========== 6. MIGRATION DES CONTRAINTES ==========
    private static void migrateConstraints(Oracle oracle, PostgreSQL postgres, DatabaseObjects db, MigrationStats stats) throws SQLException {
        System.out.println("📊 Migration des contraintes pour " + db.validTables.size() + " tables");
        
        Connection oracleConn = OracleService.OracleConnexion(oracle);
        
        for (String tableName : db.validTables) {
            try {
                // Primary Keys
                List<String> pkConstraints = getPrimaryKeyConstraints(oracleConn, tableName);
                for (String sql : pkConstraints) {
                    stats.constraintsTotal++;
                    try {
                        executeSQL(postgres, sql);
                        stats.constraintsSuccess++;
                    } catch (Exception e) {
                        stats.constraintsFailed++;
                        System.err.println("❌ PK échouée pour " + tableName);
                    }
                }
                
                // Unique Constraints
                List<String> ukConstraints = getUniqueConstraints(oracleConn, tableName);
                for (String sql : ukConstraints) {
                    stats.constraintsTotal++;
                    try {
                        executeSQL(postgres, sql);
                        stats.constraintsSuccess++;
                    } catch (Exception e) {
                        stats.constraintsFailed++;
                        System.err.println("❌ UK échouée pour " + tableName);
                    }
                }
                
                // Check Constraints
                List<String> ckConstraints = getCheckConstraints(oracleConn, tableName);
                for (String sql : ckConstraints) {
                    stats.constraintsTotal++;
                    try {
                        executeSQL(postgres, sql);
                        stats.constraintsSuccess++;
                    } catch (Exception e) {
                        stats.constraintsFailed++;
                        System.err.println("❌ CK échouée pour " + tableName);
                    }
                }
                
            } catch (Exception e) {
                System.err.println("❌ Erreur contraintes " + tableName);
            }
        }
        
        // Foreign Keys (à la fin)
        for (String tableName : db.validTables) {
            try {
                List<String> fkConstraints = getForeignKeyConstraints(oracleConn, tableName);
                for (String sql : fkConstraints) {
                    stats.constraintsTotal++;
                    try {
                        executeSQL(postgres, sql);
                        stats.constraintsSuccess++;
                    } catch (Exception e) {
                        stats.constraintsFailed++;
                        System.err.println("❌ FK échouée pour " + tableName);
                    }
                }
            } catch (Exception e) {
                System.err.println("❌ Erreur FK " + tableName);
            }
        }
        
        oracleConn.close();
        System.out.println("✅ Contraintes: " + stats.constraintsSuccess + " réussies, " + stats.constraintsFailed + " échouées");
    }

    // ========== 7. MIGRATION DES INDEX ==========
    private static void migrateIndexes(Oracle oracle, PostgreSQL postgres, DatabaseObjects db, MigrationStats stats) throws SQLException {
        System.out.println("📊 Migration des index");
        
        Connection oracleConn = OracleService.OracleConnexion(oracle);
        
        for (String tableName : db.validTables) {
            try {
                List<String> indexes = getIndexes(oracleConn, tableName);
                for (String sql : indexes) {
                    stats.indexesTotal++;
                    try {
                        executeSQL(postgres, sql);
                        stats.indexesSuccess++;
                    } catch (Exception e) {
                        stats.indexesFailed++;
                        System.err.println("❌ Index échoué pour " + tableName);
                    }
                }
            } catch (Exception e) {
                System.err.println("❌ Erreur indexes " + tableName);
            }
        }
        
        oracleConn.close();
        System.out.println("✅ Index: " + stats.indexesSuccess + " réussis, " + stats.indexesFailed + " échoués");
    }

    // ========== 8. MIGRATION DES TRIGGERS ==========
    private static void migrateTriggers(Oracle oracle, PostgreSQL postgres, DatabaseObjects db, MigrationStats stats) throws SQLException {
        stats.triggersTotal = db.validTriggers.size();
        System.out.println("📊 Nombre de triggers à migrer: " + stats.triggersTotal);
        
        Connection oracleConn = OracleService.OracleConnexion(oracle);
        
        for (String triggerName : db.validTriggers) {
            try {
                String oracleSQL = getTriggerDefinition(oracleConn, triggerName);
                String pgSQL = convertTriggerToPostgres(oracleSQL, triggerName);
                if (pgSQL != null) {
                    executeSQL(postgres, pgSQL);
                    System.out.println("✅ Trigger créé: " + triggerName);
                    stats.triggersSuccess++;
                } else {
                    stats.triggersFailed++;
                }
            } catch (Exception e) {
                System.err.println("❌ Trigger échoué: " + triggerName);
                stats.triggersFailed++;
            }
        }
        
        oracleConn.close();
    }

    // ========== FONCTIONS UTILITAIRES ==========
    
    private static void executeSQL(PostgreSQL postgres, String sql) throws SQLException {
        Connection conn = PostgresService.PostgresConnexion(postgres);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        } finally {
            conn.close();
        }
    }

    private static SequenceInfo getSequenceInfo(Oracle oracle, String name) throws SQLException {
        SequenceInfo info = new SequenceInfo();
        info.setName(name);
        Connection conn = OracleService.OracleConnexion(oracle);
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT last_number, increment_by, min_value, max_value, cycle_flag, cache_size FROM user_sequences WHERE sequence_name = ?")) {
            ps.setString(1, name.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    info.setLastValue(rs.getLong(1));
                    info.setIncrementBy(rs.getLong(2));
                    info.setMinValue(rs.getLong(3));
                    info.setMaxValue(rs.getLong(4));
                    info.setCycle("Y".equals(rs.getString(5)));
                    info.setCacheSize(rs.getLong(6));
                }
            }
        } finally {
            conn.close();
        }
        return info;
    }

    private static String generateSequenceSQL(SequenceInfo info) {
        return "CREATE SEQUENCE IF NOT EXISTS " + info.getName().toLowerCase() +
               " INCREMENT BY " + info.getIncrementBy() +
               " MINVALUE " + info.getMinValue() +
               " MAXVALUE " + info.getMaxValue() +
               " START WITH " + info.getLastValue() +
               " CACHE " + info.getCacheSize() +
               (info.isCycle() ? " CYCLE" : " NO CYCLE") + ";";
    }

    private static void syncSequenceValue(PostgreSQL postgres, String name, long value) throws SQLException {
        String sql = "SELECT setval('" + name.toLowerCase() + "', " + value + ", false)";
        Connection conn = PostgresService.PostgresConnexion(postgres);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeQuery(sql);
        } finally {
            conn.close();
        }
    }

    private static List<String> sortFunctionsByDependencies(Oracle oracle, DatabaseObjects db) throws SQLException {
        Connection conn = OracleService.OracleConnexion(oracle);
        Map<String, Set<String>> deps = new HashMap<>();
        
        for (String func : db.validFunctions) {
            String sql = getFunctionDefinition(conn, func);
            Set<String> funcDeps = new HashSet<>();
            if (sql != null) {
                String upper = sql.toUpperCase();
                for (String other : db.validFunctions) {
                    if (!other.equals(func) && upper.contains(other.toUpperCase() + "(")) {
                        funcDeps.add(other);
                    }
                }
            }
            deps.put(func, funcDeps);
        }
        
        conn.close();
        return topologicalSort(deps);
    }

    private static List<String> sortViewsByDependencies(DatabaseObjects db) {
        return topologicalSort(db.viewDependencies.entrySet().stream()
            .collect(HashMap::new, 
                (m, e) -> m.put(e.getKey(), 
                    e.getValue().stream()
                        .filter(d -> d.startsWith("VIEW:"))
                        .map(d -> d.substring(5))
                        .collect(HashSet::new, HashSet::add, HashSet::addAll)
                ), 
                HashMap::putAll));
    }

    private static List<String> topologicalSort(Map<String, Set<String>> deps) {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        
        for (String item : deps.keySet()) {
            if (!visited.contains(item)) {
                dfs(item, deps, visited, visiting, result);
            }
        }
        return result;
    }

    private static void dfs(String item, Map<String, Set<String>> deps, Set<String> visited, Set<String> visiting, List<String> result) {
        if (visited.contains(item)) return;
        if (visiting.contains(item)) {
            if (!result.contains(item)) {
                result.add(item);
                visited.add(item);
            }
            return;
        }
        
        visiting.add(item);
        for (String dep : deps.getOrDefault(item, Collections.emptySet())) {
            dfs(dep, deps, visited, visiting, result);
        }
        visiting.remove(item);
        
        if (!result.contains(item)) result.add(item);
        visited.add(item);
    }

    private static String getFunctionDefinition(Connection conn, String name) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT text FROM user_source WHERE name = ? AND type = 'FUNCTION' ORDER BY line")) {
            ps.setString(1, name.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) sb.append(rs.getString(1));
            }
        }
        return sb.toString();
    }

    private static String getViewDefinition(Connection conn, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT text FROM user_views WHERE view_name = ?")) {
            ps.setString(1, name.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        return null;
    }

    private static String convertFunctionToPostgres(String oracleSQL) {
        if (oracleSQL == null || oracleSQL.trim().isEmpty()) {
            return null;
        }
        
        String pgSQL = oracleSQL
            // Suppression des éléments Oracle-specific
            .replaceAll("(?i)\\bOR REPLACE\\b", "OR REPLACE")
            .replaceAll("(?i)\\bAUTHID CURRENT_USER\\b", "")
            .replaceAll("(?i)\\bAUTHID DEFINER\\b", "")
            .replaceAll("(?i)\\bDETERMINISTIC\\b", "")
            .replaceAll("(?i)\\bPARALLEL_ENABLE\\b", "")
            
            // Types de données Oracle → PostgreSQL
            .replaceAll("(?i)\\bNUMBER\\s*\\(\\s*\\d+\\s*,\\s*\\d+\\s*\\)", "NUMERIC")
            .replaceAll("(?i)\\bNUMBER\\s*\\(\\s*\\d+\\s*\\)", "NUMERIC")
            .replaceAll("(?i)\\bNUMBER\\b", "NUMERIC")
            .replaceAll("(?i)\\bVARCHAR2\\s*\\(", "VARCHAR(")
            .replaceAll("(?i)\\bVARCHAR2\\b", "VARCHAR")
            .replaceAll("(?i)\\bCLOB\\b", "TEXT")
            .replaceAll("(?i)\\bBLOB\\b", "BYTEA")
            .replaceAll("(?i)\\bRAW\\b", "BYTEA")
            .replaceAll("(?i)\\bLONG\\b", "TEXT")
            
            // Syntaxe fonction Oracle → PostgreSQL
            .replaceAll("(?i)\\bRETURN\\s+NUMBER\\b", "RETURNS NUMERIC")
            .replaceAll("(?i)\\bRETURN\\s+VARCHAR2", "RETURNS VARCHAR")
            .replaceAll("(?i)\\bRETURN\\s+VARCHAR", "RETURNS VARCHAR")
            .replaceAll("(?i)\\bRETURN\\s+CLOB\\b", "RETURNS TEXT")
            .replaceAll("(?i)\\bRETURN\\s+DATE\\b", "RETURNS TIMESTAMP")
            .replaceAll("(?i)\\bRETURN\\b", "RETURNS")
            
            // IS/AS → AS $ (PL/pgSQL)
            .replaceAll("(?i)\\bIS\\s*$", "AS $")
            .replaceAll("(?i)\\bAS\\s*$", "AS $")
            .replaceAll("(?i)\\bIS\\s*\\n", "AS $\n")
            .replaceAll("(?i)\\bAS\\s*\\n", "AS $\n")
            
            // BEGIN → DECLARE section
            .replaceAll("(?i)(AS \\$\\$)\\s*BEGIN", "$1\nBEGIN")
            
            // END; → END; $ LANGUAGE plpgsql;
            .replaceAll("(?i)\\bEND;\\s*$", "END;\n$ LANGUAGE plpgsql;")
            .replaceAll("(?i)\\bEND\\s+\\w+;\\s*$", "END;\n$ LANGUAGE plpgsql;")
            
            // Fonctions Oracle → PostgreSQL
            .replaceAll("(?i)\\bSYSDATE\\b", "CURRENT_TIMESTAMP")
            .replaceAll("(?i)\\bNVL\\s*\\(", "COALESCE(")
            .replaceAll("(?i)\\bNVL2\\s*\\(([^,]+),([^,]+),([^)]+)\\)", "CASE WHEN $1 IS NOT NULL THEN $2 ELSE $3 END")
            .replaceAll("(?i)\\bDECODE\\s*\\(", "CASE ")
            .replaceAll("(?i)\\bTO_CHAR\\s*\\(", "TO_CHAR(")
            .replaceAll("(?i)\\bTO_NUMBER\\s*\\(", "TO_NUMBER(")
            .replaceAll("(?i)\\bTO_DATE\\s*\\(", "TO_TIMESTAMP(")
            .replaceAll("(?i)\\bSUBSTR\\s*\\(", "SUBSTRING(")
            .replaceAll("(?i)\\bINSTR\\s*\\(", "POSITION(")
            .replaceAll("(?i)\\bTRUNC\\s*\\(", "DATE_TRUNC('day', ")
            
            // Gestion des séquences Oracle → PostgreSQL
            .replaceAll("(?i)(\\w+)\\.NEXTVAL", "NEXTVAL('$1')")
            .replaceAll("(?i)(\\w+)\\.CURRVAL", "CURRVAL('$1')")
            
            // Variables et curseurs
            .replaceAll("(?i)\\bDECLARE\\s+", "DECLARE\n  ")
            .replaceAll("(?i)\\bCURSOR\\s+(\\w+)\\s+IS", "CURSOR $1 FOR")
            
            // Boucles
            .replaceAll("(?i)\\bFOR\\s+(\\w+)\\s+IN\\s+", "FOR $1 IN ")
            .replaceAll("(?i)\\bLOOP\\b", "LOOP")
            .replaceAll("(?i)\\bEND LOOP;", "END LOOP;")
            
            // IF THEN ELSE
            .replaceAll("(?i)\\bTHEN\\b", "THEN")
            .replaceAll("(?i)\\bELSIF\\b", "ELSIF")
            .replaceAll("(?i)\\bEND IF;", "END IF;")
            
            // Gestion des exceptions
            .replaceAll("(?i)\\bEXCEPTION\\s+WHEN", "EXCEPTION\n  WHEN")
            .replaceAll("(?i)\\bNO_DATA_FOUND", "NO_DATA_FOUND")
            .replaceAll("(?i)\\bTOO_MANY_ROWS", "TOO_MANY_ROWS")
            .replaceAll("(?i)\\bOTHERS\\s+THEN", "OTHERS THEN")
            
            // RAISE
            .replaceAll("(?i)\\bRAISE_APPLICATION_ERROR\\s*\\(\\s*-?\\d+\\s*,\\s*([^)]+)\\)", "RAISE EXCEPTION $1")
            
            // DBMS_OUTPUT
            .replaceAll("(?i)\\bDBMS_OUTPUT\\.PUT_LINE\\s*\\(", "RAISE NOTICE '%', ")
            
            // NULL statement
            .replaceAll("(?i)\\bNULL;", "NULL;")
            
            // Nettoyage des doubles espaces
            .replaceAll("\\s+", " ")
            .replaceAll("\\s*;\\s*", ";\n");
        
        // Vérifier si la fonction se termine correctement
        if (!pgSQL.contains("$ LANGUAGE plpgsql")) {
            // Ajouter la fin si manquante
            pgSQL = pgSQL.replaceAll("(?i)\\s*END;?\\s*$", "") + "\nEND;\n$ LANGUAGE plpgsql;";
        }
        
        return pgSQL;
    }

    private static String convertViewToPostgres(String oracleSQL) {
        return OracleService.enhancedMapOracleViewSQLToPostgres(oracleSQL);
    }

    private static List<String> getPrimaryKeyConstraints(Connection conn, String table) throws SQLException {
        List<String> list = new ArrayList<>();
        String sql = "SELECT cols.column_name FROM user_constraints cons " +
                    "JOIN user_cons_columns cols ON cons.constraint_name = cols.constraint_name " +
                    "WHERE cons.table_name = ? AND cons.constraint_type = 'P' ORDER BY cols.position";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table.toUpperCase());
            List<String> columns = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) columns.add(rs.getString(1).toLowerCase());
            }
            if (!columns.isEmpty()) {
                list.add("ALTER TABLE " + table.toLowerCase() + " ADD PRIMARY KEY (" + String.join(", ", columns) + ");");
            }
        }
        return list;
    }

    private static List<String> getForeignKeyConstraints(Connection conn, String table) throws SQLException {
        List<String> list = new ArrayList<>();
        String sql = "SELECT cons.constraint_name, cols.column_name, ref_cons.table_name, ref_cols.column_name " +
                    "FROM user_constraints cons " +
                    "JOIN user_cons_columns cols ON cons.constraint_name = cols.constraint_name " +
                    "JOIN user_constraints ref_cons ON cons.r_constraint_name = ref_cons.constraint_name " +
                    "JOIN user_cons_columns ref_cols ON ref_cons.constraint_name = ref_cols.constraint_name " +
                    "WHERE cons.table_name = ? AND cons.constraint_type = 'R'";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String fkCol = rs.getString(2).toLowerCase();
                    String refTable = rs.getString(3).toLowerCase();
                    String refCol = rs.getString(4).toLowerCase();
                    list.add("ALTER TABLE " + table.toLowerCase() + " ADD FOREIGN KEY (" + fkCol + ") REFERENCES " + refTable + "(" + refCol + ");");
                }
            }
        }
        return list;
    }

    private static List<String> getUniqueConstraints(Connection conn, String table) throws SQLException {
        List<String> list = new ArrayList<>();
        String sql = "SELECT cons.constraint_name, cols.column_name FROM user_constraints cons " +
                    "JOIN user_cons_columns cols ON cons.constraint_name = cols.constraint_name " +
                    "WHERE cons.table_name = ? AND cons.constraint_type = 'U'";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table.toUpperCase());
            Map<String, List<String>> uks = new HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    String col = rs.getString(2).toLowerCase();
                    uks.computeIfAbsent(name, k -> new ArrayList<>()).add(col);
                }
            }
            for (List<String> cols : uks.values()) {
                list.add("ALTER TABLE " + table.toLowerCase() + " ADD UNIQUE (" + String.join(", ", cols) + ");");
            }
        }
        return list;
    }

    private static List<String> getCheckConstraints(Connection conn, String table) throws SQLException {
        List<String> list = new ArrayList<>();
        String sql = "SELECT search_condition FROM user_constraints WHERE table_name = ? AND constraint_type = 'C' AND constraint_name NOT LIKE 'SYS_%'";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String condition = rs.getString(1);
                    if (condition != null && !condition.contains("IS NOT NULL")) {
                        list.add("ALTER TABLE " + table.toLowerCase() + " ADD CHECK (" + condition.toLowerCase() + ");");
                    }
                }
            }
        }
        return list;
    }

    private static List<String> getIndexes(Connection conn, String table) throws SQLException {
        List<String> list = new ArrayList<>();
        String sql = "SELECT idx.index_name, idx.uniqueness, cols.column_name " +
                    "FROM user_indexes idx " +
                    "JOIN user_ind_columns cols ON idx.index_name = cols.index_name " +
                    "WHERE idx.table_name = ? AND idx.index_name NOT IN " +
                    "(SELECT constraint_name FROM user_constraints WHERE table_name = ? AND constraint_type IN ('P', 'U')) " +
                    "ORDER BY idx.index_name, cols.column_position";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table.toUpperCase());
            ps.setString(2, table.toUpperCase());
            Map<String, List<String>> indexes = new HashMap<>();
            Map<String, Boolean> unique = new HashMap<>();
            
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    unique.put(name, "UNIQUE".equals(rs.getString(2)));
                    String col = rs.getString(3).toLowerCase();
                    indexes.computeIfAbsent(name, k -> new ArrayList<>()).add(col);
                }
            }
            
            for (Map.Entry<String, List<String>> entry : indexes.entrySet()) {
                String idxName = entry.getKey().toLowerCase();
                List<String> cols = entry.getValue();
                String uniqueStr = unique.get(entry.getKey()) ? "UNIQUE " : "";
                list.add("CREATE " + uniqueStr + "INDEX " + idxName + " ON " + table.toLowerCase() + " (" + String.join(", ", cols) + ");");
            }
        }
        return list;
    }

    private static String getTriggerDefinition(Connection conn, String name) throws SQLException {
        StringBuilder sb = new StringBuilder();
        String sql = "SELECT trigger_type, triggering_event, table_name, trigger_body FROM user_triggers WHERE trigger_name = ?";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String type = rs.getString(1);
                    String event = rs.getString(2);
                    String table = rs.getString(3);
                    String body = rs.getString(4);
                    
                    sb.append("TYPE:").append(type).append("\n");
                    sb.append("EVENT:").append(event).append("\n");
                    sb.append("TABLE:").append(table).append("\n");
                    sb.append("BODY:").append(body);
                }
            }
        }
        return sb.toString();
    }

    private static String convertTriggerToPostgres(String oracleSQL, String triggerName) {
        if (oracleSQL == null || oracleSQL.isEmpty()) return null;
        
        try {
            String[] parts = oracleSQL.split("\n");
            String type = "", event = "", table = "", body = "";
            
            for (String part : parts) {
                if (part.startsWith("TYPE:")) type = part.substring(5);
                else if (part.startsWith("EVENT:")) event = part.substring(6);
                else if (part.startsWith("TABLE:")) table = part.substring(6);
                else if (part.startsWith("BODY:")) body = part.substring(5);
            }
            
            if (table.isEmpty() || event.isEmpty()) return null;
            
            // Convertir le timing
            String timing = type.contains("BEFORE") ? "BEFORE" : "AFTER";
            
            // Convertir l'événement
            String pgEvent = event.toUpperCase()
                .replace(" OR ", " OR ")
                .replace("INSERT", "INSERT")
                .replace("UPDATE", "UPDATE")
                .replace("DELETE", "DELETE");
            
            // Convertir le corps
            String pgBody = body
                .replaceAll("(?i):NEW\\.", "NEW.")
                .replaceAll("(?i):OLD\\.", "OLD.")
                .replaceAll("(?i)\\bNUMBER\\b", "NUMERIC")
                .replaceAll("(?i)\\bVARCHAR2\\b", "VARCHAR")
                .replaceAll("(?i)\\bDATE\\b", "TIMESTAMP")
                .replaceAll("(?i)\\bSYSDATE\\b", "CURRENT_TIMESTAMP")
                .replaceAll("(?i)\\bNVL\\(", "COALESCE(");
            
            // Créer la fonction trigger
            String funcName = "trg_func_" + triggerName.toLowerCase();
            StringBuilder sql = new StringBuilder();
            
            sql.append("CREATE OR REPLACE FUNCTION ").append(funcName).append("()\n");
            sql.append("RETURNS TRIGGER LANGUAGE plpgsql AS $\n");
            sql.append("BEGIN\n");
            sql.append(pgBody);
            if (!pgBody.trim().endsWith(";")) sql.append(";");
            sql.append("\n");
            sql.append("RETURN NEW;\n");
            sql.append("END;\n");
            sql.append("$;\n\n");
            
            // Créer le trigger
            sql.append("CREATE TRIGGER ").append(triggerName.toLowerCase()).append("\n");
            sql.append(timing).append(" ").append(pgEvent).append(" ON ").append(table.toLowerCase()).append("\n");
            sql.append("FOR EACH ROW EXECUTE FUNCTION ").append(funcName).append("();");
            
            return sql.toString();
            
        } catch (Exception e) {
            System.err.println("❌ Erreur conversion trigger: " + e.getMessage());
            return null;
        }
    }
}