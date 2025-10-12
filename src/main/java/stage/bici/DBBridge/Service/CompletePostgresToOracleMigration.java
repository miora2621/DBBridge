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

public class CompletePostgresToOracleMigration {
    
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
    
    // ========== MIGRATION COMPLÈTE POSTGRESQL → ORACLE ==========
    public static void migrateCompleteDatabase(PostgreSQL postgres, Oracle oracle) throws SQLException {
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("=== MIGRATION COMPLÈTE POSTGRESQL → ORACLE ===");
        System.out.println("=".repeat(80));
        
        long startTime = System.currentTimeMillis();
        MigrationStats stats = new MigrationStats();
        
        try {
            // ÉTAPE 1 : VALIDATION DES OBJETS POSTGRESQL
            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 1/9 : VALIDATION DES OBJETS POSTGRESQL");
            System.out.println("=".repeat(80));
            DatabaseObjects dbObjects = validatePostgresObjects(postgres);
            
            // ÉTAPE 2 : TABLES (structure seulement)
            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 2/9 : MIGRATION DES TABLES (structure)");
            System.out.println("=".repeat(80));
            migrateTables(postgres, oracle, dbObjects, stats);
            
            // ÉTAPE 3 : DONNÉES
            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 3/9 : MIGRATION DES DONNÉES");
            System.out.println("=".repeat(80));
            migrateData(postgres, oracle, dbObjects, stats);
            
            // ÉTAPE 4 : SÉQUENCES
            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 4/9 : MIGRATION DES SÉQUENCES");
            System.out.println("=".repeat(80));
            migrateSequences(postgres, oracle, dbObjects, stats);
            
            // ÉTAPE 5 : FONCTIONS
            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 5/9 : MIGRATION DES FONCTIONS");
            System.out.println("=".repeat(80));
            migrateFunctions(postgres, oracle, dbObjects, stats);
            
            // ÉTAPE 6 : VUES
            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 6/9 : MIGRATION DES VUES");
            System.out.println("=".repeat(80));
            migrateViews(postgres, oracle, dbObjects, stats);
            
            // ÉTAPE 7 : CONTRAINTES
            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 7/9 : MIGRATION DES CONTRAINTES");
            System.out.println("=".repeat(80));
            migrateConstraints(postgres, oracle, dbObjects, stats);
            
            // ÉTAPE 8 : INDEX
            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 8/9 : MIGRATION DES INDEX");
            System.out.println("=".repeat(80));
            migrateIndexes(postgres, oracle, dbObjects, stats);
            
            // ÉTAPE 9 : TRIGGERS
            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 9/9 : MIGRATION DES TRIGGERS");
            System.out.println("=".repeat(80));
            migrateTriggers(postgres, oracle, dbObjects, stats);
            
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

    // ========== VALIDATION DES OBJETS POSTGRESQL ==========
    private static DatabaseObjects validatePostgresObjects(PostgreSQL postgres) throws SQLException {
        DatabaseObjects db = new DatabaseObjects();
        Connection conn = PostgresService.PostgresConnexion(postgres);
        
        try {
            // Récupérer toutes les tables valides
            System.out.println("🔍 Validation des tables...");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT table_name FROM information_schema.tables " +
                     "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'")) {
                while (rs.next()) {
                    db.validTables.add(rs.getString(1));
                }
            }
            System.out.println("✅ Tables valides: " + db.validTables.size());
            
            // Récupérer toutes les séquences valides
            System.out.println("🔍 Validation des séquences...");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT c.relname FROM pg_class c " +
                     "JOIN pg_namespace n ON n.oid = c.relnamespace " +
                     "WHERE c.relkind = 'S' AND n.nspname = 'public'")) {
                while (rs.next()) {
                    String seqName = rs.getString(1);
                    db.validSequences.add(seqName);
                }
            }
            System.out.println("✅ Séquences valides: " + db.validSequences.size());
            
            // Récupérer toutes les fonctions valides
            System.out.println("🔍 Validation des fonctions...");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT routine_name FROM information_schema.routines " +
                     "WHERE routine_schema = 'public' AND routine_type = 'FUNCTION'")) {
                while (rs.next()) {
                    db.validFunctions.add(rs.getString(1));
                }
            }
            System.out.println("✅ Fonctions valides: " + db.validFunctions.size());
            
            // Récupérer toutes les vues et leurs dépendances
            System.out.println("🔍 Validation des vues et dépendances...");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT viewname FROM pg_views WHERE schemaname = 'public'")) {
                while (rs.next()) {
                    String viewName = rs.getString(1);
                    Set<String> deps = getViewDependencies(conn, viewName, db);
                    // Accepter toutes les vues, même sans dépendances valides
                    if (deps != null || isViewValid(conn, viewName)) {
                        db.validViews.add(viewName);
                        if (deps != null) {
                            db.viewDependencies.put(viewName, deps);
                        } else {
                            db.viewDependencies.put(viewName, new HashSet<>());
                        }
                    }
                }
            }
            System.out.println("✅ Vues valides: " + db.validViews.size());
            
            // Récupérer les triggers valides
            System.out.println("🔍 Validation des triggers...");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT DISTINCT trigger_name FROM information_schema.triggers " +
                     "WHERE trigger_schema = 'public'")) {
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
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + seqName + " LIMIT 1")) {
            return rs.next();
        } catch (Exception e) {
            return false;
        }
    }

    // Vérifier si une vue est valide
    private static boolean isViewValid(Connection conn, String viewName) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT definition FROM pg_views " +
                "WHERE schemaname = 'public' AND viewname = ?")) {
            ps.setString(1, viewName.toLowerCase());
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
            String viewDef = getViewDefinition(conn, viewName);
            if (viewDef == null || viewDef.trim().isEmpty()) {
                // Retourner un set vide au lieu de null pour accepter la vue quand même
                return deps;
            }
            
            String upperDef = viewDef.toUpperCase();
            
            for (String table : db.validTables) {
                if (upperDef.contains(table.toUpperCase())) {
                    deps.add("TABLE:" + table);
                }
            }
            
            for (String func : db.validFunctions) {
                if (upperDef.contains(func.toUpperCase() + "(")) {
                    deps.add("FUNCTION:" + func);
                }
            }
            
            for (String view : db.validViews) {
                if (!view.equals(viewName) && upperDef.contains(view.toUpperCase())) {
                    deps.add("VIEW:" + view);
                }
            }
            
            return deps;
            
        } catch (Exception e) {
            System.err.println("⚠️  Erreur analyse dépendances vue " + viewName + ": " + e.getMessage());
            // Retourner un set vide au lieu de null
            return deps;
        }
    }

    // ========== 1. MIGRATION DES TABLES ==========
    private static void migrateTables(PostgreSQL postgres, Oracle oracle, DatabaseObjects db, MigrationStats stats) throws SQLException {
        stats.tablesTotal = db.validTables.size();
        System.out.println("📊 Nombre de tables à migrer: " + stats.tablesTotal);
        
        for (String tableName : db.validTables) {
            try {
                String createSQL = PostgresService.generateCreateTableSQL(postgres, tableName);
                PostgresService.createOracleTable(oracle, createSQL);
                System.out.println("✅ Table créée: " + tableName);
                stats.tablesSuccess++;
            } catch (Exception e) {
                System.err.println("❌ Erreur table " + tableName + ": " + e.getMessage());
                stats.tablesFailed++;
            }
        }
    }

    // ========== 2. MIGRATION DES DONNÉES ==========
    private static void migrateData(PostgreSQL postgres, Oracle oracle, DatabaseObjects db, MigrationStats stats) throws SQLException {
        stats.dataTotal = db.validTables.size();
        System.out.println("📊 Nombre de tables à remplir: " + stats.dataTotal);
        
        for (String tableName : db.validTables) {
            try {
                PostgresService.insertDataIntoOracle(postgres, oracle, tableName);
                System.out.println("✅ Données migrées: " + tableName);
                stats.dataSuccess++;
            } catch (Exception e) {
                System.err.println("❌ Erreur données " + tableName + ": " + e.getMessage());
                stats.dataFailed++;
            }
        }
    }

    // ========== 3. MIGRATION DES SÉQUENCES ==========
    private static void migrateSequences(PostgreSQL postgres, Oracle oracle, DatabaseObjects db, MigrationStats stats) throws SQLException {
        stats.sequencesTotal = db.validSequences.size();
        System.out.println("📊 Nombre de séquences à migrer: " + stats.sequencesTotal);
        
        if (db.validSequences.isEmpty()) return;
        
        final long ORACLE_MAX_VALUE = 999999999999999999L;
        
        for (String seqName : db.validSequences) {
            try {
                SequenceInfo info = getSequenceInfo(postgres, seqName);
                
                if (info.getMaxValue() > ORACLE_MAX_VALUE || info.getMaxValue() <= 0) {
                    System.out.println("⚠️  Séquence " + seqName + ": maxValue ajusté de " + 
                        info.getMaxValue() + " à " + ORACLE_MAX_VALUE);
                    info.setMaxValue(ORACLE_MAX_VALUE);
                }
                
                String createSQL = generateOracleSequenceSQL(info);
                System.out.println("🔧 SQL: " + createSQL);
                executeSQL(oracle, createSQL);
                
                System.out.println("✅ Séquence créée: " + seqName + " (valeur: " + info.getLastValue() + ")");
                stats.sequencesSuccess++;
                
            } catch (Exception e) {
                System.err.println("❌ Erreur séquence " + seqName + ": " + e.getMessage());
                e.printStackTrace();
                stats.sequencesFailed++;
            }
        }
    }

    // ========== 4. MIGRATION DES FONCTIONS ==========
    private static void migrateFunctions(PostgreSQL postgres, Oracle oracle, DatabaseObjects db, MigrationStats stats) throws SQLException {
        List<String> orderedFunctions = sortFunctionsByDependencies(postgres, db);
        stats.functionsTotal = orderedFunctions.size();
        System.out.println("📊 Nombre de fonctions à migrer: " + stats.functionsTotal);
        
        if (orderedFunctions.isEmpty()) return;
        
        Connection pgConn = PostgresService.PostgresConnexion(postgres);
        Set<String> created = new HashSet<>();
        
        for (int pass = 1; pass <= 5; pass++) {
            int passSuccess = 0;
            
            for (String funcName : orderedFunctions) {
                if (created.contains(funcName)) continue;
                
                try {
                    String pgSQL = getFunctionDefinition(pgConn, funcName);
                    if (pgSQL == null || pgSQL.trim().isEmpty()) {
                        System.err.println("⚠️  Fonction " + funcName + ": définition vide");
                        continue;
                    }
                    
                    String oracleSQL = convertFunctionToOracle(pgSQL);
                    if (oracleSQL == null || oracleSQL.trim().isEmpty()) {
                        System.err.println("⚠️  Fonction " + funcName + ": conversion échouée");
                        continue;
                    }
                    
                    executeSQL(oracle, oracleSQL);
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
        
        pgConn.close();
        stats.functionsSuccess = created.size();
        stats.functionsFailed = stats.functionsTotal - stats.functionsSuccess;
    }

    // ========== 5. MIGRATION DES VUES ==========
    private static void migrateViews(PostgreSQL postgres, Oracle oracle, DatabaseObjects db, MigrationStats stats) throws SQLException {
        List<String> orderedViews = sortViewsByDependencies(db);
        stats.viewsTotal = orderedViews.size();
        System.out.println("📊 Nombre de vues à migrer: " + stats.viewsTotal);
        
        if (orderedViews.isEmpty()) return;
        
        Connection pgConn = PostgresService.PostgresConnexion(postgres);
        Set<String> created = new HashSet<>();
        
        for (int pass = 1; pass <= 5; pass++) {
            int passSuccess = 0;
            
            for (String viewName : orderedViews) {
                if (created.contains(viewName)) continue;
                
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
                    String pgSQL = getViewDefinition(pgConn, viewName);
                    if (pgSQL == null || pgSQL.trim().isEmpty()) {
                        System.err.println("⚠️  Vue " + viewName + ": définition vide");
                        stats.viewsFailed++;
                        continue;
                    }
                    
                    System.out.println("🔧 Vue " + viewName + " - SQL original: " + pgSQL.substring(0, Math.min(100, pgSQL.length())) + "...");
                    
                    String oracleSQL = convertViewToOracle(pgSQL);
                    if (oracleSQL == null || oracleSQL.trim().isEmpty()) {
                        System.err.println("⚠️  Vue " + viewName + ": conversion échouée");
                        stats.viewsFailed++;
                        continue;
                    }
                    
                    String createSQL = "CREATE OR REPLACE VIEW " + viewName.toUpperCase() + " AS " + oracleSQL;
                    System.out.println("🔧 SQL Oracle: " + createSQL.substring(0, Math.min(150, createSQL.length())) + "...");
                    
                    executeSQL(oracle, createSQL);
                    created.add(viewName);
                    passSuccess++;
                    System.out.println("✅ Vue créée: " + viewName);
                } catch (Exception e) {
                    System.err.println("❌ Vue échouée: " + viewName + " - " + e.getMessage());
                    e.printStackTrace();
                    if (pass == 5) {
                        stats.viewsFailed++;
                    }
                }
            }
            
            if (passSuccess == 0) break;
        }
        
        pgConn.close();
        stats.viewsSuccess = created.size();
        stats.viewsFailed = stats.viewsTotal - stats.viewsSuccess;
    }

    // ========== 6. MIGRATION DES CONTRAINTES ==========
    private static void migrateConstraints(PostgreSQL postgres, Oracle oracle, DatabaseObjects db, MigrationStats stats) throws SQLException {
        System.out.println("📊 Migration des contraintes pour " + db.validTables.size() + " tables");
        
        Connection pgConn = PostgresService.PostgresConnexion(postgres);
        
        for (String tableName : db.validTables) {
            try {
                List<String> pkConstraints = getPrimaryKeyConstraints(pgConn, tableName);
                for (String sql : pkConstraints) {
                    stats.constraintsTotal++;
                    try {
                        executeSQL(oracle, sql);
                        stats.constraintsSuccess++;
                    } catch (Exception e) {
                        stats.constraintsFailed++;
                        System.err.println("❌ PK échouée pour " + tableName);
                    }
                }
                
                List<String> ukConstraints = getUniqueConstraints(pgConn, tableName);
                for (String sql : ukConstraints) {
                    stats.constraintsTotal++;
                    try {
                        executeSQL(oracle, sql);
                        stats.constraintsSuccess++;
                    } catch (Exception e) {
                        stats.constraintsFailed++;
                        System.err.println("❌ UK échouée pour " + tableName);
                    }
                }
                
                List<String> ckConstraints = getCheckConstraints(pgConn, tableName);
                for (String sql : ckConstraints) {
                    stats.constraintsTotal++;
                    try {
                        executeSQL(oracle, sql);
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
        
        for (String tableName : db.validTables) {
            try {
                List<String> fkConstraints = getForeignKeyConstraints(pgConn, tableName);
                for (String sql : fkConstraints) {
                    stats.constraintsTotal++;
                    try {
                        executeSQL(oracle, sql);
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
        
        pgConn.close();
        System.out.println("✅ Contraintes: " + stats.constraintsSuccess + " réussies, " + stats.constraintsFailed + " échouées");
    }

    // ========== 7. MIGRATION DES INDEX ==========
    private static void migrateIndexes(PostgreSQL postgres, Oracle oracle, DatabaseObjects db, MigrationStats stats) throws SQLException {
        System.out.println("📊 Migration des index");
        
        Connection pgConn = PostgresService.PostgresConnexion(postgres);
        
        for (String tableName : db.validTables) {
            try {
                List<String> indexes = getIndexes(pgConn, tableName);
                for (String sql : indexes) {
                    stats.indexesTotal++;
                    try {
                        executeSQL(oracle, sql);
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
        
        pgConn.close();
        System.out.println("✅ Index: " + stats.indexesSuccess + " réussis, " + stats.indexesFailed + " échoués");
    }

    // ========== 8. MIGRATION DES TRIGGERS ==========
    private static void migrateTriggers(PostgreSQL postgres, Oracle oracle, DatabaseObjects db, MigrationStats stats) throws SQLException {
        stats.triggersTotal = db.validTriggers.size();
        System.out.println("📊 Nombre de triggers à migrer: " + stats.triggersTotal);
        
        Connection pgConn = PostgresService.PostgresConnexion(postgres);
        
        for (String triggerName : db.validTriggers) {
            try {
                String pgSQL = getTriggerDefinition(pgConn, triggerName);
                String oracleSQL = convertTriggerToOracle(pgSQL, triggerName);
                if (oracleSQL != null) {
                    executeSQL(oracle, oracleSQL);
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
        
        pgConn.close();
    }

    // ========== FONCTIONS UTILITAIRES ==========
    
    private static void executeSQL(Oracle oracle, String sql) throws SQLException {
        Connection conn = OracleService.OracleConnexion(oracle);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        } finally {
            conn.close();
        }
    }

    private static SequenceInfo getSequenceInfo(PostgreSQL postgres, String name) throws SQLException {
        SequenceInfo info = new SequenceInfo();
        info.setName(name);
        Connection conn = PostgresService.PostgresConnexion(postgres);
        try {
            // Utiliser pg_sequences pour récupérer les infos (PostgreSQL 10+)
            String query = "SELECT last_value, increment_by, min_value, max_value, cycle " +
                          "FROM pg_sequences WHERE schemaname = 'public' AND sequencename = ?";
            
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, name.toLowerCase());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        info.setLastValue(rs.getLong("last_value"));
                        info.setIncrementBy(rs.getLong("increment_by"));
                        info.setMinValue(rs.getLong("min_value"));
                        info.setMaxValue(rs.getLong("max_value"));
                        info.setCycle(rs.getBoolean("cycle"));
                        info.setCacheSize(20);
                        return info;
                    }
                }
            } catch (SQLException e) {
                // Si pg_sequences n'existe pas (version < 10), utiliser l'ancienne méthode
                System.out.println("⚠️  pg_sequences non disponible, utilisation méthode alternative");
            }
            
            // Méthode alternative : interroger directement la séquence
            query = "SELECT last_value FROM " + name;
            long lastValue = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                if (rs.next()) {
                    lastValue = rs.getLong(1);
                }
            }
            
            // Récupérer les autres infos depuis information_schema
            query = "SELECT increment, minimum_value, maximum_value, cycle_option " +
                   "FROM information_schema.sequences " +
                   "WHERE sequence_schema = 'public' AND sequence_name = ?";
            
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, name.toLowerCase());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        info.setLastValue(lastValue);
                        info.setIncrementBy(Long.parseLong(rs.getString("increment")));
                        info.setMinValue(Long.parseLong(rs.getString("minimum_value")));
                        info.setMaxValue(Long.parseLong(rs.getString("maximum_value")));
                        info.setCycle("YES".equals(rs.getString("cycle_option")));
                        info.setCacheSize(20);
                    }
                }
            }
        } finally {
            conn.close();
        }
        return info;
    }

    private static String generateOracleSequenceSQL(SequenceInfo info) {
        return "CREATE SEQUENCE " + info.getName().toUpperCase() +
               " INCREMENT BY " + info.getIncrementBy() +
               " MINVALUE " + info.getMinValue() +
               " MAXVALUE " + info.getMaxValue() +
               " START WITH " + info.getLastValue() +
               " CACHE " + info.getCacheSize() +
               (info.isCycle() ? " CYCLE" : " NOCYCLE");
    }

    private static List<String> sortFunctionsByDependencies(PostgreSQL postgres, DatabaseObjects db) throws SQLException {
        Connection conn = PostgresService.PostgresConnexion(postgres);
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
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT pg_get_functiondef(p.oid) FROM pg_proc p " +
            "JOIN pg_namespace n ON p.pronamespace = n.oid " +
            "WHERE n.nspname = 'public' AND p.proname = ?")) {
            ps.setString(1, name.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        return null;
    }

    private static String getViewDefinition(Connection conn, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT definition FROM pg_views WHERE schemaname = 'public' AND viewname = ?")) {
            ps.setString(1, name.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        return null;
    }

    private static String convertFunctionToOracle(String pgSQL) {
        if (pgSQL == null || pgSQL.trim().isEmpty()) {
            return null;
        }
        
        String oracleSQL = pgSQL
            // Types de données PostgreSQL → Oracle
            .replaceAll("(?i)\\bINTEGER\\b", "NUMBER")
            .replaceAll("(?i)\\bBIGINT\\b", "NUMBER")
            .replaceAll("(?i)\\bSMALLINT\\b", "NUMBER")
            .replaceAll("(?i)\\bREAL\\b", "FLOAT")
            .replaceAll("(?i)\\bDOUBLE PRECISION\\b", "FLOAT")
            .replaceAll("(?i)\\bBOOLEAN\\b", "NUMBER(1)")
            .replaceAll("(?i)\\bTEXT\\b", "CLOB")
            .replaceAll("(?i)\\bVARCHAR\\s*\\(", "VARCHAR2(")
            .replaceAll("(?i)\\bVARCHAR\\b", "VARCHAR2")
            .replaceAll("(?i)\\bTIMESTAMP\\b", "DATE")
            .replaceAll("(?i)\\bTIMESTAMP WITH TIME ZONE\\b", "TIMESTAMP WITH TIME ZONE")
            .replaceAll("(?i)\\bSERIAL\\b", "NUMBER")
            .replaceAll("(?i)\\bBIGSERIAL\\b", "NUMBER")
            .replaceAll("(?i)\\bNUMERIC\\b", "NUMBER")
            .replaceAll("(?i)\\bBYTEA\\b", "BLOB")
            
            // Syntaxe fonction PostgreSQL → Oracle
            .replaceAll("(?i)\\bRETURNS\\s+INTEGER\\b", "RETURN NUMBER")
            .replaceAll("(?i)\\bRETURNS\\s+BIGINT\\b", "RETURN NUMBER")
            .replaceAll("(?i)\\bRETURNS\\s+NUMERIC\\b", "RETURN NUMBER")
            .replaceAll("(?i)\\bRETURNS\\s+VARCHAR\\b", "RETURN VARCHAR2")
            .replaceAll("(?i)\\bRETURNS\\s+TEXT\\b", "RETURN CLOB")
            .replaceAll("(?i)\\bRETURNS\\s+TIMESTAMP\\b", "RETURN DATE")
            .replaceAll("(?i)\\bRETURNS\\s+BOOLEAN\\b", "RETURN NUMBER")
            .replaceAll("(?i)\\bRETURNS\\b", "RETURN")
            
            // Suppression du langage PL/pgSQL
            .replaceAll("(?i)\\bLANGUAGE\\s+plpgsql\\b", "")
            .replaceAll("(?i)\\bLANGUAGE\\s+sql\\b", "")
            .replaceAll("(?i)\\s+IMMUTABLE\\b", "")
            .replaceAll("(?i)\\s+STABLE\\b", "")
            .replaceAll("(?i)\\s+VOLATILE\\b", "")
            .replaceAll("(?i)\\s+STRICT\\b", "")
            .replaceAll("(?i)\\s+SECURITY\\s+DEFINER\\b", "")
            .replaceAll("(?i)\\s+SECURITY\\s+INVOKER\\b", "")
            
            // Délimiteurs $ → IS/AS
            .replaceAll("(?i)\\s+AS\\s+\\$\\$", " IS")
            .replaceAll("(?i)\\s+AS\\s+\\$[^\\$]+\\$", " IS")
            .replaceAll("(?i)\\$\\$;?\\s*$", "")
            .replaceAll("(?i)\\$[^\\$]+\\$;?\\s*$", "")
            
            // Fonctions PostgreSQL → Oracle
            .replaceAll("(?i)\\bCURRENT_TIMESTAMP\\b", "SYSDATE")
            .replaceAll("(?i)\\bNOW\\(\\)", "SYSDATE")
            .replaceAll("(?i)\\bCURRENT_DATE\\b", "TRUNC(SYSDATE)")
            .replaceAll("(?i)\\bCOALESCE\\s*\\(", "NVL(")
            .replaceAll("(?i)\\bSUBSTRING\\s*\\(", "SUBSTR(")
            .replaceAll("(?i)\\bPOSITION\\s*\\(([^)]+)\\s+IN\\s+([^)]+)\\)", "INSTR($2, $1)")
            .replaceAll("(?i)\\bLENGTH\\s*\\(", "LENGTH(")
            .replaceAll("(?i)\\bTO_TIMESTAMP\\s*\\(", "TO_DATE(")
            .replaceAll("(?i)\\bEXTRACT\\s*\\(", "EXTRACT(")
            .replaceAll("(?i)\\bDATE_TRUNC\\s*\\(", "TRUNC(")
            
            // Cast PostgreSQL → Oracle
            .replaceAll("(?i)::VARCHAR", "")
            .replaceAll("(?i)::INTEGER", "")
            .replaceAll("(?i)::BIGINT", "")
            .replaceAll("(?i)::NUMERIC", "")
            .replaceAll("(?i)::TEXT", "")
            .replaceAll("(?i)::DATE", "")
            .replaceAll("(?i)::TIMESTAMP", "")
            .replaceAll("(?i)::BOOLEAN", "")
            
            // Valeurs booléennes
            .replaceAll("(?i)\\bTRUE\\b", "1")
            .replaceAll("(?i)\\bFALSE\\b", "0")
            
            // Variables et déclarations
            .replaceAll("(?i)\\bDECLARE\\s+", "DECLARE\n  ")
            
            // Gestion des exceptions
            .replaceAll("(?i)\\bEXCEPTION\\s+WHEN", "EXCEPTION\n  WHEN")
            .replaceAll("(?i)\\bRAISE NOTICE\\s*'([^']*)'", "DBMS_OUTPUT.PUT_LINE('$1')")
            .replaceAll("(?i)\\bRAISE NOTICE\\s*%", "DBMS_OUTPUT.PUT_LINE")
            .replaceAll("(?i)\\bRAISE EXCEPTION\\s*'([^']*)'", "RAISE_APPLICATION_ERROR(-20001, '$1')")
            
            // Boucles et contrôle de flux
            .replaceAll("(?i)\\bFOREACH\\b", "FOR")
            .replaceAll("(?i)\\bRETURN NEXT\\b", "PIPE ROW")
            .replaceAll("(?i)\\bRETURN QUERY\\b", "RETURN")
            
            // Opérateurs
            .replaceAll("(?i)\\bISNULL\\s*\\(", "NVL(")
            .replaceAll("(?i)\\|\\|", "||")
            
            // Nettoyage
            .replaceAll("\\s+", " ")
            .replaceAll("\\s*;\\s*", ";\n")
            .trim();
        
        // Ajouter END si manquant
        if (!oracleSQL.toUpperCase().contains("END;") && !oracleSQL.toUpperCase().endsWith("END")) {
            oracleSQL += "\nEND;";
        }
        
        return oracleSQL;
    }

    private static String convertViewToOracle(String pgSQL) {
        if (pgSQL == null || pgSQL.trim().isEmpty()) {
            return null;
        }
        
        return pgSQL
            // Fonctions PostgreSQL → Oracle
            .replaceAll("(?i)\\bCURRENT_TIMESTAMP\\b", "SYSDATE")
            .replaceAll("(?i)\\bNOW\\(\\)", "SYSDATE")
            .replaceAll("(?i)\\bCURRENT_DATE\\b", "TRUNC(SYSDATE)")
            .replaceAll("(?i)\\bCOALESCE\\s*\\(", "NVL(")
            .replaceAll("(?i)\\bSUBSTRING\\s*\\(", "SUBSTR(")
            .replaceAll("(?i)\\bPOSITION\\s*\\(([^)]+)\\s+IN\\s+([^)]+)\\)", "INSTR($2, $1)")
            .replaceAll("(?i)\\bLENGTH\\s*\\(", "LENGTH(")
            
            // Cast PostgreSQL
            .replaceAll("(?i)::VARCHAR", "")
            .replaceAll("(?i)::INTEGER", "")
            .replaceAll("(?i)::BIGINT", "")
            .replaceAll("(?i)::NUMERIC", "")
            .replaceAll("(?i)::TEXT", "")
            .replaceAll("(?i)::DATE", "")
            .replaceAll("(?i)::TIMESTAMP", "")
            .replaceAll("(?i)::BOOLEAN", "")
            
            // Valeurs booléennes
            .replaceAll("(?i)\\bTRUE\\b", "1")
            .replaceAll("(?i)\\bFALSE\\b", "0")
            
            // LIMIT et OFFSET → ROWNUM (simplification)
            .replaceAll("(?i)\\bLIMIT\\s+\\d+", "")
            .replaceAll("(?i)\\bOFFSET\\s+\\d+", "")
            
            // Expressions régulières PostgreSQL
            .replaceAll("(?i)~", "REGEXP_LIKE")
            .replaceAll("(?i)!~", "NOT REGEXP_LIKE")
            
            .trim();
    }

    private static List<String> getPrimaryKeyConstraints(Connection conn, String table) throws SQLException {
        List<String> list = new ArrayList<>();
        String sql = "SELECT kcu.column_name FROM information_schema.table_constraints tc " +
                    "JOIN information_schema.key_column_usage kcu " +
                    "ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema AND tc.table_name = kcu.table_name " +
                    "WHERE tc.table_schema = 'public' AND tc.table_name = ? " +
                    "AND tc.constraint_type = 'PRIMARY KEY' ORDER BY kcu.ordinal_position";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table.toLowerCase());
            List<String> columns = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1).toUpperCase());
                }
            }
            if (!columns.isEmpty()) {
                String alterSQL = "ALTER TABLE " + table.toUpperCase() + " ADD CONSTRAINT PK_" + table.toUpperCase() + " PRIMARY KEY (" + String.join(", ", columns) + ")";
                list.add(alterSQL);
                System.out.println("🔧 PK pour " + table + ": " + alterSQL);
            }
        } catch (Exception e) {
            System.err.println("⚠️  Erreur récupération PK pour " + table + ": " + e.getMessage());
        }
        return list;
    }

    private static List<String> getForeignKeyConstraints(Connection conn, String table) throws SQLException {
        List<String> list = new ArrayList<>();
        String sql = "SELECT " +
                    "kcu.column_name, " +
                    "ccu.table_name AS foreign_table_name, " +
                    "ccu.column_name AS foreign_column_name " +
                    "FROM information_schema.table_constraints AS tc " +
                    "JOIN information_schema.key_column_usage AS kcu " +
                    "  ON tc.constraint_name = kcu.constraint_name " +
                    "  AND tc.table_schema = kcu.table_schema " +
                    "JOIN information_schema.constraint_column_usage AS ccu " +
                    "  ON ccu.constraint_name = tc.constraint_name " +
                    "  AND ccu.table_schema = tc.table_schema " +
                    "WHERE tc.constraint_type = 'FOREIGN KEY' " +
                    "AND tc.table_schema = 'public' " +
                    "AND tc.table_name = ?";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String fkCol = rs.getString(1).toUpperCase();
                    String refTable = rs.getString(2).toUpperCase();
                    String refCol = rs.getString(3).toUpperCase();
                    String alterSQL = "ALTER TABLE " + table.toUpperCase() + " ADD FOREIGN KEY (" + fkCol + ") REFERENCES " + refTable + "(" + refCol + ")";
                    list.add(alterSQL);
                    System.out.println("🔧 FK pour " + table + ": " + alterSQL);
                }
            }
        } catch (Exception e) {
            System.err.println("⚠️  Erreur récupération FK pour " + table + ": " + e.getMessage());
        }
        return list;
    }

    private static List<String> getUniqueConstraints(Connection conn, String table) throws SQLException {
        List<String> list = new ArrayList<>();
        String sql = "SELECT tc.constraint_name, kcu.column_name " +
                    "FROM information_schema.table_constraints tc " +
                    "JOIN information_schema.key_column_usage kcu " +
                    "  ON tc.constraint_name = kcu.constraint_name " +
                    "  AND tc.table_schema = kcu.table_schema " +
                    "  AND tc.table_name = kcu.table_name " +
                    "WHERE tc.table_schema = 'public' " +
                    "AND tc.table_name = ? " +
                    "AND tc.constraint_type = 'UNIQUE' " +
                    "ORDER BY tc.constraint_name, kcu.ordinal_position";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table.toLowerCase());
            Map<String, List<String>> uks = new HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String constraintName = rs.getString(1);
                    String col = rs.getString(2).toUpperCase();
                    uks.computeIfAbsent(constraintName, k -> new ArrayList<>()).add(col);
                }
            }
            for (Map.Entry<String, List<String>> entry : uks.entrySet()) {
                String alterSQL = "ALTER TABLE " + table.toUpperCase() + " ADD CONSTRAINT " + entry.getKey().toUpperCase() + " UNIQUE (" + String.join(", ", entry.getValue()) + ")";
                list.add(alterSQL);
                System.out.println("🔧 UK pour " + table + ": " + alterSQL);
            }
        } catch (Exception e) {
            System.err.println("⚠️  Erreur récupération UK pour " + table + ": " + e.getMessage());
        }
        return list;
    }

    private static List<String> getCheckConstraints(Connection conn, String table) throws SQLException {
        List<String> list = new ArrayList<>();
        String sql = "SELECT con.conname, pg_get_constraintdef(con.oid) AS definition " +
                    "FROM pg_constraint con " +
                    "JOIN pg_class rel ON rel.oid = con.conrelid " +
                    "JOIN pg_namespace nsp ON nsp.oid = connamespace " +
                    "WHERE nsp.nspname = 'public' " +
                    "AND rel.relname = ? " +
                    "AND con.contype = 'c' " +
                    "AND con.conname NOT LIKE '%_not_null'";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String constraintName = rs.getString(1);
                    String definition = rs.getString(2);
                    
                    // Extraire la condition CHECK
                    if (definition != null && definition.toUpperCase().startsWith("CHECK")) {
                        String condition = definition.substring(5).trim();
                        if (condition.startsWith("(") && condition.endsWith(")")) {
                            condition = condition.substring(1, condition.length() - 1);
                        }
                        
                        if (!condition.toUpperCase().contains("IS NOT NULL")) {
                            String alterSQL = "ALTER TABLE " + table.toUpperCase() + " ADD CONSTRAINT " + constraintName.toUpperCase() + " CHECK (" + condition.toUpperCase() + ")";
                            list.add(alterSQL);
                            System.out.println("🔧 CK pour " + table + ": " + alterSQL);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("⚠️  Erreur récupération CK pour " + table + ": " + e.getMessage());
        }
        return list;
    }

    private static List<String> getIndexes(Connection conn, String table) throws SQLException {
        List<String> list = new ArrayList<>();
        String sql = "SELECT i.relname AS index_name, " +
                    "ix.indisunique AS is_unique, " +
                    "array_agg(a.attname ORDER BY array_position(ix.indkey, a.attnum)) AS columns " +
                    "FROM pg_class t " +
                    "JOIN pg_index ix ON t.oid = ix.indrelid " +
                    "JOIN pg_class i ON i.oid = ix.indexrelid " +
                    "JOIN pg_attribute a ON a.attrelid = t.oid " +
                    "WHERE t.relkind = 'r' " +
                    "AND t.relname = ? " +
                    "AND a.attnum = ANY(ix.indkey) " +
                    "AND i.relname NOT IN (" +
                    "  SELECT constraint_name FROM information_schema.table_constraints " +
                    "  WHERE table_schema = 'public' AND table_name = ? " +
                    "  AND constraint_type IN ('PRIMARY KEY', 'UNIQUE')" +
                    ") " +
                    "GROUP BY i.relname, ix.indisunique " +
                    "ORDER BY i.relname";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table.toLowerCase());
            ps.setString(2, table.toLowerCase());
            
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String idxName = rs.getString("index_name").toUpperCase();
                    boolean isUnique = rs.getBoolean("is_unique");
                    
                    // Récupérer le tableau de colonnes
                    java.sql.Array colArray = rs.getArray("columns");
                    String[] columns = (String[]) colArray.getArray();
                    
                    List<String> colList = new ArrayList<>();
                    for (String col : columns) {
                        colList.add(col.toUpperCase());
                    }
                    
                    String uniqueStr = isUnique ? "UNIQUE " : "";
                    String createSQL = "CREATE " + uniqueStr + "INDEX " + idxName + " ON " + 
                                      table.toUpperCase() + " (" + String.join(", ", colList) + ")";
                    list.add(createSQL);
                    System.out.println("🔧 Index pour " + table + ": " + createSQL);
                }
            }
        } catch (Exception e) {
            System.err.println("⚠️  Erreur récupération indexes pour " + table + ": " + e.getMessage());
            e.printStackTrace();
        }
        return list;
    }

    private static String getTriggerDefinition(Connection conn, String name) throws SQLException {
        StringBuilder sb = new StringBuilder();
        String sql = "SELECT event_manipulation, event_object_table, " +
                    "action_timing, action_statement " +
                    "FROM information_schema.triggers " +
                    "WHERE trigger_schema = 'public' AND trigger_name = ?";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String event = rs.getString(1);
                    String table = rs.getString(2);
                    String timing = rs.getString(3);
                    String statement = rs.getString(4);
                    
                    sb.append("EVENT:").append(event).append("\n");
                    sb.append("TABLE:").append(table).append("\n");
                    sb.append("TIMING:").append(timing).append("\n");
                    sb.append("STATEMENT:").append(statement);
                }
            }
        }
        return sb.toString();
    }

    private static String convertTriggerToOracle(String pgSQL, String triggerName) {
        if (pgSQL == null || pgSQL.isEmpty()) return null;
        
        try {
            String[] parts = pgSQL.split("\n");
            String event = "", table = "", timing = "", statement = "";
            
            for (String part : parts) {
                if (part.startsWith("EVENT:")) event = part.substring(6);
                else if (part.startsWith("TABLE:")) table = part.substring(6);
                else if (part.startsWith("TIMING:")) timing = part.substring(7);
                else if (part.startsWith("STATEMENT:")) statement = part.substring(10);
            }
            
            if (table.isEmpty() || event.isEmpty()) return null;
            
            // Extraire le corps du trigger
            String body = "NULL;";
            if (statement.contains("EXECUTE FUNCTION") || statement.contains("EXECUTE PROCEDURE")) {
                body = "NULL;";
            } else {
                body = statement;
            }
            
            // Convertir le corps PostgreSQL → Oracle
            String oracleBody = body
                .replaceAll("(?i)\\bNEW\\.", ":NEW.")
                .replaceAll("(?i)\\bOLD\\.", ":OLD.")
                .replaceAll("(?i)\\bINTEGER\\b", "NUMBER")
                .replaceAll("(?i)\\bVARCHAR\\b", "VARCHAR2")
                .replaceAll("(?i)\\bTIMESTAMP\\b", "DATE")
                .replaceAll("(?i)\\bCURRENT_TIMESTAMP\\b", "SYSDATE")
                .replaceAll("(?i)\\bNOW\\(\\)", "SYSDATE")
                .replaceAll("(?i)\\bCOALESCE\\(", "NVL(")
                .replaceAll("(?i)\\bRETURN NEW", "")
                .replaceAll("(?i)\\bRETURN OLD", "")
                .replaceAll("(?i)\\bRETURN NULL", "");
            
            // Construire le trigger Oracle
            StringBuilder sql = new StringBuilder();
            
            sql.append("CREATE OR REPLACE TRIGGER ").append(triggerName.toUpperCase()).append("\n");
            sql.append(timing.toUpperCase()).append(" ").append(event.toUpperCase());
            sql.append(" ON ").append(table.toUpperCase()).append("\n");
            sql.append("FOR EACH ROW\n");
            sql.append("BEGIN\n");
            sql.append("  ").append(oracleBody);
            if (!oracleBody.trim().endsWith(";")) sql.append(";");
            sql.append("\nEND ").append(triggerName.toUpperCase()).append(";");
            
            return sql.toString();
            
        } catch (Exception e) {
            System.err.println("❌ Erreur conversion trigger: " + e.getMessage());
            return null;
        }
    }
}