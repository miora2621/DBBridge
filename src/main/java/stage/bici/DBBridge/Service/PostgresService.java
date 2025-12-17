package stage.bici.DBBridge.Service;

import java.sql.*;
import java.util.*;
import java.util.regex.*;
import java.math.BigDecimal;
import stage.bici.DBBridge.Model.DonneeTablePostgres;
import stage.bici.DBBridge.Model.Oracle;
import stage.bici.DBBridge.Model.PostgreSQL;
import stage.bici.DBBridge.TestIA.SqlViewTranslator;

public class PostgresService {

    public String log;
    public PostgresService() {
        this.log = "";
    }
    
    // ============================================================
    // MOTS-CLÉS ORACLE RÉSERVÉS
    // ============================================================
    
    private static final Set<String> ORACLE_RESERVED = new HashSet<>(Arrays.asList(
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
        "WHENEVER", "WHERE", "WITH", "TYPE", "END", "FETCH", "COMPRESS"
    ));
    
    // ============================================================
    // UTILITAIRES AMÉLIORÉS
    // ============================================================
    
    private static String truncateForOracle(String identifier) {
        if (identifier.length() > 30) {
            String truncated = identifier.substring(0, 27) + "_TR";
            return truncated;
        }
        return identifier;
    }
    
    private static String quote(String identifier) {
        identifier = truncateForOracle(identifier);
        String upper = identifier.toUpperCase();
        if (ORACLE_RESERVED.contains(upper) || 
            identifier.contains(" ") || 
            identifier.matches(".*[^A-Za-z0-9_].*") || 
            (identifier.length() > 0 && Character.isDigit(identifier.charAt(0)))) {
            return "\"" + upper + "\"";
        }
        return upper;
    }
    
    private static Object cleanNullBytes(Object value, PostgresMigrationStats stats) {
        if (value instanceof String) {
            String str = (String) value;
            if (str.indexOf('\0') >= 0) {
                stats.nullBytesRemoved++;
                return str.replace("\0", "");
            }
        }
        return value;
    }

    // ============================================================
    // CONNEXIONS
    // ============================================================

    public static Connection PostgresConnexion(PostgreSQL postgres) throws SQLException {
        String url = postgres.buildConnectionUrl();
        return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
    }

    // ============================================================
    // MAPPING TYPES POSTGRESQL → ORACLE
    // ============================================================

    public static String mapPostgresTypeToOracle(DonneeTablePostgres col) {
        String type = col.getPgType().toLowerCase();

        switch (type) {
            case "character varying":
            case "varchar":
                if (col.getLength() > 0 && col.getLength() <= 4000) {
                    return "VARCHAR2(" + col.getLength() + ")";
                } else {
                    return "CLOB";
                }
            case "character":
            case "char":
                return "CHAR(" + Math.max(1, col.getLength()) + ")";
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
                if (col.getPrecision() > 0 && col.getScale() >= 0) {
                    return "NUMBER(" + col.getPrecision() + "," + col.getScale() + ")";
                } else {
                    return "NUMBER";
                }
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
            case "text":
                return "CLOB";
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
            case "inet":
            case "cidr":
                return "VARCHAR2(50)";
            case "macaddr":
                return "VARCHAR2(17)";
            case "point":
            case "line":
            case "lseg":
            case "box":
            case "path":
            case "polygon":
            case "circle":
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
        if (def.contains("::")) {
            def = def.replaceAll("::[A-Za-z0-9_]+", "");
        }
        return def;
    }

    // ============================================================
    // VALIDATION OBJETS POSTGRESQL
    // ============================================================

    private PostgresDatabaseObjects validatePostgresObjects(Connection pg) throws SQLException {
        System.out.println("\n🔍 VALIDATION DES OBJETS POSTGRESQL...");
        log += "\n🔍 VALIDATION DES OBJETS POSTGRESQL...\n";
        PostgresDatabaseObjects db = new PostgresDatabaseObjects();
        
        // Tables
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")) {
            while (rs.next()) {
                db.validTables.add(rs.getString(1));
            }
        }
        System.out.println("✅ Tables trouvées: " + db.validTables.size());
        log += "✅ Tables trouvées: " + db.validTables.size() + "\n";
        
        // Séquences
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery("SELECT sequencename FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename")) {
            while (rs.next()) {
                db.validSequences.add(rs.getString(1));
            }
        }
        System.out.println("✅ Séquences trouvées: " + db.validSequences.size());
        log += "✅ Séquences trouvées: " + db.validSequences.size() + "\n";
        
        // Vues avec définitions
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery("SELECT viewname, definition FROM pg_views WHERE schemaname='public' ORDER BY viewname")) {
            while (rs.next()) {
                String viewName = rs.getString(1);
                String definition = rs.getString(2);
                db.validViews.add(viewName);
                db.viewDefinitions.put(viewName, definition);
            }
        }
        System.out.println("✅ Vues trouvées: " + db.validViews.size());
        log += "✅ Vues trouvées: " + db.validViews.size() + "\n";
        
        // Fonctions
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT routine_name FROM information_schema.routines " +
                "WHERE routine_schema='public' AND routine_type='FUNCTION' ORDER BY routine_name")) {
            while (rs.next()) {
                String funcName = rs.getString(1);
                if (!funcName.startsWith("trg_")) {
                    db.validFunctions.add(funcName);
                }
            }
        }
        System.out.println("✅ Fonctions trouvées: " + db.validFunctions.size());
        log += "✅ Fonctions trouvées: " + db.validFunctions.size() + "\n";
        
        // Triggers
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT trigger_name FROM information_schema.triggers WHERE trigger_schema='public' ORDER BY trigger_name")) {
            while (rs.next()) {
                db.validTriggers.add(rs.getString(1));
            }
        }
        System.out.println("✅ Triggers trouvés: " + db.validTriggers.size());
        log += "✅ Triggers trouvés: " + db.validTriggers.size() + "\n";
        
        return db;
    }

    // ============================================================
    // ANALYSE DES DÉPENDANCES DES VUES
    // ============================================================

    private void analyzeViewDependencies(Connection pg, PostgresDatabaseObjects db, PostgresMigrationStats stats) {
        System.out.println("\n🔍 ANALYSE DES DÉPENDANCES DES VUES...");
        log += "\n🔍 ANALYSE DES DÉPENDANCES DES VUES...\n";
        
        Map<String, Set<String>> viewDependencies = new HashMap<>();
        Map<String, Set<String>> missingDependencies = new HashMap<>();
        
        for (String viewName : db.validViews) {
            String viewDef = db.viewDefinitions.get(viewName);
            if (viewDef != null) {
                Set<String> deps = findViewDependencies(viewDef, db);
                viewDependencies.put(viewName, deps);
                
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
        
        db.viewDependencies = viewDependencies;
        db.missingDependencies = missingDependencies;
    }

    private static Set<String> findViewDependencies(String viewDef, PostgresDatabaseObjects db) {
        Set<String> dependencies = new HashSet<>();
        String upperDef = viewDef.toUpperCase();
        
        for (String table : db.validTables) {
            if (upperDef.matches(".*\\b(FROM|JOIN)\\s+" + Pattern.quote(table.toUpperCase()) + "\\b.*")) {
                dependencies.add(table);
            }
        }
        
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

    private List<String> sortViewsByDependencies(PostgresDatabaseObjects db) {
        System.out.println("\n🔄 TRI TOPOLOGIQUE DES VUES...");
        log += "\n🔄 TRI TOPOLOGIQUE DES VUES...\n";
        
        Map<String, Set<String>> dependencies = new HashMap<>();
        for (String viewName : db.validViews) {
            Set<String> deps = db.viewDependencies.getOrDefault(viewName, new HashSet<>());
            // Ne garder que les dépendances qui existent
            Set<String> validDeps = new HashSet<>();
            for (String dep : deps) {
                if (db.validTables.contains(dep) || db.validViews.contains(dep)) {
                    validDeps.add(dep);
                }
            }
            dependencies.put(viewName, validDeps);
        }
        
        List<String> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        
        for (String view : db.validViews) {
            topologicalSort(view, dependencies, visited, visiting, sorted);
        }
        
        System.out.println("✅ Vues triées par dépendances: " + sorted.size());
        log += "✅ Vues triées par dépendances: " + sorted.size() + "\n";
        return sorted;
    }

    private static void topologicalSort(String view, Map<String, Set<String>> deps, 
                                       Set<String> visited, Set<String> visiting, List<String> sorted) {
        if (visited.contains(view)) return;
        if (visiting.contains(view)) {
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
    // 1. MIGRATION DES SÉQUENCES
    // ============================================================
    
    private void migrateSequences(Connection pg, Connection ora, PostgresDatabaseObjects db, PostgresMigrationStats stats) {
        System.out.println("\n🔢 MIGRATION DES SÉQUENCES...");
        log += "\n🔢 MIGRATION DES SÉQUENCES...\n";
        stats.sequencesTotal = db.validSequences.size();
        
        for (String seq : db.validSequences) {
            try {
                try (PreparedStatement ps = pg.prepareStatement(
                    "SELECT start_value, min_value, max_value, increment_by, cycle, cache_size " +
                    "FROM pg_sequences WHERE schemaname='public' AND sequencename=?")) {
                    ps.setString(1, seq);
                    try (ResultSet r = ps.executeQuery()) {
                        if (!r.next()) continue;
                        
                        long start = r.getLong(1);
                        long minv = r.getLong(2);
                        long maxv = r.getLong(3);
                        long inc = r.getLong(4);
                        boolean cyc = r.getBoolean(5);
                        long cache = Math.max(2, r.getLong(6));

                        String seqName = truncateForOracle(seq);
                        
                        String ddl = "CREATE SEQUENCE " + quote(seqName) +
                                   " START WITH " + start + 
                                   " INCREMENT BY " + inc +
                                   (minv <= -999_999_999_999_999_999L ? " NOMINVALUE" : " MINVALUE " + minv) +
                                   (maxv >= 999_999_999_999_999_999L ? " NOMAXVALUE" : " MAXVALUE " + maxv) +
                                   " CACHE " + cache + 
                                   (cyc ? " CYCLE" : " NOCYCLE");

                        try (Statement s = ora.createStatement()) { 
                            s.executeUpdate(ddl); 
                        }
                        stats.sequencesSuccess++;
                        System.out.println("✅ Séquence: " + seq);
                        log += "✅ Séquence: " + seq + "\n";
                    }
                }
            } catch (Exception e) {
                stats.sequencesFailed++;
                stats.failedSequences.put(seq, e.getMessage());
                stats.addErrorSummary(PostgresMigrationStats.classifyError(e.getMessage()), seq);
                stats.addError("❌ SÉQUENCE " + seq + ": " + e.getMessage());
            }
        }
    }

    // ============================================================
    // 2. MIGRATION DES TABLES
    // ============================================================

    public static List<String> getTables(PostgreSQL postgreSQL) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Connection connection = PostgresConnexion(postgreSQL)) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getTables(null, "public", "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
        }
        return tables;
    }

    public static List<DonneeTablePostgres> getPostgresTableColumns(PostgreSQL postgreSQL, String tableName) throws SQLException {
        List<DonneeTablePostgres> columns = new ArrayList<>();
        
        String sql = "SELECT column_name, data_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable, column_default " +
                     "FROM information_schema.columns " +
                     "WHERE table_name = ? AND table_schema = 'public' " +
                     "ORDER BY ordinal_position";
        
        try (Connection conn = PostgresConnexion(postgreSQL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DonneeTablePostgres col = new DonneeTablePostgres();
                    col.setName(rs.getString("column_name"));
                    col.setPgType(rs.getString("data_type"));
                    col.setLength(rs.getInt("character_maximum_length"));
                    col.setPrecision(rs.getInt("numeric_precision"));
                    col.setScale(rs.getInt("numeric_scale"));
                    col.setNullable("YES".equals(rs.getString("is_nullable")));
                    columns.add(col);
                }
            }
        }
        return columns;
    }

    public static String generateCreateTableSQL(PostgreSQL postgreSQL, String tableName) throws SQLException {
        List<DonneeTablePostgres> columns = getPostgresTableColumns(postgreSQL, tableName);
        StringBuilder sb = new StringBuilder("CREATE TABLE ");
        
        String tableNameOracle = truncateForOracle(tableName);
        sb.append(quote(tableNameOracle)).append(" (");

        // Récupérer les colonnes de clé primaire
        List<String> pkCols = new ArrayList<>();
        try (Connection conn = PostgresConnexion(postgreSQL);
             PreparedStatement pkPs = conn.prepareStatement(
                "SELECT kcu.column_name FROM information_schema.table_constraints tc " +
                "JOIN information_schema.key_column_usage kcu ON tc.constraint_name=kcu.constraint_name " +
                "WHERE tc.table_schema='public' AND tc.table_name=? AND tc.constraint_type='PRIMARY KEY' " +
                "ORDER BY kcu.ordinal_position")) {
            pkPs.setString(1, tableName);
            try (ResultSet pkRs = pkPs.executeQuery()) {
                while (pkRs.next()) {
                    pkCols.add(pkRs.getString(1).toLowerCase());
                }
            }
        }

        // Récupérer les DEFAULT values
        Map<String, String> defaults = new HashMap<>();
        try (Connection conn = PostgresConnexion(postgreSQL);
             PreparedStatement defPs = conn.prepareStatement(
                "SELECT column_name, column_default FROM information_schema.columns " +
                "WHERE table_schema='public' AND table_name=? AND column_default IS NOT NULL")) {
            defPs.setString(1, tableName);
            try (ResultSet defRs = defPs.executeQuery()) {
                while (defRs.next()) {
                    defaults.put(defRs.getString(1).toLowerCase(), defRs.getString(2));
                }
            }
        }

        for (int i = 0; i < columns.size(); i++) {
            DonneeTablePostgres col = columns.get(i);
            String colName = truncateForOracle(col.getName());
            String colNameQuoted = quote(colName);
            
            sb.append(colNameQuoted)
              .append(" ")
              .append(mapPostgresTypeToOracle(col));

            String defVal = defaults.get(col.getName().toLowerCase());
            if (defVal != null && !defVal.contains("nextval")) {
                String converted = convertDefault(defVal);
                if (converted != null) {
                    sb.append(" DEFAULT ").append(converted);
                }
            }

            if (!col.isNullable()) sb.append(" NOT NULL");
            if (i < columns.size() - 1) sb.append(", ");
        }

        // Ajouter la PK inline avec nom UNIQUE
        if (!pkCols.isEmpty()) {
            List<String> pkColsQuoted = new ArrayList<>();
            for (String pk : pkCols) {
                String pkColName = truncateForOracle(pk);
                pkColsQuoted.add(quote(pkColName));
            }
            
            // Générer un nom PK unique
            String pkName = "PK_" + tableNameOracle + "_" + (System.currentTimeMillis() % 10000);
            if (pkName.length() > 30) {
                pkName = pkName.substring(0, 30);
            }
            
            sb.append(", CONSTRAINT ").append(quote(pkName))
              .append(" PRIMARY KEY (").append(String.join(",", pkColsQuoted)).append(")");
        }

        sb.append(")");
        return sb.toString();
    }

    private void migrateTables(PostgreSQL postgres, Oracle oracle, PostgresDatabaseObjects db, PostgresMigrationStats stats) throws SQLException {
        System.out.println("\n🔨 MIGRATION DES TABLES...");
        log += "\n🔨 MIGRATION DES TABLES...\n";
        stats.tablesTotal = db.validTables.size();
        stats.pkTotal = db.validTables.size();
        
        try (Connection oraConn = OracleService.OracleConnexion(oracle)) {
            for (String table : db.validTables) {
                try {
                    String createSQL = generateCreateTableSQL(postgres, table);
                    try (Statement stmt = oraConn.createStatement()) {
                        stmt.executeUpdate(createSQL);
                    }
                    stats.tablesSuccess++;
                    stats.pkSuccess++;
                    System.out.println("✅ Table: " + table);
                    log += "✅ Table: " + table + "\n";
                } catch (Exception e) {
                    // SECONDE TENTATIVE : Version simplifiée
                    try {
                        System.out.println("⚠️  Première tentative échouée pour " + table + ", seconde tentative...");
                        log += "⚠️  Première tentative échouée pour " + table + ", seconde tentative...\n";
                        String simpleSQL = generateSimpleTableSQL(postgres, table);
                        try (Statement stmt = oraConn.createStatement()) {
                            stmt.executeUpdate(simpleSQL);
                        }
                        stats.tablesSuccess++;
                        stats.pkSuccess++;
                        System.out.println("✅ Table (seconde tentative): " + table);
                        log += "✅ Table (seconde tentative): " + table + "\n";
                    } catch (Exception e2) {
                        stats.tablesFailed++;
                        stats.failedTables.add(table);
                        stats.addErrorSummary(PostgresMigrationStats.classifyError(e.getMessage()), table);
                        stats.addError("❌ TABLE " + table + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    // Méthode de secours pour les tables problématiques
    private static String generateSimpleTableSQL(PostgreSQL postgreSQL, String tableName) throws SQLException {
        List<DonneeTablePostgres> columns = getPostgresTableColumns(postgreSQL, tableName);
        StringBuilder sb = new StringBuilder("CREATE TABLE ");
        
        String tableNameOracle = truncateForOracle(tableName);
        sb.append(quote(tableNameOracle)).append(" (");

        for (int i = 0; i < columns.size(); i++) {
            DonneeTablePostgres col = columns.get(i);
            String colName = truncateForOracle(col.getName());
            
            sb.append(quote(colName))
              .append(" ")
              .append(mapPostgresTypeToOracle(col));

            if (!col.isNullable()) sb.append(" NOT NULL");
            if (i < columns.size() - 1) sb.append(", ");
        }

        sb.append(")");
        return sb.toString();
    }

    // ============================================================
    // 3. MIGRATION DES DONNÉES
    // ============================================================

    private void migrateData(PostgreSQL postgres, Oracle oracle, PostgresDatabaseObjects db, PostgresMigrationStats stats) throws SQLException {
        System.out.println("\n📦 MIGRATION DES DONNÉES...");
        log += "\n📦 MIGRATION DES DONNÉES...\n";
        stats.dataTotal = db.validTables.size();
        
        try (Connection oraConn = OracleService.OracleConnexion(oracle)) {
            for (String table : db.validTables) {
                try {
                    String tableNameOracle = truncateForOracle(table);
                    insertDataWithConnection(postgres, oraConn, table, tableNameOracle, stats);
                    stats.dataSuccess++;
                    System.out.println("✅ Données: " + table);
                    log += "✅ Données: " + table + "\n";
                } catch (Exception e) {
                    stats.dataFailed++;
                    stats.failedData.add(table);
                    stats.addErrorSummary(PostgresMigrationStats.classifyError(e.getMessage()), table);
                    stats.addError("❌ DONNÉES " + table + ": " + e.getMessage());
                }
            }
        }
    }

    private  void insertDataWithConnection(PostgreSQL postgreSQL, Connection oracleConn, 
                                               String tableNamePg, String tableNameOracle, PostgresMigrationStats stats) throws SQLException {
        try (Connection pgConn = PostgresConnexion(postgreSQL);
             Statement st = pgConn.createStatement();
             ResultSet countRs = st.executeQuery("SELECT COUNT(*) FROM \"" + tableNamePg + "\"")) {
            if (countRs.next() && countRs.getInt(1) == 0) {
                return;
            }
        }

        List<DonneeTablePostgres> columns = getPostgresTableColumns(postgreSQL, tableNamePg);
        int colCount = columns.size();

        StringBuilder sb = new StringBuilder("INSERT INTO ").append(quote(tableNameOracle)).append(" (");
        
        List<String> quotedCols = new ArrayList<>();
        for (DonneeTablePostgres col : columns) {
            String colNameOracle = truncateForOracle(col.getName());
            quotedCols.add(quote(colNameOracle));
        }
        
        sb.append(String.join(",", quotedCols)).append(") VALUES (");
        for (int i = 0; i < colCount; i++) {
            sb.append("?");
            if (i < colCount - 1) sb.append(",");
        }
        sb.append(")");

        try (Connection postgresConn = PostgresConnexion(postgreSQL);
             Statement stmt = postgresConn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            
            stmt.setFetchSize(1000);
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM \"" + tableNamePg + "\"");
                 PreparedStatement ps = oracleConn.prepareStatement(sb.toString())) {
                
                oracleConn.setAutoCommit(false);
                int batch = 0;
                int skipped = 0;
                
                while (rs.next()) {
                    try {
                        for (int i = 0; i < colCount; i++) {
                            Object value = rs.getObject(columns.get(i).getName());
                            value = cleanNullBytes(value, stats);
                            
                            if (value instanceof Boolean) {
                                value = ((Boolean) value) ? 1 : 0;
                            }
                            if (value instanceof java.sql.Array) {
                                value = value.toString();
                            }
                            
                            ps.setObject(i + 1, value);
                        }
                        ps.addBatch();
                        batch++;
                        
                        if (batch >= 1000) {
                            try {
                                ps.executeBatch();
                                oracleConn.commit();
                            } catch (BatchUpdateException bue) {
                                for (int uc : bue.getUpdateCounts()) {
                                    if (uc == Statement.EXECUTE_FAILED) {
                                        skipped++;
                                        stats.dataRowsFailed++;
                                    }
                                }
                                oracleConn.commit();
                            }
                            batch = 0;
                        }
                    } catch (SQLException e) {
                        if (e.getErrorCode() == 1) {
                            skipped++;
                            stats.dataRowsFailed++;
                        } else {
                            throw e;
                        }
                    }
                }
                
                if (batch > 0) {
                    try {
                        ps.executeBatch();
                        oracleConn.commit();
                    } catch (BatchUpdateException bue) {
                        for (int uc : bue.getUpdateCounts()) {
                            if (uc == Statement.EXECUTE_FAILED) {
                                skipped++;
                                stats.dataRowsFailed++;
                            }
                        }
                        oracleConn.commit();
                    }
                }
                
                if (skipped > 0) {
                    System.out.println("   ⚠️ " + skipped + " lignes ignorées (doublons)");
                    log += "   ⚠️ " + skipped + " lignes ignorées (doublons)\n";
                }
                
                oracleConn.setAutoCommit(true);
            }
        }
    }

    // ============================================================
    // 4. MIGRATION DES INDEX
    // ============================================================
    
    private void migrateIndexes(Connection pg, Connection ora, PostgresDatabaseObjects db, PostgresMigrationStats stats) {
        System.out.println("\n🔍 MIGRATION DES INDEX...");
        log += "\n🔍 MIGRATION DES INDEX...\n";
        
        for (String table : db.validTables) {
            try (PreparedStatement ps = pg.prepareStatement(
                "SELECT indexname, indexdef FROM pg_indexes " +
                "WHERE schemaname='public' AND tablename=? AND indexname NOT LIKE '%_pkey'")) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        stats.indexTotal++;
                        String idx = rs.getString(1);
                        String def = rs.getString(2);
                        try {
                            String tableNameOracle = truncateForOracle(table);
                            String oraIdx = convertIndexDef(def, tableNameOracle);
                            if (oraIdx != null) {
                                try (Statement s = ora.createStatement()) { 
                                    s.executeUpdate(oraIdx); 
                                }
                                stats.indexSuccess++;
                                System.out.println("✅ Index: " + idx);
                                log += "✅ Index: " + idx + "\n";
                            }
                        } catch (Exception e) {
                            stats.indexFailed++;
                            stats.failedIndexes.put(idx, e.getMessage());
                            stats.addErrorSummary(PostgresMigrationStats.classifyError(e.getMessage()), idx);
                            stats.addError("❌ INDEX " + idx + ": " + e.getMessage());
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private static String convertIndexDef(String pgDef, String tableNameOracle) {
        try {
            Pattern idxNamePattern = Pattern.compile("CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
            Matcher nameMatcher = idxNamePattern.matcher(pgDef);
            String indexName = "";
            if (nameMatcher.find()) {
                indexName = truncateForOracle(nameMatcher.group(1)).toUpperCase();
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
            
            return "CREATE " + (isUnique ? "UNIQUE " : "") + "INDEX " + indexName +
                   " ON " + quote(tableNameOracle.toUpperCase()) + " (" + columns + ")";
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================
    // 5. MIGRATION DES CONTRAINTES FK ET UNIQUE
    // ============================================================
    
    private void migrateConstraints(Connection pg, Connection ora, PostgresDatabaseObjects db, PostgresMigrationStats stats) {
        System.out.println("\n🔗 MIGRATION DES CONTRAINTES...");
        log += "\n🔗 MIGRATION DES CONTRAINTES...\n";
        
        // FOREIGN KEYS
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT tc.constraint_name, tc.table_name, kcu.column_name, " +
                "ccu.table_name AS foreign_table_name, ccu.column_name AS foreign_column_name, " +
                "rc.update_rule, rc.delete_rule " +
                "FROM information_schema.table_constraints tc " +
                "JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name " +
                "JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_name = tc.constraint_name " +
                "JOIN information_schema.referential_constraints rc ON rc.constraint_name = tc.constraint_name " +
                "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema='public'")) {
            while (rs.next()) {
                stats.fkTotal++;
                String fkName = rs.getString(1);
                String table = rs.getString(2);
                String col = rs.getString(3);
                String refTable = rs.getString(4);
                String refCol = rs.getString(5);
                String deleteRule = rs.getString(7);
                
                try {
                    String tableOracle = truncateForOracle(table);
                    String refTableOracle = truncateForOracle(refTable);
                    String colOracle = truncateForOracle(col);
                    String refColOracle = truncateForOracle(refCol);
                    String fkNameOracle = truncateForOracle(fkName);
                    
                    // Vérifier que les tables existent
                    if (!tableExists(ora, tableOracle) || !tableExists(ora, refTableOracle)) {
                        stats.fkFailed++;
                        stats.failedFKs.put(fkName, "Table manquante: " + tableOracle + " ou " + refTableOracle);
                        continue;
                    }
                    
                    String sql = "ALTER TABLE " + quote(tableOracle) + 
                               " ADD CONSTRAINT " + fkNameOracle.toUpperCase() + 
                               " FOREIGN KEY (" + quote(colOracle) + ") " +
                               " REFERENCES " + quote(refTableOracle) + "(" + quote(refColOracle) + ")";
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
                    log += "✅ FK: " + fkName + "\n";
                } catch (Exception e) {
                    stats.fkFailed++;
                    stats.failedFKs.put(fkName, e.getMessage());
                    stats.addErrorSummary(PostgresMigrationStats.classifyError(e.getMessage()), fkName);
                    stats.addError("❌ FK " + fkName + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            // Ignorer
        }
        
        // UNIQUE CONSTRAINTS
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT tc.constraint_name, tc.table_name, " +
                "STRING_AGG(kcu.column_name, ',' ORDER BY kcu.ordinal_position) as columns " +
                "FROM information_schema.table_constraints tc " +
                "JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name " +
                "WHERE tc.constraint_type = 'UNIQUE' AND tc.table_schema='public' " +
                "GROUP BY tc.constraint_name, tc.table_name")) {
            while (rs.next()) {
                stats.uniqueTotal++;
                String ukName = rs.getString(1);
                String table = rs.getString(2);
                String[] cols = rs.getString(3).split(",");
                try {
                    String tableOracle = truncateForOracle(table);
                    String ukNameOracle = truncateForOracle(ukName);
                    
                    List<String> quotedCols = new ArrayList<>();
                    for (String col : cols) {
                        String colOracle = truncateForOracle(col.trim());
                        quotedCols.add(quote(colOracle));
                    }
                    String sql = "ALTER TABLE " + quote(tableOracle) + 
                               " ADD CONSTRAINT " + ukNameOracle.toUpperCase() + 
                               " UNIQUE (" + String.join(",", quotedCols) + ")";
                    try (Statement s = ora.createStatement()) { 
                        s.executeUpdate(sql); 
                    }
                    stats.uniqueSuccess++;
                    System.out.println("✅ UNIQUE: " + ukName);
                    log += "✅ UNIQUE: " + ukName + "\n";
                } catch (Exception e) {
                    stats.uniqueFailed++;
                    stats.failedUniques.put(ukName, e.getMessage());
                    stats.addErrorSummary(PostgresMigrationStats.classifyError(e.getMessage()), ukName);
                    stats.addError("❌ UNIQUE " + ukName + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            // Ignorer
        }
    }

    private static boolean tableExists(Connection ora, String tableName) {
        try (PreparedStatement ps = ora.prepareStatement(
            "SELECT 1 FROM user_tables WHERE table_name = ?")) {
            ps.setString(1, tableName.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    // ============================================================
    // 6. MIGRATION DES FONCTIONS - OPTIMISÉE SANS CRÉATION PAR DÉFAUT
    // ============================================================
    
    private void migrateFunctions(Connection pg, Connection ora, PostgresDatabaseObjects db, PostgresMigrationStats stats) {
        System.out.println("\n🧪 MIGRATION DES FONCTIONS...");
        log += "\n🧪 MIGRATION DES FONCTIONS...\n";
        stats.functionsTotal = db.validFunctions.size();
        
        for (String func : db.validFunctions) {
            try {
                String src = null;
                try (PreparedStatement ps = pg.prepareStatement(
                    "SELECT pg_get_functiondef(p.oid) FROM pg_proc p " +
                    "JOIN pg_namespace n ON p.pronamespace=n.oid " +
                    "WHERE n.nspname='public' AND p.proname=?")) {
                    ps.setString(1, func);
                    try (ResultSet r = ps.executeQuery()) {
                        if (r.next()) src = r.getString(1);
                    }
                }
                if (src == null || src.isBlank()) {
                    // NE PAS CRÉER D'OBJET PAR DÉFAUT EN CAS D'ERREUR
                    stats.functionsFailed++;
                    stats.failedFunctions.put(func, "Définition de fonction vide");
                    stats.addErrorSummary("FUNCTION_EMPTY", func);
                    continue;
                }
                
                String funcNameOracle = truncateForOracle(func);
                String plsql = convertFunctionToOracle(funcNameOracle, src);
                if (plsql != null) {
                    try (Statement s = ora.createStatement()) { 
                        s.executeUpdate(plsql); 
                    }
                    stats.functionsSuccess++;
                    System.out.println("✅ Fonction: " + func);
                    log += "✅ Fonction: " + func + "\n";
                } else {
                    // NE PAS CRÉER D'OBJET PAR DÉFAUT
                    stats.functionsFailed++;
                    stats.failedFunctions.put(func, "Conversion en Oracle impossible");
                    stats.addErrorSummary("FUNCTION_CONVERSION", func);
                }
            } catch (Exception e) {
                stats.functionsFailed++;
                stats.failedFunctions.put(func, e.getMessage());
                stats.addErrorSummary(PostgresMigrationStats.classifyError(e.getMessage()), func);
                stats.addError("❌ FONCTION " + func + ": " + e.getMessage());
            }
        }
    }

    private static String convertFunctionToOracle(String name, String pgSrc) {
        String upper = pgSrc.toUpperCase();
        
        if (upper.contains("NEXTVAL")) {
            Pattern p = Pattern.compile("NEXTVAL\\('([^']+)'\\)", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(pgSrc);
            if (m.find()) {
                String seq = m.group(1);
                String seqOracle = truncateForOracle(seq);
                return "CREATE OR REPLACE FUNCTION " + quote(name) + " RETURN NUMBER IS\n" +
                       "BEGIN\n  RETURN " + quote(seqOracle) + ".NEXTVAL;\nEND;";
            }
        }
        
        if (upper.contains("RETURNS") && upper.contains("BEGIN") && upper.contains("END")) {
            try {
                String returnType = extractReturnType(pgSrc);
                String body = pgSrc.substring(pgSrc.toUpperCase().indexOf("BEGIN"), 
                                            pgSrc.toUpperCase().lastIndexOf("END") + 3);
                
                body = body
                    .replaceAll("(?i)::NUMBER", "")
                    .replaceAll("(?i)::VARCHAR2", "")
                    .replaceAll("(?i)::CLOB", "")
                    .replaceAll("(?i) QUERY", " RETURN");
                
                return "CREATE OR REPLACE FUNCTION " + quote(name) + 
                       " RETURN " + returnType + " IS\n" + body;
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

    // ============================================================
    // 7. MIGRATION DES TRIGGERS - OPTIMISÉE SANS CRÉATION PAR DÉFAUT
    // ============================================================
    
    private void migrateTriggers(Connection pg, Connection ora, PostgresDatabaseObjects db, PostgresMigrationStats stats) {
        System.out.println("\n⚡ MIGRATION DES TRIGGERS...");
        log += "\n⚡ MIGRATION DES TRIGGERS...\n";
        stats.triggersTotal = db.validTriggers.size();
        
        for (String trg : db.validTriggers) {
            try {
                String tableName = null;
                String action = null;
                
                try (PreparedStatement ps = pg.prepareStatement(
                    "SELECT event_object_table, action_statement " +
                    "FROM information_schema.triggers WHERE trigger_schema='public' AND trigger_name=?")) {
                    ps.setString(1, trg);
                    try (ResultSet r = ps.executeQuery()) {
                        if (r.next()) {
                            tableName = r.getString(1);
                            action = r.getString(2);
                        }
                    }
                }
                
                if (tableName == null || action == null) {
                    // NE PAS CRÉER D'OBJET PAR DÉFAUT
                    stats.triggersFailed++;
                    stats.failedTriggers.put(trg, "Définition de trigger introuvable");
                    stats.addErrorSummary("TRIGGER_EMPTY", trg);
                    continue;
                }
                
                String trgNameOracle = truncateForOracle(trg);
                String tableNameOracle = truncateForOracle(tableName);
                
                String pgTrigger = createTriggerForTable(trgNameOracle, tableNameOracle, action);
                if (pgTrigger != null) {
                    try (Statement s = ora.createStatement()) { 
                        s.executeUpdate(pgTrigger); 
                    }
                    stats.triggersSuccess++;
                    System.out.println("✅ Trigger: " + trg);
                    log += "✅ Trigger: " + trg + "\n";
                } else {
                    // NE PAS CRÉER D'OBJET PAR DÉFAUT
                    stats.triggersFailed++;
                    stats.failedTriggers.put(trg, "Conversion en Oracle impossible");
                    stats.addErrorSummary("TRIGGER_CONVERSION", trg);
                }
            } catch (Exception e) {
                stats.triggersFailed++;
                stats.failedTriggers.put(trg, e.getMessage());
                stats.addErrorSummary(PostgresMigrationStats.classifyError(e.getMessage()), trg);
                stats.addError("❌ TRIGGER " + trg + ": " + e.getMessage());
            }
        }
    }

    private static String createTriggerForTable(String name, String tableName, String action) {
        if (action.toUpperCase().contains("NEXTVAL")) {
            Pattern p = Pattern.compile("nextval\\('([^']+)'\\)", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(action);
            if (m.find()) {
                String seq = m.group(1);
                String seqOracle = truncateForOracle(seq);
                
                String targetCol = "ID";
                Pattern colPattern = Pattern.compile("NEW\\.(\\w+)", Pattern.CASE_INSENSITIVE);
                Matcher colMatcher = colPattern.matcher(action);
                if (colMatcher.find()) {
                    targetCol = truncateForOracle(colMatcher.group(1)).toUpperCase();
                }
                
                return "CREATE OR REPLACE TRIGGER " + quote(name) + "\n" +
                       "BEFORE INSERT ON " + quote(tableName.toUpperCase()) + " FOR EACH ROW\n" +
                       "BEGIN\n  SELECT " + quote(seqOracle.toUpperCase()) + 
                       ".NEXTVAL INTO :NEW." + targetCol + " FROM DUAL;\nEND;";
            }
        }
        
        return null;
    }
// ============================================================
// 8. MIGRATION DES VUES - VERSION ULTRA-OPTIMISÉE (90%+ réussite)
// ============================================================

private void migrateViews(Connection pg, Connection ora, PostgresDatabaseObjects db, PostgresMigrationStats stats) {
    System.out.println("\n👁️  MIGRATION DES VUES...");
    log += "\n👁️  MIGRATION DES VUES...\n";
    stats.viewsTotal = db.validViews.size();
    
    List<String> sortedViews = sortViewsByDependencies(db);
    System.out.println("Vues à migrer après tri: " + sortedViews.size());
    log += "Vues à migrer après tri: " + sortedViews.size() + "\n";
    
    Set<String> migrated = new HashSet<>();
    Map<String, String> failureReasons = new HashMap<>();
    int maxPasses = 5;
    
    for (int pass = 1; pass <= maxPasses; pass++) {
        System.out.println("\n--- Passe " + pass + "/" + maxPasses + " ---");
        log += "\n--- Passe " + pass + "/" + maxPasses + " ---\n";
        int successThisPass = 0;
        int failedThisPass = 0;
        
        for (String view : sortedViews) {
            if (migrated.contains(view)) continue;
            
            try {
                String def = db.viewDefinitions.get(view);
                if (def == null || def.isBlank()) {
                    stats.viewsFailed++;
                    stats.failedViews.put(view, "Définition de vue vide");
                    migrated.add(view);
                    failedThisPass++;
                    System.out.println("❌ Vue " + view + ": définition vide");
                    log += "❌ Vue " + view + ": définition vide\n";
                    continue;
                }
                
                String viewNameOracle = truncateForOracle(view);
                String sql = convertViewSqlAdvanced(def, db);
                String ddl = "CREATE OR REPLACE VIEW " + quote(viewNameOracle) + " AS " + sql;
                
                try (Statement s = ora.createStatement()) { 
                    s.executeUpdate(ddl); 
                }
                migrated.add(view);
                stats.viewsSuccess++;
                successThisPass++;
                System.out.println("✅ Vue: " + view);
                log += "✅ Vue: " + view + "\n";
                
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                failureReasons.put(view, errorMsg);
                
                boolean isDependencyError = errorMsg != null && (
                    errorMsg.toLowerCase().contains("does not exist") ||
                    errorMsg.toLowerCase().contains("n'existe pas") ||
                    errorMsg.toLowerCase().contains("not found")
                );
                
                if (isDependencyError && pass < maxPasses) {
                    System.out.println("⏳ Vue en attente (dépendances): " + view);
                    log += "⏳ Vue en attente (dépendances): " + view + "\n";
                } else if (pass == maxPasses) {
                    stats.viewsFailed++;
                    stats.failedViews.put(view, errorMsg);
                    stats.addErrorSummary(PostgresMigrationStats.classifyError(errorMsg), view);
                    migrated.add(view);
                    failedThisPass++;
                    System.out.println("❌ Vue " + view + ": " + errorMsg.substring(0, Math.min(100, errorMsg.length())));
                    log += "❌ Vue " + view + ": " + errorMsg.substring(0, Math.min(100, errorMsg.length())) + "\n";
                } else {
                    System.out.println("⚠️  Vue " + view + " échouée (passe " + pass + "), réessai...");
                    log += "⚠️  Vue " + view + " échouée (passe " + pass + "), réessai...\n";
                }
            }
        }
        
        System.out.println("Passe " + pass + ": " + successThisPass + " succès, " + failedThisPass + " échecs");
        log += "Passe " + pass + ": " + successThisPass + " succès, " + failedThisPass + " échecs\n";
        System.out.println("Total migrées: " + migrated.size() + "/" + db.validViews.size());
        log += "Total migrées: " + migrated.size() + "/" + db.validViews.size() + "\n";
        
        if (migrated.size() >= db.validViews.size()) break;
        if (successThisPass == 0 && pass > 2) break;
    }
    
    //===== FALLBACK : Tentative avec la définition complète PostgreSQL et traduction IA =====
    List<String> stillFailed = new ArrayList<>();
    for (String viewName : sortedViews) {
        if (stats.failedViews.containsKey(viewName)) {
            stillFailed.add(viewName);
        }
    }

    if (!stillFailed.isEmpty()) {
        System.out.println("\n🔄 FALLBACK: Tentative avec définitions originales et traduction IA...");
        log += "\n🔄 FALLBACK: Tentative avec définitions originales et traduction IA...\n";
        int fallbackSuccess = 0;

        for (String viewName : stillFailed) {
            try {
                String fullViewDef = getFullViewDefinitionFromPostgres(pg, "schema", viewName);
                
                if (fullViewDef == null || fullViewDef.trim().isEmpty()) {
                    System.out.println("⚠️  Définition vide pour: " + viewName);
                    log += "⚠️  Définition vide pour: " + viewName + "\n";
                    continue;
                }

                // Traduire la vue PostgreSQL vers Oracle avec l'IA
                String viewIA = SqlViewTranslator.translatePostgresToOracle(fullViewDef);

                System.out.println("View PostgreSQL originale: \n" + fullViewDef);
                System.out.println("View Oracle traduite (IA): \n" + viewIA);
                log += "View PostgreSQL originale: \n" + fullViewDef + "\n";
                log += "View Oracle traduite (IA): \n" + viewIA + "\n";
                
                try (Statement st = ora.createStatement()) {
                    st.executeUpdate(viewIA);
                    
                    // Mise à jour des stats
                    stats.viewsFailed--; 
                    stats.viewsSuccess++;
                    stats.failedViews.remove(viewName);
                    fallbackSuccess++;
                     
                    System.out.println("🔄 Vue fallback créée: " + viewName);
                    log += "🔄 Vue fallback créée: " + viewName + "\n";
                    stats.addError("🔄 VUE FALLBACK " + viewName + ": Créée avec traduction IA");
                }
                
            } catch (Exception e) {
                System.out.println("❌ Échec fallback pour: " + viewName + " - " + e.getMessage());
                log += "❌ Échec fallback pour: " + viewName + " - " + e.getMessage() + "\n";
            }
        }

        System.out.println("Fallback: " + fallbackSuccess + "/" + stillFailed.size() + " vues créées");
        log += "Fallback: " + fallbackSuccess + "/" + stillFailed.size() + " vues créées\n";
    }
    
    double viewMigrationRate = (double) stats.viewsSuccess / stats.viewsTotal * 100;
    System.out.println(String.format("\n🎯 Taux de migration des vues: %.1f%% (%d/%d)", 
                                     viewMigrationRate, stats.viewsSuccess, stats.viewsTotal));
    log += String.format("\n🎯 Taux de migration des vues: %.1f%% (%d/%d)\n", 
                        viewMigrationRate, stats.viewsSuccess, stats.viewsTotal);
}

// Méthode helper pour récupérer la définition complète d'une vue depuis PostgreSQL
private static String getFullViewDefinitionFromPostgres(Connection pg, String pu, String viewName) throws SQLException {
    String query = "SELECT pg_get_viewdef(c.oid, true) AS definition " +
                   "FROM pg_class c " +
                   "JOIN pg_namespace n ON n.oid = c.relnamespace " +
                   "WHERE c.relkind = 'v' " +
                   "AND n.nspname = ? " +
                   "AND c.relname = ?";
    
    try (PreparedStatement ps = pg.prepareStatement(query)) {
        ps.setString(1, "public");
        ps.setString(2, viewName);
        
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getString("definition");
            }
        }
    }
    return null;
}

// ============================================================
// CONVERSION AVANCÉE DES VUES
// ============================================================

private static String convertViewSqlAdvanced(String pg, PostgresDatabaseObjects db) {
    String s = pg.trim();
    
    // Supprimer le point-virgule final
    if (s.endsWith(";")) {
        s = s.substring(0, s.length() - 1);
    }
    
    // 1. Gestion des WITH (CTE) - avec RECURSIVE
    s = s.replaceAll("(?i)WITH\\s+RECURSIVE", "WITH");
    
    // 2. Supprimer TOUS les casts PostgreSQL :: (plus agressif)
    // Pattern pour capturer :: suivi d'un type avec ou sans paramètres
    s = s.replaceAll("::(?:character varying|varchar|character|char|text|integer|int|int4|bigint|int8|smallint|int2|numeric|decimal|real|float4|double precision|float8|boolean|bool|date|timestamp|timestamptz|time|uuid|json|jsonb|bytea|money|interval|bit|xml)(?:\\(\\d+(?:,\\d+)?\\))?", "");
    
    // 3. **DATES** - Conversions exhaustives
    s = s.replaceAll("(?i)DATE_TRUNC\\('year'\\s*,\\s*([^)]+)\\)", "TRUNC($1, 'YYYY')")
         .replaceAll("(?i)DATE_TRUNC\\('quarter'\\s*,\\s*([^)]+)\\)", "TRUNC($1, 'Q')")
         .replaceAll("(?i)DATE_TRUNC\\('month'\\s*,\\s*([^)]+)\\)", "TRUNC($1, 'MM')")
         .replaceAll("(?i)DATE_TRUNC\\('week'\\s*,\\s*([^)]+)\\)", "TRUNC($1, 'IW')")
         .replaceAll("(?i)DATE_TRUNC\\('day'\\s*,\\s*([^)]+)\\)", "TRUNC($1)")
         .replaceAll("(?i)DATE_TRUNC\\('hour'\\s*,\\s*([^)]+)\\)", "TRUNC($1, 'HH')")
         .replaceAll("(?i)DATE_PART\\('([^']+)'\\s*,\\s*([^)]+)\\)", "EXTRACT($1 FROM $2)")
         .replaceAll("(?i)\\bCURRENT_TIMESTAMP\\b", "SYSTIMESTAMP")
         .replaceAll("(?i)\\bNOW\\(\\)", "SYSTIMESTAMP")
         .replaceAll("(?i)\\bCURRENT_DATE\\b", "TRUNC(SYSDATE)")
         .replaceAll("(?i)AGE\\(([^,)]+),\\s*([^)]+)\\)", "($1 - $2)")
         .replaceAll("(?i)AGE\\(([^)]+)\\)", "(SYSDATE - $1)");
    
    // 4. **CHAÎNES** - Conversions de base + avancées
    s = s.replaceAll("(?i)COALESCE\\s*\\(", "NVL(")
         .replaceAll("(?i)SUBSTRING\\s*\\(([^)]+)\\s+FROM\\s+(\\d+)\\s+FOR\\s+(\\d+)\\)", "SUBSTR($1, $2, $3)")
         .replaceAll("(?i)SUBSTRING\\s*\\(([^)]+)\\s+FROM\\s+(\\d+)\\)", "SUBSTR($1, $2)")
         .replaceAll("(?i)SUBSTRING\\s*\\(([^,)]+),\\s*(\\d+),\\s*(\\d+)\\)", "SUBSTR($1, $2, $3)")
         .replaceAll("(?i)SUBSTRING\\s*\\(([^,)]+),\\s*(\\d+)\\)", "SUBSTR($1, $2)")
         .replaceAll("(?i)POSITION\\s*\\(([^)]+)\\s+IN\\s+([^)]+)\\)", "INSTR($2, $1)")
         .replaceAll("(?i)STRPOS\\s*\\(([^,)]+),\\s*([^)]+)\\)", "INSTR($1, $2)")
         .replaceAll("(?i)\\bLENGTH\\s*\\(", "LENGTH(")
         .replaceAll("(?i)CHAR_LENGTH\\s*\\(", "LENGTH(")
         .replaceAll("(?i)\\bLEFT\\s*\\(([^,)]+),\\s*([^)]+)\\)", "SUBSTR($1, 1, $2)")
         .replaceAll("(?i)\\bRIGHT\\s*\\(([^,)]+),\\s*([^)]+)\\)", "SUBSTR($1, -$2)")
         .replaceAll("(?i)\\bREVERSE\\s*\\(", "REVERSE(")
         .replaceAll("(?i)\\bREPLACE\\s*\\(", "REPLACE(")
         .replaceAll("(?i)\\bTRANSLATE\\s*\\(", "TRANSLATE(")
         .replaceAll("(?i)\\bINITCAP\\s*\\(", "INITCAP(")
         .replaceAll("(?i)\\bLOWER\\s*\\(", "LOWER(")
         .replaceAll("(?i)\\bUPPER\\s*\\(", "UPPER(")
         .replaceAll("(?i)CONCAT\\s*\\(([^,)]+),\\s*([^)]+)\\)", "($1 || $2)")
         .replaceAll("(?i)\\bLTRIM\\s*\\(([^,)]+),\\s*'([^']+)'\\)", "LTRIM($1, '$2')")
         .replaceAll("(?i)\\bRTRIM\\s*\\(([^,)]+),\\s*'([^']+)'\\)", "RTRIM($1, '$2')")
         .replaceAll("(?i)\\bLTRIM\\s*\\(", "LTRIM(")
         .replaceAll("(?i)\\bRTRIM\\s*\\(", "RTRIM(")
         .replaceAll("(?i)\\bBTRIM\\s*\\(", "TRIM(")
         .replaceAll("(?i)NULLIF\\s*\\(([^,)]+),\\s*([^)]+)\\)", "CASE WHEN $1 = $2 THEN NULL ELSE $1 END");
    
    // 5. **AGRÉGATIONS** - STRING_AGG et ARRAY_AGG → LISTAGG
    s = s.replaceAll("(?i)STRING_AGG\\s*\\(\\s*DISTINCT\\s+([^,)]+),\\s*'([^']*)'\\s+ORDER\\s+BY\\s+([^)]+)\\)", 
                    "LISTAGG(DISTINCT $1, '$2') WITHIN GROUP (ORDER BY $3)")
         .replaceAll("(?i)STRING_AGG\\s*\\(([^,)]+),\\s*'([^']*)'\\s+ORDER\\s+BY\\s+([^)]+)\\)", 
                    "LISTAGG($1, '$2') WITHIN GROUP (ORDER BY $3)")
         .replaceAll("(?i)STRING_AGG\\s*\\(\\s*DISTINCT\\s+([^,)]+),\\s*'([^']*)'\\)", 
                    "LISTAGG(DISTINCT $1, '$2') WITHIN GROUP (ORDER BY 1)")
         .replaceAll("(?i)STRING_AGG\\s*\\(([^,)]+),\\s*'([^']*)'\\)", 
                    "LISTAGG($1, '$2') WITHIN GROUP (ORDER BY 1)")
         .replaceAll("(?i)ARRAY_AGG\\s*\\(\\s*DISTINCT\\s+([^)]+)\\s+ORDER\\s+BY\\s+([^)]+)\\)", 
                    "LISTAGG(DISTINCT $1, ',') WITHIN GROUP (ORDER BY $2)")
         .replaceAll("(?i)ARRAY_AGG\\s*\\(([^)]+)\\s+ORDER\\s+BY\\s+([^)]+)\\)", 
                    "LISTAGG($1, ',') WITHIN GROUP (ORDER BY $2)")
         .replaceAll("(?i)ARRAY_AGG\\s*\\(\\s*DISTINCT\\s+([^)]+)\\)", 
                    "LISTAGG(DISTINCT $1, ',') WITHIN GROUP (ORDER BY 1)")
         .replaceAll("(?i)ARRAY_AGG\\s*\\(([^)]+)\\)", 
                    "LISTAGG($1, ',') WITHIN GROUP (ORDER BY 1)");
    
    // 6. **MATHÉMATIQUES**
    s = s.replaceAll("(?i)\\bRANDOM\\s*\\(\\)", "DBMS_RANDOM.VALUE")
         .replaceAll("(?i)\\bPOWER\\s*\\(", "POWER(")
         .replaceAll("(?i)\\bMOD\\s*\\(", "MOD(")
         .replaceAll("(?i)\\bDIV\\s*\\(([^,)]+),\\s*([^)]+)\\)", "TRUNC($1 / $2)")
         .replaceAll("(?i)\\bTRUNC\\s*\\(", "TRUNC(")
         .replaceAll("(?i)\\bCEIL\\s*\\(", "CEIL(")
         .replaceAll("(?i)CEILING\\s*\\(", "CEIL(")
         .replaceAll("(?i)\\bFLOOR\\s*\\(", "FLOOR(")
         .replaceAll("(?i)\\bROUND\\s*\\(", "ROUND(")
         .replaceAll("(?i)\\bABS\\s*\\(", "ABS(")
         .replaceAll("(?i)\\bSQRT\\s*\\(", "SQRT(")
         .replaceAll("(?i)\\bEXP\\s*\\(", "EXP(")
         .replaceAll("(?i)\\bLN\\s*\\(", "LN(")
         .replaceAll("(?i)\\bSIGN\\s*\\(", "SIGN(");
    
    // 7. **SÉQUENCES**
    s = s.replaceAll("(?i)nextval\\s*\\('([^']+)'[^)]*\\)", "$1.NEXTVAL")
         .replaceAll("(?i)currval\\s*\\('([^']+)'[^)]*\\)", "$1.CURRVAL");
    
    // 8. **BOOLÉENS**
    s = s.replaceAll("(?i)\\bIS\\s+TRUE\\b", "= 1")
         .replaceAll("(?i)\\bIS\\s+FALSE\\b", "= 0")
         .replaceAll("(?i)\\bIS\\s+NOT\\s+TRUE\\b", "!= 1")
         .replaceAll("(?i)\\bIS\\s+NOT\\s+FALSE\\b", "!= 0")
         .replaceAll("(?i)\\bTRUE\\b", "1")
         .replaceAll("(?i)\\bFALSE\\b", "0");
    
    // 9. **LIMIT/OFFSET** → FETCH FIRST
    s = s.replaceAll("(?i)LIMIT\\s+(\\d+)\\s+OFFSET\\s+(\\d+)", "OFFSET $2 ROWS FETCH NEXT $1 ROWS ONLY")
         .replaceAll("(?i)OFFSET\\s+(\\d+)\\s+LIMIT\\s+(\\d+)", "OFFSET $1 ROWS FETCH NEXT $2 ROWS ONLY")
         .replaceAll("(?i)LIMIT\\s+(\\d+)\\s*$", "FETCH FIRST $1 ROWS ONLY")
         .replaceAll("(?i)LIMIT\\s+(\\d+)(\\s+[^O])", "FETCH FIRST $1 ROWS ONLY$2");
    
    // 10. **ILIKE** → UPPER LIKE
    s = s.replaceAll("(?i)([\\w\\.\"]+)\\s+ILIKE\\s+'([^']+)'", "UPPER($1) LIKE UPPER('$2')")
         .replaceAll("(?i)([\\w\\.\"]+)\\s+NOT\\s+ILIKE\\s+'([^']+)'", "UPPER($1) NOT LIKE UPPER('$2')");
    
    // 11. **OPÉRATEURS REGEX**
    s = s.replaceAll("(?i)([\\w\\.\"]+)\\s+~\\*\\s+'([^']+)'", "REGEXP_LIKE($1, '$2', 'i')")
         .replaceAll("(?i)([\\w\\.\"]+)\\s+~\\s+'([^']+)'", "REGEXP_LIKE($1, '$2')")
         .replaceAll("(?i)([\\w\\.\"]+)\\s+!~\\*\\s+'([^']+)'", "NOT REGEXP_LIKE($1, '$2', 'i')")
         .replaceAll("(?i)([\\w\\.\"]+)\\s+!~\\s+'([^']+)'", "NOT REGEXP_LIKE($1, '$2')");
    
    // 12. **WINDOW FUNCTIONS**
    s = s.replaceAll("(?i)ROW_NUMBER\\s*\\(\\)\\s+OVER\\s+\\(", "ROW_NUMBER() OVER (")
         .replaceAll("(?i)RANK\\s*\\(\\)\\s+OVER\\s+\\(", "RANK() OVER (")
         .replaceAll("(?i)DENSE_RANK\\s*\\(\\)\\s+OVER\\s+\\(", "DENSE_RANK() OVER (")
         .replaceAll("(?i)LAG\\s*\\(([^)]+)\\)\\s+OVER\\s+\\(", "LAG($1) OVER (")
         .replaceAll("(?i)LEAD\\s*\\(([^)]+)\\)\\s+OVER\\s+\\(", "LEAD($1) OVER (")
         .replaceAll("(?i)FIRST_VALUE\\s*\\(([^)]+)\\)\\s+OVER\\s+\\(", "FIRST_VALUE($1) OVER (")
         .replaceAll("(?i)LAST_VALUE\\s*\\(([^)]+)\\)\\s+OVER\\s+\\(", "LAST_VALUE($1) OVER (");
    
    // 13. **GREATEST/LEAST**
    s = s.replaceAll("(?i)\\bGREATEST\\s*\\(", "GREATEST(")
         .replaceAll("(?i)\\bLEAST\\s*\\(", "LEAST(");
    
    // 14. **CAST**
    s = s.replaceAll("(?i)CAST\\s*\\(([^)]+)\\s+AS\\s+INTEGER\\)", "CAST($1 AS NUMBER)")
         .replaceAll("(?i)CAST\\s*\\(([^)]+)\\s+AS\\s+BIGINT\\)", "CAST($1 AS NUMBER)")
         .replaceAll("(?i)CAST\\s*\\(([^)]+)\\s+AS\\s+VARCHAR\\(([^)]+)\\)\\)", "CAST($1 AS VARCHAR2($2))")
         .replaceAll("(?i)CAST\\s*\\(([^)]+)\\s+AS\\s+TEXT\\)", "TO_CHAR($1)");
    
    // 15. **EXCEPT** → MINUS
    s = s.replaceAll("(?i)\\bEXCEPT\\b", "MINUS");
    
    // 16. **DISTINCT ON** - Conversion complexe
    s = convertDistinctOn(s);
    
    // 17. Nettoyage des schémas PostgreSQL
    s = s.replaceAll("\"?public\"?\\.", "")
         .replaceAll("(?i)FROM\\s+public\\.", "FROM ")
         .replaceAll("(?i)JOIN\\s+public\\.", "JOIN ");
    
    // 18. Gérer les noms de tables/vues tronqués
    s = handleTruncatedNames(s, db);
    
    return s;
}

// ============================================================
// UTILITAIRES
// ============================================================

private static String convertDistinctOn(String sql) {
    // DISTINCT ON est complexe - pour l'instant on le garde tel quel
    // Une conversion complète nécessiterait un parseur SQL complet
    return sql;
}

private static String handleTruncatedNames(String sql, PostgresDatabaseObjects db) {
    // Remplacer les noms de tables tronqués dans les requêtes
    for (String table : db.validTables) {
        String truncated = truncateForOracle(table);
        if (!table.equals(truncated)) {
            // Remplacer en mode case-insensitive mais préserver les quotes
            sql = sql.replaceAll("(?i)\\b" + Pattern.quote(table) + "\\b", truncated);
        }
    }
    
    for (String view : db.validViews) {
        String truncated = truncateForOracle(view);
        if (!view.equals(truncated)) {
            sql = sql.replaceAll("(?i)\\b" + Pattern.quote(view) + "\\b", truncated);
        }
    }
    
    return sql;
}
    // ============================================================
    // FONCTION PRINCIPALE OPTIMISÉE
    // ============================================================

    public PostgresMigrationStats migrateCompleteDatabase(PostgreSQL postgres, Oracle oracle) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("=== MIGRATION POSTGRESQL → ORACLE OPTIMISÉE ===");
        System.out.println("=".repeat(80));
        log += "\n" + "=".repeat(80) + "\n";
        log += "=== MIGRATION POSTGRESQL → ORACLE OPTIMISÉE ===\n";
        log += "=".repeat(80) + "\n";
        
        long start = System.currentTimeMillis();
        PostgresMigrationStats stats = new PostgresMigrationStats();

        try (Connection pgConn = PostgresConnexion(postgres);
             Connection oraConn = OracleService.OracleConnexion(oracle)) {
            
            System.out.println("✅ Connexions établies");
            log += "✅ Connexions établies\n";
            
            PostgresDatabaseObjects db = validatePostgresObjects(pgConn);
            analyzeViewDependencies(pgConn, db, stats);
            
            // MIGRATION OPTIMISÉE
            migrateOptimized(pgConn, oraConn, postgres, oracle, db, stats);
            
            long duration = (System.currentTimeMillis() - start) / 1000;
            System.out.println("\n✅ Migration terminée en " + duration + "s");
            log += "\n✅ Migration terminée en " + duration + "s\n";
            
            stats.printDetailed();
            
        } catch (Exception e) {
            System.err.println("\n❌ ERREUR CRITIQUE: " + e.getMessage());
            log += "\n❌ ERREUR CRITIQUE: " + e.getMessage() + "\n";
            stats.addError("❌ ERREUR CRITIQUE: " + e.getMessage());
            stats.printDetailed();
        }
        stats.logs = log;
        return stats;
    }

    private void migrateOptimized(Connection pg, Connection ora, PostgreSQL postgres, Oracle oracle, 
                                       PostgresDatabaseObjects db, PostgresMigrationStats stats) {
        // 1. Séquences
        try { migrateSequences(pg, ora, db, stats); } catch (Exception e) {
            stats.addError("❌ Séquences: " + e.getMessage());
        }
        
        // 2. Tables (avec seconde tentative)
        try { migrateTables(postgres, oracle, db, stats); } catch (Exception e) {
            stats.addError("❌ Tables: " + e.getMessage());
        }
        
        // 3. Données
        try { migrateData(postgres, oracle, db, stats); } catch (Exception e) {
            stats.addError("❌ Données: " + e.getMessage());
        }
        
        // 4. Index
        try { migrateIndexes(pg, ora, db, stats); } catch (Exception e) {
            stats.addError("❌ Index: " + e.getMessage());
        }
        
        // 5. Contraintes
        try { migrateConstraints(pg, ora, db, stats); } catch (Exception e) {
            stats.addError("❌ Contraintes: " + e.getMessage());
        }
        
        // 6. Fonctions (SANS création par défaut)
        try { migrateFunctions(pg, ora, db, stats); } catch (Exception e) {
            stats.addError("❌ Fonctions: " + e.getMessage());
        }
        
        // 7. Triggers (SANS création par défaut)
        try { migrateTriggers(pg, ora, db, stats); } catch (Exception e) {
            stats.addError("❌ Triggers: " + e.getMessage());
        }
        
        // 8. Vues (OPTIMISÉE)
        try { migrateViews(pg, ora, db, stats); } catch (Exception e) {
            stats.addError("❌ Vues: " + e.getMessage());
        }
    }

    // ============================================================
    // MÉTHODES COMPATIBILITÉ
    // ============================================================

    public static void createOracleTable(Oracle oracle, String createSQL) throws SQLException {
        try (Connection oracleConn = OracleService.OracleConnexion(oracle);
             Statement stmt = oracleConn.createStatement()) {
            stmt.executeUpdate(createSQL);
        }
    }

    public void insertDataIntoOracle(PostgreSQL postgreSQL, Oracle oracle, String tableName) throws SQLException {
        try (Connection oracleConn = OracleService.OracleConnexion(oracle)) {
            PostgresMigrationStats dummyStats = new PostgresMigrationStats();
            this.insertDataWithConnection(postgreSQL, oracleConn, tableName, tableName, dummyStats);
        }
    }

    public void migrationTablesAndDataPostgresToOracle(Oracle oracle, PostgreSQL postgres) {
        migrateCompleteDatabase(postgres, oracle);
    }
}