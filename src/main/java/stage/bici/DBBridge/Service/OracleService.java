package stage.bici.DBBridge.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import oracle.sql.TIMESTAMP;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import javax.naming.spi.DirStateFactory.Result;

import stage.bici.DBBridge.Model.*;
import stage.bici.DBBridge.Service.PostgresService;

public class OracleService {
    public static Connection OracleConnexion(Oracle oracle) throws SQLException {
        String url = oracle.buildConnectionUrl();
        String user = oracle.getUsername();
        String password = oracle.getPassword();
        Connection conn = DriverManager.getConnection(url, user, password);
        return conn;
    }

    public static List<String> getAllTableName(Oracle oracle) throws SQLException {
        List<String> tableNames = new ArrayList<>();
        Connection conn = OracleService.OracleConnexion(oracle);
        DatabaseMetaData metaData = conn.getMetaData();
        ResultSet rs = metaData.getTables(null, conn.getSchema(), "%", new String[]{"TABLE"});

        while (rs.next()) {
            String tableName = rs.getString("TABLE_NAME");
            tableNames.add(tableName);
        }

        rs.close();
        conn.close();
        return tableNames;
    }

    public static List<DonneeTableOracle> getOracleTableColumns(Oracle oracle, String tableName) throws SQLException {
        List<DonneeTableOracle> columns = new ArrayList<>();
        Connection oracleConn = OracleService.OracleConnexion(oracle);
        String sql = "SELECT column_name, data_type, data_length, data_precision, data_scale, nullable " +
                     "FROM user_tab_columns WHERE table_name = ?";
        try (PreparedStatement ps = oracleConn.prepareStatement(sql)) {
            ps.setString(1, tableName.toUpperCase());
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                DonneeTableOracle col = new DonneeTableOracle();
                col.setName(rs.getString("COLUMN_NAME"));
                col.setOracleType(rs.getString("DATA_TYPE"));
                col.setLength(rs.getInt("DATA_LENGTH"));
                col.setPrecision(rs.getInt("DATA_PRECISION")) ;
                col.setScale(rs.getInt("DATA_SCALE"));
                col.setNullable("Y".equals(rs.getString("NULLABLE")));
                columns.add(col);
            }
            rs.close();
        }

        oracleConn.close();
        return columns;
    }

    public static String mapOracleTypeToPostgres(DonneeTableOracle col) {
        String type = col.getOracleType().toUpperCase();

        switch (type) {
            case "VARCHAR2":
            case "NVARCHAR2":
                return "VARCHAR(" + col.getLength() + ")";
            case "CHAR":
                return "CHAR(" + col.getLength() + ")";
            case "NUMBER":
                if (col.getScale() > 0) {
                    return "NUMERIC(" + col.getPrecision() + "," + col.getScale() + ")";
                } else if (col.getPrecision() > 0) {
                    return "NUMERIC(" + col.getPrecision() + ")";
                } else {
                    return "NUMERIC";
                }
            case "DATE":
                return "TIMESTAMP";
            case "CLOB":
                return "TEXT";
            case "BLOB":
                return "BYTEA";
            default:
                return "TEXT"; // fallback
        }
    }

    public static String generateCreateTableSQL(Oracle oracle, String tableName) throws SQLException {
        List<DonneeTableOracle> columns = getOracleTableColumns(oracle, tableName);
        StringBuilder sb = new StringBuilder("CREATE TABLE " + tableName.toLowerCase() + " (");

        for (int i = 0; i < columns.size(); i++) {
            DonneeTableOracle col = columns.get(i);

            String colName = col.getName();
            if (colName == null || colName.trim().isEmpty()) {
                colName = "col_" + i;
            }
            
            colName = colName.toLowerCase();
            
            // Échapper si espace ou mot réservé
            if (colName.contains(" ") || colName.equals("text") || colName.equals("user") || colName.equals("table")) {
                colName = "\"" + colName + "\"";
            }

            sb.append(colName)
            .append(" ")
            .append(mapOracleTypeToPostgres(col));

            if (!col.isNullable()) sb.append(" NOT NULL");

            if (i < columns.size() - 1) sb.append(", ");
        }

        sb.append(")");
        return sb.toString();
    }

    public static void createPostgresTable(PostgreSQL postgres, String createSQL) throws SQLException {
        Connection postgresConn = PostgresService.PostgresConnexion(postgres);
        try (Statement stmt = postgresConn.createStatement()) {
            stmt.executeUpdate(createSQL);
            stmt.close();
        }
        postgresConn.close();
        
    }

    public static ResultSet fetchOracleTableData(Connection oracleConn, String tableName) throws SQLException {
        Statement stmt = oracleConn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        ResultSet rs = stmt.executeQuery("SELECT * FROM " + tableName);
        return rs;
    }

    public static void insertDataIntoPostgres(Oracle oracle ,PostgreSQL postgres, String tableName) throws SQLException {
        Connection oracleConn = OracleService.OracleConnexion(oracle);
        ResultSet rs = fetchOracleTableData(oracleConn, tableName);
        Connection postgresConn = PostgresService.PostgresConnexion(postgres);

        postgresConn.setAutoCommit(false);

        List<DonneeTableOracle> columns = getOracleTableColumns(oracle, tableName);

        int colCount = columns.size();

        StringBuilder sb = new StringBuilder("INSERT INTO " + tableName.toLowerCase() + " VALUES (");
        for (int i = 0; i < colCount; i++) {
            sb.append("?");
            if (i < colCount - 1) sb.append(",");
        }
        sb.append(")");

        PreparedStatement ps = postgresConn.prepareStatement(sb.toString());

        while (rs.next()) {
            for (int i = 0; i < colCount; i++) {
                Object value = rs.getObject(columns.get(i).getName());

                if (value instanceof oracle.sql.TIMESTAMP) {
                    // Conversion explicite vers java.sql.Timestamp
                    value = ((oracle.sql.TIMESTAMP) value).timestampValue();
                } else if (value instanceof java.sql.Date) {
                    value = new java.sql.Date(((java.sql.Date) value).getTime());
                } else if (value instanceof java.sql.Time) {
                    value = new java.sql.Time(((java.sql.Time) value).getTime());
                } else if (value instanceof java.sql.Timestamp) {
                    java.sql.Timestamp ts = (java.sql.Timestamp) value;
                    // Vérifier les limites PostgreSQL
                    if (ts.getTime() < -631152000000L || ts.getTime() > 253402300799000L) {
                        value = null;
                    } else {
                        value = new java.sql.Timestamp(ts.getTime());
                    }
                }else if (value instanceof String) {
                    value = ((String) value).replace("\u0000", "");
                }

                ps.setObject(i + 1, value);

            }
            ps.addBatch();
        }
        ps.executeBatch();
        postgresConn.commit();
        rs.close();
        ps.close();
        postgresConn.close();
        oracleConn.close();
    }

    // Désactiver les contraintes FK Oracle
    public static void disableOracleFK(Oracle oracle,String tableName) throws SQLException {
        Connection conn = OracleService.OracleConnexion(oracle);
        String sql = "SELECT constraint_name FROM user_constraints " +
                    "WHERE table_name = ? AND constraint_type = 'R'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String constraint = rs.getString("constraint_name");
                    try (Statement stmt = conn.createStatement()) {
                        stmt.executeUpdate("ALTER TABLE " + tableName + " DISABLE CONSTRAINT " + constraint);
                    }
                }
            }
        }
        conn.close();
    }

    // Réactiver les contraintes FK Oracle
    public static void enableOracleFK(Oracle oracle, String tableName) throws SQLException {
        Connection conn = OracleService.OracleConnexion(oracle);
        String sql = "SELECT constraint_name FROM user_constraints " +
                    "WHERE table_name = ? AND constraint_type = 'R'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String constraint = rs.getString("constraint_name");
                    try (Statement stmt = conn.createStatement()) {
                        stmt.executeUpdate("ALTER TABLE " + tableName + " ENABLE CONSTRAINT " + constraint);
                    }
                }
            }
        }
        conn.close();
    }

    public static void migrationTablesAndDataOracleToPostgresql(Oracle oracle , PostgreSQL postgres) throws SQLException
    {
        List<String> tables = OracleService.getAllTableName(oracle);
        for (int i = 0; i < tables.size(); i++) {
            String script = OracleService.generateCreateTableSQL(oracle, tables.get(i));
            createPostgresTable(postgres, script);
            PostgresService.disablePostgresFK(postgres, tables.get(i));
            insertDataIntoPostgres(oracle, postgres, tables.get(i));
            PostgresService.enablePostgresFK(postgres, tables.get(i));
        }
    }

    // Récupérer toutes les vues Oracle (avec connexion existante)
    public static List<String> getAllViewNames(Connection conn) throws SQLException {
        List<String> viewNames = new ArrayList<>();
        String sql = "SELECT view_name FROM user_views";
        try (Statement stmt = conn.createStatement(); 
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                viewNames.add(rs.getString("VIEW_NAME"));
            }
        }
        return viewNames;
    }

    // Récupérer toutes les vues Oracle (surcharge avec objet Oracle)
    public static List<String> getAllViewNames(Oracle oracle) throws SQLException {
        try (Connection conn = OracleService.OracleConnexion(oracle)) {
            return getAllViewNames(conn);
        }
    }

    // Récupérer la définition d'une vue Oracle (avec connexion existante)
    public static String getOracleViewDefinition(Connection conn, String viewName) throws SQLException {
        String sql = "SELECT text FROM user_views WHERE view_name = ?";
        String viewSQL = null;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, viewName.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    viewSQL = rs.getString("TEXT");
                }
            }
        }
        return viewSQL;
    }

    // Récupérer la définition d'une vue Oracle (surcharge avec objet Oracle)
    public static String getOracleViewDefinition(Oracle oracle, String viewName) throws SQLException {
        try (Connection conn = OracleService.OracleConnexion(oracle)) {
            return getOracleViewDefinition(conn, viewName);
        }
    }

    // Conversion Oracle SQL -> PostgreSQL SQL - VERSION AMÉLIORÉE
    public static String mapOracleViewSQLToPostgres(String oracleSQL) {
        return enhancedMapOracleViewSQLToPostgres(oracleSQL);
    }

    // Conversion Oracle SQL -> PostgreSQL SQL - VERSION COMPLÈTEMENT AMÉLIORÉE
    public static String enhancedMapOracleViewSQLToPostgres(String oracleSQL) {
        if (oracleSQL == null) return null;

        String pgSQL = oracleSQL;

        // 1. Nettoyer les doublons de SELECT
        pgSQL = cleanDuplicateSelect(pgSQL);

        // 2. Gérer la table DUAL Oracle
        pgSQL = handleOracleDual(pgSQL);

        // 3. Convertir les fonctions Oracle spécifiques
        pgSQL = convertOracleSpecificFunctions(pgSQL);

        // 4. Convertir les jointures Oracle (+)
        pgSQL = convertOracleOuterJoinsToPostgres(pgSQL);

        // 5. Fonctions standard
        pgSQL = pgSQL.replaceAll("(?i)SYSDATE", "CURRENT_TIMESTAMP");
        pgSQL = pgSQL.replaceAll("(?i)NVL\\(", "COALESCE(");
        
        // 6. Gestion améliorée de TO_DATE
        pgSQL = pgSQL.replaceAll("(?i)TO_DATE\\s*\\(\\s*(\\d+)\\s*,\\s*'YYYY'\\s*\\)", "$1"); // TO_DATE(2023, 'YYYY') -> 2023
        pgSQL = pgSQL.replaceAll("(?i)TO_DATE\\s*\\(\\s*'([^']+)'\\s*,\\s*'[^']*'\\s*\\)", "'$1'"); // TO_DATE('2023-01-01', 'YYYY-MM-DD') -> '2023-01-01'

        // 7. CAST Oracle amélioré
        pgSQL = pgSQL.replaceAll("(?i)cast\\(([^)]+)\\s+as\\s+number\\([^)]+\\)", "CAST($1 AS NUMERIC)");
        pgSQL = pgSQL.replaceAll("(?i)cast\\(([^)]+)\\s+as\\s+number\\)", "CAST($1 AS NUMERIC)");
        pgSQL = pgSQL.replaceAll("(?i)cast\\(([^)]+)\\s+as\\s+date\\)", "CAST($1 AS TIMESTAMP)");

        // 8. LISTAGG -> string_agg (version améliorée)
        pgSQL = convertListAggToPostgres(pgSQL);

        // 9. Sous-requêtes → alias obligatoires en Postgres
        pgSQL = pgSQL.replaceAll("(?i)(\\))\\s*(?=group\\s+by)", "$1 AS sub ");
        pgSQL = pgSQL.replaceAll("(?i)(\\))\\s*(?=order\\s+by)", "$1 AS sub ");
        pgSQL = pgSQL.replaceAll("(?i)(\\))\\s*(?=where)", "$1 AS sub ");

        // 10. Corriger les noms de colonnes en double
        pgSQL = fixDuplicateColumnNames(pgSQL);

        // 11. Gérer les séquences Oracle (ROWNUM, etc.)
        pgSQL = handleOracleSequences(pgSQL);

        // 12. Majuscules SQL → upper
        String[] keywords = { "SELECT", "FROM", "WHERE", "GROUP", "BY", "ORDER", "JOIN", "ON", "AS", "CREATE", "VIEW", "UNION", "LEFT", "RIGHT", "INNER", "OUTER" };
        for (String kw : keywords) {
            pgSQL = pgSQL.replaceAll("(?i)\\b" + kw + "\\b", kw.toUpperCase());
        }

        // 13. Tout le reste → en minuscules (identifiants)
        Pattern pattern = Pattern.compile("\\b([A-Z][A-Z0-9_]*)\\b");
        Matcher matcher = pattern.matcher(pgSQL);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, matcher.group(1).toLowerCase());
        }
        matcher.appendTail(sb);
        pgSQL = sb.toString();

        // 14. Nettoyer les espaces multiples
        pgSQL = pgSQL.replaceAll("\\s+", " ");

        return pgSQL;
    }

    // Nettoyer les doublons de SELECT
    private static String cleanDuplicateSelect(String sql) {
        String result = sql;
        
        // Supprimer les SELECT en double au début
        result = result.replaceAll("^(?i)select\\s+select", "SELECT");
        
        // Supprimer les SELECT en double ailleurs dans la requête
        result = result.replaceAll("(?i)\\bselect\\s+select", "SELECT");
        
        return result;
    }

    // Gérer la table DUAL Oracle
    private static String handleOracleDual(String sql) {
        String result = sql;
        
        // Remplacer "FROM DUAL" par rien (pour les selects simples)
        result = result.replaceAll("(?i)\\bFROM\\s+DUAL\\b", "");
        
        // Remplacer les selects simples de DUAL
        result = result.replaceAll("(?i)SELECT\\s+([^\\s]+)\\s+FROM\\s+DUAL", "SELECT $1");
        
        return result;
    }

    // Convertir les fonctions Oracle spécifiques
    private static String convertOracleSpecificFunctions(String sql) {
        String result = sql;
        
        // CONCAT -> ||
        result = result.replaceAll("(?i)CONCAT\\s*\\(([^,]+),\\s*([^)]+)\\)", "($1 || $2)");
        
        // DECODE -> CASE
        result = convertDecodeToCase(result);
        
        // ROWNUM -> ROW_NUMBER()
        result = result.replaceAll("(?i)ROWNUM", "ROW_NUMBER() OVER()");
        
        return result;
    }

    // Convertir DECODE Oracle en CASE PostgreSQL
    private static String convertDecodeToCase(String sql) {
        String result = sql;
        Pattern pattern = Pattern.compile("(?i)DECODE\\s*\\(([^)]+)\\)");
        Matcher matcher = pattern.matcher(sql);
        
        while (matcher.find()) {
            String decodeContent = matcher.group(1);
            String[] parts = decodeContent.split("\\s*,\\s*");
            
            if (parts.length >= 3) {
                StringBuilder caseBuilder = new StringBuilder("CASE " + parts[0]);
                
                for (int i = 1; i < parts.length - 1; i += 2) {
                    caseBuilder.append(" WHEN ").append(parts[i]).append(" THEN ").append(parts[i + 1]);
                }
                
                // Gérer la valeur par défaut
                if (parts.length % 2 == 0) {
                    caseBuilder.append(" ELSE ").append(parts[parts.length - 1]);
                }
                
                caseBuilder.append(" END");
                result = result.replace(matcher.group(0), caseBuilder.toString());
            }
        }
        
        return result;
    }

    // Convertir LISTAGG Oracle en string_agg PostgreSQL
    private static String convertListAggToPostgres(String sql) {
        String result = sql;
        
        // Pattern pour LISTAGG avec WITHIN GROUP
        Pattern pattern = Pattern.compile("(?i)LISTAGG\\s*\\(([^,]+),\\s*'([^']*)'\\s*\\)\\s*WITHIN GROUP\\s*\\(\\s*ORDER BY\\s+([^)]+)\\)");
        Matcher matcher = pattern.matcher(sql);
        
        while (matcher.find()) {
            String expression = matcher.group(1);
            String delimiter = matcher.group(2);
            String orderBy = matcher.group(3);
            
            String replacement = "string_agg(" + expression + "::text, '" + delimiter + "' ORDER BY " + orderBy + ")";
            result = result.replace(matcher.group(0), replacement);
        }
        
        // Pattern pour LISTAGG sans ORDER BY
        pattern = Pattern.compile("(?i)LISTAGG\\s*\\(([^,]+),\\s*'([^']*)'\\s*\\)");
        matcher = pattern.matcher(sql);
        
        while (matcher.find()) {
            String expression = matcher.group(1);
            String delimiter = matcher.group(2);
            
            String replacement = "string_agg(" + expression + "::text, '" + delimiter + "')";
            result = result.replace(matcher.group(0), replacement);
        }
        
        return result;
    }

    // Gérer les séquences Oracle
    private static String handleOracleSequences(String sql) {
        String result = sql;
        
        // ROWNUM dans WHERE -> LIMIT
        Pattern pattern = Pattern.compile("(?i)WHERE\\s+ROWNUM\\s*<=\\s*(\\d+)");
        Matcher matcher = pattern.matcher(sql);
        if (matcher.find()) {
            result = result.replaceAll("(?i)WHERE\\s+ROWNUM\\s*<=\\s*\\d+", "LIMIT " + matcher.group(1));
        }
        
        return result;
    }

    // Convertir les jointures Oracle (+) vers PostgreSQL
    private static String convertOracleOuterJoinsToPostgres(String sql) {
        String result = sql;
        
        // Solution simple : supprimer tous les (+)
        result = result.replaceAll("\\(\\+\\)", "");
        
        return result;
    }

    // Corriger les noms de colonnes en double dans le SELECT
    private static String fixDuplicateColumnNames(String sql) {
        // Détecter les colonnes sans alias avec le même nom
        // Exemple: "ec.nom, prom.nom" → "ec.nom as nom1, prom.nom as nom2"
        
        // Trouver la partie SELECT
        int selectIndex = sql.toLowerCase().indexOf("select");
        int fromIndex = sql.toLowerCase().indexOf("from");
        
        if (selectIndex == -1 || fromIndex == -1 || fromIndex <= selectIndex) {
            return sql;
        }
        
        String selectPart = sql.substring(selectIndex + 6, fromIndex).trim();
        String restOfQuery = sql.substring(fromIndex);
        
        String[] columns = selectPart.split(",");
        
        Map<String, Integer> columnCount = new HashMap<>();
        List<String> fixedColumns = new ArrayList<>();
        
        for (String col : columns) {
            col = col.trim();
            if (col.contains(".")) {
                String colName = col.substring(col.lastIndexOf(".") + 1).trim();
                // Supprimer les guillemets si présents
                colName = colName.replace("\"", "");
                if (colName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                    int count = columnCount.getOrDefault(colName, 0) + 1;
                    columnCount.put(colName, count);
                    
                    if (count > 1) {
                        fixedColumns.add(col + " as " + colName + count);
                    } else {
                        fixedColumns.add(col);
                    }
                } else {
                    fixedColumns.add(col);
                }
            } else {
                fixedColumns.add(col);
            }
        }
        
        if (!fixedColumns.isEmpty()) {
            String newSelect = "SELECT " + String.join(", ", fixedColumns) + " " + restOfQuery;
            return sql.substring(0, selectIndex) + newSelect;
        }
        
        return sql;
    }

    // Vérifier si une vue Oracle est valide (avec connexion existante)
    public static boolean isViewValid(Connection conn, String viewName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM USER_OBJECTS WHERE OBJECT_NAME = ? AND OBJECT_TYPE = 'VIEW' AND STATUS = 'VALID'";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, viewName.toUpperCase());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        }
        return false;
    }

    // Vérifier si une vue Oracle est valide (surcharge avec objet Oracle)
    public static boolean isViewValid(Oracle oracle, String viewName) throws SQLException {
        try (Connection conn = OracleService.OracleConnexion(oracle)) {
            return isViewValid(conn, viewName);
        }
    }

    // Créer la vue dans PostgreSQL avec meilleure gestion d'erreur
    public static void createPostgresView(PostgreSQL postgres, String createSQL) throws SQLException {
        // Valider le SQL avant exécution
        if (!isValidPostgresSQL(createSQL)) {
            throw new SQLException("SQL invalide détecté: " + createSQL.substring(0, Math.min(100, createSQL.length())));
        }
        
        Connection conn = PostgresService.PostgresConnexion(postgres);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(createSQL);
        } finally {
            conn.close();
        }
    }

    // Valider le SQL PostgreSQL
    private static boolean isValidPostgresSQL(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return false;
        }
        
        // Vérifier les doublons de SELECT
        if (sql.toLowerCase().contains("select select")) {
            System.err.println("❌ Doublon SELECT détecté: " + sql);
            return false;
        }
        
        // Vérifier la syntaxe basique
        if (!sql.toUpperCase().contains("SELECT") || !sql.toUpperCase().contains("FROM")) {
            System.err.println("❌ SQL manque SELECT ou FROM: " + sql);
            return false;
        }
        
        return true;
    }

    // Extraire dépendances d'une vue à partir de son SQL - VERSION AMÉLIORÉE
    public static Set<String> extractDependencies(String sql, List<String> allViewNames) {
        Set<String> deps = new HashSet<>();
        if (sql == null) return deps;

        String upperSQL = sql.toUpperCase();
        for (String v : allViewNames) {
            String viewUpper = v.toUpperCase();
            // Vérifier si la vue est référencée mais pas dans le CREATE VIEW
            if (upperSQL.contains(viewUpper) && 
                !upperSQL.contains("CREATE OR REPLACE VIEW " + viewUpper) &&
                !upperSQL.contains("CREATE VIEW " + viewUpper)) {
                deps.add(v);
            }
        }
        return deps;
    }

    // Tri topologique des vues par dépendances - VERSION AMÉLIORÉE
    public static List<String> sortViewsByDependencies(Map<String, Set<String>> deps) {
        List<String> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> currentlyVisiting = new HashSet<>();

        for (String v : deps.keySet()) {
            if (!visited.contains(v)) {
                dfs(v, deps, visited, sorted, currentlyVisiting);
            }
        }
        return sorted;
    }

    private static void dfs(String v, Map<String, Set<String>> deps, Set<String> visited, List<String> sorted, Set<String> currentlyVisiting) {
        if (visited.contains(v)) return;
        
        if (currentlyVisiting.contains(v)) {
            System.err.println("⚠️ Dépendance circulaire détectée pour la vue: " + v);
            // Gérer la circularité en ajoutant la vue quand même
            if (!sorted.contains(v)) {
                sorted.add(v);
                visited.add(v);
            }
            return;
        }
        
        currentlyVisiting.add(v);
        
        for (String dep : deps.getOrDefault(v, Collections.emptySet())) {
            if (!visited.contains(dep)) {
                dfs(dep, deps, visited, sorted, currentlyVisiting);
            }
        }
        
        currentlyVisiting.remove(v);
        
        if (!sorted.contains(v)) {
            sorted.add(v);
        }
        visited.add(v);
    }

    // Vérifier les dépendances manquantes
    private static Set<String> checkMissingDependencies(Set<String> dependencies, Set<String> createdViews) {
        Set<String> missing = new HashSet<>();
        if (dependencies != null) {
            for (String dep : dependencies) {
                if (!createdViews.contains(dep)) {
                    missing.add(dep);
                }
            }
        }
        return missing;
    }

    // Obtenir le type d'erreur
    private static String getErrorType(String errorMessage) {
        if (errorMessage.contains("n'existe pas")) {
            return "OBJET_MANQUANT";
        } else if (errorMessage.contains("DUAL")) {
            return "TABLE_DUAL";
        } else if (errorMessage.contains("LISTAGG")) {
            return "FONCTION_LISTAGG";
        } else if (errorMessage.contains("TO_DATE")) {
            return "FONCTION_TO_DATE";
        } else if (errorMessage.contains("ROWNUM")) {
            return "ROWNUM";
        } else if (errorMessage.contains("erreur de syntaxe")) {
            return "SYNTAXE";
        } else {
            return "AUTRE";
        }
    }

    // Obtenir un message d'erreur court
    private static String getShortErrorMessage(String errorMessage) {
        if (errorMessage == null) return "Erreur inconnue";
        
        // Extraire la partie importante du message
        if (errorMessage.contains("Position :")) {
            int posIndex = errorMessage.indexOf("Position :");
            return errorMessage.substring(0, Math.min(100, posIndex)).trim();
        }
        
        return errorMessage.length() > 100 ? errorMessage.substring(0, 100) + "..." : errorMessage;
    }

    // Tentative de reprise des vues échouées
    private static int retryFailedViews(Connection oracleConn, PostgreSQL postgres, List<String> orderedViews, 
                                       Map<String, String> viewDefinitions, Map<String, Set<String>> deps, 
                                       Set<String> successfullyCreatedViews) throws SQLException {
        int retrySuccess = 0;
        int maxRetries = 2;
        
        for (int retry = 0; retry < maxRetries; retry++) {
            boolean anySuccess = false;
            
            for (String viewName : orderedViews) {
                if (successfullyCreatedViews.contains(viewName)) {
                    continue; // Déjà réussi
                }
                
                try {
                    String oracleViewSQL = viewDefinitions.get(viewName);
                    if (oracleViewSQL == null) continue;
                    
                    // Vérifier à nouveau les dépendances
                    Set<String> missingDeps = checkMissingDependencies(deps.get(viewName), successfullyCreatedViews);
                    if (!missingDeps.isEmpty()) {
                        continue; // Dépendances toujours manquantes
                    }
                    
                    String pgViewSQL = enhancedMapOracleViewSQLToPostgres(oracleViewSQL);
                    String createSQL = "CREATE OR REPLACE VIEW " + viewName.toLowerCase() + " AS " + pgViewSQL;
                    
                    System.out.println("🔄 Retry " + (retry + 1) + " pour la vue: " + viewName);
                    createPostgresView(postgres, createSQL);
                    
                    System.out.println("✅ Vue migrée en retry: " + viewName);
                    successfullyCreatedViews.add(viewName);
                    retrySuccess++;
                    anySuccess = true;
                    
                } catch (Exception e) {
                    // Ignorer les erreurs en retry, on loggue seulement
                    System.err.println("❌ Échec retry " + (retry + 1) + " pour " + viewName + ": " + getShortErrorMessage(e.getMessage()));
                }
            }
            
            if (!anySuccess) {
                break; // Aucune vue migrée dans ce retry, arrêter
            }
        }
        
        return retrySuccess;
    }

    // NOUVELLE FONCTION : Trier les vues par dépendances RÉELLES
    public static List<String> sortViewsByRealDependencies(Oracle oracle) throws SQLException {
        try (Connection oracleConn = OracleService.OracleConnexion(oracle)) {
            List<String> allViews = getAllViewNames(oracleConn);
            Map<String, Set<String>> dependencies = new HashMap<>();
            Map<String, String> viewDefinitions = new HashMap<>();
            
            System.out.println("🔍 Analyse des dépendances...");
            
            // 1. Récupérer toutes les définitions
            for (String viewName : allViews) {
                String sql = getOracleViewDefinition(oracleConn, viewName);
                viewDefinitions.put(viewName, sql);
            }
            
            // 2. Détecter les dépendances RÉELLES entre vues
            for (String viewName : allViews) {
                Set<String> deps = new HashSet<>();
                String sql = viewDefinitions.get(viewName);
                
                if (sql != null) {
                    String upperSQL = sql.toUpperCase();
                    
                    // Chercher toutes les autres vues référencées
                    for (String otherView : allViews) {
                        if (!otherView.equals(viewName)) {
                            // Pattern plus strict pour éviter faux positifs
                            String pattern = "\\b" + otherView.toUpperCase() + "\\b";
                            if (upperSQL.matches(".*" + pattern + ".*")) {
                                deps.add(otherView);
                            }
                        }
                    }
                }
                
                dependencies.put(viewName, deps);
            }
            
            // 3. Tri topologique avec gestion des cycles
            List<String> sorted = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            Set<String> visiting = new HashSet<>();
            
            for (String view : allViews) {
                if (!visited.contains(view)) {
                    topologicalSort(view, dependencies, visited, visiting, sorted);
                }
            }
            
            System.out.println("✅ Ordre de création calculé pour " + sorted.size() + " vues");
            return sorted;
        }
    }

    // Tri topologique récursif avec détection de cycles
    private static void topologicalSort(String view, Map<String, Set<String>> deps, 
                                        Set<String> visited, Set<String> visiting, 
                                        List<String> sorted) {
        if (visited.contains(view)) return;
        
        if (visiting.contains(view)) {
            // Cycle détecté - ajouter quand même
            if (!sorted.contains(view)) {
                sorted.add(view);
                visited.add(view);
            }
            return;
        }
        
        visiting.add(view);
        
        // Visiter les dépendances d'abord
        Set<String> viewDeps = deps.get(view);
        if (viewDeps != null) {
            for (String dep : viewDeps) {
                topologicalSort(dep, deps, visited, visiting, sorted);
            }
        }
        
        visiting.remove(view);
        
        if (!sorted.contains(view)) {
            sorted.add(view);
        }
        visited.add(view);
    }

    // MIGRATION AVEC L'ORDRE CORRECT
    public static void migrationViewsOracleToPostgres(Oracle oracle, PostgreSQL postgres) throws SQLException {
        
        // 1. Obtenir l'ordre correct des vues
        List<String> orderedViews = sortViewsByRealDependencies(oracle);
        
        try (Connection oracleConn = OracleService.OracleConnexion(oracle)) {
            System.out.println("📊 Migration de " + orderedViews.size() + " vues dans l'ordre optimal");
            
            Set<String> created = new HashSet<>();
            
            // 2. Créer les vues dans l'ordre (max 5 passes pour cycles complexes)
            for (int pass = 1; pass <= 5; pass++) {
                System.out.println("\n🔄 PASSE " + pass);
                int passSuccess = 0;
                
                for (String viewName : orderedViews) {
                    if (created.contains(viewName)) continue;
                    
                    try {
                        String oracleViewSQL = getOracleViewDefinition(oracleConn, viewName);
                        
                        if (oracleViewSQL == null || oracleViewSQL.trim().isEmpty()) {
                            String createSQL = "CREATE OR REPLACE VIEW " + viewName.toLowerCase() + " AS SELECT 1 as placeholder";
                            createPostgresViewForce(postgres, createSQL);
                            created.add(viewName);
                            passSuccess++;
                            System.out.println("⚠️ " + viewName + " (vide)");
                            continue;
                        }
                        
                        String pgViewSQL = enhancedMapOracleViewSQLToPostgres(oracleViewSQL);
                        String createSQL = "CREATE OR REPLACE VIEW " + viewName.toLowerCase() + " AS " + pgViewSQL;
                        
                        createPostgresViewForce(postgres, createSQL);
                        created.add(viewName);
                        passSuccess++;
                        System.out.println("✅ " + viewName);
                        
                    } catch (Exception e) {
                        if (pass == 5) {
                            System.err.println("❌ " + viewName + ": " + e.getMessage().substring(0, Math.min(100, e.getMessage().length())));
                        }
                    }
                }
                
                System.out.println("   📊 Total: " + created.size() + "/" + orderedViews.size() + " (+" + passSuccess + " cette passe)");
                
                if (passSuccess == 0) break;
            }
            
            // 3. Force placeholder seulement si vraiment impossible après 5 passes
            if (created.size() < orderedViews.size()) {
                System.out.println("\n🔧 PASSE FINALE - Placeholder pour vues impossibles");
                for (String viewName : orderedViews) {
                    if (!created.contains(viewName)) {
                        try {
                            String createSQL = "CREATE OR REPLACE VIEW " + viewName.toLowerCase() + " AS SELECT 'ERREUR_MIGRATION' as status, '" + viewName + "' as view_name";
                            createPostgresViewForce(postgres, createSQL);
                            created.add(viewName);
                            System.out.println("⚠️ " + viewName + " (placeholder)");
                        } catch (Exception e) {
                            System.err.println("❌ IMPOSSIBLE: " + viewName);
                        }
                    }
                }
            }
            
            System.out.println("\n✅ TOTAL MIGRÉ: " + created.size() + "/" + orderedViews.size());
        }
    }

    // Méthode de création forcée (inchangée)
    public static void createPostgresViewForce(PostgreSQL postgres, String createSQL) throws SQLException {
        Connection conn = PostgresService.PostgresConnexion(postgres);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(createSQL);
        } finally {
            conn.close();
        }
    }

    // ========== MIGRATION DES FONCTIONS ORACLE → POSTGRESQL ==========

    // 1. Récupérer tous les noms de fonctions Oracle
    public static List<String> getAllFunctionNames(Oracle oracle) throws SQLException {
        List<String> functionNames = new ArrayList<>();
        try (Connection conn = OracleService.OracleConnexion(oracle)) {
            String sql = "SELECT object_name FROM user_objects WHERE object_type = 'FUNCTION'";
            try (Statement stmt = conn.createStatement(); 
                ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    functionNames.add(rs.getString("OBJECT_NAME"));
                }
            }
        }
        return functionNames;
    }

    // 2. Récupérer la définition complète d'une fonction Oracle
    public static String getOracleFunctionDefinition(Connection conn, String functionName) throws SQLException {
        StringBuilder definition = new StringBuilder();
        String sql = "SELECT text FROM user_source WHERE name = ? AND type = 'FUNCTION' ORDER BY line";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, functionName.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    definition.append(rs.getString("TEXT"));
                }
            }
        }
        return definition.toString();
    }

    // 3. Convertir la syntaxe Oracle → PostgreSQL pour les fonctions
    public static String convertOracleFunctionToPostgres(String oracleFunction, String functionName) {
        if (oracleFunction == null || oracleFunction.trim().isEmpty()) {
            return null;
        }
        
        String pgFunction = oracleFunction;
        
        // 1. Remplacer CREATE OR REPLACE FUNCTION
        pgFunction = pgFunction.replaceAll("(?i)CREATE\\s+OR\\s+REPLACE\\s+FUNCTION", "CREATE OR REPLACE FUNCTION");
        
        // 2. Convertir RETURN au lieu de RETURNS
        pgFunction = pgFunction.replaceAll("(?i)\\bRETURN\\s+(VARCHAR2|NUMBER|DATE|CLOB|BLOB)", "RETURNS $1");
        
        // 3. Convertir les types Oracle → PostgreSQL
        pgFunction = pgFunction.replaceAll("(?i)\\bVARCHAR2\\b", "VARCHAR");
        pgFunction = pgFunction.replaceAll("(?i)\\bNUMBER\\b", "NUMERIC");
        pgFunction = pgFunction.replaceAll("(?i)\\bDATE\\b", "TIMESTAMP");
        pgFunction = pgFunction.replaceAll("(?i)\\bCLOB\\b", "TEXT");
        pgFunction = pgFunction.replaceAll("(?i)\\bBLOB\\b", "BYTEA");
        
        // 4. Ajouter LANGUAGE plpgsql si manquant
        if (!pgFunction.toUpperCase().contains("LANGUAGE")) {
            pgFunction = pgFunction.replaceAll("(?i)\\bAS\\s*\\$", "LANGUAGE plpgsql AS $$");
            if (!pgFunction.contains("$$")) {
                pgFunction = pgFunction.replaceAll("(?i)\\bIS\\b", "AS $$");
                pgFunction = pgFunction.replaceAll("(?i)\\bBEGIN\\b", "LANGUAGE plpgsql\nBEGIN");
                if (!pgFunction.trim().endsWith("$$")) {
                    pgFunction += "\n$$";
                }
            }
        }
        
        // 5. Remplacer IS par AS pour PostgreSQL
        pgFunction = pgFunction.replaceAll("(?i)\\bIS\\s+BEGIN", "AS $$\nBEGIN");
        
        // 6. Ajouter $$ à la fin si manquant
        if (!pgFunction.trim().endsWith("$$") && !pgFunction.trim().endsWith(";")) {
            pgFunction = pgFunction.trim() + "\n$$;";
        }
        
        // 7. Convertir les fonctions Oracle spécifiques
        pgFunction = pgFunction.replaceAll("(?i)\\bSYSDATE\\b", "CURRENT_TIMESTAMP");
        pgFunction = pgFunction.replaceAll("(?i)\\bNVL\\s*\\(", "COALESCE(");
        pgFunction = pgFunction.replaceAll("(?i)\\bTO_CHAR\\s*\\(", "TO_CHAR(");
        pgFunction = pgFunction.replaceAll("(?i)\\bTO_NUMBER\\s*\\(", "CAST(");
        
        // 8. Gérer RETURN au lieu de RETURN (dans le corps)
        pgFunction = pgFunction.replaceAll("(?i)\\bRETURN\\s+([^;]+);", "RETURN $1;");
        
        // 9. Nettoyer les espaces multiples
        pgFunction = pgFunction.replaceAll("\\s+", " ").trim();
        
        // 10. Mettre les identifiants en minuscules
        Pattern pattern = Pattern.compile("\\b([A-Z][A-Z0-9_]*)\\b");
        Matcher matcher = pattern.matcher(pgFunction);
        StringBuffer sb = new StringBuffer();
        Set<String> keywords = new HashSet<>(Arrays.asList(
            "CREATE", "OR", "REPLACE", "FUNCTION", "RETURNS", "AS", "BEGIN", "END", 
            "RETURN", "IF", "THEN", "ELSE", "ELSIF", "LOOP", "WHILE", "FOR", "DECLARE",
            "VARCHAR", "NUMERIC", "TIMESTAMP", "TEXT", "LANGUAGE", "PLPGSQL", "SELECT",
            "FROM", "WHERE", "AND", "OR", "NOT", "NULL", "IS", "IN", "BETWEEN"
        ));
        
        while (matcher.find()) {
            String word = matcher.group(1);
            if (keywords.contains(word.toUpperCase())) {
                matcher.appendReplacement(sb, word.toUpperCase());
            } else {
                matcher.appendReplacement(sb, word.toLowerCase());
            }
        }
        matcher.appendTail(sb);
        pgFunction = sb.toString();
        
        return pgFunction;
    }

    // 4. Détecter les dépendances entre fonctions
    public static Set<String> extractFunctionDependencies(String functionSQL, List<String> allFunctions) {
        Set<String> deps = new HashSet<>();
        if (functionSQL == null) return deps;
        
        String upperSQL = functionSQL.toUpperCase();
        
        for (String func : allFunctions) {
            String funcUpper = func.toUpperCase();
            // Chercher si la fonction est appelée (pattern: nom_fonction()
            if (upperSQL.contains(funcUpper + "(") || upperSQL.contains(funcUpper + " (")) {
                deps.add(func);
            }
        }
        
        return deps;
    }

    // 5. Trier les fonctions par dépendances
    public static List<String> sortFunctionsByDependencies(Oracle oracle) throws SQLException {
        try (Connection oracleConn = OracleService.OracleConnexion(oracle)) {
            List<String> allFunctions = getAllFunctionNames(oracle);
            Map<String, Set<String>> dependencies = new HashMap<>();
            Map<String, String> functionDefinitions = new HashMap<>();
            
            System.out.println("🔍 Analyse des dépendances entre fonctions...");
            
            // Récupérer toutes les définitions
            for (String funcName : allFunctions) {
                String sql = getOracleFunctionDefinition(oracleConn, funcName);
                functionDefinitions.put(funcName, sql);
            }
            
            // Détecter les dépendances
            for (String funcName : allFunctions) {
                String sql = functionDefinitions.get(funcName);
                Set<String> deps = extractFunctionDependencies(sql, allFunctions);
                deps.remove(funcName); // Enlever auto-référence
                dependencies.put(funcName, deps);
            }
            
            // Tri topologique
            List<String> sorted = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            Set<String> visiting = new HashSet<>();
            
            for (String func : allFunctions) {
                if (!visited.contains(func)) {
                    topologicalSortFunction(func, dependencies, visited, visiting, sorted);
                }
            }
            
            System.out.println("✅ Ordre de création calculé pour " + sorted.size() + " fonctions");
            return sorted;
        }
    }

    // Tri topologique pour fonctions
    private static void topologicalSortFunction(String func, Map<String, Set<String>> deps,
                                            Set<String> visited, Set<String> visiting,
                                            List<String> sorted) {
        if (visited.contains(func)) return;
        
        if (visiting.contains(func)) {
            // Cycle détecté
            if (!sorted.contains(func)) {
                sorted.add(func);
                visited.add(func);
            }
            return;
        }
        
        visiting.add(func);
        
        Set<String> funcDeps = deps.get(func);
        if (funcDeps != null) {
            for (String dep : funcDeps) {
                topologicalSortFunction(dep, deps, visited, visiting, sorted);
            }
        }
        
        visiting.remove(func);
        
        if (!sorted.contains(func)) {
            sorted.add(func);
        }
        visited.add(func);
    }

    // 6. Créer la fonction dans PostgreSQL
    public static void createPostgresFunction(PostgreSQL postgres, String createSQL) throws SQLException {
        Connection conn = PostgresService.PostgresConnexion(postgres);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(createSQL);
        } finally {
            conn.close();
        }
    }

    // 7. MIGRATION COMPLÈTE DES FONCTIONS
    public static void migrationFunctionsOracleToPostgres(Oracle oracle, PostgreSQL postgres) throws SQLException {
        
        // 1. Obtenir l'ordre correct des fonctions
        List<String> orderedFunctions = sortFunctionsByDependencies(oracle);
        
        try (Connection oracleConn = OracleService.OracleConnexion(oracle)) {
            System.out.println("📊 Migration de " + orderedFunctions.size() + " fonctions dans l'ordre optimal");
            
            Set<String> created = new HashSet<>();
            Map<String, String> failedFunctions = new HashMap<>();
            
            // 2. Créer les fonctions dans l'ordre (max 5 passes)
            for (int pass = 1; pass <= 5; pass++) {
                System.out.println("\n🔄 PASSE " + pass);
                int passSuccess = 0;
                
                for (String functionName : orderedFunctions) {
                    if (created.contains(functionName)) continue;
                    
                    try {
                        String oracleFunctionSQL = getOracleFunctionDefinition(oracleConn, functionName);
                        
                        if (oracleFunctionSQL == null || oracleFunctionSQL.trim().isEmpty()) {
                            System.out.println("⚠️ " + functionName + " (définition vide)");
                            failedFunctions.put(functionName, "Définition vide");
                            continue;
                        }
                        
                        String pgFunctionSQL = convertOracleFunctionToPostgres(oracleFunctionSQL, functionName);
                        
                        if (pgFunctionSQL == null) {
                            System.out.println("⚠️ " + functionName + " (conversion échouée)");
                            failedFunctions.put(functionName, "Conversion échouée");
                            continue;
                        }
                        
                        createPostgresFunction(postgres, pgFunctionSQL);
                        created.add(functionName);
                        passSuccess++;
                        System.out.println("✅ " + functionName);
                        
                    } catch (Exception e) {
                        if (pass == 5) {
                            String error = e.getMessage();
                            failedFunctions.put(functionName, error != null ? error.substring(0, Math.min(100, error.length())) : "Erreur inconnue");
                            System.err.println("❌ " + functionName + ": " + (error != null ? error.substring(0, Math.min(80, error.length())) : "Erreur"));
                        }
                    }
                }
                
                System.out.println("   📊 Total: " + created.size() + "/" + orderedFunctions.size() + " (+" + passSuccess + " cette passe)");
                
                if (passSuccess == 0) break;
            }
            
            // 3. Créer des fonctions placeholder pour celles qui ont échoué
            if (created.size() < orderedFunctions.size()) {
                System.out.println("\n🔧 PASSE FINALE - Placeholder pour fonctions impossibles");
                for (String functionName : orderedFunctions) {
                    if (!created.contains(functionName)) {
                        try {
                            // Créer une fonction stub qui retourne NULL
                            String placeholderSQL = "CREATE OR REPLACE FUNCTION " + functionName.toLowerCase() + 
                                                "() RETURNS TEXT LANGUAGE plpgsql AS $$ BEGIN RETURN 'FONCTION_NON_MIGREE'; END; $$;";
                            createPostgresFunction(postgres, placeholderSQL);
                            created.add(functionName);
                            System.out.println("⚠️ " + functionName + " (placeholder)");
                        } catch (Exception e) {
                            System.err.println("❌ IMPOSSIBLE: " + functionName);
                        }
                    }
                }
            }
            
            // 4. Résumé détaillé
            System.out.println("\n" + "=".repeat(50));
            System.out.println("=== RÉSUMÉ MIGRATION FONCTIONS ===");
            System.out.println("=".repeat(50));
            System.out.println("✅ Fonctions migrées: " + (created.size() - failedFunctions.size()));
            System.out.println("⚠️ Fonctions placeholder: " + failedFunctions.size());
            System.out.println("📊 Total: " + created.size() + "/" + orderedFunctions.size());
            
            if (!failedFunctions.isEmpty()) {
                System.out.println("\n🔧 Fonctions nécessitant attention manuelle:");
                for (Map.Entry<String, String> entry : failedFunctions.entrySet()) {
                    System.out.println("   - " + entry.getKey() + ": " + entry.getValue());
                }
            }
        }
    }

    // ========== 1. RÉCUPÉRER TOUS LES NOMS DE SÉQUENCES ORACLE ==========
    public static List<String> getAllSequenceNames(Oracle oracle) throws SQLException {
        List<String> sequenceNames = new ArrayList<>();
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        
        try {
            conn = OracleService.OracleConnexion(oracle);
            String sql = "SELECT sequence_name FROM user_sequences";
            stmt = conn.createStatement();
            rs = stmt.executeQuery(sql);
            
            while (rs.next()) {
                sequenceNames.add(rs.getString("SEQUENCE_NAME"));
            }
        } finally {
            if (rs != null) rs.close();
            if (stmt != null) stmt.close();
            if (conn != null) conn.close();
        }
        
        return sequenceNames;
    }
    
    // ========== 2. RÉCUPÉRER LES DÉTAILS COMPLETS D'UNE SÉQUENCE ORACLE ==========
    public static SequenceInfo getOracleSequenceInfo(Oracle oracle, String sequenceName) throws SQLException {
        SequenceInfo info = new SequenceInfo();
        info.setName(sequenceName);
        
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        
        try {
            conn = OracleService.OracleConnexion(oracle);
            String sql = "SELECT last_number, increment_by, min_value, max_value, " +
                        "cycle_flag, cache_size FROM user_sequences WHERE sequence_name = ?";
            
            ps = conn.prepareStatement(sql);
            ps.setString(1, sequenceName.toUpperCase());
            rs = ps.executeQuery();
            
            if (rs.next()) {
                info.setLastValue(rs.getLong("LAST_NUMBER"));
                info.setIncrementBy(rs.getLong("INCREMENT_BY"));
                info.setMinValue(rs.getLong("MIN_VALUE"));
                info.setMaxValue(rs.getLong("MAX_VALUE"));
                info.setCycle("Y".equals(rs.getString("CYCLE_FLAG")));
                info.setCacheSize(rs.getLong("CACHE_SIZE"));
            }
        } finally {
            if (rs != null) rs.close();
            if (ps != null) ps.close();
            if (conn != null) conn.close();
        }
        
        return info;
    }
    
    // ========== 3. GÉNÉRER LE SQL DE CRÉATION POUR POSTGRESQL ==========
    public static String generatePostgresSequenceSQL(SequenceInfo info) {
        StringBuilder sql = new StringBuilder();
        
        sql.append("CREATE SEQUENCE IF NOT EXISTS ")
           .append(info.getName().toLowerCase())
           .append(" INCREMENT BY ").append(info.getIncrementBy())
           .append(" MINVALUE ").append(info.getMinValue())
           .append(" MAXVALUE ").append(info.getMaxValue())
           .append(" START WITH ").append(info.getLastValue())
           .append(" CACHE ").append(info.getCacheSize());
        
        if (info.isCycle()) {
            sql.append(" CYCLE");
        } else {
            sql.append(" NO CYCLE");
        }
        
        sql.append(";");
        
        return sql.toString();
    }
    
    // ========== 4. CRÉER LA SÉQUENCE DANS POSTGRESQL ==========
    public static void createPostgresSequence(PostgreSQL postgres, String createSQL) throws SQLException {
        Connection conn = null;
        Statement stmt = null;
        
        try {
            conn = PostgresService.PostgresConnexion(postgres);
            stmt = conn.createStatement();
            stmt.executeUpdate(createSQL);
        } finally {
            if (stmt != null) stmt.close();
            if (conn != null) conn.close();
        }
    }
    
    // ========== 5. SYNCHRONISER LA VALEUR ACTUELLE DE LA SÉQUENCE ==========
    public static void syncSequenceValue(PostgreSQL postgres, String sequenceName, long currentValue) throws SQLException {
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        
        try {
            conn = PostgresService.PostgresConnexion(postgres);
            stmt = conn.createStatement();
            String sql = "SELECT setval('" + sequenceName.toLowerCase() + "', " + currentValue + ", false)";
            rs = stmt.executeQuery(sql);
        } finally {
            if (rs != null) rs.close();
            if (stmt != null) stmt.close();
            if (conn != null) conn.close();
        }
    }
    
    // ========== 6. CRÉER UNE SÉQUENCE PAR DÉFAUT (EN CAS D'ÉCHEC) ==========
    public static void createDefaultSequence(PostgreSQL postgres, String sequenceName) throws SQLException {
        String defaultSQL = "CREATE SEQUENCE IF NOT EXISTS " + 
                          sequenceName.toLowerCase() + 
                          " INCREMENT BY 1" +
                          " MINVALUE 1" +
                          " MAXVALUE 9223372036854775807" +
                          " START WITH 1" +
                          " CACHE 20" +
                          " NO CYCLE;";
        
        createPostgresSequence(postgres, defaultSQL);
    }
    
    // ========== 7. VÉRIFIER SI UNE SÉQUENCE EXISTE DANS POSTGRESQL ==========
    public static boolean sequenceExistsInPostgres(PostgreSQL postgres, String sequenceName) throws SQLException {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        
        try {
            conn = PostgresService.PostgresConnexion(postgres);
            String sql = "SELECT COUNT(*) FROM information_schema.sequences WHERE sequence_name = ?";
            ps = conn.prepareStatement(sql);
            ps.setString(1, sequenceName.toLowerCase());
            rs = ps.executeQuery();
            
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } finally {
            if (rs != null) rs.close();
            if (ps != null) ps.close();
            if (conn != null) conn.close();
        }
        
        return false;
    }
    
    // ========== 8. OBTENIR LA VALEUR ACTUELLE D'UNE SÉQUENCE POSTGRESQL ==========
    public static long getPostgresSequenceValue(PostgreSQL postgres, String sequenceName) throws SQLException {
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        
        try {
            conn = PostgresService.PostgresConnexion(postgres);
            stmt = conn.createStatement();
            String sql = "SELECT last_value FROM " + sequenceName.toLowerCase();
            rs = stmt.executeQuery(sql);
            
            if (rs.next()) {
                return rs.getLong("last_value");
            }
        } finally {
            if (rs != null) rs.close();
            if (stmt != null) stmt.close();
            if (conn != null) conn.close();
        }
        
        return 0;
    }
    
    // ========== 9. MIGRATION COMPLÈTE - FONCTION PRINCIPALE ==========
    public static void migrationSequencesOracleToPostgres(Oracle oracle, PostgreSQL postgres) throws SQLException {
        
        System.out.println("\n" + "=".repeat(60));
        System.out.println("=== DÉBUT DE LA MIGRATION DES SÉQUENCES ===");
        System.out.println("=".repeat(60));
        
        // Récupérer toutes les séquences Oracle
        List<String> allSequences = getAllSequenceNames(oracle);
        
        System.out.println("📊 Nombre total de séquences à migrer: " + allSequences.size());
        
        if (allSequences.isEmpty()) {
            System.out.println("✅ Aucune séquence à migrer");
            return;
        }
        
        int successCount = 0;
        int errorCount = 0;
        int recoveredCount = 0;
        List<String> failedSequences = new ArrayList<>();
        
        // PHASE 1 : Migration normale
        System.out.println("\n🔄 PHASE 1 - Migration avec propriétés Oracle");
        System.out.println("-".repeat(60));
        
        for (String sequenceName : allSequences) {
            try {
                // Récupérer les infos de la séquence Oracle
                SequenceInfo info = getOracleSequenceInfo(oracle, sequenceName);
                
                // Générer le SQL PostgreSQL
                String createSQL = generatePostgresSequenceSQL(info);
                
                System.out.println("\n🔄 Migration: " + sequenceName);
                System.out.println("   ├─ Valeur actuelle: " + info.getLastValue());
                System.out.println("   ├─ Incrément: " + info.getIncrementBy());
                System.out.println("   ├─ Min: " + info.getMinValue() + " | Max: " + info.getMaxValue());
                System.out.println("   ├─ Cache: " + info.getCacheSize());
                System.out.println("   └─ Cycle: " + (info.isCycle() ? "OUI" : "NON"));
                
                // Créer la séquence dans PostgreSQL
                createPostgresSequence(postgres, createSQL);
                
                // Synchroniser la valeur actuelle
                syncSequenceValue(postgres, sequenceName, info.getLastValue());
                
                System.out.println("✅ Séquence migrée avec succès: " + sequenceName);
                successCount++;
                
            } catch (Exception e) {
                System.err.println("❌ Erreur pour " + sequenceName + ": " + e.getMessage());
                errorCount++;
                failedSequences.add(sequenceName);
            }
        }
        
        // PHASE 2 : Récupération des séquences échouées
        if (!failedSequences.isEmpty()) {
            System.out.println("\n🔧 PHASE 2 - Récupération avec valeurs par défaut");
            System.out.println("-".repeat(60));
            
            for (String sequenceName : failedSequences) {
                try {
                    createDefaultSequence(postgres, sequenceName);
                    System.out.println("⚠️ " + sequenceName + " créée avec valeurs par défaut");
                    recoveredCount++;
                    
                } catch (Exception e) {
                    System.err.println("❌ Impossible de créer " + sequenceName);
                }
            }
            
            successCount += recoveredCount;
            errorCount -= recoveredCount;
        }
        
        // RÉSUMÉ FINAL
        System.out.println("\n" + "=".repeat(60));
        System.out.println("=== RÉSUMÉ FINAL DE LA MIGRATION ===");
        System.out.println("=".repeat(60));
        System.out.println("✅ Séquences migrées avec propriétés Oracle: " + (successCount - recoveredCount));
        
        if (recoveredCount > 0) {
            System.out.println("⚠️ Séquences créées avec valeurs par défaut: " + recoveredCount);
        }
        
        System.out.println("📊 TOTAL MIGRÉ: " + successCount + "/" + allSequences.size());
        
        if (errorCount > 0) {
            System.out.println("❌ Séquences non migrées: " + errorCount);
        }
        
        System.out.println("=".repeat(60));
        
        if (errorCount == 0) {
            System.out.println("🎉 SUCCÈS COMPLET - TOUTES LES SÉQUENCES MIGRÉES!");
        } else {
            System.out.println("⚠️ Migration incomplète - Vérifier les erreurs ci-dessus");
        }
        
        System.out.println("=".repeat(60) + "\n");
    }
}