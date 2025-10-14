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
                        else db.invalidViews.add(name);
                    }
                }
            }
            for (String v : allViews) {
                db.viewDependencies.put(v, getViewDependencies(conn, db.owner, v, db));
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

    // ========== SÉQUENCES: FIX DÉBORDEMENT NUMÉRIQUE ==========
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
                                // Utiliser BigDecimal pour éviter débordement
                                BigDecimal minValue = rs.getBigDecimal("min_value");
                                BigDecimal maxValue = rs.getBigDecimal("max_value");
                                long increment = rs.getLong("increment_by");
                                BigDecimal startValue = rs.getBigDecimal("last_number");
                                long cacheSize = rs.getLong("cache_size");
                                String cycleFlag = rs.getString("cycle_flag");
                                
                                StringBuilder sql = new StringBuilder("CREATE SEQUENCE IF NOT EXISTS ");
                                sql.append(seqName.toLowerCase());
                                sql.append(" INCREMENT BY ").append(increment);
                                
                                // Gérer les débordements: utiliser START WITH 1 si last_number dépasse BIGINT
                                if (startValue.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
                                    sql.append(" START WITH 1");
                                } else {
                                    sql.append(" START WITH ").append(startValue.longValue());
                                }
                                
                                // Gérer MINVALUE
                                BigDecimal pgMinValue = BigDecimal.valueOf(-9223372036854775807L);
                                if (minValue.compareTo(pgMinValue) <= 0) {
                                    sql.append(" NO MINVALUE");
                                } else {
                                    sql.append(" MINVALUE ").append(minValue.longValue());
                                }
                                
                                // Gérer MAXVALUE
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

    // ========== FONCTIONS: FIX PARSING COMPLET ==========
    private static void migrateFunctionsComplete(Oracle oracle, PostgreSQL postgres, DatabaseObjects db, MigrationStats stats) {
        stats.functionsValid = db.validFunctions.size();
        stats.functionsInvalid = db.invalidFunctions.size();
        
        try (Connection oraConn = OracleService.OracleConnexion(oracle)) {
            for (String funcName : db.validFunctions) {
                try {
                    // Toutes les fonctions GETSEQ* sont des wrappers de séquence
                    if (funcName.toUpperCase().startsWith("GETSEQ")) {
                        String seqName = funcName.substring(6); // Enlever "GETSEQ"
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
                    
                    // Pour les autres fonctions, extraire le code source
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
            
            // Pattern: FUNCTION name (params) RETURN type IS/AS ... BEGIN ... END
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
            
            // Pattern sans paramètres
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

    // ========== VUES: FIX COLONNES DUPLIQUÉES + RELATIONS MANQUANTES ==========
    private static void migrateViewsComplete(Oracle oracle, PostgreSQL postgres, DatabaseObjects db, MigrationStats stats) {
        stats.viewsValid = db.validViews.size();
        stats.viewsInvalid = db.invalidViews.size();
        
        List<String> orderedViews = sortViewsByDependencies(db);
        
        try (Connection oraConn = OracleService.OracleConnexion(oracle)) {
            for (String viewName : orderedViews) {
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
                    
                    String pgView = convertViewToPostgres(viewName, viewDef);
                    if (pgView != null && executeSQL(postgres, pgView)) {
                        stats.viewsMigrated++;
                        System.out.println("✅ Vue: " + viewName);
                    }
                } catch (Exception e) {
                    System.err.println("❌ Vue " + viewName + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Erreur globale vues: " + e.getMessage());
        }
    }

    private static String convertViewToPostgres(String name, String oracleSQL) {
        try {
            String pgSQL = oracleSQL
                // Outer joins Oracle (+)
                .replaceAll("\\(\\+\\)", "")
                // Types
                .replaceAll("(?i)\\bNUMBER\\b", "NUMERIC")
                .replaceAll("(?i)\\bVARCHAR2\\b", "VARCHAR")
                // Fonctions Oracle → PostgreSQL
                .replaceAll("(?i)\\bSYSDATE\\b", "CURRENT_TIMESTAMP")
                .replaceAll("(?i)\\bNVL\\s*\\(", "COALESCE(")
                .replaceAll("(?i)\\bSUBSTR\\s*\\(", "SUBSTRING(")
                .replaceAll("(?i)\\bTO_DATE\\s*\\(", "TO_TIMESTAMP(")
                .replaceAll("(?i)\\bTO_CHAR\\s*\\(", "TO_CHAR(")
                // TRUNC(date) → DATE_TRUNC('day', date)
                .replaceAll("(?i)\\bTRUNC\\s*\\(\\s*([^)]+)\\s*\\)", "DATE_TRUNC('day', $1)")
                // LISTAGG → STRING_AGG
                .replaceAll("(?i)\\bLISTAGG\\s*\\(", "STRING_AGG(")
                // TO_TIMESTAMP avec 1 arg numérique → TO_TIMESTAMP avec CAST
                .replaceAll("(?i)TO_TIMESTAMP\\s*\\(\\s*(\\w+)\\s*,", "TO_TIMESTAMP(CAST($1 AS TEXT),")
                // ROWNUM → ROW_NUMBER()
                .replaceAll("(?i)\\bROWNUM\\b", "ROW_NUMBER() OVER ()")
                // Dual
                .replaceAll("(?i)\\bFROM\\s+DUAL\\b", "")
                // Noms en minuscules
                .toLowerCase();
            
            return "CREATE OR REPLACE VIEW " + name.toLowerCase() + " AS " + pgSQL;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> sortViewsByDependencies(DatabaseObjects db) {
        Map<String, Set<String>> deps = new HashMap<>();
        for (String view : db.validViews) {
            Set<String> viewDeps = new HashSet<>();
            Set<String> allDeps = db.viewDependencies.get(view);
            if (allDeps != null) {
                for (String dep : allDeps) {
                    if (dep.startsWith("VIEW:")) {
                        String depView = dep.substring(5);
                        if (db.validViews.contains(depView)) viewDeps.add(depView);
                    }
                }
            }
            deps.put(view, viewDeps);
        }
        return topologicalSort(deps);
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

    private static Set<String> getViewDependencies(Connection conn, String owner, String viewName, DatabaseObjects db) {
        Set<String> deps = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT text FROM all_views WHERE owner = ? AND view_name = ?")) {
            ps.setString(1, owner);
            ps.setString(2, viewName.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String text = rs.getString(1).toUpperCase();
                    for (String view : db.validViews) {
                        if (!view.equals(viewName) && text.contains(view.toUpperCase())) deps.add("VIEW:" + view);
                    }
                }
            }
        } catch (Exception ignore) {}
        return deps;
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
