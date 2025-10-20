package stage.bici.DBBridge.Service;

import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.math.BigDecimal;

import stage.bici.DBBridge.Model.Oracle;
import stage.bici.DBBridge.Model.PostgreSQL;

public class CompleteMigrationService {

    private static class MigrationStats {
        int tablesTotal, tablesSuccess, tablesFailed;
        int dataTotal, dataSuccess, dataFailed;
        int sequencesValid, sequencesMigrated, sequencesInvalid;
        int functionsValid, functionsMigrated, functionsInvalid;
        int viewsValid, viewsMigrated, viewsInvalid;
        int constraintsValid, constraintsMigrated, constraintsInvalid;
        int indexesValid, indexesMigrated, indexesInvalid;
        int triggersValid, triggersMigrated, triggersInvalid;

        void printSummary() {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("=== RÉSUMÉ DE LA MIGRATION ===");
            System.out.println("=".repeat(80));
            System.out.println(String.format("📊 TABLES       : %d/%d migrées (%d échecs)", tablesSuccess, tablesTotal, tablesFailed));
            System.out.println(String.format("📊 DONNÉES      : %d/%d migrées (%d échecs)", dataSuccess, dataTotal, dataFailed));
            System.out.println(String.format("📊 SÉQUENCES    : %d valides, %d migrées, %d invalides", sequencesValid, sequencesMigrated, sequencesInvalid));
            System.out.println(String.format("📊 VUES         : %d valides, %d migrées, %d invalides", viewsValid, viewsMigrated, viewsInvalid));
            System.out.println(String.format("📊 FONCTIONS    : %d valides, %d migrées, %d invalides", functionsValid, functionsMigrated, functionsInvalid));
            System.out.println(String.format("📊 CONTRAINTES  : %d valides, %d migrées, %d invalides", constraintsValid, constraintsMigrated, constraintsInvalid));
            System.out.println(String.format("📊 INDEX        : %d valides, %d migrés, %d invalides", indexesValid, indexesMigrated, indexesInvalid));
            System.out.println(String.format("📊 TRIGGERS     : %d valides, %d migrés, %d invalides", triggersValid, triggersMigrated, triggersInvalid));
            System.out.println("=".repeat(80));
        }
    }

    private static class DatabaseObjects {
        String owner;
        Set<String> validTables = new HashSet<>();
        Set<String> validSequences = new HashSet<>();
        Set<String> invalidSequences = new HashSet<>();
        Set<String> validFunctions = new HashSet<>();
        Set<String> invalidFunctions = new HashSet<>();
        Set<String> validViews = new HashSet<>();
        Set<String> invalidViews = new HashSet<>();
        Map<String, Set<String>> viewDependencies = new HashMap<>();
        Set<String> validTriggers = new HashSet<>();
        Set<String> invalidTriggers = new HashSet<>();
        Map<String, Boolean> pkEnabledByTable = new HashMap<>();
        Set<String> validIndexes = new HashSet<>();
        Set<String> invalidIndexes = new HashSet<>();
    }

    public static void migrateCompleteDatabase(Oracle oracle, PostgreSQL postgres) throws SQLException {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("=== MIGRATION COMPLÈTE ORACLE → POSTGRESQL ===");
        System.out.println("=".repeat(80));
        long start = System.currentTimeMillis();
        MigrationStats stats = new MigrationStats();

        try {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 1/9 : VALIDATION DES OBJETS ORACLE");
            System.out.println("=".repeat(80));
            DatabaseObjects db = validateOracleObjects(oracle);

            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 2/9 : MIGRATION DES TABLES (structure)");
            System.out.println("=".repeat(80));
            migrateTables(oracle, postgres, db, stats);

            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 3/9 : MIGRATION DES DONNÉES");
            System.out.println("=".repeat(80));
            migrateData(oracle, postgres, db, stats);

            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 4/9 : MIGRATION DES SÉQUENCES");
            System.out.println("=".repeat(80));
            migrateSequencesComplete(oracle, postgres, db, stats);

            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 5/9 : MIGRATION DES FONCTIONS");
            System.out.println("=".repeat(80));
            migrateFunctionsComplete(oracle, postgres, db, stats);

            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 6/9 : MIGRATION DES VUES");
            System.out.println("=".repeat(80));
            migrateViewsComplete(oracle, postgres, db, stats);

            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 7/9 : MIGRATION DES CONTRAINTES");
            System.out.println("=".repeat(80));
            migrateConstraints(oracle, postgres, db, stats);

            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 8/9 : MIGRATION DES INDEX");
            System.out.println("=".repeat(80));
            migrateIndexes(oracle, postgres, db, stats);

            System.out.println("\n" + "=".repeat(80));
            System.out.println("ÉTAPE 9/9 : MIGRATION DES TRIGGERS");
            System.out.println("=".repeat(80));
            migrateTriggersComplete(oracle, postgres, db, stats);

            System.out.println("\n" + "=".repeat(80));
            System.out.println("=== MIGRATION TERMINÉE ===");
            System.out.println("=".repeat(80));
            System.out.println("⏱️  Durée totale: " + ((System.currentTimeMillis() - start) / 1000) + " secondes");
            stats.printSummary();
        } catch (Exception e) {
            System.err.println("❌ ERREUR CRITIQUE: " + e.getMessage());
            e.printStackTrace();
            throw new SQLException("Migration échouée", e);
        }
    }

    private static DatabaseObjects validateOracleObjects(Oracle oracle) throws SQLException {
        DatabaseObjects db = new DatabaseObjects();
        try (Connection conn = OracleService.OracleConnexion(oracle)) {
            db.owner = getCurrentUser(conn);

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT table_name FROM user_tables")) {
                while (rs.next()) db.validTables.add(rs.getString(1));
            }

            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT object_name, status FROM all_objects WHERE owner = ? AND object_type = 'SEQUENCE'")) {
                ps.setString(1, db.owner);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if ("VALID".equalsIgnoreCase(rs.getString(2))) db.validSequences.add(rs.getString(1));
                        else db.invalidSequences.add(rs.getString(1));
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT object_name, status FROM all_objects WHERE owner = ? AND object_type = 'FUNCTION'")) {
                ps.setString(1, db.owner);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if ("VALID".equalsIgnoreCase(rs.getString(2))) db.validFunctions.add(rs.getString(1));
                        else db.invalidFunctions.add(rs.getString(1));
                    }
                }
            }

            Set<String> allViews = new HashSet<>();
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT object_name, status FROM all_objects WHERE owner = ? AND object_type = 'VIEW'")) {
                ps.setString(1, db.owner);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(1);
                        allViews.add(name);
                        if ("VALID".equalsIgnoreCase(rs.getString(2))) db.validViews.add(name);
                        else db.invalidViews.add(rs.getString(1));
                    }
                }
            }
            
            // Analyser les dépendances COMPLÈTES (tables, vues, fonctions)
            for (String v : allViews) {
                db.viewDependencies.put(v, getViewDependenciesComplete(conn, db.owner, v, db));
            }

            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT object_name, status FROM all_objects WHERE owner = ? AND object_type = 'TRIGGER'")) {
                ps.setString(1, db.owner);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if ("VALID".equalsIgnoreCase(rs.getString(2))) db.validTriggers.add(rs.getString(1));
                        else db.invalidTriggers.add(rs.getString(1));
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT table_name, status FROM all_constraints WHERE owner = ? AND constraint_type = 'P'")) {
                ps.setString(1, db.owner);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) db.pkEnabledByTable.put(rs.getString(1), "ENABLED".equalsIgnoreCase(rs.getString(2)));
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT idx.index_name, idx.status FROM all_indexes idx " +
                "WHERE idx.owner = ? AND idx.index_name NOT IN (" +
                "  SELECT constraint_name FROM all_constraints WHERE owner = ? AND constraint_type IN ('P','U'))")) {
                ps.setString(1, db.owner);
                ps.setString(2, db.owner);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if ("VALID".equalsIgnoreCase(rs.getString(2))) db.validIndexes.add(rs.getString(1));
                        else db.invalidIndexes.add(rs.getString(1));
                    }
                }
            }
        }
        return db;
    }

    private static String getCurrentUser(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT user FROM dual")) {
            if (rs.next()) return rs.getString(1).toUpperCase();
        } catch (SQLException ignore) {}
        return null;
    }

    private static void migrateTables(Oracle oracle, PostgreSQL postgres, DatabaseObjects db, MigrationStats stats) throws SQLException {
        stats.tablesTotal = db.validTables.size();
        for (String tableName : db.validTables) {
            try {
                String createSQL = OracleService.generateCreateTableSQL(oracle, tableName);
                OracleService.createPostgresTable(postgres, createSQL);
                stats.tablesSuccess++;
            } catch (Exception e) {
                stats.tablesFailed++;
            }
        }
    }

    private static void migrateData(Oracle oracle, PostgreSQL postgres, DatabaseObjects db, MigrationStats stats) throws SQLException {
        stats.dataTotal = db.validTables.size();
        for (String tableName : db.validTables) {
            try {
                OracleService.insertDataIntoPostgres(oracle, postgres, tableName);
                stats.dataSuccess++;
            } catch (Exception e) {
                stats.dataFailed++;
            }
        }
    }

    private static void migrateSequencesComplete(Oracle oracle, PostgreSQL postgres, DatabaseObjects db, MigrationStats stats) throws SQLException {
        stats.sequencesValid = db.validSequences.size();
        stats.sequencesInvalid = db.invalidSequences.size();
        
        try (Connection oraConn = OracleService.OracleConnexion(oracle)) {
            for (String seqName : db.validSequences) {
                try {
                    try (PreparedStatement ps = oraConn.prepareStatement(
                        "SELECT min_value, max_value, increment_by, last_number, cache_size, cycle_flag, order_flag " +
                        "FROM all_sequences WHERE sequence_owner = ? AND sequence_name = ?")) {
                        ps.setString(1, db.owner);
                        ps.setString(2, seqName.toUpperCase());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                BigDecimal minValue = rs.getBigDecimal("min_value");
                                BigDecimal maxValue = rs.getBigDecimal("max_value");
                                long increment = rs.getLong("increment_by");
                                BigDecimal startValue = rs.getBigDecimal("last_number");
                                long cacheSize = rs.getLong("cache_size");
                                String cycleFlag = rs.getString("cycle_flag");
                                
                                StringBuilder sql = new StringBuilder("CREATE SEQUENCE IF NOT EXISTS ");
                                sql.append(seqName.toLowerCase());
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
                                
                                if (executeSQL(postgres, sql.toString())) {
                                    stats.sequencesMigrated++;
                                    System.out.println("✅ Séquence: " + seqName);
                                }
                            }
                        }
                    }
                } catch (SQLException e) {
                    System.err.println("❌ Séquence " + seqName + ": " + e.getMessage());
                }
            }
        }
    }

    private static void migrateFunctionsComplete(Oracle oracle, PostgreSQL postgres, DatabaseObjects db, MigrationStats stats) {
        stats.functionsValid = db.validFunctions.size();
        stats.functionsInvalid = db.invalidFunctions.size();
        
        try (Connection oraConn = OracleService.OracleConnexion(oracle)) {
            for (String funcName : db.validFunctions) {
                try {
                    if (funcName.toUpperCase().startsWith("GETSEQ")) {
                        String seqName = funcName.substring(6);
                        String pgSQL = "CREATE OR REPLACE FUNCTION " + funcName.toLowerCase() + 
                                      "() RETURNS BIGINT AS $$\n" +
                                      "BEGIN\n" +
                                      "    RETURN nextval('" + seqName.toLowerCase() + "');\n" +
                                      "END;\n" +
                                      "$$ LANGUAGE plpgsql;";
                        
                        if (executeSQL(postgres, pgSQL)) {
                            stats.functionsMigrated++;
                            System.out.println("✅ Fonction: " + funcName);
                            continue;
                        }
                    }
                    
                    StringBuilder source = new StringBuilder();
                    try (PreparedStatement ps = oraConn.prepareStatement(
                        "SELECT text FROM all_source WHERE owner = ? AND name = ? AND type = 'FUNCTION' ORDER BY line")) {
                        ps.setString(1, db.owner);
                        ps.setString(2, funcName.toUpperCase());
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) source.append(rs.getString(1)).append("\n");
                        }
                    }
                    
                    if (source.length() == 0) continue;
                    
                    String pgFunction = convertFunctionToPostgres(funcName, source.toString());
                    if (pgFunction != null && executeSQL(postgres, pgFunction)) {
                        stats.functionsMigrated++;
                        System.out.println("✅ Fonction: " + funcName);
                    }
                } catch (Exception e) {
                    System.err.println("❌ Fonction " + funcName + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Erreur globale fonctions: " + e.getMessage());
        }
    }

    private static String convertFunctionToPostgres(String name, String oracleSource) {
        try {
            String src = oracleSource.replaceAll("\\s+", " ").trim();
            
            Pattern p1 = Pattern.compile(
                "(?i)FUNCTION\\s+" + Pattern.quote(name) + "\\s*\\(([^)]*)\\)\\s*RETURN\\s+([\\w\\(\\),\\s]+?)\\s+(?:IS|AS)\\s+(.*?)\\s*BEGIN\\s+(.*?)\\s*END",
                Pattern.DOTALL
            );
            Matcher m1 = p1.matcher(src);
            
            if (m1.find()) {
                String params = m1.group(1).trim();
                String returnType = mapOracleTypeToPg(m1.group(2).trim());
                String declarations = m1.group(3).trim();
                String body = m1.group(4).trim();
                return buildPostgresFunction(name, params, returnType, declarations, body);
            }
            
            Pattern p2 = Pattern.compile(
                "(?i)FUNCTION\\s+" + Pattern.quote(name) + "\\s*RETURN\\s+([\\w\\(\\),\\s]+?)\\s+(?:IS|AS)\\s+(.*?)\\s*BEGIN\\s+(.*?)\\s*END",
                Pattern.DOTALL
            );
            Matcher m2 = p2.matcher(src);
            
            if (m2.find()) {
                String returnType = mapOracleTypeToPg(m2.group(1).trim());
                String declarations = m2.group(2).trim();
                String body = m2.group(3).trim();
                return buildPostgresFunction(name, "", returnType, declarations, body);
            }
            
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String buildPostgresFunction(String name, String oraParams, String pgReturnType, String oraDecl, String oraBody) {
        StringBuilder pg = new StringBuilder();
        pg.append("CREATE OR REPLACE FUNCTION ").append(name.toLowerCase()).append("(");
        
        if (!oraParams.isEmpty()) {
            String[] params = oraParams.split(",");
            List<String> pgParams = new ArrayList<>();
            for (String param : params) {
                String p = param.trim();
                if (p.isEmpty()) continue;
                
                String[] parts = p.split("\\s+");
                if (parts.length >= 2) {
                    String paramName = parts[0].toLowerCase();
                    String type = parts[parts.length - 1];
                    String mode = "";
                    if (parts.length >= 3 && ("IN".equalsIgnoreCase(parts[1]) || "OUT".equalsIgnoreCase(parts[1]))) {
                        mode = parts[1].toUpperCase() + " ";
                    }
                    pgParams.add(mode + paramName + " " + mapOracleTypeToPg(type));
                }
            }
            pg.append(String.join(", ", pgParams));
        }
        
        pg.append(") RETURNS ").append(pgReturnType).append(" AS $$\n");
        
        if (!oraDecl.isEmpty()) {
            String pgDecl = oraDecl
                .replaceAll("(?i)\\bNUMBER\\b", "NUMERIC")
                .replaceAll("(?i)\\bVARCHAR2\\b", "VARCHAR")
                .replaceAll("(?i)\\bDATE\\b", "TIMESTAMP")
                .replaceAll("(?i)\\bCLOB\\b", "TEXT")
                .replaceAll("(?i)\\bBLOB\\b", "BYTEA")
                .replaceAll("(?i)\\bPLS_INTEGER\\b", "INTEGER")
                .replaceAll("(?i)\\bBINARY_INTEGER\\b", "INTEGER")
                .replaceAll("(?i)\\bPRAGMA\\s+AUTONOMOUS_TRANSACTION\\b;?", "");
            pg.append("DECLARE\n").append(pgDecl).append("\n");
        }
        
        String pgBody = oraBody
            .replaceAll("(?i)\\bSYSDATE\\b", "CURRENT_TIMESTAMP")
            .replaceAll("(?i)\\bSYSTIMESTAMP\\b", "CURRENT_TIMESTAMP")
            .replaceAll("(?i)\\bNVL\\s*\\(", "COALESCE(")
            .replaceAll("(?i)\\bSUBSTR\\s*\\(", "SUBSTRING(")
            .replaceAll("(?i)\\bTO_DATE\\s*\\(", "TO_TIMESTAMP(")
            .replaceAll("(?i)\\bCOMMIT\\b\\s*;?", "")
            .replaceAll("(?i)\\bROLLBACK\\b\\s*;?", "");
        
        pg.append("BEGIN\n").append(pgBody).append("\nEND;\n$$ LANGUAGE plpgsql;");
        
        return pg.toString();
    }

    private static String mapOracleTypeToPg(String oraType) {
        if (oraType == null) return "TEXT";
        String type = oraType.trim().toUpperCase();
        
        if (type.startsWith("NUMBER")) return type.replace("NUMBER", "NUMERIC");
        if (type.startsWith("VARCHAR2")) return type.replace("VARCHAR2", "VARCHAR");
        if (type.equals("DATE")) return "TIMESTAMP";
        if (type.equals("CLOB")) return "TEXT";
        if (type.equals("BLOB") || type.equals("RAW")) return "BYTEA";
        if (type.equals("PLS_INTEGER") || type.equals("BINARY_INTEGER")) return "INTEGER";
        
        return type;
    }

    // ========== VUES: MIGRATION STRICTE (avec toutes dépendances) ==========
    private static void migrateViewsComplete(Oracle oracle, PostgreSQL postgres, DatabaseObjects db, MigrationStats stats) {
        stats.viewsValid = db.validViews.size();
        stats.viewsInvalid = db.invalidViews.size();
        
        System.out.println("📊 Vues valides totales: " + stats.viewsValid);
        System.out.println("📊 Analyse des dépendances...");
        
        // Filtrer les vues dont TOUTES les dépendances sont satisfaites
        Set<String> migrableViews = filterViewsWithSatisfiedDependencies(db);
        System.out.println("📊 Vues migrables (dépendances OK): " + migrableViews.size());
        System.out.println("⚠️  Vues non migrables (dépendances manquantes): " + (stats.viewsValid - migrableViews.size()));
        
        // Trier par ordre de dépendances
        List<String> orderedViews = sortViewsByDependencies(db, migrableViews);
        
        // Migrer avec retry multi-passes
        try (Connection oraConn = OracleService.OracleConnexion(oracle)) {
            Set<String> migrated = new HashSet<>();
            int maxPasses = 3;
            
            for (int pass = 1; pass <= maxPasses; pass++) {
                System.out.println("\n🔄 Passe " + pass + "/" + maxPasses + "...");
                int successThisPass = 0;
                
                for (String viewName : orderedViews) {
                    if (migrated.contains(viewName)) continue;
                    
                    try {
                        String viewDef = null;
                        try (PreparedStatement ps = oraConn.prepareStatement(
                            "SELECT text FROM all_views WHERE owner = ? AND view_name = ?")) {
                            ps.setString(1, db.owner);
                            ps.setString(2, viewName.toUpperCase());
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) viewDef = rs.getString(1);
                            }
                        }
                        
                        if (viewDef == null || viewDef.trim().isEmpty()) continue;
                        
                        String pgView = convertViewToPostgresAdvanced(viewName, viewDef);
                        if (pgView != null && executeSQLSilent(postgres, pgView)) {
                            migrated.add(viewName);
                            stats.viewsMigrated++;
                            successThisPass++;
                            System.out.println("✅ Vue: " + viewName);
                        }
                    } catch (Exception e) {
                        // Retry au prochain pass
                    }
                }
                
                System.out.println("📊 Succès passe " + pass + ": " + successThisPass + " vues");
                
                if (successThisPass == 0) break; // Plus de progrès possible
            }
            
            System.out.println("\n📊 RÉSULTAT FINAL VUES:");
            System.out.println("  ✅ Migrées avec succès: " + stats.viewsMigrated);
            System.out.println("  ⚠️  Non migrables (dépendances): " + (stats.viewsValid - migrableViews.size()));
            System.out.println("  ❌ Échec conversion SQL: " + (migrableViews.size() - stats.viewsMigrated));
            
        } catch (Exception e) {
            System.err.println("❌ Erreur globale vues: " + e.getMessage());
        }
    }

    // Filtrer les vues dont TOUTES les dépendances sont présentes
    private static Set<String> filterViewsWithSatisfiedDependencies(DatabaseObjects db) {
        Set<String> result = new HashSet<>();
        
        for (String view : db.validViews) {
            Set<String> deps = db.viewDependencies.get(view);
            if (deps == null || deps.isEmpty()) {
                result.add(view);
                continue;
            }
            
            boolean allDepsOk = true;
            for (String dep : deps) {
                if (dep.startsWith("TABLE:")) {
                    String table = dep.substring(6);
                    if (!db.validTables.contains(table)) {
                        allDepsOk = false;
                        break;
                    }
                } else if (dep.startsWith("VIEW:")) {
                    String depView = dep.substring(5);
                    if (!db.validViews.contains(depView)) {
                        allDepsOk = false;
                        break;
                    }
                } else if (dep.startsWith("FUNCTION:")) {
                    String func = dep.substring(9);
                    if (!db.validFunctions.contains(func)) {
                        allDepsOk = false;
                        break;
                    }
                }
            }
            
            if (allDepsOk) {
                result.add(view);
            }
        }
        
        return result;
    }

    // Conversion SQL Oracle → PostgreSQL avancée
    private static String convertViewToPostgresAdvanced(String name, String oracleSQL) {
        try {
            String pgSQL = oracleSQL
                // Outer joins (+)
                .replaceAll("\\(\\+\\)", "")
                // Types
                .replaceAll("(?i)\\bNUMBER\\b", "NUMERIC")
                .replaceAll("(?i)\\bVARCHAR2\\b", "VARCHAR")
                .replaceAll("(?i)\\bNVARCHAR2\\b", "VARCHAR")
                .replaceAll("(?i)\\bCLOB\\b", "TEXT")
                // Fonctions date
                .replaceAll("(?i)\\bSYSDATE\\b", "CURRENT_TIMESTAMP")
                .replaceAll("(?i)\\bSYSTIMESTAMP\\b", "CURRENT_TIMESTAMP")
                .replaceAll("(?i)\\bTRUNC\\s*\\(\\s*([^,)]+)\\s*\\)", "DATE_TRUNC('day', $1)")
                .replaceAll("(?i)\\bTRUNC\\s*\\(\\s*([^,)]+)\\s*,\\s*'(\\w+)'\\s*\\)", "DATE_TRUNC('$2', $1)")
                .replaceAll("(?i)\\bADD_MONTHS\\s*\\(\\s*([^,)]+)\\s*,\\s*(\\d+)\\s*\\)", "($1 + INTERVAL '$2 months')")
                .replaceAll("(?i)\\bMONTHS_BETWEEN\\s*\\(\\s*([^,)]+)\\s*,\\s*([^)]+)\\s*\\)", 
                           "(EXTRACT(YEAR FROM AGE($1, $2)) * 12 + EXTRACT(MONTH FROM AGE($1, $2)))")
                .replaceAll("(?i)\\bLAST_DAY\\s*\\(\\s*([^)]+)\\s*\\)", 
                           "(DATE_TRUNC('month', $1) + INTERVAL '1 month' - INTERVAL '1 day')")
                // Fonctions string
                .replaceAll("(?i)\\bNVL\\s*\\(", "COALESCE(")
                .replaceAll("(?i)\\bNVL2\\s*\\(\\s*([^,)]+)\\s*,\\s*([^,)]+)\\s*,\\s*([^)]+)\\s*\\)", 
                           "CASE WHEN $1 IS NOT NULL THEN $2 ELSE $3 END")
                .replaceAll("(?i)\\bSUBSTR\\s*\\(", "SUBSTRING(")
                .replaceAll("(?i)\\bINSTR\\s*\\(\\s*([^,)]+)\\s*,\\s*([^)]+)\\s*\\)", "POSITION($2 IN $1)")
                .replaceAll("(?i)\\bCONCAT\\s*\\(\\s*([^,)]+)\\s*,\\s*([^)]+)\\s*\\)", "($1 || $2)")
                .replaceAll("(?i)\\bINITCAP\\s*\\(", "INITCAP(")
                // Conversions
                .replaceAll("(?i)\\bTO_CHAR\\s*\\(", "TO_CHAR(")
                .replaceAll("(?i)\\bTO_NUMBER\\s*\\(([^,)]+)\\)", "CAST($1 AS NUMERIC)")
                .replaceAll("(?i)\\bTO_DATE\\s*\\(", "TO_TIMESTAMP(")
                .replaceAll("(?i)\\bTO_TIMESTAMP\\s*\\(", "TO_TIMESTAMP(")
                // Agrégations
                .replaceAll("(?i)\\bLISTAGG\\s*\\(\\s*([^,)]+)\\s*,\\s*([^)]+)\\s*\\)", "STRING_AGG($1, $2)")
                .replaceAll("(?i)\\bWM_CONCAT\\s*\\(", "STRING_AGG(")
                // ROWNUM
                .replaceAll("(?i)\\bROWNUM\\b", "ROW_NUMBER() OVER ()")
                // DUAL
                .replaceAll("(?i)\\bFROM\\s+DUAL\\b", "")
                .replaceAll("(?i)\\bFROM\\s+SYS\\.DUAL\\b", "")
                // Séquences
                .replaceAll("(?i)(\\w+)\\.NEXTVAL", "nextval('$1')")
                .replaceAll("(?i)(\\w+)\\.CURRVAL", "currval('$1')")
                // Guillemets
                .replaceAll("\"([^\"]+)\"", "$1")
                // Minuscules
                .toLowerCase();
            
            return "CREATE OR REPLACE VIEW " + name.toLowerCase() + " AS " + pgSQL;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> sortViewsByDependencies(DatabaseObjects db, Set<String> viewsToSort) {
        Map<String, Set<String>> deps = new HashMap<>();
        for (String view : viewsToSort) {
            Set<String> viewDeps = new HashSet<>();
            Set<String> allDeps = db.viewDependencies.get(view);
            if (allDeps != null) {
                for (String dep : allDeps) {
                    if (dep.startsWith("VIEW:")) {
                        String depView = dep.substring(5);
                        if (viewsToSort.contains(depView)) viewDeps.add(depView);
                    }
                }
            }
            deps.put(view, viewDeps);
        }
        return topologicalSort(deps);
    }

    // Récupérer dépendances COMPLÈTES (tables, vues, fonctions)
    private static Set<String> getViewDependenciesComplete(Connection conn, String owner, String viewName, DatabaseObjects db) {
        Set<String> deps = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT text FROM all_views WHERE owner = ? AND view_name = ?")) {
            ps.setString(1, owner);
            ps.setString(2, viewName.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String text = rs.getString(1).toUpperCase();
                    
                    // Tables
                    for (String table : db.validTables) {
                        if (text.contains(table.toUpperCase())) deps.add("TABLE:" + table);
                    }
                    
                    // Vues
                    for (String view : db.validViews) {
                        if (!view.equals(viewName) && text.contains(view.toUpperCase())) deps.add("VIEW:" + view);
                    }
                    
                    // Fonctions
                    for (String func : db.validFunctions) {
                        if (text.contains(func.toUpperCase() + "(")) deps.add("FUNCTION:" + func);
                    }
                }
            }
        } catch (Exception ignore) {}
        return deps;
    }

    private static void migrateConstraints(Oracle oracle, PostgreSQL postgres, DatabaseObjects db, MigrationStats stats) throws SQLException {
        for (Map.Entry<String, Boolean> e : db.pkEnabledByTable.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) stats.constraintsValid++;
            else stats.constraintsInvalid++;
        }
        
        try (Connection oraConn = OracleService.OracleConnexion(oracle)) {
            for (String table : db.validTables) {
                if (!Boolean.TRUE.equals(db.pkEnabledByTable.get(table))) continue;
                
                try (PreparedStatement ps = oraConn.prepareStatement(
                    "SELECT cols.column_name FROM all_constraints cons " +
                    "JOIN all_cons_columns cols ON cons.owner = cols.owner AND cons.constraint_name = cols.constraint_name " +
                    "WHERE cons.owner = ? AND cons.table_name = ? AND cons.constraint_type = 'P' ORDER BY cols.position")) {
                    ps.setString(1, db.owner);
                    ps.setString(2, table.toUpperCase());
                    List<String> cols = new ArrayList<>();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) cols.add(rs.getString(1).toLowerCase());
                    }
                    if (!cols.isEmpty()) {
                        String sql = "ALTER TABLE " + table.toLowerCase() + " ADD PRIMARY KEY (" + String.join(", ", cols) + ")";
                        if (executeSQL(postgres, sql)) stats.constraintsMigrated++;
                    }
                }
            }
        }
    }

    private static void migrateIndexes(Oracle oracle, PostgreSQL postgres, DatabaseObjects db, MigrationStats stats) throws SQLException {
        stats.indexesValid = db.validIndexes.size();
        stats.indexesInvalid = db.invalidIndexes.size();
        
        try (Connection oraConn = OracleService.OracleConnexion(oracle)) {
            for (String table : db.validTables) {
                try (PreparedStatement ps = oraConn.prepareStatement(
                    "SELECT idx.index_name, idx.uniqueness, cols.column_name " +
                    "FROM all_indexes idx " +
                    "JOIN all_ind_columns cols ON idx.owner = cols.index_owner AND idx.index_name = cols.index_name " +
                    "WHERE idx.owner = ? AND idx.table_name = ? " +
                    "AND idx.index_name NOT IN (SELECT constraint_name FROM all_constraints WHERE owner = ? AND table_name = ? AND constraint_type IN ('P','U')) " +
                    "AND idx.status = 'VALID' " +
                    "ORDER BY idx.index_name, cols.column_position")) {
                    ps.setString(1, db.owner);
                    ps.setString(2, table.toUpperCase());
                    ps.setString(3, db.owner);
                    ps.setString(4, table.toUpperCase());
                    
                    Map<String, List<String>> indexes = new HashMap<>();
                    Map<String, Boolean> unique = new HashMap<>();
                    
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String idxName = rs.getString(1);
                            unique.put(idxName, "UNIQUE".equalsIgnoreCase(rs.getString(2)));
                            indexes.computeIfAbsent(idxName, k -> new ArrayList<>()).add(rs.getString(3).toLowerCase());
                        }
                    }
                    
                    for (Map.Entry<String, List<String>> e : indexes.entrySet()) {
                        String idxName = e.getKey().toLowerCase();
                        String uniqueStr = Boolean.TRUE.equals(unique.get(e.getKey())) ? "UNIQUE " : "";
                        String sql = "CREATE " + uniqueStr + "INDEX IF NOT EXISTS " + idxName + 
                                   " ON " + table.toLowerCase() + " (" + String.join(", ", e.getValue()) + ")";
                        if (executeSQL(postgres, sql)) stats.indexesMigrated++;
                    }
                }
            }
        }
    }

    private static void migrateTriggersComplete(Oracle oracle, PostgreSQL postgres, DatabaseObjects db, MigrationStats stats) {
        stats.triggersValid = db.validTriggers.size();
        stats.triggersInvalid = db.invalidTriggers.size();
        
        try (Connection oraConn = OracleService.OracleConnexion(oracle)) {
            for (String trigName : db.validTriggers) {
                try {
                    String tableName = null, trigType = null, trigEvent = null;
                    try (PreparedStatement ps = oraConn.prepareStatement(
                        "SELECT table_name, trigger_type, triggering_event FROM all_triggers WHERE owner = ? AND trigger_name = ?")) {
                        ps.setString(1, db.owner);
                        ps.setString(2, trigName.toUpperCase());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                tableName = rs.getString(1);
                                trigType = rs.getString(2);
                                trigEvent = rs.getString(3);
                            }
                        }
                    }
                    
                    if (tableName == null) continue;
                    
                    StringBuilder body = new StringBuilder();
                    try (PreparedStatement ps = oraConn.prepareStatement(
                        "SELECT text FROM all_source WHERE owner = ? AND name = ? AND type = 'TRIGGER' ORDER BY line")) {
                        ps.setString(1, db.owner);
                        ps.setString(2, trigName.toUpperCase());
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) body.append(rs.getString(1)).append("\n");
                        }
                    }
                    
                    String pgTrigger = convertTriggerToPostgres(trigName, tableName, trigType, trigEvent, body.toString());
                    if (pgTrigger != null && executeSQL(postgres, pgTrigger)) {
                        stats.triggersMigrated++;
                        System.out.println("✅ Trigger: " + trigName + " sur " + tableName);
                    }
                } catch (Exception e) {
                    System.err.println("❌ Trigger " + trigName + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Erreur globale triggers: " + e.getMessage());
        }
    }

    private static String convertTriggerToPostgres(String name, String table, String type, String event, String oraBody) {
        try {
            String timing = type.contains("BEFORE") ? "BEFORE" : "AFTER";
            String level = type.contains("STATEMENT") ? "STATEMENT" : "ROW";
            
            List<String> events = new ArrayList<>();
            if (event.contains("INSERT")) events.add("INSERT");
            if (event.contains("UPDATE")) events.add("UPDATE");
            if (event.contains("DELETE")) events.add("DELETE");
            
            Pattern pattern = Pattern.compile("(?is)BEGIN\\s+(.*?)\\s+END", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(oraBody);
            String body = matcher.find() ? matcher.group(1) : "";
            
            String pgBody = body
                .replaceAll("(?i):NEW\\.", "NEW.")
                .replaceAll("(?i):OLD\\.", "OLD.")
                .replaceAll("(?i)\\bSYSDATE\\b", "CURRENT_TIMESTAMP")
                .replaceAll("(?i)\\bNVL\\s*\\(", "COALESCE(");
            
            String funcName = "trg_" + name.toLowerCase();
            String returnStmt = "ROW".equals(level) ? "RETURN NEW;" : "RETURN NULL;";
            
            StringBuilder sql = new StringBuilder();
            sql.append("CREATE OR REPLACE FUNCTION ").append(funcName).append("() RETURNS TRIGGER AS $$\n");
            sql.append("BEGIN\n").append(pgBody).append("\n").append(returnStmt).append("\nEND;\n$$ LANGUAGE plpgsql;\n\n");
            sql.append("DROP TRIGGER IF EXISTS ").append(name.toLowerCase()).append(" ON ").append(table.toLowerCase()).append(";\n");
            sql.append("CREATE TRIGGER ").append(name.toLowerCase()).append("\n");
            sql.append(timing).append(" ").append(String.join(" OR ", events)).append(" ON ").append(table.toLowerCase()).append("\n");
            sql.append("FOR EACH ").append(level).append(" EXECUTE FUNCTION ").append(funcName).append("();");
            
            return sql.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean executeSQL(PostgreSQL postgres, String sql) {
        try (Connection conn = PostgresService.PostgresConnexion(postgres);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean executeSQLSilent(PostgreSQL postgres, String sql) {
        try (Connection conn = PostgresService.PostgresConnexion(postgres);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> topologicalSort(Map<String, Set<String>> deps) {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String item : deps.keySet()) {
            if (!visited.contains(item)) dfs(item, deps, visited, visiting, result);
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
}
