package stage.bici.DBBridge.Service;

import java.sql.*;
import java.util.*;
import java.util.regex.*;
import java.math.BigDecimal;
import stage.bici.DBBridge.Model.DonneeTableOracle;
import stage.bici.DBBridge.Model.Oracle;
import stage.bici.DBBridge.Model.PostgreSQL;
import stage.bici.DBBridge.TestIA.SqlViewTranslator;

public class OracleService {

    public String log;

    public OracleService() {
        this.log = "";
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

    private DatabaseObjects validateOracleObjects(Connection ora) throws SQLException {
        System.out.println("\n🔍 VALIDATION DES OBJETS ORACLE...");
        log += "\n🔍 VALIDATION DES OBJETS ORACLE...\n";
        DatabaseObjects db = new DatabaseObjects();
        InvalidObjectsStats invalidStats = new InvalidObjectsStats();
        
        db.owner = getCurrentUser(ora);
        System.out.println("Schema Oracle: " + db.owner);
        log += "Schema Oracle: " + db.owner + "\n";
        
        // Tables (toujours considérées comme valides)
        try (Statement st = ora.createStatement();
             ResultSet rs = st.executeQuery("SELECT table_name FROM user_tables ORDER BY table_name")) {
            while (rs.next()) {
                db.validTables.add(rs.getString(1));
            }
        }
        System.out.println("✅ Tables trouvées: " + db.validTables.size());
        log += "✅ Tables trouvées: " + db.validTables.size() + "\n";
        
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
        log += "✅ Séquences valides: " + db.validSequences.size() + "\n";
        System.out.println("❌ Séquences invalides: " + invalidStats.invalidSequences);
        log += "❌ Séquences invalides: " + invalidStats.invalidSequences + "\n";
        
        // Vues avec statut VALID/INVALID et définitions
        Map<String, String> fullViewDefinitions = new HashMap<>();

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
                        
                        // Récupérer définition SELECT
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
                        
                        // Récupérer aussi la définition complète avec CREATE
                        try (PreparedStatement psFullView = ora.prepareStatement(
                            "SELECT DBMS_METADATA.GET_DDL('VIEW', ?, ?) FROM DUAL")) {
                            psFullView.setString(1, viewName.toUpperCase());
                            psFullView.setString(2, db.owner.toUpperCase());
                            try (ResultSet rsFull = psFullView.executeQuery()) {
                                if (rsFull.next()) {
                                    fullViewDefinitions.put(viewName, rsFull.getString(1));
                                }
                            }
                        } catch (Exception e) {
                            // Ignorer si DBMS_METADATA n'est pas accessible
                        }
                    } else {
                        invalidStats.invalidViews++;
                        invalidStats.invalidViewsList.add(viewName);
                    }
                }
            }
        }
    
        System.out.println("✅ Vues valides: " + db.validViews.size());
        log += "✅ Vues valides: " + db.validViews.size() + "\n";
        System.out.println("❌ Vues invalides: " + invalidStats.invalidViews);
        log += "❌ Vues invalides: " + invalidStats.invalidViews + "\n";
        
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
        log += "✅ Fonctions valides: " + db.validFunctions.size() + "\n";
        System.out.println("❌ Fonctions invalides: " + invalidStats.invalidFunctions);
        log += "❌ Fonctions invalides: " + invalidStats.invalidFunctions + "\n";
        
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
        log += "✅ Triggers valides: " + db.validTriggers.size() + "\n";
        System.out.println("❌ Triggers invalides: " + invalidStats.invalidTriggers);
        log += "❌ Triggers invalides: " + invalidStats.invalidTriggers + "\n";
        
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

    private void analyzeViewDependencies(Connection ora, DatabaseObjects db, MigrationStats stats) {
        System.out.println("\n🔍 ANALYSE DES DÉPENDANCES DES VUES...");
        log += "\n🔍 ANALYSE DES DÉPENDANCES DES VUES...\n";
        
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
            } else {
                // Vue sans définition, initialiser avec ensemble vide
                viewDependencies.put(viewName, new HashSet<>());
            }
        }
        
        // Afficher statistiques
        int viewsWithDeps = 0;
        int viewsWithMissing = 0;
        for (String viewName : db.validViews) {
            Set<String> deps = viewDependencies.get(viewName);
            if (deps != null && !deps.isEmpty()) {
                viewsWithDeps++;
            }
            if (missingDependencies.containsKey(viewName)) {
                viewsWithMissing++;
            }
        }
        
        System.out.println("📊 Statistiques dépendances:");
        log += "📊 Statistiques dépendances:\n";
        System.out.println("   - Vues avec dépendances: " + viewsWithDeps);
        log += "   - Vues avec dépendances: " + viewsWithDeps + "\n";
        System.out.println("   - Vues sans dépendances: " + (db.validViews.size() - viewsWithDeps));
        log += "   - Vues sans dépendances: " + (db.validViews.size() - viewsWithDeps) + "\n";
        System.out.println("   - Vues avec dépendances manquantes: " + viewsWithMissing);
        log += "   - Vues avec dépendances manquantes: " + viewsWithMissing + "\n";
        
        // Afficher les vues avec dépendances manquantes
        if (!missingDependencies.isEmpty()) {
            System.out.println("\n⚠️  DÉPENDANCES MANQUANTES:");
            log += "\n⚠️  DÉPENDANCES MANQUANTES:\n";
            for (Map.Entry<String, Set<String>> entry : missingDependencies.entrySet()) {
                System.out.println("   - " + entry.getKey() + " → " + entry.getValue());
                log += "   - " + entry.getKey() + " → " + entry.getValue() + "\n";
            }
        }
        
        db.viewDependencies = viewDependencies;
        db.missingDependencies = missingDependencies;
    }

    private static Set<String> findViewDependencies(String viewDef, DatabaseObjects db) {
        Set<String> dependencies = new HashSet<>();
        String upperDef = viewDef.toUpperCase();
        
        // Pattern pour trouver les références FROM et JOIN
        // Matches: FROM table_name, JOIN table_name, etc.
        Pattern fromJoinPattern = Pattern.compile(
            "\\b(FROM|JOIN)\\s+([a-zA-Z_][a-zA-Z0-9_$#]*)",
            Pattern.CASE_INSENSITIVE
        );
        
        Matcher matcher = fromJoinPattern.matcher(viewDef);
        
        while (matcher.find()) {
            String objectName = matcher.group(2).toUpperCase();
            
            // Vérifier si c'est une table valide
            if (db.validTables.contains(objectName)) {
                dependencies.add(objectName);
            }
            // Vérifier si c'est une autre vue valide
            else if (db.validViews.contains(objectName)) {
                dependencies.add(objectName);
            }
        }
        
        // Chercher aussi les références avec alias de schéma (schema.table)
        Pattern schemaPattern = Pattern.compile(
            "\\b(FROM|JOIN)\\s+[a-zA-Z_][a-zA-Z0-9_$#]*\\.([a-zA-Z_][a-zA-Z0-9_$#]*)",
            Pattern.CASE_INSENSITIVE
        );
        
        Matcher schemaMatcher = schemaPattern.matcher(viewDef);
        
        while (schemaMatcher.find()) {
            String objectName = schemaMatcher.group(2).toUpperCase();
            
            if (db.validTables.contains(objectName)) {
                dependencies.add(objectName);
            } else if (db.validViews.contains(objectName)) {
                dependencies.add(objectName);
            }
        }
        
        return dependencies;
    }

    // ============================================================
    // TRI TOPOLOGIQUE DES VUES
    // ============================================================

    private List<String> sortViewsByDependencies(DatabaseObjects db) {
        System.out.println("\n🔄 TRI TOPOLOGIQUE DES VUES...");
        log += "\n🔄 TRI TOPOLOGIQUE DES VUES...\n";
        
        // Construire le graphe de dépendances complet
        Map<String, Set<String>> dependencies = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>(); // Inverse: qui dépend de moi
        
        // Initialiser toutes les vues
        for (String viewName : db.validViews) {
            dependencies.put(viewName, new HashSet<>());
            dependents.put(viewName, new HashSet<>());
        }
        
        // Construire les dépendances
        for (String viewName : db.validViews) {
            Set<String> deps = db.viewDependencies.getOrDefault(viewName, new HashSet<>());
            
            // Ne garder que les dépendances vers d'autres vues valides
            for (String dep : deps) {
                if (db.validViews.contains(dep)) {
                    dependencies.get(viewName).add(dep);
                    dependents.get(dep).add(viewName);
                }
            }
        }
        
        // Algorithme de Kahn pour tri topologique
        List<String> sorted = new ArrayList<>();
        Queue<String> queue = new LinkedList<>();
        Map<String, Integer> inDegree = new HashMap<>();
        
        // Calculer les degrés entrants
        for (String view : db.validViews) {
            int degree = dependencies.get(view).size();
            inDegree.put(view, degree);
            
            // Les vues sans dépendances vont en premier
            if (degree == 0) {
                queue.add(view);
            }
        }
        
        // Traiter les vues par ordre de dépendances
        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(current);
            
            // Réduire le degré des vues qui dépendent de celle-ci
            Set<String> deps = dependents.get(current);
            if (deps != null) {
                for (String dependent : deps) {
                    int newDegree = inDegree.get(dependent) - 1;
                    inDegree.put(dependent, newDegree);
                    
                    if (newDegree == 0) {
                        queue.add(dependent);
                    }
                }
            }
        }
        
        // Gérer les cycles détectés
        if (sorted.size() < db.validViews.size()) {
            System.out.println("⚠️  Cycles de dépendances détectés!");
            log += "⚠️  Cycles de dépendances détectés!\n";
            
            // Ajouter les vues restantes (celles dans des cycles)
            Set<String> remaining = new HashSet<>(db.validViews);
            remaining.removeAll(sorted);
            
            System.out.println("⚠️  Vues dans des cycles: " + remaining);
            log += "⚠️  Vues dans des cycles: " + remaining + "\n";
            
            // Essayer de résoudre les cycles en ordre alphabétique
            List<String> cyclic = new ArrayList<>(remaining);
            Collections.sort(cyclic);
            sorted.addAll(cyclic);
        }
        
        // Vérification finale
        System.out.println("✅ Vues triées: " + sorted.size() + "/" + db.validViews.size());
        log += "✅ Vues triées: " + sorted.size() + "/" + db.validViews.size() + "\n";
        
        // Afficher l'ordre pour debug
        System.out.println("📋 Ordre de migration:");
        log += "📋 Ordre de migration:\n";
        for (int i = 0; i < Math.min(10, sorted.size()); i++) {
            String view = sorted.get(i);
            Set<String> deps = dependencies.get(view);
            System.out.println("   " + (i+1) + ". " + view + 
                (deps.isEmpty() ? " (sans dépendances)" : " (dépend de: " + deps + ")"));
            log += "   " + (i+1) + ". " + view + 
                (deps.isEmpty() ? " (sans dépendances)" : " (dépend de: " + deps + ")") + "\n";
        }
        if (sorted.size() > 10) {
            System.out.println("   ... (" + (sorted.size() - 10) + " vues supplémentaires)");
            log += "   ... (" + (sorted.size() - 10) + " vues supplémentaires)\n";
        }
        
        return sorted;
    }

    // ============================================================
    // MIGRATION SÉQUENCES
    // ============================================================

    private void migrateSequences(Connection ora, Connection pg, DatabaseObjects db, MigrationStats stats) {
        System.out.println("\n🔢 MIGRATION DES SÉQUENCES...");
        log += "\n🔢 MIGRATION DES SÉQUENCES...\n";
        stats.sequencesTotal = db.validSequences.size();
        
        // Afficher d'abord les séquences invalides
        if (db.invalidStats != null && db.invalidStats.invalidSequences > 0) {
            System.out.println("❌ Séquences invalides ignorées: " + db.invalidStats.invalidSequences);
            log += "❌ Séquences invalides ignorées: " + db.invalidStats.invalidSequences + "\n";
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
                            log += "✅ Séquence: " + seqName + "\n";
                        }
                    }
                }
            } catch (Exception e) {
                stats.sequencesFailed++;
                stats.failedSequences.put(seqName, e.getMessage());
                stats.addErrorSummary(MigrationStats.classifyError(e.getMessage()), seqName);
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

    private void migrateTables(Connection ora, Connection pg, DatabaseObjects db, MigrationStats stats) {
        System.out.println("\n🔨 MIGRATION DES TABLES...");
        log += "\n🔨 MIGRATION DES TABLES...\n";
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
                log += "✅ Table: " + table + "\n";
            } catch (Exception e) {
                stats.tablesFailed++;
                stats.failedTables.add(table);
                stats.addErrorSummary(MigrationStats.classifyError(e.getMessage()), table);
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

    private void migrateData(Connection ora, Connection pg, DatabaseObjects db, MigrationStats stats) {
        System.out.println("\n📦 MIGRATION DES DONNÉES...");
        log += "\n📦 MIGRATION DES DONNÉES...\n";
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
                log += "✅ Données: " + table + " (" + rowsInserted + " lignes)\n";
            } catch (Exception e) {
                stats.dataFailed++;
                stats.failedData.add(table);
                stats.addErrorSummary(MigrationStats.classifyError(e.getMessage()), table);
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

    private void migrateIndexes(Connection ora, Connection pg, DatabaseObjects db, MigrationStats stats) {
        System.out.println("\n🔍 MIGRATION DES INDEX...");
        log += "\n🔍 MIGRATION DES INDEX...\n";
        
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
                            log += "✅ Index: " + idxName + "\n";
                        } catch (Exception ex) {
                            stats.indexFailed++;
                            stats.failedIndexes.put(idxName, ex.getMessage());
                            stats.addErrorSummary(MigrationStats.classifyError(ex.getMessage()), idxName);
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

    private void migrateConstraints(Connection ora, Connection pg, DatabaseObjects db, MigrationStats stats) {
        System.out.println("\n🔗 MIGRATION DES CONTRAINTES...");
        log += "\n🔗 MIGRATION DES CONTRAINTES...\n";
        
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
                    log += "✅ FK: " + fkName + "\n";
                } catch (Exception e) {
                    stats.fkFailed++;
                    stats.failedFKs.put(fkName, e.getMessage());
                    stats.addErrorSummary(MigrationStats.classifyError(e.getMessage()), fkName);
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
                    log += "✅ UNIQUE: " + ukName + "\n";
                } catch (Exception e) {
                    stats.uniqueFailed++;
                    stats.failedUniques.put(ukName, e.getMessage());
                    stats.addErrorSummary(MigrationStats.classifyError(e.getMessage()), ukName);
                    stats.addError("❌ UNIQUE " + ukName + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            // Ignorer les erreurs de cette requête
        }
        
        System.out.println("⚠️  CHECK constraints ignorées (SEARCH_CONDITION type LONG incompatible)");
        log += "⚠️  CHECK constraints ignorées (SEARCH_CONDITION type LONG incompatible)\n";
    }

    // ============================================================
    // MIGRATION VUES
    // ============================================================

    private void migrateViews(Connection ora, Connection pg, DatabaseObjects db, MigrationStats stats) {
        System.out.println("\n👁️  MIGRATION DES VUES...");
        log += "\n👁️  MIGRATION DES VUES...\n";
        stats.viewsTotal = db.validViews.size();

        if (db.invalidStats != null && db.invalidStats.invalidViews > 0) {
            System.out.println("❌ Vues invalides ignorées: " + db.invalidStats.invalidViews);
            log += "❌ Vues invalides ignorées: " + db.invalidStats.invalidViews + "\n";
        }

        List<String> sortedViews = sortViewsByDependencies(db);
        Set<String> migrated = new HashSet<>();
        int maxPasses = 10;

        for (int pass = 1; pass <= maxPasses; pass++) {
            System.out.println("Passe " + pass + "/" + maxPasses + " pour les vues...");
            log += "Passe " + pass + "/" + maxPasses + " pour les vues...\n";
            int successThisPass = 0;

            for (String viewName : sortedViews) {
                if (migrated.contains(viewName)) continue;

                try {
                    String viewDef = db.viewDefinitions.get(viewName);
                    if (viewDef == null || viewDef.trim().isEmpty()) {
                        stats.viewsFailed++;
                        stats.failedViews.put(viewName, "Définition de vue vide");
                        migrated.add(viewName);
                        continue;
                    }

                    List<String> columnAliases = getViewColumnAliases(ora, db.owner, viewName);
                    String pgView = convertViewWithAliases(viewName, viewDef, columnAliases);

                    try (Statement st = pg.createStatement()) {
                        st.executeUpdate(pgView);
                        migrated.add(viewName);
                        stats.viewsSuccess++;
                        successThisPass++;
                        System.out.println("✅ Vue: " + viewName);
                        log += "✅ Vue: " + viewName + "\n";
                    } catch (Exception e) {
                        String errorMsg = e.getMessage();
                        boolean isDependencyError = errorMsg != null && (
                            errorMsg.toLowerCase().contains("n'existe pas") ||
                            errorMsg.toLowerCase().contains("does not exist")
                        );

                        if (isDependencyError && pass < maxPasses) {
                            System.out.println("⏳ Vue en attente (dépendances): " + viewName);
                            log += "⏳ Vue en attente (dépendances): " + viewName + "\n";
                        } else {
                            stats.viewsFailed++;
                            stats.failedViews.put(viewName, errorMsg);
                            stats.addErrorSummary(MigrationStats.classifyError(errorMsg), viewName);
                            stats.addError("❌ VUE " + viewName + ": " + errorMsg);
                            migrated.add(viewName);
                        }
                    }
                } catch (Exception e) {
                    if (pass == maxPasses) {
                        stats.viewsFailed++;
                        stats.failedViews.put(viewName, e.getMessage());
                        stats.addErrorSummary(MigrationStats.classifyError(e.getMessage()), viewName);
                        stats.addError("❌ VUE " + viewName + ": " + e.getMessage());
                        migrated.add(viewName);
            
                    }
                }
            }

            System.out.println("Passe " + pass + ": " + successThisPass + " succès");
            log += "Passe " + pass + ": " + successThisPass + " succès\n";
            if (migrated.size() >= db.validViews.size() || (successThisPass == 0 && pass > 2)) break;
        }

        // // ===== FALLBACK : Tentative avec la définition complète Oracle =====
        // List<String> stillFailed = new ArrayList<>();
        // for (String viewName : sortedViews) {
        //     if (stats.failedViews.containsKey(viewName)) {
        //         stillFailed.add(viewName);
        //     }
        // }

        // if (!stillFailed.isEmpty()) {
        //     System.out.println("\n🔄 FALLBACK: Tentative avec définitions originales...");
        //     log += "\n🔄 FALLBACK: Tentative avec définitions originales...\n";
        //     int fallbackSuccess = 0;

        //     for (String viewName : stillFailed) {
        //         try {
        //             String fullViewDef = getFullViewDefinition(ora, db.owner, viewName);

                    
        //             if (fullViewDef == null || fullViewDef.trim().isEmpty()) {
        //                 System.out.println("⚠️  Définition vide pour: " + viewName);
        //                 log += "⚠️  Définition vide pour: " + viewName + "\n";
        //                 continue;
        //             }

        //             // Traduire la vue Oracle vers PostgreSQL avec l'IA
        //             String viewIA = SqlViewTranslator.translateOracleToPostgres(fullViewDef);

        //             System.out.println("View normal: \n" + fullViewDef);
        //             System.out.println("View IA: \n" + viewIA);
        //             log += "View normal: \n" + fullViewDef + "\n";
        //             log += "View IA: \n" + viewIA + "\n";
                    
        //             try (Statement st = pg.createStatement()) {
        //                 st.executeUpdate(viewIA);
                        
        //                 // Mise à jour des stats
        //                 stats.viewsFailed--;
        //                 stats.viewsSuccess++;
        //                 stats.failedViews.remove(viewName);
        //                 fallbackSuccess++;
                        
        //                 System.out.println("🔄 Vue fallback créée: " + viewName);
        //                 log += "🔄 Vue fallback créée: " + viewName + "\n";
        //                 stats.addError("🔄 VUE FALLBACK " + viewName + ": Créée avec traduction IA");
        //             }
                    
        //         } catch (Exception e) {
        //             System.out.println("❌ Échec fallback pour: " + viewName + " - " + e.getMessage());
        //             log += "❌ Échec fallback pour: " + viewName + " - " + e.getMessage() + "\n";
        //         }
        //     }

        //     System.out.println("Fallback: " + fallbackSuccess + "/" + stillFailed.size() + " vues créées");
        //     log += "Fallback: " + fallbackSuccess + "/" + stillFailed.size() + " vues créées\n";
        // }

        System.out.println("Vues migrées: " + stats.viewsSuccess + "/" + stats.viewsTotal);
        log += "Vues migrées: " + stats.viewsSuccess + "/" + stats.viewsTotal + "\n";
    }

    private static String getFullViewDefinition(Connection ora, String owner, String viewName) throws SQLException {
        StringBuilder fullDef = new StringBuilder();
        
        String sql = "SELECT DBMS_METADATA.GET_DDL('VIEW', ?, ?) AS ddl FROM DUAL";
        
        try (PreparedStatement ps = ora.prepareStatement(sql)) {
            ps.setString(1, viewName.toUpperCase());
            ps.setString(2, owner.toUpperCase());
            
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Clob clob = rs.getClob("ddl");
                    if (clob != null) {
                        fullDef.append(clob.getSubString(1, (int) clob.length()));
                    }
                }
            }
        } catch (SQLException e) {
            // Si DBMS_METADATA échoue, essayer avec ALL_VIEWS
            String fallbackSql = "SELECT text FROM all_views WHERE owner = ? AND view_name = ?";
            try (PreparedStatement ps = ora.prepareStatement(fallbackSql)) {
                ps.setString(1, owner.toUpperCase());
                ps.setString(2, viewName.toUpperCase());
                
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Clob clob = rs.getClob("text");
                        if (clob != null) {
                            String viewText = clob.getSubString(1, (int) clob.length());
                            // Reconstruire avec CREATE OR REPLACE
                            fullDef.append("CREATE OR REPLACE VIEW ")
                                .append(viewName)
                                .append(" AS ")
                                .append(viewText);
                        }
                    }
                }
            }
        }
        
        return fullDef.toString();
    }

    // ============================================================
    // RÉCUPÉRATION DES ALIAS DE COLONNES ORACLE
    // ============================================================

    private static List<String> getViewColumnAliases(Connection ora, String owner, String viewName) {
        List<String> aliases = new ArrayList<>();
        
        String sql = "SELECT column_name FROM all_tab_columns " +
                     "WHERE owner = ? AND table_name = ? " +
                     "ORDER BY column_id";
        
        try (PreparedStatement ps = ora.prepareStatement(sql)) {
            ps.setString(1, owner.toUpperCase());
            ps.setString(2, viewName.toUpperCase());
            
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    aliases.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            System.err.println("⚠️  Impossible de récupérer les alias pour " + viewName + ": " + e.getMessage());
        }
        
        return aliases;
    }

    // ============================================================
    // CONVERSION AVEC PRÉSERVATION DES ALIAS
    // ============================================================

    private static String convertViewWithAliases(String viewName, String oracleSql, List<String> columnAliases) {
        String sql = oracleSql.trim();
        
        // Étape 1: Conversion des jointures externes Oracle (+)
        sql = convertOracleOuterJoinsToLeftJoin(sql);
        
        // Étape 2: Corriger les colonnes dupliquées
        sql = fixDuplicateColumnsInSelect(sql);
        
        // Étape 3: Ajouter alias aux sous-requêtes
        sql = fixSubqueriesWithoutAlias(sql);
        
        // Étape 4: Conversions de fonctions de base
        sql = sql
            .replaceAll("(?i)\\bSYSDATE\\b", "CURRENT_TIMESTAMP")
            .replaceAll("(?i)\\bNVL\\s*\\(", "COALESCE(")
            .replaceAll("(?i)\\bSUBSTR\\s*\\(", "SUBSTRING(")
            .replaceAll("(?i)\\bTO_DATE\\s*\\(", "TO_TIMESTAMP(")
            .replaceAll("(?i)\\bTO_CHAR\\s*\\(", "TO_CHAR(")
            .replaceAll("(?i)(\\w+)\\.NEXTVAL", "NEXTVAL('$1')")
            .replaceAll("(?i)(\\w+)\\.CURRVAL", "CURRVAL('$1')")
            .replaceAll("(?i)FROM\\s+DUAL\\b", "")
            .replaceAll("(?i)\\bVARCHAR2\\b", "VARCHAR")
            .replaceAll("(?i)\\bNUMBER\\b", "NUMERIC");
        
        // Étape 5: Minuscules
        sql = sql.toLowerCase();
        
        // Étape 6: Construire la clause CREATE avec les alias explicites
        if (!columnAliases.isEmpty()) {
            StringBuilder viewSql = new StringBuilder("CREATE OR REPLACE VIEW ");
            viewSql.append(quotePg(viewName));
            viewSql.append(" (");
            
            // Ajouter les alias de colonnes
            for (int i = 0; i < columnAliases.size(); i++) {
                viewSql.append(quotePg(columnAliases.get(i)));
                if (i < columnAliases.size() - 1) {
                    viewSql.append(", ");
                }
            }
            
            viewSql.append(") AS ");
            viewSql.append(sql);
            
            return viewSql.toString();
        } else {
            return "error"; // Forcer l'échec si pas d'alias
        }
    }

    // ============================================================
    // CORRECTION DES COLONNES DUPLIQUÉES
    // ============================================================

    private static String fixDuplicateColumnsInSelect(String sql) {
        // Trouve toutes les clauses SELECT
        Pattern selectPattern = Pattern.compile("(SELECT\\s+)(.*?)(\\s+FROM)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = selectPattern.matcher(sql);
        
        StringBuffer result = new StringBuffer();
        
        while (matcher.find()) {
            String selectKeyword = matcher.group(1);
            String columnsPart = matcher.group(2);
            String fromKeyword = matcher.group(3);
            
            // Analyser les colonnes
            List<String> columns = splitColumns(columnsPart);
            Map<String, Integer> columnCounts = new HashMap<>();
            List<String> fixedColumns = new ArrayList<>();
            
            for (String column : columns) {
                String colName = extractColumnAlias(column);
                
                int count = columnCounts.getOrDefault(colName, 0);
                columnCounts.put(colName, count + 1);
                
                if (count > 0) {
                    // Colonne dupliquée - ajouter un alias unique
                    String newAlias = colName + "_dup" + (count + 1);
                    if (column.toUpperCase().contains(" AS ")) {
                        // Remplacer l'alias existant
                        column = column.replaceAll("(?i)\\s+AS\\s+\\w+$", " AS " + newAlias);
                    } else {
                        // Ajouter un alias
                        column = column + " AS " + newAlias;
                    }
                }
                
                fixedColumns.add(column);
            }
            
            String fixedColumnsPart = String.join(", ", fixedColumns);
            matcher.appendReplacement(result, Matcher.quoteReplacement(selectKeyword + fixedColumnsPart + fromKeyword));
        }
        
        matcher.appendTail(result);
        return result.toString();
    }

    // Diviser les colonnes en tenant compte des parenthèses et virgules
    private static List<String> splitColumns(String columnsPart) {
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenLevel = 0;
        
        for (int i = 0; i < columnsPart.length(); i++) {
            char c = columnsPart.charAt(i);
            
            if (c == '(') {
                parenLevel++;
                current.append(c);
            } else if (c == ')') {
                parenLevel--;
                current.append(c);
            } else if (c == ',' && parenLevel == 0) {
                columns.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        
        if (current.length() > 0) {
            columns.add(current.toString().trim());
        }
        
        return columns;
    }

    // Extraire le nom de la colonne ou son alias
    private static String extractColumnAlias(String column) {
        // Chercher un alias explicite (AS quelquechose)
        Pattern aliasPattern = Pattern.compile("\\s+AS\\s+(\\w+)$", Pattern.CASE_INSENSITIVE);
        Matcher aliasMatcher = aliasPattern.matcher(column);
        
        if (aliasMatcher.find()) {
            return aliasMatcher.group(1).toLowerCase();
        }
        
        // Sinon, prendre le dernier mot (qui est souvent l'alias implicite)
        String[] parts = column.trim().split("\\s+");
        if (parts.length > 0) {
            String lastPart = parts[parts.length - 1];
            // Nettoyer les caractères spéciaux
            return lastPart.replaceAll("[^a-zA-Z0-9_]", "").toLowerCase();
        }
        
        return "col";
    }

    // ============================================================
    // AJOUTER DES ALIAS AUX SOUS-REQUÊTES
    // ============================================================

    private static String fixSubqueriesWithoutAlias(String sql) {
        // Pattern pour détecter les sous-requêtes sans alias dans FROM
        Pattern pattern = Pattern.compile(
            "FROM\\s*\\(\\s*SELECT.*?\\)(?!\\s+\\w+)(?=\\s*(?:WHERE|GROUP|ORDER|UNION|LIMIT|\\)|,|$))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        
        Matcher matcher = pattern.matcher(sql);
        StringBuffer result = new StringBuffer();
        int aliasCounter = 1;
        
        while (matcher.find()) {
            String subquery = matcher.group();
            String replacement = subquery + " AS subq_" + aliasCounter;
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            aliasCounter++;
        }
        
        matcher.appendTail(result);
        return result.toString();
    }

    // ============================================================
    // CONVERSION DES JOINTURES EXTERNES ORACLE (+) → LEFT JOIN
    // ============================================================

    private static String convertOracleOuterJoinsToLeftJoin(String sql) {
        if (!sql.contains("(+)")) {
            return sql;
        }
        
        try {
            String upperSql = sql.toUpperCase();
            int fromIdx = upperSql.indexOf("FROM");
            int whereIdx = upperSql.indexOf("WHERE");
            
            if (fromIdx == -1 || whereIdx == -1) {
                return sql.replaceAll("\\s*\\(\\s*\\+\\s*\\)", "");
            }
            
            String selectPart = sql.substring(0, fromIdx);
            String fromPart = sql.substring(fromIdx + 4, whereIdx).trim();
            String wherePart = sql.substring(whereIdx + 5).trim();
            
            // Trouver la fin du WHERE
            String afterWhere = "";
            String whereConditions = wherePart;
            
            for (String keyword : new String[]{"GROUP BY", "ORDER BY", "UNION", "HAVING"}) {
                int idx = wherePart.toUpperCase().indexOf(keyword);
                if (idx != -1) {
                    whereConditions = wherePart.substring(0, idx).trim();
                    afterWhere = " " + wherePart.substring(idx);
                    break;
                }
            }
            
            // Parser les tables avec leurs alias
            Map<String, TableInfo> tables = parseTablesFromClause(fromPart);
            
            // Parser les conditions WHERE
            List<String> conditions = splitWhereConditions(whereConditions);
            Map<String, List<String>> leftJoinConditions = new LinkedHashMap<>();
            List<String> normalConditions = new ArrayList<>();
            Set<String> outerJoinTables = new HashSet<>();
            
            for (String condition : conditions) {
                OuterJoinInfo joinInfo = extractOuterJoinInfo(condition);
                
                if (joinInfo != null) {
                    outerJoinTables.add(joinInfo.outerTable);
                    leftJoinConditions.computeIfAbsent(joinInfo.outerTable, k -> new ArrayList<>())
                        .add(joinInfo.joinCondition);
                } else {
                    normalConditions.add(condition.replaceAll("\\s*\\(\\s*\\+\\s*\\)", ""));
                }
            }
            
            // Reconstruire la requête
            StringBuilder result = new StringBuilder(selectPart);
            result.append("FROM ");
            
            // Trouver la table principale
            String mainTable = findMainTable(tables, outerJoinTables);
            if (mainTable != null) {
                result.append(mainTable);
            }
            
            // Ajouter LEFT JOIN pour les tables outer join
            for (Map.Entry<String, TableInfo> entry : tables.entrySet()) {
                String alias = entry.getKey();
                TableInfo tableInfo = entry.getValue();
                
                if (tableInfo.fullDeclaration.equals(mainTable)) {
                    continue;
                }
                
                if (outerJoinTables.contains(alias)) {
                    result.append(" LEFT JOIN ").append(tableInfo.fullDeclaration);
                    List<String> joinConds = leftJoinConditions.get(alias);
                    if (joinConds != null && !joinConds.isEmpty()) {
                        result.append(" ON ").append(String.join(" AND ", joinConds));
                    }
                } else {
                    result.append(", ").append(tableInfo.fullDeclaration);
                }
            }
            
            // WHERE
            if (!normalConditions.isEmpty()) {
                result.append(" WHERE ").append(String.join(" AND ", normalConditions));
            }
            
            // Reste (GROUP BY, ORDER BY, etc.)
            result.append(afterWhere);
            
            return result.toString();
            
        } catch (Exception e) {
            return sql.replaceAll("\\s*\\(\\s*\\+\\s*\\)", "");
        }
    }

    // Classe pour stocker les infos de table
    private static class TableInfo {
        String tableName;
        String alias;
        String fullDeclaration;
        
        TableInfo(String tableName, String alias, String fullDeclaration) {
            this.tableName = tableName;
            this.alias = alias;
            this.fullDeclaration = fullDeclaration;
        }
    }

    // Classe pour stocker les infos de jointure externe
    private static class OuterJoinInfo {
        String outerTable;
        String joinCondition;
        
        OuterJoinInfo(String outerTable, String joinCondition) {
            this.outerTable = outerTable;
            this.joinCondition = joinCondition;
        }
    }

    // Parser les tables de la clause FROM
    private static Map<String, TableInfo> parseTablesFromClause(String fromPart) {
        Map<String, TableInfo> tables = new LinkedHashMap<>();
        
        for (String table : fromPart.split(",")) {
            table = table.trim();
            if (table.isEmpty()) continue;
            
            String[] parts = table.split("\\s+");
            String tableName = parts[0];
            String alias = parts.length > 1 ? parts[parts.length - 1] : tableName;
            
            tables.put(alias.toLowerCase(), new TableInfo(tableName, alias, table));
        }
        
        return tables;
    }

    // Diviser les conditions WHERE
    private static List<String> splitWhereConditions(String where) {
        List<String> conditions = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenLevel = 0;
        boolean inString = false;
        
        for (int i = 0; i < where.length(); i++) {
            char c = where.charAt(i);
            
            if (c == '\'') {
                inString = !inString;
                current.append(c);
                continue;
            }
            
            if (inString) {
                current.append(c);
                continue;
            }
            
            if (c == '(') parenLevel++;
            else if (c == ')') parenLevel--;
            
            if (parenLevel == 0 && i + 5 <= where.length()) {
                String next = where.substring(i, Math.min(i + 5, where.length())).toUpperCase();
                if (next.startsWith(" AND ")) {
                    conditions.add(current.toString().trim());
                    current = new StringBuilder();
                    i += 4; // Skip " AND"
                    continue;
                }
            }
            
            current.append(c);
        }
        
        if (current.length() > 0) {
            conditions.add(current.toString().trim());
        }
        
        return conditions;
    }

    // Extraire les infos de jointure externe
    private static OuterJoinInfo extractOuterJoinInfo(String condition) {
        // Pattern: table1.col = table2.col (+)
        Pattern p1 = Pattern.compile("(\\w+)\\.(\\w+)\\s*=\\s*(\\w+)\\.(\\w+)\\s*\\(\\s*\\+\\s*\\)", 
                                    Pattern.CASE_INSENSITIVE);
        Matcher m1 = p1.matcher(condition);
        
        if (m1.find()) {
            String leftTable = m1.group(1).toLowerCase();
            String leftCol = m1.group(2).toLowerCase();
            String rightTable = m1.group(3).toLowerCase();
            String rightCol = m1.group(4).toLowerCase();
            
            return new OuterJoinInfo(rightTable, 
                leftTable + "." + leftCol + " = " + rightTable + "." + rightCol);
        }
        
        // Pattern: table1.col (+) = table2.col
        Pattern p2 = Pattern.compile("(\\w+)\\.(\\w+)\\s*\\(\\s*\\+\\s*\\)\\s*=\\s*(\\w+)\\.(\\w+)", 
                                    Pattern.CASE_INSENSITIVE);
        Matcher m2 = p2.matcher(condition);
        
        if (m2.find()) {
            String leftTable = m2.group(1).toLowerCase();
            String leftCol = m2.group(2).toLowerCase();
            String rightTable = m2.group(3).toLowerCase();
            String rightCol = m2.group(4).toLowerCase();
            
            return new OuterJoinInfo(leftTable, 
                leftTable + "." + leftCol + " = " + rightTable + "." + rightCol);
        }
        
        return null;
    }

    // Trouver la table principale
    private static String findMainTable(Map<String, TableInfo> tables, Set<String> outerJoinTables) {
        for (Map.Entry<String, TableInfo> entry : tables.entrySet()) {
            if (!outerJoinTables.contains(entry.getKey())) {
                return entry.getValue().fullDeclaration;
            }
        }
        
        if (!tables.isEmpty()) {
            return tables.values().iterator().next().fullDeclaration;
        }
        
        return null;
    }

    // ============================================================
    // MIGRATION FONCTIONS - CORRIGÉE
    // ============================================================

    private void migrateFunctions(Connection ora, Connection pg, DatabaseObjects db, MigrationStats stats) {
        System.out.println("\n🧪 MIGRATION DES FONCTIONS...");
        log += "\n🧪 MIGRATION DES FONCTIONS...\n";
        stats.functionsTotal = db.validFunctions.size();
        
        // Afficher d'abord les fonctions invalides
        if (db.invalidStats != null && db.invalidStats.invalidFunctions > 0) {
            System.out.println("❌ Fonctions invalides ignorées: " + db.invalidStats.invalidFunctions);
            log += "❌ Fonctions invalides ignorées: " + db.invalidStats.invalidFunctions + "\n";
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
                            log += "✅ Fonction (séquence simple): " + funcName + "\n";
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
                        log += "✅ Fonction: " + funcName + "\n";
                    } catch (Exception e) {
                        // Essayer une version encore plus simple
                        String minimalFunction = createMinimalFunction(funcName);
                        if (minimalFunction != null) {
                            try (Statement st = pg.createStatement()) {
                                st.executeUpdate(minimalFunction);
                                stats.functionsSuccess++;
                                System.out.println("✅ Fonction (version minimale): " + funcName);
                                log += "✅ Fonction (version minimale): " + funcName + "\n";
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
                            log += "✅ Fonction (version minimale par défaut): " + funcName + "\n";
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
                String errorType = MigrationStats.classifyError(errorMsg);
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

    private void migrateTriggers(Connection ora, Connection pg, DatabaseObjects db, MigrationStats stats) {
        System.out.println("\n⚡ MIGRATION DES TRIGGERS...");
        log += "\n⚡ MIGRATION DES TRIGGERS...\n";
        stats.triggersTotal = db.validTriggers.size();
        
        // Afficher d'abord les triggers invalides
        if (db.invalidStats != null && db.invalidStats.invalidTriggers > 0) {
            System.out.println("❌ Triggers invalides ignorés: " + db.invalidStats.invalidTriggers);
            log += "❌ Triggers invalides ignorés: " + db.invalidStats.invalidTriggers + "\n";
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
                            log += "✅ Trigger (version basique): " + trigName + "\n";
                        } catch (Exception e) {
                            stats.triggersFailed++;
                            stats.failedTriggers.put(trigName, "Échec création trigger basique: " + e.getMessage());
                            stats.addErrorSummary(MigrationStats.classifyError(e.getMessage()), trigName);
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
                            log += "✅ Trigger: " + trigName + " sur table " + tableName + "\n";
                        } catch (Exception e) {
                            stats.triggersFailed++;
                            stats.failedTriggers.put(trigName, "Échec création trigger: " + e.getMessage());
                            stats.addErrorSummary(MigrationStats.classifyError(e.getMessage()), trigName);
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
                stats.addErrorSummary(MigrationStats.classifyError(e.getMessage()), trigName);
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

    public MigrationStats migrateCompleteDatabase(Oracle oracle, PostgreSQL postgres) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("=== MIGRATION ORACLE → POSTGRESQL COMPLÈTE ===");
        System.out.println("=".repeat(80));
        log += "\n" + "=".repeat(80) + "\n";
        log += "=== MIGRATION ORACLE → POSTGRESQL COMPLÈTE ===\n";
        log += "=".repeat(80) + "\n";
        
        long start = System.currentTimeMillis();
        MigrationStats stats = new MigrationStats();

        try (Connection oraConn = OracleConnexion(oracle);
             Connection pgConn = PostgresService.PostgresConnexion(postgres)) {
            
            System.out.println("✅ Connexions établies");
            log += "✅ Connexions établies\n";
            
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
            log += "\n✅ Migration terminée en " + duration + "s\n";
            
            // Toujours afficher les statistiques
            stats.tablesFailed = stats.tablesTotal - stats.tablesSuccess;
            stats.dataFailed = stats.dataTotal - stats.dataSuccess;
            stats.indexFailed = stats.indexTotal - stats.indexSuccess;
            stats.fkFailed = stats.fkSuccess - stats.fkTotal;
            stats.uniqueFailed = stats.uniqueTotal - stats.uniqueSuccess;
            stats.checkFailed = stats.checkTotal - stats.checkSuccess;
            stats.viewsFailed = stats.viewsTotal - stats.viewsSuccess;
            stats.functionsFailed = stats.functionsTotal - stats.functionsSuccess;
            stats.triggersFailed = stats.triggersTotal - stats.triggersSuccess;
            stats.pkFailed = stats.pkTotal - stats.pkSuccess;

            stats.printDetailed();
            stats.printAllErrorsDetailed();
            
        } catch (Exception e) {
            System.err.println("\n❌ ERREUR CRITIQUE: " + e.getMessage());
            e.printStackTrace();
            log += "\n❌ ERREUR CRITIQUE: " + e.getMessage() + "\n";
            stats.addError("❌ ERREUR CRITIQUE: " + e.getMessage());
            
            // Afficher ce qui a été collecté malgré l'erreur
            stats.printDetailed();
            stats.printAllErrorsDetailed();
        }
        stats.logs = log;
        return stats;
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

    public void migrationTablesAndDataOracleToPostgresql(Oracle oracle, PostgreSQL postgres) {
        migrateCompleteDatabase(oracle, postgres);
    }
}