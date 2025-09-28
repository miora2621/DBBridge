package stage.bici.DBBridge.Model;

public class PostgreSQL {
    private String host;
    private String port;
    private String database;  // équivalent de serviceName/sid
    private String username;
    private String password;
    private String driver = "org.postgresql.Driver";

    public PostgreSQL() {
    }

    // Getters et Setters
    public String getHost() {
        return host;
    }
    public void setHost(String host) {
        this.host = host;
    }

    public String getPort() {
        return port;
    }
    public void setPort(String port) {
        this.port = port;
    }

    public String getDatabase() {
        return database;
    }
    public void setDatabase(String database) {
        this.database = database;
    }

    public String getUsername() {
        return username;
    }
    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }
    public void setPassword(String password) {
        this.password = password;
    }

    public String getDriver() {
        return driver;
    }
    public void setDriver(String driver) {
        this.driver = driver;
    }

    // Méthode utilitaire pour construire l'URL
    public String buildConnectionUrl() {
        if (database != null && !database.isEmpty()) {
            return String.format("jdbc:postgresql://%s:%s/%s", host, port, database);
        } else {
            throw new IllegalArgumentException("Le nom de la base de données doit être fourni !");
        }
    }
}
