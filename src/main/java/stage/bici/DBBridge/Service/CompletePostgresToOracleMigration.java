package stage.bici.DBBridge.Service;

import java.sql.*;
import java.util.*;
import java.util.regex.*;
import stage.bici.DBBridge.Model.Oracle;
import stage.bici.DBBridge.Model.PostgreSQL;

public class CompletePostgresToOracleMigration {

    private static class MigrationStats {
        int tablesTotal, tablesSuccess, tablesFailed;
        int dataTotal, dataSuccess, dataFailed;
        int sequencesTotal, sequencesSuccess;
        int functionsTotal, functionsSuccess;
        int viewsTotal, viewsSuccess;
        int pkTotal, pkSuccess;
        int fkTotal, fkSuccess;
        int indexTotal, indexSuccess;
        int triggersTotal, triggersSuccess;
        
        List<String> failedTables = new ArrayList<>();
        List<String> failedData = new ArrayList<>();
        Map<String, String> failedFunctions = new HashMap<>();
        Map<String, String> failedViews = new HashMap<>();
        Map<String, String> failedIndexes = new HashMap<>();

        void printDetailed() {
            System.out.println("=".repeat(80));
            System.out.println("=== RÉSUMÉ DÉTAILLÉ DE LA MIGRATION ===");
            System.out.println("=".repeat(80));
            System.out.println(String.format("📊 TABLES       : %d/%d migrées (%.1f%%) - %d échecs", 
                tablesSuccess, tablesTotal, (tablesSuccess * 100.0 / Math.max(1, tablesTotal)), tablesFailed));
            if (!failedTables.isEmpty()) {
                System.out.println("   ❌ Échecs: " + String.join(", ", failedTables));
            }
            System.out.println(String.format("📊 DONNÉES      : %d/%d migrées (%.1f%%) - %d échecs", 
                dataSuccess, dataTotal, (dataSuccess * 100.0 / Math.max(1, dataTotal)), dataFailed));
            if (!failedData.isEmpty()) {
                System.out.println("   ❌ Échecs: " + String.join(", ", failedData));
            }
            System.out.println(String.format("📊 SÉQUENCES    : %d/%d migrées (%.1f%%)", 
                sequencesSuccess, sequencesTotal, (sequencesSuccess * 100.0 / Math.max(1, sequencesTotal))));
            System.out.println(String.format("📊 INDEX        : %d/%d migrés (%.1f%%) - %d échecs", 
                indexSuccess, indexTotal, (indexSuccess * 100.0 / Math.max(1, indexTotal)), indexTotal - indexSuccess));
            System.out.println(String.format("📊 FONCTIONS    : %d/%d migrées (%.1f%%) - %d échecs", 
                functionsSuccess, functionsTotal, (functionsSuccess * 100.0 / Math.max(1, functionsTotal)), functionsTotal - functionsSuccess));
            if (failedFunctions.size() > 0 && failedFunctions.size() <= 5) {
                failedFunctions.forEach((k,v) -> System.out.println("   ❌ " + k + ": " + v));
            } else if (failedFunctions.size() > 5) {
                System.out.println("   ❌ " + failedFunctions.size() + " fonctions non migrées (voir logs)");
            }
            System.out.println(String.format("📊 VUES         : %d/%d migrées (%.1f%%) - %d échecs", 
                viewsSuccess, viewsTotal, (viewsSuccess * 100.0 / Math.max(1, viewsTotal)), viewsTotal - viewsSuccess));
            if (failedViews.size() > 0 && failedViews.size() <= 5) {
                failedViews.forEach((k,v) -> System.out.println("   ❌ " + k + ": " + v.substring(0, Math.min(100, v.length()))));
            } else if (failedViews.size() > 5) {
                System.out.println("   ❌ " + failedViews.size() + " vues non migrées (voir logs)");
            }
            System.out.println(String.format("📊 TRIGGERS     : %d/%d migrés (%.1f%%)", 
                triggersSuccess, triggersTotal, (triggersSuccess * 100.0 / Math.max(1, triggersTotal))));
            System.out.println(String.format("📊 CONTRAINTES PK: %d/%d migrées (%.1f%%)", 
                pkSuccess, pkTotal, (pkSuccess * 100.0 / Math.max(1, pkTotal))));
            System.out.println(String.format("📊 CONTRAINTES FK: %d/%d migrées (%.1f%%)", 
                fkSuccess, fkTotal, (fkSuccess * 100.0 / Math.max(1, fkTotal))));
            System.out.println("=".repeat(80));
            
            int totalObjets = tablesTotal + dataTotal + sequencesTotal + functionsTotal + viewsTotal + pkTotal + fkTotal + indexTotal + triggersTotal;
            int totalSuccess = tablesSuccess + dataSuccess + sequencesSuccess + functionsSuccess + viewsSuccess + pkSuccess + fkSuccess + indexSuccess + triggersSuccess;
            double globalScore = (totalSuccess * 100.0 / Math.max(1, totalObjets));
            System.out.println(String.format("🎯 SCORE GLOBAL : %.1f%% (%d/%d objets migrés)", globalScore, totalSuccess, totalObjets));
            System.out.println("=".repeat(80));
        }
    }

    public static void migrateCompleteDatabase(PostgreSQL postgres, Oracle oracle) throws SQLException {
        System.out.println("\n=== MIGRATION POSTGRESQL → ORACLE 100% COMPLÈTE ===\n");
        long start = System.currentTimeMillis();
        MigrationStats stats = new MigrationStats();

        try (Connection pgConn = PostgresService.PostgresConnexion(postgres);
             Connection oraConn = OracleService.OracleConnexion(oracle)) {
            
            // 1. SÉQUENCES (avant tables pour auto-increment)
            migrateSequences(pgConn, oraConn, stats);
            
            // 2. TABLES (structure seulement)
            migrateTables(pgConn, oraConn, stats);
            
            // 3. DONNÉES
            migrateData(pgConn, oraConn, stats);
            
            // 4. INDEX (avant contraintes pour performance)
            migrateIndexes(pgConn, oraConn, stats);
            
            // 5. CONTRAINTES FK SEULEMENT (PK déjà créées avec tables)
            migrateConstraints(pgConn, oraConn, stats);
            
            // 6. FONCTIONS
            migrateFunctions(pgConn, oraConn, stats);
            
            // 7. TRIGGERS
            migrateTriggers(pgConn, oraConn, stats);
            
            // 8. VUES (en dernier car dépendent de tout)
            migrateViews(pgConn, oraConn, stats);
            
            System.out.println("\n✅ Migration terminée en " + (System.currentTimeMillis() - start) / 1000 + "s\n");
            stats.printDetailed();
        } catch (Exception e) {
            e.printStackTrace();
            throw new SQLException("Migration échouée", e);
        }
    }

    private static void migrateTables(Connection pg, Connection ora, MigrationStats stats) throws SQLException {
        System.out.println("\n🔨 MIGRATION DES TABLES...");
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")) {
            while (rs.next()) {
                stats.tablesTotal++;
                String table = rs.getString(1);
                try {
                    String createSQL = generateEnhancedCreateTableSQL(pg, table);
                    try (Statement stmt = ora.createStatement()) {
                        stmt.executeUpdate(createSQL);
                    }
                    stats.tablesSuccess++;
                    System.out.println("✅ Table: " + table);
                } catch (Exception e) {
                    stats.tablesFailed++;
                    stats.failedTables.add(table);
                    System.err.println("❌ Table " + table + ": " + e.getMessage());
                }
            }
        }
    }

    private static String generateEnhancedCreateTableSQL(Connection pg, String table) throws SQLException {
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(quote(table.toUpperCase())).append(" (");
        
        String sql = "SELECT column_name, data_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable, column_default " +
                     "FROM information_schema.columns " +
                     "WHERE table_name = ? AND table_schema = 'public' " +
                     "ORDER BY ordinal_position";
        
        List<String> cols = new ArrayList<>();
        List<String> pkCols = new ArrayList<>();
        
        // Récupérer les colonnes de clé primaire
        try (PreparedStatement pkPs = pg.prepareStatement(
            "SELECT kcu.column_name FROM information_schema.table_constraints tc " +
            "JOIN information_schema.key_column_usage kcu ON tc.constraint_name=kcu.constraint_name " +
            "WHERE tc.table_schema='public' AND tc.table_name=? AND tc.constraint_type='PRIMARY KEY' " +
            "ORDER BY kcu.ordinal_position")) {
            pkPs.setString(1, table);
            try (ResultSet pkRs = pkPs.executeQuery()) {
                while (pkRs.next()) {
                    pkCols.add(pkRs.getString(1).toLowerCase());
                }
            }
        }
        
        try (PreparedStatement ps = pg.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String colName = rs.getString(1);
                    String colNameQuoted = quote(colName.toUpperCase());
                    String pgType = rs.getString(2);
                    int length = rs.getInt(3);
                    int precision = rs.getInt(4);
                    int scale = rs.getInt(5);
                    boolean nullable = "YES".equals(rs.getString(6));
                    String defVal = rs.getString(7);
                    
                    String oraType = mapEnhancedType(pgType, length, precision, scale);
                    String colDef = colNameQuoted + " " + oraType;
                    
                    if (defVal != null && !defVal.contains("nextval")) {
                        colDef += " DEFAULT " + convertDefault(defVal);
                    }
                    if (!nullable) {
                        colDef += " NOT NULL";
                    }
                    cols.add(colDef);
                }
            }
        }
        
        sb.append(String.join(", ", cols));
        
        // Ajouter la PK DIRECTEMENT dans CREATE TABLE
        if (!pkCols.isEmpty()) {
            List<String> pkColsQuoted = new ArrayList<>();
            for (String pk : pkCols) {
                pkColsQuoted.add(quote(pk.toUpperCase()));
            }
            sb.append(", CONSTRAINT PK_").append(table.toUpperCase())
              .append(" PRIMARY KEY (").append(String.join(",", pkColsQuoted)).append(")");
        }
        
        sb.append(")");
        return sb.toString();
    }

    private static String mapEnhancedType(String pgType, int len, int prec, int scale) {
        switch (pgType.toLowerCase()) {
            case "character varying":
            case "varchar":
                return (len > 0 && len <= 4000) ? "VARCHAR2(" + len + ")" : "CLOB";
            case "character":
            case "char":
                return "CHAR(" + Math.max(1, len) + ")";
            case "text":
                return "CLOB";
            case "integer":
            case "int4":
                return "NUMBER(10)";
            case "bigint":
            case "int8":
                return "NUMBER(19)";
            case "smallint":
            case "int2":
                return "NUMBER(5)";
            case "numeric":
            case "decimal":
                return (prec > 0) ? "NUMBER(" + prec + "," + Math.max(0, scale) + ")" : "NUMBER";
            case "double precision":
            case "float8":
                return "BINARY_DOUBLE";
            case "real":
            case "float4":
                return "BINARY_FLOAT";
            case "boolean":
                return "NUMBER(1)";
            case "timestamp without time zone":
            case "timestamp":
                return "TIMESTAMP";
            case "timestamp with time zone":
            case "timestamptz":
                return "TIMESTAMP WITH TIME ZONE";
            case "date":
                return "DATE";
            case "time without time zone":
            case "time":
                return "DATE";
            case "bytea":
                return "BLOB";
            case "uuid":
                return "VARCHAR2(36)";
            case "json":
            case "jsonb":
                return "CLOB";
            case "money":
                return "NUMBER(19,2)";
            case "interval":
                return "INTERVAL DAY TO SECOND";
            case "bit":
                return "NUMBER(1)";
            case "xml":
                return "XMLTYPE";
            case "array":
                return "CLOB";
            default:
                return "CLOB";
        }
    }

    private static String convertDefault(String def) {
        if (def == null) return null;
        def = def.trim();
        if (def.equalsIgnoreCase("true")) return "1";
        if (def.equalsIgnoreCase("false")) return "0";
        if (def.toUpperCase().contains("NOW")) return "SYSDATE";
        if (def.toUpperCase().contains("CURRENT_TIMESTAMP")) return "SYSDATE";
        if (def.toUpperCase().contains("CURRENT_DATE")) return "TRUNC(SYSDATE)";
        return def;
    }

    private static void migrateData(Connection pg, Connection ora, MigrationStats stats) throws SQLException {
        System.out.println("\n📦 MIGRATION DES DONNÉES...");
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='public'")) {
            while (rs.next()) {
                stats.dataTotal++;
                String table = rs.getString(1);
                try {
                    insertDataBatchWithDuplicateHandling(pg, ora, table);
                    stats.dataSuccess++;
                    System.out.println("✅ Données: " + table);
                } catch (Exception e) {
                    stats.dataFailed++;
                    stats.failedData.add(table);
                    System.err.println("❌ Données " + table + ": " + e.getMessage());
                }
            }
        }
    }

    private static void insertDataBatchWithDuplicateHandling(Connection pg, Connection ora, String table) throws SQLException {
        String countSql = "SELECT COUNT(*) FROM \"" + table + "\"";
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery(countSql)) {
            if (rs.next() && rs.getInt(1) == 0) {
                return;
            }
        }
        
        try (Statement st = pg.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            st.setFetchSize(1000);
            try (ResultSet rs = st.executeQuery("SELECT * FROM \"" + table + "\"")) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                
                StringBuilder sb = new StringBuilder("INSERT INTO ").append(quote(table.toUpperCase())).append(" (");
                List<String> cols = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    cols.add(quote(meta.getColumnName(i).toUpperCase()));
                }
                sb.append(String.join(",", cols)).append(") VALUES (");
                for (int i = 0; i < colCount; i++) {
                    sb.append(i > 0 ? ",?" : "?");
                }
                sb.append(")");
                
                try (PreparedStatement ps = ora.prepareStatement(sb.toString())) {
                    ora.setAutoCommit(false);
                    int batch = 0;
                    int skipped = 0;
                    
                    while (rs.next()) {
                        try {
                            for (int i = 1; i <= colCount; i++) {
                                Object val = rs.getObject(i);
                                if (val instanceof Boolean) {
                                    val = ((Boolean) val) ? 1 : 0;
                                }
                                if (val instanceof java.sql.Array) {
                                    val = val.toString();
                                }
                                ps.setObject(i, val);
                            }
                            ps.addBatch();
                            batch++;
                            
                            if (batch >= 1000) {
                                try {
                                    ps.executeBatch();
                                    ora.commit();
                                } catch (BatchUpdateException bue) {
                                    for (int[] updateCounts : new int[][]{bue.getUpdateCounts()}) {
                                        for (int uc : updateCounts) {
                                            if (uc == Statement.EXECUTE_FAILED) skipped++;
                                        }
                                    }
                                    ora.commit();
                                }
                                batch = 0;
                            }
                        } catch (SQLException e) {
                            if (e.getErrorCode() == 1) { // ORA-00001
                                skipped++;
                            } else {
                                throw e;
                            }
                        }
                    }
                    
                    if (batch > 0) {
                        try {
                            ps.executeBatch();
                            ora.commit();
                        } catch (BatchUpdateException bue) {
                            for (int[] updateCounts : new int[][]{bue.getUpdateCounts()}) {
                                for (int uc : updateCounts) {
                                    if (uc == Statement.EXECUTE_FAILED) skipped++;
                                }
                            }
                            ora.commit();
                        }
                    }
                    
                    if (skipped > 0) {
                        System.out.println("   ⚠️ " + skipped + " lignes dupliquées ignorées");
                    }
                    
                    ora.setAutoCommit(true);
                }
            }
        }
    }

    private static void migrateSequences(Connection pg, Connection ora, MigrationStats stats) {
        System.out.println("\n🔢 MIGRATION DES SÉQUENCES...");
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery("SELECT sequencename FROM pg_sequences WHERE schemaname='public'")) {
            while (rs.next()) {
                stats.sequencesTotal++;
                String seq = rs.getString(1);
                try {
                    try (PreparedStatement ps = pg.prepareStatement(
                        "SELECT start_value,min_value,max_value,increment_by,cycle,cache_size " +
                        "FROM pg_sequences WHERE schemaname='public' AND sequencename=?")) {
                        ps.setString(1, seq);
                        try (ResultSet r = ps.executeQuery()) {
                            if (!r.next()) continue;
                            long start = r.getLong(1), minv = r.getLong(2), maxv = r.getLong(3), inc = r.getLong(4);
                            boolean cyc = r.getBoolean(5);
                            long cache = Math.max(2, r.getLong(6));

                            String ddl = "CREATE SEQUENCE " + seq.toUpperCase() +
                                       " START WITH " + start + " INCREMENT BY " + inc +
                                       (minv <= -999_999_999_999_999_999L ? " NOMINVALUE" : " MINVALUE " + minv) +
                                       (maxv >= 999_999_999_999_999_999L ? " NOMAXVALUE" : " MAXVALUE " + maxv) +
                                       " CACHE " + cache + (cyc ? " CYCLE" : " NOCYCLE");

                            try (Statement s = ora.createStatement()) { 
                                s.executeUpdate(ddl); 
                            }
                            stats.sequencesSuccess++;
                            System.out.println("✅ Séquence: " + seq);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("❌ Séquence " + seq + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void migrateIndexes(Connection pg, Connection ora, MigrationStats stats) {
        System.out.println("\n🔍 MIGRATION DES INDEX...");
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery("SELECT indexname, tablename, indexdef FROM pg_indexes WHERE schemaname='public' AND indexname NOT LIKE '%_pkey'")) {
            while (rs.next()) {
                stats.indexTotal++;
                String idx = rs.getString(1);
                String table = rs.getString(2);
                String def = rs.getString(3);
                try {
                    String oraIdx = convertIndexDef(def, table);
                    if (oraIdx != null) {
                        try (Statement s = ora.createStatement()) { 
                            s.executeUpdate(oraIdx); 
                        }
                        stats.indexSuccess++;
                        System.out.println("✅ Index: " + idx);
                    }
                } catch (Exception e) {
                    stats.failedIndexes.put(idx, e.getMessage());
                    System.err.println("❌ Index " + idx + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String convertIndexDef(String pgDef, String tableName) {
        try {
            Pattern idxNamePattern = Pattern.compile("CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
            Matcher nameMatcher = idxNamePattern.matcher(pgDef);
            String indexName = "";
            if (nameMatcher.find()) {
                indexName = nameMatcher.group(1).toUpperCase();
            }
            
            Pattern colsPattern = Pattern.compile("\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE);
            Matcher colsMatcher = colsPattern.matcher(pgDef);
            String columns = "";
            if (colsMatcher.find()) {
                columns = colsMatcher.group(1).toUpperCase();
            }
            
            boolean isUnique = pgDef.toUpperCase().contains("UNIQUE");
            
            if (indexName.isEmpty() || columns.isEmpty()) {
                return null;
            }
            
            String sql = "CREATE " + (isUnique ? "UNIQUE " : "") + "INDEX " + indexName +
                        " ON " + quote(tableName.toUpperCase()) + " (" + columns + ")";
            return sql;
        } catch (Exception e) {
            return null;
        }
    }

    private static void migrateFunctions(Connection pg, Connection ora, MigrationStats stats) {
        System.out.println("\n🧪 MIGRATION DES FONCTIONS...");
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery("SELECT routine_name FROM information_schema.routines WHERE routine_schema='public' AND routine_type='FUNCTION'")) {
            while (rs.next()) {
                stats.functionsTotal++;
                String func = rs.getString(1);
                if (func.startsWith("trg_")) continue;
                
                try {
                    String src = null;
                    try (PreparedStatement ps = pg.prepareStatement(
                        "SELECT pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON p.pronamespace=n.oid WHERE n.nspname='public' AND p.proname=?")) {
                        ps.setString(1, func);
                        try (ResultSet r = ps.executeQuery()) {
                            if (r.next()) src = r.getString(1);
                        }
                    }
                    if (src == null || src.isBlank()) continue;
                    
                    String plsql = convertFunctionToOracle(func, src);
                    if (plsql != null) {
                        try (Statement s = ora.createStatement()) { 
                            s.executeUpdate(plsql); 
                        }
                        stats.functionsSuccess++;
                        System.out.println("✅ Fonction: " + func);
                    }
                } catch (Exception e) {
                    stats.failedFunctions.put(func, e.getMessage());
                    System.err.println("❌ Fonction " + func + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String convertFunctionToOracle(String name, String pgSrc) {
        String upper = pgSrc.toUpperCase();
        
        if (upper.contains("NEXTVAL")) {
            Pattern p = Pattern.compile("NEXTVAL\\('([^']+)'\\)", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(pgSrc);
            if (m.find()) {
                String seq = m.group(1);
                return "CREATE OR REPLACE FUNCTION " + name.toUpperCase() + " RETURN NUMBER IS\n" +
                       "BEGIN\n  RETURN " + seq.toUpperCase() + ".NEXTVAL;\nEND;";
            }
        }
        
        if (upper.contains("RETURNS") && upper.contains("BEGIN") && upper.contains("END")) {
            try {
                String body = pgSrc.substring(pgSrc.toUpperCase().indexOf("BEGIN"), pgSrc.toUpperCase().lastIndexOf("END") + 3);
                String returnType = extractReturnType(pgSrc);
                
                body = body
                    .replaceAll("(?i)::NUMBER", "")
                    .replaceAll("(?i)::VARCHAR2", "")
                    .replaceAll("(?i)::CLOB", "")
                    .replaceAll("(?i) QUERY", " RETURN")
                    .replaceAll("(?i) EXCEPTION", " RAISE_APPLICATION_ERROR(-20000,")
                    .replaceAll("(?i)IN\\(SELECT", "FOR i IN (SELECT")
                    .replaceAll("(?i) LOOP", " END LOOP");
                
                return "CREATE OR REPLACE FUNCTION " + name.toUpperCase() + " RETURN " + returnType + " IS\n" + body;
            } catch (Exception e) {
                return null;
            }
        }
        
        return null;
    }

    private static String extractReturnType(String pgSrc) {
        Pattern p = Pattern.compile("RETURNS\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(pgSrc);
        if (m.find()) {
            String type = m.group(1).toUpperCase();
            switch (type) {
                case "INTEGER": return "NUMBER";
                case "BIGINT": return "NUMBER";
                case "TEXT": return "CLOB";
                case "VARCHAR": return "VARCHAR2";
                case "BOOLEAN": return "NUMBER";
                default: return type;
            }
        }
        return "NUMBER";
    }

    private static void migrateTriggers(Connection pg, Connection ora, MigrationStats stats) {
        System.out.println("\n⚡ MIGRATION DES TRIGGERS...");
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery("SELECT trigger_name, event_object_table, action_statement FROM information_schema.triggers WHERE trigger_schema='public'")) {
            while (rs.next()) {
                stats.triggersTotal++;
                String trg = rs.getString(1);
                String table = rs.getString(2);
                String action = rs.getString(3);
                try {
                    if (action.toUpperCase().contains("NEXTVAL")) {
                        Pattern p = Pattern.compile("nextval\\('([^']+)'\\)", Pattern.CASE_INSENSITIVE);
                        Matcher m = p.matcher(action);
                        if (m.find()) {
                            String seq = m.group(1);
                            String plsql = "CREATE OR REPLACE TRIGGER " + trg.toUpperCase() + "\n" +
                                         "BEFORE INSERT ON " + table.toUpperCase() + " FOR EACH ROW\n" +
                                         "BEGIN\n  SELECT " + seq.toUpperCase() + ".NEXTVAL INTO :NEW.ID FROM DUAL;\nEND;";
                            try (Statement s = ora.createStatement()) { 
                                s.executeUpdate(plsql); 
                            }
                            stats.triggersSuccess++;
                            System.out.println("✅ Trigger: " + trg);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("❌ Trigger " + trg + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // FIX COMPLET: Migration des vues avec détection garantie
    private static void migrateViews(Connection pg, Connection ora, MigrationStats stats) {
        System.out.println("\n👁️ MIGRATION DES VUES...");
        
        // Méthode 1: Requête simple pour compter les vues
        List<String> allViews = new ArrayList<>();
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery("SELECT viewname FROM pg_views WHERE schemaname='public' ORDER BY viewname")) {
            while (rs.next()) {
                allViews.add(rs.getString(1));
            }
        } catch (Exception e) {
            System.err.println("❌ Impossible de récupérer les vues: " + e.getMessage());
            return;
        }
        
        if (allViews.isEmpty()) {
            System.out.println("⚠️ Aucune vue détectée dans le schéma 'public'");
            return;
        }
        
        System.out.println("📊 " + allViews.size() + " vues détectées");
        stats.viewsTotal = allViews.size();
        
        // Méthode 2: Essayer l'ordre de dépendance (peut échouer)
        List<String> orderedViews = getViewDependencyOrderSafe(pg, allViews);
        
        // Méthode 3: Si l'ordre de dépendance échoue, utiliser l'ordre simple
        if (orderedViews.isEmpty()) {
            System.out.println("⚠️ Ordre de dépendance indisponible, migration dans l'ordre alphabétique");
            orderedViews = allViews;
        } else {
            System.out.println("✅ Ordre de dépendance calculé: " + orderedViews.size() + " vues");
        }
        
        // Migrer chaque vue
        for (String view : orderedViews) {
            try {
                String def = null;
                try (PreparedStatement ps = pg.prepareStatement(
                    "SELECT definition FROM pg_views WHERE schemaname='public' AND viewname=?")) {
                    ps.setString(1, view);
                    try (ResultSet r = ps.executeQuery()) {
                        if (r.next()) def = r.getString(1);
                    }
                }
                
                if (def == null || def.isBlank()) {
                    System.err.println("⚠️ Vue " + view + ": définition vide");
                    continue;
                }
                
                String sql = convertEnhancedViewSql(def);
                String ddl = "CREATE OR REPLACE VIEW " + quote(view.toUpperCase()) + " AS " + sql;
                
                try (Statement s = ora.createStatement()) { 
                    s.executeUpdate(ddl); 
                }
                stats.viewsSuccess++;
                System.out.println("✅ Vue: " + view);
            } catch (Exception e) {
                stats.failedViews.put(view, e.getMessage());
                String errMsg = e.getMessage();
                if (errMsg.length() > 100) errMsg = errMsg.substring(0, 100) + "...";
                System.err.println("❌ Vue " + view + ": " + errMsg);
            }
        }
        
        System.out.println("📊 Vues migrées: " + stats.viewsSuccess + "/" + stats.viewsTotal);
    }

    private static List<String> getViewDependencyOrderSafe(Connection pg, List<String> fallbackViews) {
        List<String> views = new ArrayList<>();
        
        // Essayer la requête récursive
        String recursiveSQL = 
            "WITH RECURSIVE view_deps AS (" +
            "  SELECT DISTINCT v.relname AS viewname, 0 AS depth " +
            "  FROM pg_class v " +
            "  WHERE v.relkind = 'v' " +
            "    AND v.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public') " +
            "    AND NOT EXISTS (" +
            "      SELECT 1 FROM pg_depend d " +
            "      JOIN pg_rewrite r ON r.oid = d.objid " +
            "      JOIN pg_class vc ON vc.oid = r.ev_class " +
            "      WHERE vc.oid = v.oid " +
            "        AND d.refclassid = 'pg_class'::regclass " +
            "        AND d.refobjid IN (" +
            "          SELECT oid FROM pg_class " +
            "          WHERE relkind = 'v' " +
            "            AND relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')" +
            "        )" +
            "    )" +
            "  UNION ALL " +
            "  SELECT DISTINCT v.relname, vd.depth + 1 " +
            "  FROM pg_depend d " +
            "  JOIN pg_rewrite r ON r.oid = d.objid " +
            "  JOIN pg_class v ON v.oid = r.ev_class " +
            "  JOIN view_deps vd ON d.refobjid = (" +
            "    SELECT oid FROM pg_class " +
            "    WHERE relname = vd.viewname AND relkind = 'v'" +
            "  ) " +
            "  WHERE v.relkind = 'v' AND d.refclassid = 'pg_class'::regclass" +
            ") " +
            "SELECT DISTINCT viewname FROM view_deps ORDER BY depth, viewname";
        
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery(recursiveSQL)) {
            while (rs.next()) {
                views.add(rs.getString(1));
            }
            if (!views.isEmpty()) {
                return views;
            }
        } catch (Exception e) {
            System.err.println("⚠️ Requête récursive échouée: " + e.getMessage());
        }
        
        // Si la récursive échoue, essayer une requête simplifiée avec ordre de niveau
        String simpleSQL = 
            "SELECT v.viewname, COUNT(d.refobjid) as dependency_count " +
            "FROM pg_views v " +
            "LEFT JOIN pg_depend d ON d.objid = (" +
            "  SELECT r.ev_class FROM pg_rewrite r " +
            "  WHERE r.ev_class = (SELECT c.oid FROM pg_class c WHERE c.relname = v.viewname AND c.relkind = 'v')" +
            ") " +
            "AND d.refclassid = 'pg_class'::regclass " +
            "AND d.refobjid IN (" +
            "  SELECT c.oid FROM pg_class c WHERE c.relkind = 'v' AND c.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')" +
            ") " +
            "WHERE v.schemaname = 'public' " +
            "GROUP BY v.viewname " +
            "ORDER BY dependency_count, v.viewname";
        
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery(simpleSQL)) {
            while (rs.next()) {
                views.add(rs.getString(1));
            }
            if (!views.isEmpty()) {
                return views;
            }
        } catch (Exception e) {
            System.err.println("⚠️ Requête simplifiée échouée: " + e.getMessage());
        }
        
        // Fallback: retourner la liste fournie en entrée
        return fallbackViews;
    }

    private static String convertEnhancedViewSql(String pg) {
        String s = pg.trim();
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1);

        // Supprimer les casts PostgreSQL
        s = s.replaceAll("(?i)::VARCHAR(\\(\\d+\\))?", "")
             .replaceAll("(?i)::INTEGER", "")
             .replaceAll("(?i)::BIGINT", "")
             .replaceAll("(?i)::NUMERIC(\\(\\d+(,\\d+)?\\))?", "")
             .replaceAll("(?i)::TEXT", "")
             .replaceAll("(?i)::DATE", "")
             .replaceAll("(?i)::TIMESTAMP(\\s+WITH(OUT)?\\s+TIME\\s+ZONE)?", "")
             .replaceAll("(?i)::BOOLEAN", "")
             .replaceAll("(?i)::CHARACTER\\s+VARYING(\\(\\d+\\))?", "");

        // Fonctions de date
        s = s.replaceAll("(?i)DATE_TRUNC\\('month'\\s*,\\s*([^)]+)\\)", "TRUNC($1,'MM')")
             .replaceAll("(?i)DATE_TRUNC\\('day'\\s*,\\s*([^)]+)\\)", "TRUNC($1)")
             .replaceAll("(?i)DATE_TRUNC\\('year'\\s*,\\s*([^)]+)\\)", "TRUNC($1,'YYYY')")
             .replaceAll("(?i)DATE_TRUNC\\('quarter'\\s*,\\s*([^)]+)\\)", "TRUNC($1,'Q')")
             .replaceAll("(?i)DATE_TRUNC\\('week'\\s*,\\s*([^)]+)\\)", "TRUNC($1,'IW')")
             .replaceAll("(?i)\\bCURRENT_TIMESTAMP\\b", "SYSDATE")
             .replaceAll("(?i)\\bNOW\\(\\)", "SYSDATE")
             .replaceAll("(?i)\\bCURRENT_DATE\\b", "TRUNC(SYSDATE)")
             .replaceAll("(?i)\\bAGE\\(([^,]+),([^)]+)\\)", "($1 - $2)")
             .replaceAll("(?i)\\bEXTRACT\\(EPOCH\\s+FROM\\s+([^)]+)\\)", 
                        "(EXTRACT(DAY FROM $1)*86400 + EXTRACT(HOUR FROM $1)*3600 + EXTRACT(MINUTE FROM $1)*60 + EXTRACT(SECOND FROM $1))");

        // Fonctions de chaînes
        s = s.replaceAll("(?i)\\bCOALESCE\\(([^,]+),([^)]+)\\)", "NVL($1,$2)")
             .replaceAll("(?i)\\bSUBSTRING\\(", "SUBSTR(")
             .replaceAll("(?i)\\bPOSITION\\(([^)]+)\\s+IN\\s+([^)]+)\\)", "INSTR($2,$1)")
             .replaceAll("(?i)\\bLENGTH\\(", "LENGTH(")
             .replaceAll("(?i)\\bCONCAT\\(", "CONCAT(")
             .replaceAll("(?i)\\|\\|", "||");

        // STRING_AGG → LISTAGG
        s = s.replaceAll("(?i)STRING_AGG\\(([^,]+),\\s*'([^']*)'\\s+ORDER\\s+BY\\s+([^)]+)\\)", 
                        "LISTAGG($1,'$2') WITHIN GROUP (ORDER BY $3)")
             .replaceAll("(?i)STRING_AGG\\(([^,]+),\\s*'([^']*)'\\)", 
                        "LISTAGG($1,'$2') WITHIN GROUP (ORDER BY $1)");

        // Agrégations booléennes
        s = s.replaceAll("(?i)\\bBOOL_AND\\(", "MIN(")
             .replaceAll("(?i)\\bBOOL_OR\\(", "MAX(");

        // Window functions
        s = s.replaceAll("(?i)\\bROW_NUMBER\\(\\)\\s+OVER", "ROW_NUMBER() OVER")
             .replaceAll("(?i)\\bRANK\\(\\)\\s+OVER", "RANK() OVER")
             .replaceAll("(?i)\\bDENSE_RANK\\(\\)\\s+OVER", "DENSE_RANK() OVER")
             .replaceAll("(?i)\\bLEAD\\(", "LEAD(")
             .replaceAll("(?i)\\bLAG\\(", "LAG(")
             .replaceAll("(?i)\\bFIRST_VALUE\\(", "FIRST_VALUE(")
             .replaceAll("(?i)\\bLAST_VALUE\\(", "LAST_VALUE(");

        // Séquences
        s = s.replaceAll("(?i)nextval\\('([^']+)'\\)", "$1.NEXTVAL")
             .replaceAll("(?i)currval\\('([^']+)'\\)", "$1.CURRVAL");

        // Opérateurs
        s = s.replaceAll("(?i)\\bTRUE\\b", "1")
             .replaceAll("(?i)\\bFALSE\\b", "0")
             .replaceAll("(?i)\\bNOT\\s+IN\\s+ALL\\(", "NOT IN (")
             .replaceAll("(?i)\\bIN\\s+ANY\\(", "IN (")
             .replaceAll("(?i)\\bNULLIF\\(", "NULLIF(");

        // DISTINCT ON → Subquery avec ROW_NUMBER
        Pattern distinctOnPattern = Pattern.compile(
            "SELECT\\s+DISTINCT\\s+ON\\s*\\(([^)]+)\\)\\s+(.+?)\\s+FROM", 
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        Matcher distinctOnMatcher = distinctOnPattern.matcher(s);
        if (distinctOnMatcher.find()) {
            String distinctCols = distinctOnMatcher.group(1);
            String selectCols = distinctOnMatcher.group(2);
            s = s.replaceFirst("(?i)SELECT\\s+DISTINCT\\s+ON\\s*\\([^)]+\\)", 
                "SELECT * FROM (SELECT " + selectCols + ", ROW_NUMBER() OVER (PARTITION BY " + distinctCols + 
                " ORDER BY " + distinctCols + ") as rn FROM");
            s = s + ") WHERE rn = 1";
        }

        // LIMIT → FETCH FIRST
        s = s.replaceAll("(?i)OFFSET\\s+(\\d+)\\s+LIMIT\\s+(\\d+)", "OFFSET $1 ROWS FETCH NEXT $2 ROWS ONLY")
             .replaceAll("(?i)LIMIT\\s+(\\d+)", "FETCH FIRST $1 ROWS ONLY");

        // Regex → REGEXP_LIKE
        s = s.replaceAll("(?i)([\\w\\.]+)\\s*~\\s*'([^']+)'", "REGEXP_LIKE($1,'$2')")
             .replaceAll("(?i)([\\w\\.]+)\\s*~\\*\\s*'([^']+)'", "REGEXP_LIKE($1,'$2','i')");

        // ILIKE → UPPER(x) LIKE UPPER(y)
        s = s.replaceAll("(?i)([\\w\\.]+)\\s+ILIKE\\s+'([^']+)'", "UPPER($1) LIKE UPPER('$2')");

        // Generate_series → LEVEL
        Pattern genSeriesPattern = Pattern.compile("generate_series\\((\\d+)\\s*,\\s*(\\d+)\\)", Pattern.CASE_INSENSITIVE);
        Matcher genSeriesMatcher = genSeriesPattern.matcher(s);
        if (genSeriesMatcher.find()) {
            s = genSeriesMatcher.replaceAll("(SELECT LEVEL FROM DUAL CONNECT BY LEVEL <= $2)");
        }

        // JSON functions
        s = s.replaceAll("(?i)([\\w\\.]+)->>'([^']+)'", "JSON_VALUE($1,'\\$.$2')")
             .replaceAll("(?i)([\\w\\.]+)->'([^']+)'", "JSON_QUERY($1,'\\$.$2')")
             .replaceAll("(?i)\\bTO_JSON\\(", "JSON_OBJECT(")
             .replaceAll("(?i)\\bTO_JSONB\\(", "JSON_OBJECT(");

        // Array functions → LISTAGG
        s = s.replaceAll("(?i)\\bARRAY_AGG\\(", "LISTAGG(");

        return s;
    }

    private static void migrateConstraints(Connection pg, Connection ora, MigrationStats stats) {
        System.out.println("\n🔗 MIGRATION DES CONTRAINTES...");
        
        // PRIMARY KEYS: SKIP (déjà créées dans CREATE TABLE)
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'")) {
            while (rs.next()) {
                stats.pkTotal++;
                stats.pkSuccess++; // Déjà créées
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // FOREIGN KEYS
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT tc.constraint_name, tc.table_name, kcu.column_name, " +
                "ccu.table_name AS foreign_table_name, ccu.column_name AS foreign_column_name, " +
                "rc.update_rule, rc.delete_rule " +
                "FROM information_schema.table_constraints tc " +
                "JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema " +
                "JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_name = tc.constraint_name AND ccu.table_schema = tc.table_schema " +
                "JOIN information_schema.referential_constraints rc ON rc.constraint_name = tc.constraint_name AND rc.constraint_schema = tc.table_schema " +
                "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema='public'")) {
            while (rs.next()) {
                stats.fkTotal++;
                String fkName = rs.getString(1);
                String table = rs.getString(2);
                String col = rs.getString(3);
                String refTable = rs.getString(4);
                String refCol = rs.getString(5);
                String updateRule = rs.getString(6);
                String deleteRule = rs.getString(7);
                try {
                    String sql = "ALTER TABLE " + quote(table.toUpperCase()) + 
                               " ADD CONSTRAINT " + fkName.toUpperCase() + 
                               " FOREIGN KEY (" + quote(col.toUpperCase()) + ") " +
                               " REFERENCES " + quote(refTable.toUpperCase()) + "(" + quote(refCol.toUpperCase()) + ")";
                    if ("CASCADE".equals(deleteRule)) {
                        sql += " ON DELETE CASCADE";
                    } else if ("SET NULL".equals(deleteRule)) {
                        sql += " ON DELETE SET NULL";
                    }
                    
                    try (Statement s = ora.createStatement()) { 
                        s.executeUpdate(sql); 
                    }
                    stats.fkSuccess++;
                    System.out.println("✅ FK: " + fkName);
                } catch (Exception e) {
                    System.err.println("❌ FK " + fkName + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // UNIQUE CONSTRAINTS
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT tc.constraint_name, tc.table_name, STRING_AGG(kcu.column_name, ',' ORDER BY kcu.ordinal_position) as columns " +
                "FROM information_schema.table_constraints tc " +
                "JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema " +
                "WHERE tc.constraint_type = 'UNIQUE' AND tc.table_schema='public' " +
                "GROUP BY tc.constraint_name, tc.table_name")) {
            while (rs.next()) {
                String ukName = rs.getString(1);
                String table = rs.getString(2);
                String[] cols = rs.getString(3).split(",");
                try {
                    List<String> quotedCols = new ArrayList<>();
                    for (String col : cols) {
                        quotedCols.add(quote(col.trim().toUpperCase()));
                    }
                    String sql = "ALTER TABLE " + quote(table.toUpperCase()) + 
                               " ADD CONSTRAINT " + ukName.toUpperCase() + 
                               " UNIQUE (" + String.join(",", quotedCols) + ")";
                    try (Statement s = ora.createStatement()) { 
                        s.executeUpdate(sql); 
                    }
                    System.out.println("✅ UNIQUE: " + ukName);
                } catch (Exception e) {
                    System.err.println("❌ UNIQUE " + ukName + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // CHECK CONSTRAINTS: SKIP (noms invalides pour Oracle)
        System.out.println("⚠️ CHECK constraints ignorées (générées automatiquement par PostgreSQL)");
    }

    private static String quote(String identifier) {
        Set<String> reserved = new HashSet<>(Arrays.asList(
            "ACCESS", "ADD", "ALL", "ALTER", "AND", "ANY", "AS", "ASC", "AUDIT", "BETWEEN", "BY",
            "CHAR", "CHECK", "CLUSTER", "COLUMN", "COMMENT", "COMPRESS", "CONNECT", "CREATE",
            "CURRENT", "DATE", "DECIMAL", "DEFAULT", "DELETE", "DESC", "DISTINCT", "DROP", "ELSE",
            "EXCLUSIVE", "EXISTS", "FILE", "FLOAT", "FOR", "FROM", "GRANT", "GROUP", "HAVING",
            "IDENTIFIED", "IMMEDIATE", "IN", "INCREMENT", "INDEX", "INITIAL", "INSERT", "INTEGER",
            "INTERSECT", "INTO", "IS", "LEVEL", "LIKE", "LOCK", "LONG", "MAXEXTENTS", "MINUS",
            "MLSLABEL", "MODE", "MODIFY", "NOAUDIT", "NOCOMPRESS", "NOT", "NOWAIT", "NULL",
            "NUMBER", "OF", "OFFLINE", "ON", "ONLINE", "OPTION", "OR", "ORDER", "PCTFREE", "PRIOR",
            "PRIVILEGES", "PUBLIC", "RAW", "RENAME", "RESOURCE", "REVOKE", "ROW", "ROWID",
            "ROWNUM", "ROWS", "SELECT", "SESSION", "SET", "SHARE", "SIZE", "SMALLINT", "START",
            "SUCCESSFUL", "SYNONYM", "SYSDATE", "TABLE", "THEN", "TO", "TRIGGER", "UID", "UNION",
            "UNIQUE", "UPDATE", "USER", "VALIDATE", "VALUES", "VARCHAR", "VARCHAR2", "VIEW",
            "WHENEVER", "WHERE", "WITH", "TYPE", "END", "FETCH", "LONG", "COMMENT", "COMPRESS"
        ));
        
        String upper = identifier.toUpperCase();
        if (reserved.contains(upper) || identifier.contains(" ") || 
            identifier.matches(".*[^A-Za-z0-9_].*") || Character.isDigit(identifier.charAt(0))) {
            return "\"" + identifier.toUpperCase() + "\"";
        }
        return identifier.toUpperCase();
    }
}
